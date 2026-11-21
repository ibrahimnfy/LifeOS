"""The capable mode: shell commands and edits, inside guardrails.

Design and status: docs/permission-policy.md. The mode is switched off
unless a marker file says that the guardrail probes passed for exactly the
installed Claude Code and Agent SDK versions, on the runner that would run
it. Each runner has its own marker: ``claude -p`` (``cli``) and the Agent
SDK (``sdk``). Both record both versions: an update of either switches the
mode off on both runners (decision #84).

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
# Version of the probe suite. A marker of an older suite keeps the capable mode
# on, but not what needed the newer probes; the automatic check reruns it.
# 2: the skill probes (#91). 3: a run cannot add skills to its folder (#92).
# 4: unapproved or changed MCP servers are never passed to a run (#108).
# 5 (SDK runner only): its approval probes tell a scenario that did not happen
# (recorded as inconclusive, the check counts as not run) from a guardrail that
# held or broke; the escape probe targets a file outside the sandbox (#106).
# cli 5 and sdk 6: the owner's personal skills and personal MCP servers (#162).
# Each runner reruns only when its own suite changed.
# The same versions also probe a blank chat's folder as the run folder (#163).
SUITE_VERSIONS = {"cli": 5, "sdk": 6}
SUITE_VERSION = max(SUITE_VERSIONS.values())
SKILLS_SUITE_VERSION = 3
MCP_SUITE_VERSION = 4
# The first suite of each runner that probes a chat folder as the run folder (#163).
CHAT_SUITE_VERSIONS = {"cli": 5, "sdk": 6}
# Personal skills and personal MCP servers (#162) need the probes of these suite
# versions, per runner: the SDK runner's suite 5 still proved the opposite rule.
PERSONAL_SUITE_VERSIONS = {"cli": 5, "sdk": 6}
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
    # Decision #84: a change of either version switches the mode off, on both runners.
    if not marker.get("sdk_version") == sdk_version() == installed_sdk_version():
        raise unavailable(
            "the Agent SDK was updated since the guardrail check; run scripts/verify-guardrails"
        )
    if run_dir is not None and _is_chat_folder(settings, run_dir):
        # A blank chat's folder (#163): only after a check whose chat-folder probes passed.
        version = marker.get("suite_version")
        probed = isinstance(version, int) and version >= CHAT_SUITE_VERSIONS[runner]
        if not probed or marker.get("chat_folders") is not True:
            raise unavailable(
                "the guardrail check has not covered chat folders yet; run "
                "scripts/verify-guardrails (and with --runner sdk) once the chat folder "
                "can be used"
            )
    elif run_dir is not None and marker.get("scope") != "all_allowed_folders":
        memory, folder = settings.ask_dir.resolve(), run_dir.resolve()
        if folder != memory and not folder.is_relative_to(memory):
            raise unavailable("the guardrail check covers runs in the memory folder only so far")
    if recorded != await claude_version(settings):
        raise unavailable(
            "Claude Code was updated since the guardrail check; run scripts/verify-guardrails"
        )


def _marker_suite(runner: str) -> int:
    """The suite version of the runner's marker; 0 when there is none."""
    try:
        marker = json.loads(marker_path(runner).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return 0
    version = marker.get("suite_version") if isinstance(marker, dict) else None
    return version if isinstance(version, int) else 0


async def skills_available(
    settings: Settings, run_dir: Path | None = None, runner: str = "cli"
) -> None:
    """Raise unless the capable mode is on and its check included the skill probes (#91)."""
    await check_available(settings, run_dir, runner=runner)
    if _marker_suite(runner) < SKILLS_SUITE_VERSION:
        raise skills_unavailable()


def personal_available(runner: str) -> bool:
    """Whether the runner's check included the probes for personal skills and servers (#162).

    Asked after ``check_available``. With an older marker a capable run simply
    gets no personal skill and no personal MCP server, as before #162.
    """
    return _marker_suite(runner) >= PERSONAL_SUITE_VERSIONS.get(runner, SUITE_VERSION)


PERSONAL_UNAVAILABLE_REASON = (
    "the owner's personal skills are offered after the guardrail check with the "
    "personal-skill probes has passed (it runs by itself at the next start of the bridge)"
)


async def mcp_available(
    settings: Settings, run_dir: Path | None = None, runner: str = "cli"
) -> None:
    """Raise unless the capable mode is on and its check included the MCP probes (#108)."""
    await check_available(settings, run_dir, runner=runner)
    if _marker_suite(runner) < MCP_SUITE_VERSION:
        raise ClaudeError(
            "mcp_unavailable",
            503,
            "this folder has approved MCP servers, and they are used only after the guardrail "
            "check with the MCP probes has passed (it runs by itself at the next start of the "
            "bridge); revoke their approval to work without them",
        )


def skills_unavailable() -> ClaudeError:
    return ClaudeError(
        "skills_unavailable",
        503,
        "the session's skills stay off until the guardrail check with the skill probes has "
        "passed (it runs by itself at the next start of the bridge); switch skills off for "
        "this session to work without them",
    )


def _is_chat_folder(settings: Settings, folder: Path) -> bool:
    from . import chats  # local: chats needs this module

    return chats.is_chat_folder(settings, folder)


def suite_version(runner: str) -> int:
    return SUITE_VERSIONS.get(runner, SUITE_VERSION)


def suite_outdated(runner: str) -> bool:
    """True when the runner's marker comes from an older probe suite."""
    try:
        marker = json.loads(marker_path(runner).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    version = marker.get("suite_version") if isinstance(marker, dict) else None
    return not isinstance(version, int) or version < suite_version(runner)


def _rule_path(folder: Path) -> str:
    """Absolute path in permission-rule syntax (``//`` = filesystem root)."""
    return "/" + str(folder)


def gate_command(*, ask_host: bool = False) -> str:
    command = [sys.executable, "-m", "lifeos_bridge.gate", "--audit-dir", str(audit_dir())]
    return shlex.join([*command, "--ask-host"] if ask_host else command)


def repository_root(folder: Path) -> Path | None:
    """The nearest git repository root at or above ``folder`` (as git finds it), if any."""
    for candidate in (folder, *folder.parents):
        marker = candidate / ".git"
        try:
            if marker.is_dir() and (marker / "HEAD").is_file():
                return candidate
            if marker.is_file() and marker.read_text(errors="replace").startswith("gitdir:"):
                return candidate  # a worktree or submodule
        except OSError:
            continue
    return None


def check_repositories(writable: list[Path], memory: Path) -> None:
    """Refuse a writable folder that sits inside another git repository (owner, #139).

    Claude Code's sandbox lets commands write in the root of the repository
    that contains the working folder (found with the real CLI, both runners).
    A folder that is itself a repository root is fine, and so is a folder
    whose enclosing repository root is writable in capable runs anyway: the
    memory folder (every capable run may write there). Checked before every
    capable run, not only when a folder is added.
    """
    for folder in writable:
        enclosing = repository_root(folder)
        if enclosing is None or enclosing == folder or enclosing in (*writable, memory):
            continue
        raise ClaudeError(
            "folder_inside_repository",
            403,
            f"this folder is inside the git repository at {enclosing}; the capable mode would "
            f"let commands write anywhere in that repository. Add {enclosing} as an allowed "
            "folder and work there instead",
        )


def check_copies(copies: list[Path], open_folders: list[Path]) -> None:
    """Refuse skill copies that lie where they must never be (#171).

    Not in the private settings folder (a run may read its copies, and nothing
    there may become readable), and neither inside nor around a folder a run
    works in (it could change them, or read what lies beside them).
    """
    private = config_dir().resolve()
    for copy in copies:
        misplaced = copy == private or copy.is_relative_to(private) or private.is_relative_to(copy)
        for folder in open_folders:
            folder = folder.resolve()
            misplaced = misplaced or copy.is_relative_to(folder) or folder.is_relative_to(copy)
        # The sandbox lets commands write in the root of a git repository that holds a
        # folder the run works in (section 9.9); a copy is an added folder of the run.
        if misplaced or repository_root(copy) is not None:
            raise ClaudeError(
                "skills_unavailable",
                503,
                "the folder for the copies of skills (LIFEOS_SKILL_COPIES_DIR) must lie outside "
                "the private settings folder, the allowed folders, the memory folder and every "
                "git repository",
            )


def build_policy(
    settings: Settings,
    run_dir: Path,
    *,
    ask_host: bool = False,
    skills_dir: Path | None = None,
    personal_skills_dir: Path | None = None,
    mcp: dict[str, dict] | None = None,
) -> Policy:
    """The policy of one capable run in ``run_dir``; refuses folders that may never be used.

    ``ask_host``: the run's approval actions go to the bridge's permission
    callback (SDK runner) instead of being refused by the gate.
    ``skills_dir``: the snapshot of the folder's skills (``skills.snapshot``),
    for a session whose skills are switched on.
    ``personal_skills_dir``: the copy of the owner's personal skills
    (``skills.personal_snapshot``, #162). With either copy, shell lines in
    skills and the bundled skills are switched off.
    ``mcp``: the approved, unchanged MCP servers of the folder and of the owner
    (``mcp_servers.for_run``), passed explicitly with permission to call their
    tools; they run outside the sandbox by the owner's approval (#108, #162).

    The copies are readable by the run and never writable (#171): file tools read
    them as added folders, sandboxed commands through ``allowRead``; ``denyWrite``
    and a deny rule for the file tools keep both from changing them. A copy that
    lies where a run could write, or in the private settings folder, is refused.

    A blank chat's folder (#163) is accepted like an allowed folder, with the same
    guardrails: the run may write its own chat folder and the memory folder, and
    read the allowed folders; the chat root and every other chat stay out of reach.
    """
    run_dir = run_dir.resolve()
    memory = settings.ask_dir.resolve()
    roots = settings.allowed_roots
    is_chat = _is_chat_folder(settings, run_dir)
    if is_chat:
        from . import chats  # local: chats needs this module

        reason = chats.root_refusal(settings)
        if reason is not None:
            raise chats.unusable(reason)
    elif not any(run_dir == root or run_dir.is_relative_to(root) for root in roots):
        raise ClaudeError("folder_not_allowed", 403, "this folder is not an allowed folder")
    for folder in (run_dir, memory):
        reason = refusal_reason(folder)
        if reason is not None:
            raise ClaudeError("folder_not_allowed", 403, reason)

    writable = (
        [run_dir] if run_dir == memory or run_dir.is_relative_to(memory) else [run_dir, memory]
    )
    check_repositories(writable, memory)
    copies = [folder.resolve() for folder in (skills_dir, personal_skills_dir) if folder]
    check_copies(copies, [*roots, run_dir, memory])
    # The conversation index (#164): in reach of the file tools, read-only. It gets no
    # write permission, and the sandbox is told to deny writes there as well.
    from . import conversations  # local: it needs chats, which needs this module

    index = conversations.index_dir(settings)
    shared = [] if index is None else [index]
    settings_json = {
        "sandbox": {
            "enabled": True,
            "failIfUnavailable": True,
            "allowUnsandboxedCommands": False,
            "autoAllowBashIfSandboxed": True,
            "excludedCommands": [],
            "filesystem": {
                "denyRead": list(SANDBOX_HIDDEN),
                "allowRead": sorted({str(p) for p in (*roots, run_dir, memory, *copies, *shared)}),
            },
            "network": {
                "allowedDomains": [],
                "strictAllowlist": True,
                "allowAllUnixSockets": False,
            },
        },
        "permissions": {
            "allow": [f"Edit({_rule_path(folder)}/**)" for folder in writable],
            "deny": [f"Read({pattern})" for pattern in DENIED_READS]
            # Skills, commands, settings and MCP definitions of a folder change only
            # through the bridge with the owner's approval (#92, #108); commands are kept
            # out of them by the sandbox's own protected paths.
            + [
                f"Edit({_rule_path(folder)}/{name})"
                for folder in writable
                for name in (".claude/**", ".mcp.json")
            ]
            # The copies of the skills a run was started with (#171): read, never changed.
            # ...and the conversation index (#164).
            + [f"Edit({_rule_path(folder)}/**)" for folder in (*copies, *shared)],
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
    if copies or shared:
        # Whatever else would let commands write there (an added folder, an allow
        # rule): never the copies, and never the conversation index.
        settings_json["sandbox"]["filesystem"]["denyWrite"] = [
            str(folder) for folder in (*copies, *shared)
        ]
    mcp_config = None
    if mcp:
        from . import mcp_servers  # local: only runs with approved servers need it

        settings_json["permissions"]["allow"] += mcp_servers.allow_rules(mcp)
        mcp_config = str(mcp_servers.run_config_file(mcp))
    if skills_dir is not None or personal_skills_dir is not None:
        from .skills import SETTINGS as SKILL_SETTINGS  # local: skills imports commands

        settings_json |= SKILL_SETTINGS
    return Policy(
        tools=claude.CAPABLE_TOOLS,
        capable=True,
        settings_json=json.dumps(settings_json),
        # Added folders are what the file tools may read besides the run folder.
        add_dirs=(
            *(folder for folder in writable if folder != run_dir),
            *copies,
            *shared,
        ),
        skills_dir=skills_dir,
        personal_skills_dir=personal_skills_dir,
        mcp_config=mcp_config,
        clean=is_chat,  # a blank chat: no memory file of any level is loaded
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
        # Only the gate's own records count: the bridge adds records for approvals.
        gate_records = [e for e in self.entries() if e.get("source") != "bridge"]
        if self.results_seen > len(gate_records):
            raise ClaudeError(
                "audit_mismatch", 502, "a tool ran without an audit record; the run was stopped"
            )
