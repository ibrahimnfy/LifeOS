"""The capable mode: shell commands and edits, inside guardrails.

Design and status: docs/permission-policy.md. The mode is switched off
unless a marker file says that the guardrail probes passed for exactly the
installed Claude Code version, on the runner that would run it. Each runner
has its own marker: ``claude -p`` (``cli``) and the Agent SDK (``sdk``,
which also records the SDK version).

The marker is signed with a key that only the check and the bridge read
(``guardrail-key`` in the private settings folder), so a marker changed by
hand switches the mode off instead of on. While a check runs (started by
hand or by ``guardcheck``), the mode is off. Every capable request checks
all of this again, with the versions read at that moment (#107).
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import secrets
import shlex
import sys
from importlib import metadata
from pathlib import Path

from . import claude
from .claude import ClaudeError, Policy
from .config import Settings, config_dir
from .paths import refusal_reason

MARKER_FILE_NAME = "capable-mode.json"  # the cli runner (the name it always had)
MARKER_FILE_NAMES = {"cli": MARKER_FILE_NAME, "sdk": "capable-mode-sdk.json"}
AUDIT_DIR_NAME = "audit"
KEY_FILE_NAME = "guardrail-key"
SDK_PACKAGE = "claude-agent-sdk"

# Second layer for Claude Code's own file tools; the sandbox hides the whole
# home folder from commands anyway.
DENIED_READS = (
    "~/.config/lifeos/**", "~/.ssh/**", "~/.claude/**", "~/.claude.json", "~/.gnupg/**",
    "~/.aws/**", "~/.kube/**", "~/.docker/**", "~/.netrc", "~/.git-credentials",
    "~/.password-store/**", "~/.local/share/keyrings/**", "~/.mozilla/**",
    "~/.config/google-chrome/**", "~/.config/chromium/**", "~/.config/BraveSoftware/**",
    "~/.config/gh/**", "~/.config/git/credentials", "~/snap/firefox/**", "~/snap/chromium/**",
)  # fmt: skip
SANDBOX_HIDDEN = ("~/", "/run/user", "/media", "/mnt")


def marker_path(runner: str = "cli") -> Path:
    return config_dir() / MARKER_FILE_NAMES[runner]


def sdk_version() -> str:
    """Version of the Agent SDK this bridge process has loaded."""
    import claude_agent_sdk  # imported here: the cli runner does not need it

    return str(claude_agent_sdk.__version__)


def installed_sdk_version() -> str:
    """Version of the Agent SDK package on disk now (an update shows here before a restart)."""
    try:
        return metadata.version(SDK_PACKAGE)
    except metadata.PackageNotFoundError:
        return ""


def running_flag(runner: str) -> Path:
    return config_dir() / f"guardrail-check-running-{runner}"


def set_check_running(runner: str, running: bool) -> None:
    """Mark that a check of ``runner`` runs; the mode of that runner is off meanwhile."""
    flag = running_flag(runner)
    if running:
        config_dir().mkdir(mode=0o700, parents=True, exist_ok=True)
        flag.write_text("running\n", encoding="utf-8")
    else:
        flag.unlink(missing_ok=True)


def _key(create: bool) -> bytes | None:
    path = config_dir() / KEY_FILE_NAME
    try:
        return bytes.fromhex(path.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        if not create:
            return None
    config_dir().mkdir(mode=0o700, parents=True, exist_ok=True)
    key = secrets.token_bytes(32)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(key.hex() + "\n")
    return key


def _digest(key: bytes, marker: dict) -> str:
    body = {k: v for k, v in marker.items() if k != "signature"}
    text = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hmac.new(key, text.encode(), hashlib.sha256).hexdigest()


def sign_marker(marker: dict) -> dict:
    """The marker with its signature; used by the guardrail check only."""
    key = _key(create=True)
    assert key is not None  # noqa: S101
    return {**marker, "signature": _digest(key, marker)}


def signature_valid(marker: dict) -> bool:
    key = _key(create=False)
    signature = marker.get("signature")
    return (
        key is not None
        and isinstance(signature, str)
        and hmac.compare_digest(signature, _digest(key, marker))
    )


def audit_dir() -> Path:
    return config_dir() / AUDIT_DIR_NAME


def unavailable(reason: str) -> ClaudeError:
    return ClaudeError(
        "capable_mode_unavailable", 503, f"the capable mode is switched off: {reason}"
    )


async def claude_version(settings: Settings) -> str:
    try:
        process = await asyncio.create_subprocess_exec(
            settings.claude_bin,
            "--version",
            env=claude.child_env(),
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await asyncio.wait_for(process.communicate(), timeout=20)
    except (OSError, TimeoutError) as exc:
        raise unavailable("the Claude Code version could not be read") from exc
    version = stdout.decode(errors="replace").strip()
    if process.returncode != 0 or not version:
        raise unavailable("the Claude Code version could not be read")
    return version


async def check_available(
    settings: Settings, run_dir: Path | None = None, runner: str = "cli"
) -> None:
    """Raise unless the guardrail probes passed for the installed versions, on ``runner``.

    The probes run in the memory folder. Unless the marker says they also
    covered other allowed folders, capable runs elsewhere stay refused. The
    marker must carry a valid signature and must not be older than a check
    that is running now.
    """
    if runner not in MARKER_FILE_NAMES:
        raise unavailable("unknown runner")
    if running_flag(runner).exists():
        raise unavailable("the guardrail check is running")
    try:
        marker = json.loads(marker_path(runner).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise unavailable(
            f"the guardrail check has not passed for the {runner} runner on this computer yet"
        ) from exc
    recorded = marker.get("claude_version") if isinstance(marker, dict) else None
    if not isinstance(recorded, str) or marker.get("all_passed") is not True:
        raise unavailable("the guardrail check has not passed on this computer yet")
    if not signature_valid(marker):
        raise unavailable("the guardrail marker was not written by the check; it is ignored")
    if marker.get("runner", "cli") != runner:
        raise unavailable(f"the guardrail check did not cover the {runner} runner")
    if runner == "sdk" and not (
        marker.get("sdk_version") == sdk_version() == installed_sdk_version()
    ):
        raise unavailable(
            "the Agent SDK was updated since the guardrail check; run scripts/verify-guardrails"
        )
    if run_dir is not None and marker.get("scope") != "all_allowed_folders":
        memory, folder = settings.ask_dir.resolve(), run_dir.resolve()
        if folder != memory and not folder.is_relative_to(memory):
            raise unavailable("the guardrail check covers runs in the memory folder only so far")
    if recorded != await claude_version(settings):
        raise unavailable(
            "Claude Code was updated since the guardrail check; run scripts/verify-guardrails"
        )


def _rule_path(folder: Path) -> str:
    """Absolute path in permission-rule syntax (``//`` = filesystem root)."""
    return "/" + str(folder)


def gate_command(*, ask_host: bool = False) -> str:
    command = [sys.executable, "-m", "lifeos_bridge.gate", "--audit-dir", str(audit_dir())]
    return shlex.join([*command, "--ask-host"] if ask_host else command)


def build_policy(settings: Settings, run_dir: Path, *, ask_host: bool = False) -> Policy:
    """The policy of one capable run in ``run_dir``; refuses folders that may never be used.

    ``ask_host``: the run's approval actions go to the bridge's permission
    callback (SDK runner) instead of being refused by the gate.
    """
    run_dir = run_dir.resolve()
    memory = settings.ask_dir.resolve()
    roots = settings.allowed_roots
    if not any(run_dir == root or run_dir.is_relative_to(root) for root in roots):
        raise ClaudeError("folder_not_allowed", 403, "this folder is not an allowed folder")
    for folder in (run_dir, memory):
        reason = refusal_reason(folder)
        if reason is not None:
            raise ClaudeError("folder_not_allowed", 403, reason)

    writable = (
        [run_dir] if run_dir == memory or run_dir.is_relative_to(memory) else [run_dir, memory]
    )
    settings_json = {
        "sandbox": {
            "enabled": True,
            "failIfUnavailable": True,
            "allowUnsandboxedCommands": False,
            "autoAllowBashIfSandboxed": True,
            "excludedCommands": [],
            "filesystem": {
                "denyRead": list(SANDBOX_HIDDEN),
                "allowRead": sorted({str(p) for p in (*roots, run_dir, memory)}),
            },
            "network": {
                "allowedDomains": [],
                "strictAllowlist": True,
                "allowAllUnixSockets": False,
            },
        },
        "permissions": {
            "allow": [f"Edit({_rule_path(folder)}/**)" for folder in writable],
            "deny": [f"Read({pattern})" for pattern in DENIED_READS],
        },
        "hooks": {
            "PreToolUse": [
                {
                    "matcher": "*",
                    "hooks": [{"type": "command", "command": gate_command(ask_host=ask_host)}],
                }
            ]
        },
    }
    return Policy(
        tools=claude.CAPABLE_TOOLS,
        capable=True,
        settings_json=json.dumps(settings_json),
        add_dirs=tuple(folder for folder in writable if folder != run_dir),
    )


class AuditWatch:
    """Cross-checks the tool results seen in a run against the audit log.

    Every tool call must be recorded by the gate program before it runs. If
    the stream shows more completed tool calls than audit records for the
    session, something ran unrecorded and the run is stopped.
    """

    def __init__(self, session_id: str, directory: Path | None = None) -> None:
        self.session_id = session_id
        self.directory = directory or audit_dir()
        self.results_seen = 0
        self._offsets = {path: path.stat().st_size for path in self._files()}

    def _files(self) -> list[Path]:
        try:
            return sorted(self.directory.glob("*.jsonl"))
        except OSError:
            return []

    def entries(self) -> list[dict]:
        """Audit records of this session written since the watch started."""
        found = []
        for path in self._files():
            try:
                with path.open("rb") as handle:
                    handle.seek(self._offsets.get(path, 0))
                    lines = handle.read().splitlines()
            except OSError:
                continue
            for line in lines:
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                if isinstance(entry, dict) and entry.get("session_id") == self.session_id:
                    found.append(entry)
        return found

    def refused_actions(self) -> list[str]:
        return sorted(
            {str(e.get("category")) for e in self.entries() if e.get("decision") == "refused"}
        )

    def __call__(self, event: dict) -> None:
        if event.get("type") != "user":
            return
        message = event.get("message")
        content = message.get("content") if isinstance(message, dict) else None
        if not isinstance(content, list):
            return
        for block in content:
            is_result = isinstance(block, dict) and block.get("type") == "tool_result"
            if is_result and not block.get("is_error"):
                self.results_seen += 1
        if self.results_seen > len(self.entries()):
            raise ClaudeError(
                "audit_mismatch", 502, "a tool ran without an audit record; the run was stopped"
            )
