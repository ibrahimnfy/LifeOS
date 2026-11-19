"""Run Claude Code headless.

This module is the only place that knows how the ``claude`` command is
invoked. Every flag is set explicitly: nothing relies on a CLI default.
Reference: docs/research/claude-headless.md.

Prompt text, answers and stderr are never logged here.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import re
import signal
from collections.abc import AsyncIterator, Callable, Mapping
from dataclasses import dataclass, field
from pathlib import Path

from .config import Settings

# Temporary policy until the owner decides the permission policy: file
# reading and searching only. No shell, no edits, no network tools.
READ_ONLY_TOOLS = "Read,Glob,Grep"

# The child gets a minimal environment built from this allow-list. Anything
# else (API keys, markers of a parent Claude Code session, bridge secrets)
# is deliberately not passed on.
ENV_ALLOW_LIST = (
    "HOME",
    "PATH",
    "USER",
    "LOGNAME",
    "SHELL",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TZ",
    "TMPDIR",
    "XDG_RUNTIME_DIR",
    "DBUS_SESSION_BUS_ADDRESS",
)

CAPABLE_TOOLS = "Read,Glob,Grep,Edit,Write,Bash"
# Not passed to capable runs: they point commands at the desktop session bus.
SESSION_BUS_VARS = ("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS")


@dataclass(frozen=True)
class Policy:
    """What one run may do. The default is the read-only policy."""

    tools: str = READ_ONLY_TOOLS
    capable: bool = False
    # Capable runs: one JSON carrying the sandbox requirement and all grants.
    settings_json: str | None = None
    add_dirs: tuple[Path, ...] = ()


READ_ONLY = Policy()

STREAM_LINE_LIMIT = 32 * 1024 * 1024
_UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class ClaudeError(Exception):
    """A run failed. ``code`` is a stable machine-readable reason."""

    def __init__(self, code: str, status: int, message: str = "") -> None:
        super().__init__(code)
        self.code = code
        self.status = status
        self.message = message


@dataclass(frozen=True)
class RunResult:
    answer: str
    session_id: str
    duration_ms: int
    num_turns: int
    denied_tools: list[str] = field(default_factory=list)


def is_session_id(value: str) -> bool:
    return bool(_UUID_RE.match(value))


def project_dir(cwd: Path, home: Path | None = None) -> Path:
    """Folder in which Claude Code stores the sessions of working directory ``cwd``."""
    encoded = re.sub(r"[^A-Za-z0-9]", "-", str(cwd.resolve()))
    return (home or Path.home()) / ".claude" / "projects" / encoded


def session_exists(cwd: Path, session_id: str, home: Path | None = None) -> bool:
    return is_session_id(session_id) and (project_dir(cwd, home) / f"{session_id}.jsonl").is_file()


def child_env(environ: Mapping[str, str] | None = None, *, capable: bool = False) -> dict[str, str]:
    environ = os.environ if environ is None else environ
    names = [n for n in ENV_ALLOW_LIST if not (capable and n in SESSION_BUS_VARS)]
    return {name: environ[name] for name in names if name in environ}


def build_command(
    settings: Settings,
    *,
    session_id: str,
    resume: bool,
    stream: bool,
    name: str | None = None,
    policy: Policy = READ_ONLY,
) -> list[str]:
    command = [settings.claude_bin, "-p"]
    if stream:
        command += ["--output-format", "stream-json", "--verbose", "--include-partial-messages"]
    else:
        command += ["--output-format", "json"]
    command += [
        # Nobody is at the computer: never wait for an approval, deny instead.
        "--permission-mode", "dontAsk",
        "--permission-prompts", "none",
        # File tools confined to the working directories; user and project
        # settings files ignored; only the tools named below exist.
        "--restricted",
        "--tools", policy.tools,
        "--strict-mcp-config",
        "--disable-slash-commands",
        "--max-turns", str(settings.max_turns),
    ]  # fmt: skip
    if policy.capable:
        # Everything a capable run is granted lives in this one JSON, next to
        # the sandbox requirement. Nothing is granted on the command line.
        if not policy.settings_json:
            raise ClaudeError("capable_mode_unavailable", 503, "capable run without settings")
        command += ["--settings", policy.settings_json]
        for folder in policy.add_dirs:
            command += ["--add-dir", str(folder)]
    if settings.claude_model:
        command += ["--model", settings.claude_model]
    if resume:
        command += ["--resume", session_id]
    else:
        command += ["--session-id", session_id]
        if name:
            command += ["--name", name]
    return command


async def _start(
    settings: Settings, command: list[str], cwd: Path, *, capable: bool = False
) -> asyncio.subprocess.Process:
    if not cwd.is_dir():
        raise ClaudeError("working_directory_missing", 503, "the configured folder does not exist")
    try:
        return await asyncio.create_subprocess_exec(
            *command,
            cwd=cwd,
            env=child_env(capable=capable),
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            limit=STREAM_LINE_LIMIT,
            start_new_session=True,
        )
    except (FileNotFoundError, PermissionError) as exc:
        raise ClaudeError("claude_not_found", 503, "the claude command is not available") from exc


async def _stop(process: asyncio.subprocess.Process, grace: float) -> None:
    """Interrupt, then terminate, then kill the child's whole process group."""
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGKILL):
        if process.returncode is not None:
            return
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, sig)
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(process.wait(), timeout=grace)
    await process.wait()


