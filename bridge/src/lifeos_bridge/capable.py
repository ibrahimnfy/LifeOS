"""The capable mode: shell commands and edits, inside guardrails.

Design and status: docs/permission-policy.md. The mode is switched off
unless a marker file says that the guardrail probes passed for exactly the
installed Claude Code version.
"""

from __future__ import annotations

import asyncio
import json
import shlex
import sys
from pathlib import Path

from . import claude
from .claude import ClaudeError, Policy
from .config import Settings, config_dir
from .paths import refusal_reason

MARKER_FILE_NAME = "capable-mode.json"
AUDIT_DIR_NAME = "audit"

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


def marker_path() -> Path:
    return config_dir() / MARKER_FILE_NAME


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


async def check_available(settings: Settings, run_dir: Path | None = None) -> None:
    """Raise unless the guardrail probes passed for the installed Claude Code.

    The probes run in the memory folder. Unless the marker says they also
    covered other allowed folders, capable runs elsewhere stay refused.
    """
    try:
        marker = json.loads(marker_path().read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise unavailable("the guardrail check has not passed on this computer yet") from exc
    recorded = marker.get("claude_version") if isinstance(marker, dict) else None
    if not isinstance(recorded, str) or not marker.get("all_passed"):
        raise unavailable("the guardrail check has not passed on this computer yet")
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


def gate_command() -> str:
    return shlex.join([sys.executable, "-m", "lifeos_bridge.gate", "--audit-dir", str(audit_dir())])


def build_policy(settings: Settings, run_dir: Path) -> Policy:
    """The policy of one capable run in ``run_dir``; refuses folders that may never be used."""
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
                {"matcher": "*", "hooks": [{"type": "command", "command": gate_command()}]}
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
