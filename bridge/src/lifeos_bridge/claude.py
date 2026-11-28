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
import subprocess
from collections.abc import AsyncIterator, Callable, Mapping
from dataclasses import dataclass, field
from pathlib import Path

from . import program, routing
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

# What a read-only profile may add (docs/permission-policy.md 9.10): web tools, no writing.
PROFILE_TOOLS = frozenset({"Read", "Glob", "Grep", "WebSearch", "WebFetch"})
CAPABLE_TOOLS = "Read,Glob,Grep,Edit,Write,Bash"
# The terminal's structured question tool; only runs with an answering host get it.
QUESTION_TOOL = "AskUserQuestion"
SKILL_TOOL = "Skill"
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
    # Capable runs with the session's skills on (#91): a copy of the folder's skills
    # and commands, loaded as a plugin; slash commands and the skill tool are enabled.
    skills_dir: Path | None = None
    # Capable runs (#162): a copy of the owner's personal skills and commands, loaded
    # as a second plugin, whether or not the session's own skills are switched on.
    personal_skills_dir: Path | None = None
    # Capable runs with MCP servers the owner approved (#108, #162): exactly those, as
    # the path of an --mcp-config file in the private settings folder (the definitions
    # may hold secrets, which must not appear on a command line);
    # --strict-mcp-config stays, so nothing else loads.
    mcp_config: str | None = None
    # Allow rules for tools that would otherwise ask, and so be denied in dontAsk
    # (the web tools of the course-resources profile).
    allowed: str = ""
    # A blank chat (#163): Claude Code is told to load no memory file at all (no
    # CLAUDE.md of any level, no auto-memory), on top of the restricted mode.
    clean: bool = False
    # Variables of the Claude Code process of an open-network run (#182): the owner's
    # GitHub token for gh and git, which the sandbox masks (``network.run_env``). Kept
    # out of the representation, so it never appears in a log or an error.
    env: tuple[tuple[str, str], ...] = field(default=(), repr=False)

    @property
    def plugin_dirs(self) -> tuple[Path, ...]:
        """The plugin copies a run is started with; none unless the run is capable."""
        if not self.capable:
            return ()
        found = (self.skills_dir, self.personal_skills_dir)
        return tuple(folder for folder in found if folder is not None)


READ_ONLY = Policy()
WEB_TOOLS = frozenset({"WebSearch", "WebFetch"})


def profile_policy(tools: str) -> Policy:
    """A read-only run with extra tools (docs/permission-policy.md 9.10).

    In ``dontAsk`` a web tool would be denied as "needs approval", so the web
    tools named here also get an allow rule; nothing that writes is accepted.
    """
    names = [name for name in tools.split(",") if name]
    if set(names) - PROFILE_TOOLS:
        raise ClaudeError("bad_policy", 500, "only reading and web tools may be given to a run")
    return Policy(tools=",".join(names), allowed=",".join(n for n in names if n in WEB_TOOLS))


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


# Claude Code's own switches (fixed values, no secret): no CLAUDE.md of any level is
# loaded, and no auto-memory is read or written. Set for blank chats (Policy.clean).
NO_MEMORY_FILES_ENV = {
    "CLAUDE_CODE_DISABLE_CLAUDE_MDS": "1",
    "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "1",
}


def child_env(
    environ: Mapping[str, str] | None = None, *, capable: bool = False, clean: bool = False
) -> dict[str, str]:
    environ = os.environ if environ is None else environ
    names = [n for n in ENV_ALLOW_LIST if not (capable and n in SESSION_BUS_VARS)]
    env = {name: environ[name] for name in names if name in environ}
    return {**env, **NO_MEMORY_FILES_ENV} if clean else env


STREAM_OUTPUT_FLAGS = ("--output-format", "stream-json", "--verbose", "--include-partial-messages")


def build_command(
    settings: Settings,
    *,
    session_id: str,
    resume: bool,
    stream: bool,
    name: str | None = None,
    policy: Policy = READ_ONLY,
    context: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    advisor: str | None = None,
    persist: bool = True,
) -> list[str]:
    command = [settings.claude_bin, "-p"]
    if stream:
        command += STREAM_OUTPUT_FLAGS
    else:
        command += ["--output-format", "json"]
    command += run_flags(
        settings,
        session_id=session_id,
        resume=resume,
        name=name,
        policy=policy,
        context=context,
        model=model,
        effort=effort,
        advisor=advisor,
    )
    if not persist and not resume:
        # A one-off run of the bridge itself (the context map): nothing is written to
        # disk, so it never shows up in the owner's session list.
        at = command.index("--session-id")
        command[at : at + 2] = ["--no-session-persistence"]
    return command