def _startup_failure(returncode: int | None, stderr: bytes) -> ClaudeError:
    text = stderr.decode(errors="replace")
    if "No conversation found" in text:
        return ClaudeError("session_not_found", 404, "this session no longer exists")
    if "running in the background" in text:
        return ClaudeError("session_in_use", 409, "this session is in use on the computer")
    return ClaudeError("claude_failed", 502, f"claude exited with status {returncode}")


async def sessions_in_use(settings: Settings, timeout: float = 20.0) -> set[str]:
    """Ids of sessions that a live Claude Code process holds right now.

    Uses ``claude agents --json``, the supported way to read session state
    from outside. Fails closed: when the answer cannot be obtained, a
    ClaudeError is raised and the caller must not resume anything.
    """
    failure = ClaudeError(
        "in_use_check_failed", 503, "could not check whether the session is in use"
    )
    try:
        process = await asyncio.create_subprocess_exec(
            settings.claude_bin,
            "agents",
            "--json",
            env=child_env(),
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,
        )
    except (FileNotFoundError, PermissionError) as exc:
        raise ClaudeError("claude_not_found", 503, "the claude command is not available") from exc
    try:
        stdout, _ = await asyncio.wait_for(process.communicate(), timeout=timeout)
    except TimeoutError as exc:
        await _stop(process, 1.0)
        raise failure from exc
    if process.returncode != 0:
        raise failure
    try:
        entries = json.loads(stdout)
    except ValueError as exc:
        raise failure from exc
    if not isinstance(entries, list):
        raise failure
    return {
        entry["sessionId"]
        for entry in entries
        if isinstance(entry, dict) and isinstance(entry.get("sessionId"), str)
    }


def parse_result(payload: object) -> RunResult:
    """Turn the final ``result`` object of a run into a RunResult or raise."""
    if not isinstance(payload, dict) or payload.get("type") != "result":
        raise ClaudeError("bad_output", 502, "claude returned an unexpected answer format")
    if payload.get("is_error"):
        subtype = str(payload.get("subtype") or "")
        code = subtype if subtype.startswith("error_") else "claude_error"
        detail = payload.get("result")
        if not isinstance(detail, str) or not detail:
            errors = payload.get("errors")
            detail = "; ".join(str(e) for e in errors) if isinstance(errors, list) else ""
        return _raise(ClaudeError(code, 502, detail[:500]))
    denials = payload.get("permission_denials")
    denied = sorted(
        {str(d.get("tool_name")) for d in denials if isinstance(d, dict) and d.get("tool_name")}
        if isinstance(denials, list)
        else set()
    )
    return RunResult(
        answer=str(payload.get("result") or ""),
        session_id=str(payload.get("session_id") or ""),
        duration_ms=int(payload.get("duration_ms") or 0),
        num_turns=int(payload.get("num_turns") or 0),
        denied_tools=denied,
    )


def _raise(error: ClaudeError) -> RunResult:
    raise error


async def run(
    settings: Settings,
    prompt: str,
    *,
    session_id: str,
    resume: bool,
    cwd: Path | None = None,
    name: str | None = None,
    grace: float = 5.0,
) -> RunResult:
    """Run one read-only prompt to completion and return the answer."""
    command = build_command(settings, session_id=session_id, resume=resume, stream=False, name=name)
    process = await _start(settings, command, cwd or settings.ask_dir)
    try:
        stdout, stderr = await asyncio.wait_for(
            process.communicate(prompt.encode()), timeout=settings.ask_timeout
        )
    except TimeoutError as exc:
        await _stop(process, grace)
        raise ClaudeError("timeout", 504, "the run took too long and was stopped") from exc
    except BaseException:
        await _stop(process, grace)
        raise
    if not stdout.strip():
        raise _startup_failure(process.returncode, stderr)
    try:
        payload = json.loads(stdout)
    except ValueError as exc:
        raise ClaudeError("bad_output", 502, "claude returned an unexpected answer format") from exc
    return parse_result(payload)


async def stream(
    settings: Settings,
    prompt: str,
    *,
    session_id: str,
    resume: bool,
    cwd: Path | None = None,
    name: str | None = None,
    grace: float = 5.0,
    policy: Policy = READ_ONLY,
    observer: Callable[[dict], None] | None = None,
) -> AsyncIterator[tuple[str, dict]]:
    """Run one prompt and yield ``(event, data)`` pairs as the answer is written.

    Events: ``delta`` (``{"text": ...}``), then exactly one ``done`` (the
    final result) or ``error`` (``{"error": code, "message": ...}``).
    """
    command = build_command(
        settings, session_id=session_id, resume=resume, stream=True, name=name, policy=policy
    )
    process = await _start(settings, command, cwd or settings.ask_dir, capable=policy.capable)
    assert process.stdin and process.stdout and process.stderr  # noqa: S101
    final: dict | None = None
    try:
        async with asyncio.timeout(settings.ask_timeout):
            process.stdin.write(prompt.encode())
            await process.stdin.drain()
            process.stdin.close()
            stderr_task = asyncio.ensure_future(process.stderr.read())
            async for raw in process.stdout:
                try:
                    event = json.loads(raw)
                except ValueError:
                    continue
                if not isinstance(event, dict):
                    continue
                if observer is not None:
                    observer(event)  # may raise ClaudeError to stop the run
                if event.get("type") == "stream_event":
                    inner = event.get("event") or {}
                    delta = inner.get("delta") or {}
                    if inner.get("type") == "content_block_delta" and (
                        delta.get("type") == "text_delta"
                    ):
                        yield "delta", {"text": str(delta.get("text") or "")}
                elif event.get("type") == "result":
                    final = event
            await process.wait()
            stderr = await stderr_task
    except TimeoutError:
        await _stop(process, grace)
        yield "error", {"error": "timeout", "message": "the run took too long and was stopped"}
        return
    except ClaudeError as error:  # raised by the observer
        await _stop(process, grace)
        yield "error", {"error": error.code, "message": error.message}
        return
    except BaseException:
        await _stop(process, grace)
        raise
    try:
        if final is None:
            raise _startup_failure(process.returncode, stderr)
        result = parse_result(final)
    except ClaudeError as error:
        yield "error", {"error": error.code, "message": error.message}
        return
    yield (
        "done",
        {
            "answer": result.answer,
            "session_id": result.session_id or session_id,
            "duration_ms": result.duration_ms,
            "num_turns": result.num_turns,
            "denied_tools": result.denied_tools,
        },
    )