def run_flags(
    settings: Settings,
    *,
    session_id: str,
    resume: bool,
    name: str | None = None,
    policy: Policy = READ_ONLY,
    context: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    host: bool = False,
    advisor: str | None = None,
) -> list[str]:
    """Every restriction and choice of a run. Shared by the ``claude -p`` and the SDK runner.

    ``host``: the bridge answers permission prompts itself (the SDK runner's
    callback in ``sdk_runner``). The run then starts in the default mode,
    where a prompt goes to that callback instead of being denied, and gets
    the question tool. The callback denies everything except the owner's
    answers, so nothing else is allowed that ``dontAsk`` would deny.
    """
    if host:
        prompts = ["--permission-mode", "default", "--permission-prompt-tool", "stdio"]
        tools = policy.tools + "," + QUESTION_TOOL
    else:
        # Nobody is at the computer: never wait for an approval, deny instead.
        prompts = ["--permission-mode", "dontAsk", "--permission-prompts", "none"]
        tools = policy.tools
    if policy.plugin_dirs:
        tools += "," + SKILL_TOOL
    command = [
        *prompts,
        # File tools confined to the working directories; user and project
        # settings files ignored; only the tools named below exist.
        "--restricted",
        "--tools", tools,
        "--strict-mcp-config",
    ]  # fmt: skip
    # A run that may change files is not cut short after a few turns (#199).
    turns = settings.capable_max_turns if policy.capable else settings.max_turns
    if turns:
        command += ["--max-turns", str(turns)]
    if policy.capable and policy.mcp_config:
        command += ["--mcp-config", policy.mcp_config]
    if policy.plugin_dirs:
        # Only the bridge's own copies (the owner's personal skills and commands, and
        # the folder's own when switched on): restricted mode ignores every settings
        # file and source, so nothing else is loaded.
        for folder in policy.plugin_dirs:
            command += ["--plugin-dir", str(folder)]
    else:
        command += ["--disable-slash-commands"]
    if policy.allowed:
        command += ["--allowedTools", policy.allowed]
    if policy.capable:
        # Everything a capable run is granted lives in this one JSON, next to
        # the sandbox requirement. Nothing is granted on the command line.
        if not policy.settings_json:
            raise ClaudeError("capable_mode_unavailable", 503, "capable run without settings")
        command += ["--settings", policy.settings_json]
    else:
        # The LifeOS program folder is allowed (#180): what is hidden in it stays out of
        # reach of the file tools of read-only runs as well.
        hidden = program.read_only_settings(settings.allowed_roots)
        if hidden is not None:
            command += ["--settings", json.dumps(hidden)]
    # More folders in reach of the file tools: the memory folder of a capable run (its
    # write permission comes from the settings above) and of a blank chat (a read-only
    # run has no write tool).
    for folder in policy.add_dirs:
        command += ["--add-dir", str(folder)]
    # A model named for this run wins; then the fixed override; then the default.
    chosen = model or settings.claude_model or settings.default_model
    if chosen:
        command += ["--model", chosen]
    level = effort or settings.default_effort
    if level:
        command += ["--effort", level]
    if advisor:
        # Documented, not listed in --help: the run may consult this stronger
        # model at hard steps (docs/research/claude-headless.md, "Advisor").
        command += ["--advisor", advisor]
    if context:
        # The always-loaded part of the memory; passed explicitly, never discovered.
        command += ["--append-system-prompt", context]
    if resume:
        command += ["--resume", session_id]
    else:
        command += ["--session-id", session_id]
        if name:
            command += ["--name", name]
    return command


async def _start(
    settings: Settings, command: list[str], cwd: Path, *, capable: bool = False, clean: bool = False
) -> asyncio.subprocess.Process:
    if not cwd.is_dir():
        raise ClaudeError("working_directory_missing", 503, "the configured folder does not exist")
    try:
        return await asyncio.create_subprocess_exec(
            *command,
            cwd=cwd,
            env=child_env(capable=capable, clean=clean),
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


async def sessions_in_use(
    settings: Settings, timeout: float = 20.0, *, ignore_pids: frozenset[int] = frozenset()
) -> set[str]:
    """Ids of sessions that a live Claude Code process holds right now.

    Uses ``claude agents --json``, the supported way to read session state
    from outside. Fails closed: when the answer cannot be obtained, a
    ClaudeError is raised and the caller must not resume anything.
    ``ignore_pids`` are the bridge's own live processes (the SDK runner keeps
    one per active session): they hold their session, but they are not a
    second writer.
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
        if isinstance(entry, dict)
        and isinstance(entry.get("sessionId"), str)
        and not (isinstance(entry.get("pid"), int) and entry["pid"] in ignore_pids)
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
    context: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    advisor: str | None = None,
    job: str = "chat",
    on_process: Callable[[asyncio.subprocess.Process], None] | None = None,
    persist: bool = True,
    tools: str | None = None,
    policy: Policy | None = None,
) -> RunResult:
    """Run one read-only prompt to completion and return the answer.

    ``tools`` replaces the read-only tool list for a profile of
    docs/permission-policy.md (the course-resources run); never a write tool.
    ``policy`` is a read-only policy with more folders in reach (a blank chat);
    a capable policy is refused here: capable runs are always streamed.

    ``job`` names the run for the plan-usage counters (routing.py). When the
    CLI refuses to start with ``advisor`` (the documented launch error for a
    pairing or allow-list it does not accept), the run is made once without it.
    """
    if policy is not None and (policy.capable or tools):
        raise ClaudeError("bad_policy", 500, "only a read-only policy can be run unstreamed")
    try:
        return await _run_once(
            settings, prompt, session_id=session_id, resume=resume, cwd=cwd, name=name,
            grace=grace, context=context, model=model, effort=effort, advisor=advisor,
            job=job, on_process=on_process, persist=persist, tools=tools, policy=policy,
        )  # fmt: skip
    except ClaudeError as error:
        if not advisor or error.code != "claude_failed":
            raise
    return await _run_once(
        settings, prompt, session_id=session_id, resume=resume, cwd=cwd, name=name,
        grace=grace, context=context, model=model, effort=effort, advisor=None, job=job,
        on_process=on_process, persist=persist, tools=tools, policy=policy,
    )  # fmt: skip


async def _run_once(
    settings: Settings,
    prompt: str,
    *,
    session_id: str,
    resume: bool,
    cwd: Path | None,
    name: str | None,
    grace: float,
    context: str | None,
    model: str | None,
    effort: str | None,
    advisor: str | None,
    job: str,
    on_process: Callable[[asyncio.subprocess.Process], None] | None,
    persist: bool = True,
    tools: str | None = None,
    policy: Policy | None = None,
) -> RunResult:
    policy = policy or (profile_policy(tools) if tools else READ_ONLY)
    command = build_command(
        settings,
        session_id=session_id,
        resume=resume,
        stream=False,
        name=name,
        context=context,
        model=model,
        effort=effort,
        advisor=advisor,
        persist=persist,
        policy=policy,
    )
    process = await _start(settings, command, cwd or settings.ask_dir, clean=policy.clean)
    if on_process is not None:
        on_process(process)
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
    routing.record(job, payload, resumed=resume)  # also for a failed run: it used the plan too
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
    context: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    advisor: str | None = None,
    job: str = "chat",
    on_process: Callable[[asyncio.subprocess.Process], None] | None = None,
) -> AsyncIterator[tuple[str, dict]]:
    """Run one prompt and yield ``(event, data)`` pairs as the answer is written.

    Events: ``delta`` (``{"text": ...}``), then exactly one ``done`` (the
    final result) or ``error`` (``{"error": code, "message": ...}``).
    """
    command = build_command(
        settings,
        session_id=session_id,
        resume=resume,
        stream=True,
        name=name,
        policy=policy,
        context=context,
        model=model,
        effort=effort,
        advisor=advisor,
    )
    process = await _start(
        settings, command, cwd or settings.ask_dir, capable=policy.capable, clean=policy.clean
    )
    if on_process is not None:
        on_process(process)
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
                    routing.record(job, event, resumed=resume)
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


def json_from_answer(answer: str) -> dict:
    """The JSON object in a model answer (tolerates code fences and surrounding words)."""
    start, end = answer.find("{"), answer.rfind("}")
    if start == -1 or end <= start:
        raise ClaudeError("bad_output", 502, "the answer did not contain the expected data")
    try:
        data = json.loads(answer[start : end + 1])
    except ValueError as exc:
        raise ClaudeError(
            "bad_output", 502, "the answer did not contain the expected data"
        ) from exc
    if not isinstance(data, dict):
        raise ClaudeError("bad_output", 502, "the answer did not contain the expected data")
    return data


FALLBACK_EFFORTS = ("low", "medium", "high", "xhigh", "max")
_efforts_cache: dict[str, tuple[str, ...]] = {}


def effort_levels(settings: Settings) -> tuple[str, ...]:
    """The effort levels the installed CLI accepts, read from its help text."""
    cached = _efforts_cache.get(settings.claude_bin)
    if cached:
        return cached
    levels: tuple[str, ...] = ()
    try:
        done = subprocess.run(  # noqa: S603
            [settings.claude_bin, "--help"],
            capture_output=True, text=True, timeout=20, check=False, env=child_env(),
        )  # fmt: skip
        match = re.search(r"--effort\b[^()]*\(([a-z, \n]+)\)", done.stdout)
        if match:
            levels = tuple(w for w in re.split(r"[,\s]+", match.group(1)) if w)
    except (OSError, subprocess.SubprocessError):
        levels = ()
    _efforts_cache[settings.claude_bin] = levels or FALLBACK_EFFORTS
    return _efforts_cache[settings.claude_bin]
