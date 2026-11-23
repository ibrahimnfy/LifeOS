"""The Agent SDK runner: one live Claude Code process per active session.

Selected with ``LIFEOS_RUNNER=sdk``; the default is still ``cli`` (claude.py,
one ``claude -p`` per message). Read-only runs only: the capable mode stays
off on this runner until the guardrail checks pass on it.

What is different from ``claude -p``:

- The process of a session is kept between its messages, so a follow-up
  starts without delay. It is closed ``LIFEOS_SDK_IDLE_MINUTES`` after the
  last message, on shutdown, and when another session needs its place (at
  most ``LIFEOS_SDK_MAX_LIVE`` are live; the longest-idle one goes first).
  Closing never loses the conversation: it is in the session transcript,
  and the next message resumes it from there.
- Cancel and "send now" use the SDK's own interrupt; the process is
  terminated only when the turn does not end within the grace period.

Questions and approvals (#105): the process starts in the default
permission mode with the bridge as the permission host, so the assistant's
structured questions, and in capable runs the actions that need the owner's
confirmation, come to ``LiveSession._can_use_tool``. That callback denies
everything else, exactly as ``dontAsk`` would. A question waits for the
owner (``interactions``) only while a client streams the answer; otherwise
it is refused at once with the explanation of a ``claude -p`` run.

What is the same: the restriction flags and the environment. The SDK's own
process launcher is not used. It passes the bridge's whole environment on
to the child (it can only add variables, so an API key would leak through)
and falls back to the copy of Claude Code bundled in the package. The
transport below starts exactly the installed ``claude`` named in the
settings, with the allow-listed environment of ``claude.child_env()`` and
the restriction flags of ``claude.run_flags()``; only input and output use
the SDK's stream-json protocol.

Prompt text, answers and stderr are never logged here.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import shutil
import time
from collections.abc import AsyncIterator, Callable, Coroutine
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import claude_agent_sdk
from claude_agent_sdk import (
    ClaudeAgentOptions,
    ClaudeSDKClient,
    ResultMessage,
    StreamEvent,
    ToolResultBlock,
    Transport,
    UserMessage,
)
from claude_agent_sdk._errors import MessageParseError
from claude_agent_sdk.types import PermissionResultAllow, PermissionResultDeny

from . import claude, gate, interactions, push, routing
from .claude import ClaudeError, RunResult
from .config import Settings
from .interactions import ANSWERED, APPROVAL, QUESTION, TIMEOUT, Interactions

GRACE = 5.0  # seconds for an interrupt to end the turn, and for a process to exit
STDERR_KEEP = 64 * 1024  # tail of stderr kept to explain a failed start (never logged)
REAP_INTERVAL = 30.0
KEEPALIVE_SECONDS = 30.0
# Why an interaction closed, as the stream's ``interaction_closed`` event says it.
CLOSED_REASONS = {ANSWERED: "answered", TIMEOUT: "timed_out"}
INPUT_FLAGS = ("--input-format", "stream-json")

# Set by the SDK's own launcher too: they mark the session as an SDK one and
# ask for the session-state frames the SDK reads. They carry no secret.
SDK_ENV = {
    "CLAUDE_CODE_ENTRYPOINT": "sdk-py",
    "CLAUDE_AGENT_SDK_VERSION": claude_agent_sdk.__version__,
    "CLAUDE_CODE_SDK_READS_SESSION_STATE": "1",
}


def package_dir() -> Path:
    """Folder of the installed SDK package (it contains a bundled Claude Code)."""
    return Path(claude_agent_sdk.__file__).resolve().parent


def resolve_cli(settings: Settings) -> str:
    """Absolute path of the installed ``claude`` the settings name; never the bundled copy."""
    found = shutil.which(settings.claude_bin, path=claude.child_env().get("PATH"))
    if found is None:
        raise ClaudeError("claude_not_found", 503, "the claude command is not available")
    path = Path(found).absolute()
    if package_dir() in path.resolve().parents:
        raise ClaudeError(
            "claude_not_found",
            503,
            "the Claude Code copy bundled with the SDK is not used; "
            "LIFEOS_CLAUDE_BIN must name the installed one",
        )
    return str(path)


# What the assistant reads when the bridge says no. Calls that would prompt
# are denied, as in a ``dontAsk`` run.
DENIED_NOTE = "Denied: runs from the phone never ask for permissions beyond what they already have."
NO_CLIENT_QUESTION = (
    "The owner cannot answer structured questions right now. Ask in your reply instead: one "
    "clear question with the choices as a short lettered list (A, B, C)."
)
QUESTION_TIMEOUT = (
    "The owner did not answer in time. Continue without the answer if you can, or ask in your "
    "reply as plain text."
)
QUESTION_DECLINED = "The owner chose not to answer these questions. Continue without them."
APPROVAL_REFUSED = (
    "The owner did not confirm this, so it did not run. Do not try to reach the same result "
    "another way. Tell the owner exactly what you wanted to run and why."
)
APPROVAL_TIMEOUT = (
    "The owner did not confirm this in time, so it did not run. Do not try another way; tell "
    "the owner exactly what you wanted to run and why."
)


# Input of a shell call that can be confirmed: exactly what the owner is shown.
APPROVABLE_KEYS = frozenset({"command", "description", "timeout"})
MEMORY_PUSH_NOTE = (
    "Refused: the memory folder is never pushed or published anywhere. Do not try another "
    "way; tell the owner."
)
UNSANDBOXED_NOTE = (
    "Denied: an action that needs the owner's confirmation can only be asked for as a plain "
    "command inside the sandbox."
)


def child_env(*, capable: bool = False, clean: bool = False) -> dict[str, str]:
    """The allow-listed environment of every run, plus the SDK's protocol markers."""
    return {**claude.child_env(capable=capable, clean=clean), **SDK_ENV}


def build_command(
    settings: Settings,
    *,
    session_id: str,
    resume: bool,
    name: str | None = None,
    context: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    policy: claude.Policy = claude.READ_ONLY,
    advisor: str | None = None,
) -> list[str]:
    """The command of a live session: the flags of ``claude -p``, with the bridge as host.

    ``policy`` is read-only by default; a capable policy (``capable.build_policy``
    with ``ask_host``) adds its settings JSON and folders exactly as on the
    ``claude -p`` path.
    """
    return [
        resolve_cli(settings),
        *claude.STREAM_OUTPUT_FLAGS,
        *claude.run_flags(
            settings,
            session_id=session_id,
            resume=resume,
            name=name,
            policy=policy,
            context=context,
            model=model,
            effort=effort,
            host=True,
            advisor=advisor,
        ),
        *INPUT_FLAGS,
    ]


class InstalledCLITransport(Transport):
    """Runs one command with exactly the environment given; speaks stream-json."""

    def __init__(self, command: list[str], cwd: Path, env: dict[str, str]) -> None:
        self.command = command
        self.cwd = cwd
        self.env = env
        self.process: asyncio.subprocess.Process | None = None
        self._stderr = bytearray()
        self._stderr_task: asyncio.Future | None = None
        self._closing = False

    async def connect(self) -> None:
        if self.process is not None:
            return
        if not self.cwd.is_dir():
            raise ClaudeError(
                "working_directory_missing", 503, "the configured folder does not exist"
            )
        try:
            self.process = await asyncio.create_subprocess_exec(
                *self.command,
                cwd=self.cwd,
                env=self.env,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                limit=claude.STREAM_LINE_LIMIT,
                start_new_session=True,
            )
        except (FileNotFoundError, PermissionError) as exc:
            raise ClaudeError(
                "claude_not_found", 503, "the claude command is not available"
            ) from exc
        self._stderr_task = asyncio.ensure_future(self._keep_stderr())

    async def _keep_stderr(self) -> None:
        assert self.process and self.process.stderr  # noqa: S101
        while chunk := await self.process.stderr.read(4096):
            self._stderr += chunk
            del self._stderr[:-STDERR_KEEP]

    @property
    def pid(self) -> int | None:
        return self.process.pid if self.process is not None else None

    def is_ready(self) -> bool:
        return self.process is not None and self.process.returncode is None and not self._closing

    async def write(self, data: str) -> None:
        if not self.is_ready() or self.process is None or self.process.stdin is None:
            raise ClaudeError("claude_failed", 502, "the assistant process has ended")
        try:
            self.process.stdin.write(data.encode())
            await self.process.stdin.drain()
        except (BrokenPipeError, ConnectionResetError) as exc:
            raise ClaudeError("claude_failed", 502, "the assistant process has ended") from exc

    def read_messages(self) -> AsyncIterator[dict[str, Any]]:
        return self._read()

    async def _read(self) -> AsyncIterator[dict[str, Any]]:
        assert self.process and self.process.stdout  # noqa: S101
        async for raw in self.process.stdout:
            try:
                message = json.loads(raw)
            except ValueError:
                continue
            if isinstance(message, dict):
                yield message
        code = await self.process.wait()
        if code != 0 and not self._closing:
            if self._stderr_task is not None:
                with contextlib.suppress(TimeoutError, Exception):
                    await asyncio.wait_for(asyncio.shield(self._stderr_task), 1.0)
            raise claude._startup_failure(code, bytes(self._stderr))

    async def end_input(self) -> None:
        if self.process is not None and self.process.stdin is not None:
            with contextlib.suppress(Exception):
                self.process.stdin.close()

    async def close(self) -> None:
        """End of input first (the CLI saves the transcript and exits), then escalate."""
        self._closing = True
        process = self.process
        if process is None:
            return
        await self.end_input()
        try:
            await asyncio.wait_for(process.wait(), timeout=GRACE)
        except TimeoutError:
            await claude._stop(process, GRACE)  # interrupt, terminate, kill the group
        if self._stderr_task is not None:
            self._stderr_task.cancel()
            with contextlib.suppress(BaseException):
                await self._stderr_task


@dataclass(frozen=True)
class Launch:
    """What a live process was started with; a message that needs other values restarts it."""

    cwd: Path
    model: str | None
    effort: str | None
    context: str | None
    # Read-only unless the guardrail check passed for this runner (#106).
    policy: claude.Policy = claude.READ_ONLY
    advisor: str | None = None


def observed(message: UserMessage) -> dict:
    """A user message (tool results) in the stream-json shape ``capable.AuditWatch`` reads."""
    content = message.content if isinstance(message.content, list) else []
    return {
        "type": "user",
        "message": {
            "content": [
                {"type": "tool_result", "is_error": bool(block.is_error)}
                for block in content
                if isinstance(block, ToolResultBlock)
            ]
        },
    }


def result_payload(message: ResultMessage) -> dict:
    """The final result in the shape ``claude.parse_result`` reads."""
    return {
        "type": "result",
        "subtype": message.subtype,
        "is_error": message.is_error,
        "result": message.result,
        "errors": message.errors,
        "permission_denials": message.permission_denials,
        "session_id": message.session_id,
        "duration_ms": message.duration_ms,
        "num_turns": message.num_turns,
        # What the run reports about its plan usage (routing.record reads these).
        "usage": getattr(message, "usage", None),
        "total_cost_usd": getattr(message, "total_cost_usd", None),
        "modelUsage": getattr(message, "model_usage", None),
    }


class Turn:
    """One message and its answer: the messages go to ``queue``; ``over`` is set at the end.

    ``answering``: a client streams this turn and can answer questions. While
    the owner is asked (``waiting``), the run's time limit is paused; the
    time spent is added to ``waited``.
    """

    def __init__(self, answering: bool = False) -> None:
        self.queue: asyncio.Queue = asyncio.Queue()
        self.over = asyncio.Event()
        self.answering = answering
        self.waiting = 0
        self.waited = 0.0

    async def wait(self, timeout: float) -> bool:
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(self.over.wait(), timeout)
        return self.over.is_set()


class LiveSession:
    """One SDK client and its process. A single owner task connects, reads and disconnects.

    Messages are read all the time (the SDK's read loop stalls when nobody
    reads, and then not even an interrupt gets through); during a turn they
    go to that turn's queue.
    """

    def __init__(
        self,
        settings: Settings,
        session_id: str,
        launch: Launch,
        *,
        resume: bool,
        name: str | None = None,
        asking: Interactions | None = None,
        capable: bool | None = None,
    ) -> None:
        self.session_id = session_id
        self.settings = settings
        self.launch = launch
        self.asking = asking or Interactions(settings.interaction_timeout)
        # Capable runs: approval actions may be asked for (only with the gate's ``ask``).
        self.capable = launch.policy.capable if capable is None else capable
        command = build_command(
            settings,
            session_id=session_id,
            resume=resume,
            name=name,
            context=launch.context,
            model=launch.model,
            effort=launch.effort,
            policy=launch.policy,
            advisor=launch.advisor,
        )
        env = child_env(capable=launch.policy.capable, clean=launch.policy.clean)
        env |= dict(launch.policy.env)  # an open-network run: GitHub, masked by the sandbox
        self.transport = InstalledCLITransport(command, launch.cwd, env)
        # The options are not applied to a custom transport (the command above
        # is); they only state the binary and folder for anyone reading them.
        options = ClaudeAgentOptions(
            cli_path=command[0], cwd=launch.cwd, can_use_tool=self._can_use_tool
        )
        self.client = ClaudeSDKClient(options, transport=self.transport)
        self.ready = False
        self.dead = False
        self.last_used = time.monotonic()
        self.transcript_mark: tuple[int, int] | None = None
        self._turn: Turn | None = None
        self._close_requested = asyncio.Event()
        self._task: asyncio.Task | None = None

    # -- state ------------------------------------------------------------------
    @property
    def pid(self) -> int | None:
        return self.transport.pid

    @property
    def busy(self) -> bool:
        return self._turn is not None

    @property
    def idle(self) -> bool:
        return self.ready and not self.busy and not self.dead

    @property
    def running(self) -> bool:
        """A turn is in progress (what run control may stop)."""
        return self.busy and not self.dead

    def _transcript_state(self) -> tuple[int, int] | None:
        path = claude.project_dir(self.launch.cwd) / f"{self.session_id}.jsonl"
        try:
            info = path.stat()
        except OSError:
            return None
        return (info.st_size, info.st_mtime_ns)

    def changed_elsewhere(self) -> bool:
        """The transcript changed since this process last wrote it (the terminal resumed it)."""
        return self.transcript_mark is not None and self._transcript_state() != self.transcript_mark

    # -- life cycle -------------------------------------------------------------
    async def start(self) -> None:
        ready: asyncio.Future = asyncio.get_running_loop().create_future()
        self._task = asyncio.create_task(self._own(ready))
        await ready
        self.ready = True

    async def _own(self, ready: asyncio.Future) -> None:
        try:
            await self.client.connect()
        except BaseException as exc:
            self.dead = True
            if not ready.done():
                ready.set_exception(_as_claude_error(exc, "the assistant could not be started"))
            return
        ready.set_result(None)
        pump = asyncio.create_task(self._pump())
        closing = asyncio.create_task(self._close_requested.wait())
        try:
            await asyncio.wait({pump, closing}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            self.dead = True
            closing.cancel()
            pump.cancel()
            with contextlib.suppress(BaseException):
                await pump
            with contextlib.suppress(Exception):
                await self.client.disconnect()
            self._end_turn(ClaudeError("claude_failed", 502, "the assistant process ended"))

    async def _pump(self) -> None:
        while True:
            try:
                async for message in self.client.receive_messages():
                    turn = self._turn
                    if turn is not None:
                        turn.queue.put_nowait(message)
                    if isinstance(message, ResultMessage):
                        self.last_used = time.monotonic()
                        self.transcript_mark = self._transcript_state()
                        self._turn = None
                        if turn is not None:
                            turn.over.set()
                return
            except MessageParseError:
                continue  # one unreadable message does not end the session
            except Exception as exc:  # the process ended; the turn learns why
                self._end_turn(_as_claude_error(exc, "the assistant process ended"))
                return

    def _end_turn(self, error: ClaudeError) -> None:
        turn, self._turn = self._turn, None
        if turn is not None:
            turn.queue.put_nowait(error)
            turn.over.set()

    async def close(self) -> None:
        """Close the process (the transcript keeps the conversation). Never cancels the owner."""
        self.asking.cancel(self.session_id)
        self._close_requested.set()
        if self._task is not None:
            await asyncio.wait({self._task})

    # -- turns ------------------------------------------------------------------
    async def send(self, prompt: str, *, answering: bool = False) -> Turn:
        """Start a turn; its messages, and a ClaudeError if the process ends, go to its queue."""
        if not self.idle:
            raise ClaudeError("session_in_use", 409, "this session is already answering a prompt")
        turn = self._turn = Turn(answering)
        self.last_used = time.monotonic()
        try:
            await self.client.query(prompt)
        except Exception as exc:
            error = _as_claude_error(exc, "the assistant process has ended")
            self._end_turn(error)
            raise error from exc
        return turn

    async def wait_turn_over(self, timeout: float) -> bool:
        turn = self._turn
        return turn is None or await turn.wait(timeout)

    async def stop(self, grace: float = GRACE, turn: Turn | None = None) -> None:
        """Interrupt a turn (default: the current one) with the SDK's interrupt.

        The process is closed only when the turn does not end within ``grace``.
        A stop for a turn that has already ended does nothing, so it can
        never reach the next message.
        """
        turn = turn or self._turn
        if turn is None or turn.over.is_set() or turn is not self._turn or self.dead:
            return
        self.asking.cancel(self.session_id)  # a pending question or approval is a "no"
        with contextlib.suppress(Exception):
            await asyncio.wait_for(self.client.interrupt(), grace)
        if not await turn.wait(grace) and turn is self._turn:
            await self.close()

    # -- the permission host ----------------------------------------------------
    async def _can_use_tool(self, tool_name: str, tool_input: dict, context: Any):
        """Every call that would prompt comes here. Default: deny, as ``dontAsk`` does."""
        try:
            if tool_name == claude.QUESTION_TOOL:
                return await self._question(tool_input)
            if self.capable and tool_name == "Bash":
                return await self._approval(tool_input)
        except Exception:  # anything unexpected is a "no"
            return PermissionResultDeny(message=DENIED_NOTE)
        return PermissionResultDeny(message=DENIED_NOTE)

    async def _ask(self, kind: str, data: dict) -> tuple[str, dict | None]:
        """Show the owner a question or approval and wait; ``("no_client", None)`` if nobody can."""
        turn = self._turn
        if turn is None or not turn.answering or turn.over.is_set():
            return "no_client", None
        interaction = self.asking.open(self.session_id, kind, data)
        started = time.monotonic()
        turn.waiting += 1
        turn.queue.put_nowait(("interaction", interaction.public()))
        _wake_phones(kind, self.session_id)
        outcome = "run_ended"  # stopped while waiting
        try:
            result = await self.asking.wait(interaction)
            outcome = result[0]
            return result
        finally:
            turn.waiting -= 1
            turn.waited += time.monotonic() - started
            reason = CLOSED_REASONS.get(outcome, outcome)
            turn.queue.put_nowait(("interaction_closed", {"id": interaction.id, "reason": reason}))
            # Answered, expired, refused or the run ended: the notification goes away.
            _wake_phones("cleared", self.session_id)

    async def _question(self, tool_input: dict):
        data = interactions.question_data(tool_input)
        if data is None:
            return PermissionResultDeny(message=DENIED_NOTE)
        outcome, answer = await self._ask(QUESTION, data)
        if outcome == "no_client":
            return PermissionResultDeny(message=NO_CLIENT_QUESTION)
        if outcome != ANSWERED or answer is None:
            return PermissionResultDeny(message=QUESTION_TIMEOUT)
        if answer.get("decline"):
            return PermissionResultDeny(message=QUESTION_DECLINED)
        updated = {"questions": tool_input["questions"], "answers": answer["answers"]}
        if "response" in answer:
            updated["response"] = answer["response"]
        return PermissionResultAllow(updated_input=updated)

    async def _approval(self, tool_input: dict):
        """An action the gate sent here (``ask``): runs only after the owner's explicit yes."""
        command = str(tool_input.get("command") or "")
        if set(tool_input) - APPROVABLE_KEYS or tool_input.get("dangerouslyDisableSandbox"):
            # The owner sees the command and the folder; nothing else may ride along
            # (above all no request to run outside the sandbox).
            return PermissionResultDeny(message=UNSANDBOXED_NOTE)
        found = gate.classify(command)
        if found is None or found[2] != gate.APPROVAL_NOTE:
            return PermissionResultDeny(message=DENIED_NOTE)  # only approval actions are asked
        category, reason, _note = found
        record = {
            "session_id": self.session_id,
            "cwd": str(self.launch.cwd),
            "tool": "Bash",
            "detail": {"command": command},
            "category": category,
            "source": "bridge",  # not one of the gate's records (see capable.AuditWatch)
        }
        if category == "push" and self._touches_memory(command):
            # The memory folder is never pushed (the owner's rule): refused without asking.
            _audit(record, "refused", "memory folder")
            return PermissionResultDeny(message=MEMORY_PUSH_NOTE)
        if not _audit(record, "approval_requested"):
            return PermissionResultDeny(message=gate.APPROVAL_NOTE)
        card = {"tool": "Bash", "command": command, "folder": str(self.launch.cwd),
                "category": category, "reason": reason}  # fmt: skip
        outcome, answer = await self._ask(APPROVAL, card)
        approved = outcome == ANSWERED and bool(answer and answer.get("approve"))
        why = "owner" if outcome == ANSWERED else outcome
        if not _audit(record, "approved" if approved else "refused", why):
            return PermissionResultDeny(message=gate.APPROVAL_NOTE)
        if approved:
            # Exactly what was shown; it runs inside the sandbox, with the run's network
            # (the open network of #182 when the check proved it).
            return PermissionResultAllow(updated_input=tool_input)
        if outcome == "no_client":
            return PermissionResultDeny(message=gate.APPROVAL_NOTE)  # today's refusal
        note = APPROVAL_TIMEOUT if outcome == TIMEOUT else APPROVAL_REFUSED
        return PermissionResultDeny(message=note)

    def _touches_memory(self, command: str) -> bool:
        """A push from the memory folder, or one that names it (``git -C <memory> push``)."""
        memory = self.settings.ask_dir.resolve()
        folder = self.launch.cwd.resolve()
        return folder == memory or folder.is_relative_to(memory) or str(memory) in command


_hints: set[asyncio.Task] = set()


def _wake_phones(kind: str, session_id: str) -> None:
    """A sealed push hint that a question or approval waits, or no longer waits ("cleared").

    One hint when it opens, one when it closes; nothing is ever sent again for it. Never
    blocks the run."""
    task = asyncio.create_task(asyncio.to_thread(push.send_hint, kind, session_id))
    _hints.add(task)
    task.add_done_callback(_hints.discard)


def _audit(record: dict, decision: str, why: str = "") -> bool:
    """Write an approval step to the audit log; False (the call is then refused) if impossible."""
    from . import audit  # the bridge's own records, in the same monthly files as the gate's

    entry = {"ts": datetime.now(UTC).isoformat(timespec="milliseconds"), **record}
    entry["decision"] = decision
    if why:
        entry["why"] = why
    try:
        audit.write(entry)
    except OSError:
        return False
    return True


def _as_claude_error(exc: BaseException, message: str) -> ClaudeError:
    if isinstance(exc, ClaudeError):
        return exc
    return ClaudeError("claude_failed", 502, message)


class SdkPool:
    """The live sessions. At most ``settings.sdk_max_live``; idle ones close after a while."""

    def __init__(self, settings: Settings, asking: Interactions | None = None) -> None:
        self.settings = settings
        self.asking = asking or Interactions(settings.interaction_timeout)
        self.live: dict[str, LiveSession] = {}
        self._lock = asyncio.Lock()
        self._background: set[asyncio.Task] = set()

    def pids(self) -> frozenset[int]:
        return frozenset(s.pid for s in self.live.values() if s.pid is not None)

    def spawn(self, coroutine: Coroutine[Any, Any, Any]) -> None:
        """Run cleanup outside the request that triggered it (it may be cancelled)."""
        task = asyncio.create_task(coroutine)
        self._background.add(task)
        task.add_done_callback(self._background.discard)

    async def drop(self, session_id: str) -> None:
        live = self.live.pop(session_id, None)
        if live is not None:
            await live.close()

    async def session_for(
        self, session_id: str, launch: Launch, *, resume: bool, name: str | None = None
    ) -> LiveSession:
        """The live session to send to: kept, or (re)started. Never two processes per session."""
        async with self._lock:
            live = self.live.get(session_id)
            if live is not None:
                if live.busy and not await live.wait_turn_over(2 * GRACE):
                    await live.close()  # an abandoned turn that would not end
                if live.dead or live.launch != launch or live.changed_elsewhere():
                    await self.drop(session_id)
                    resume = True  # it ran here before: continue from the transcript
                else:
                    return live
            while len(self.live) >= self.settings.sdk_max_live:
                idle = [s for s in self.live.values() if s.idle or s.dead]
                if not idle:
                    raise ClaudeError(
                        "busy", 429, "too many conversations are active; try again shortly"
                    )
                await self.drop(min(idle, key=lambda s: (not s.dead, s.last_used)).session_id)
            live = LiveSession(
                self.settings, session_id, launch, resume=resume, name=name, asking=self.asking
            )
            self.live[session_id] = live
        try:
            await live.start()
        except BaseException:
            if self.live.get(session_id) is live:
                del self.live[session_id]
            raise
        return live

    async def reap(self, now: float | None = None) -> None:
        """Close sessions idle for longer than the setting, and forget dead ones."""
        now = time.monotonic() if now is None else now
        async with self._lock:
            for session_id, live in list(self.live.items()):
                expired = live.idle and now - live.last_used >= self.settings.sdk_idle_seconds
                if live.dead or expired:
                    await self.drop(session_id)

    async def reap_forever(self) -> None:
        while True:
            await asyncio.sleep(REAP_INTERVAL)
            with contextlib.suppress(Exception):
                await self.reap()

    async def close_all(self) -> None:
        sessions = list(self.live.values())
        self.live.clear()
        await asyncio.gather(*(s.close() for s in sessions), return_exceptions=True)
        for task in list(self._background):
            with contextlib.suppress(BaseException):
                await task


async def _events(
    pool: SdkPool,
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
    on_process: Callable[[LiveSession], None] | None,
    answering: bool = False,
    policy: claude.Policy = claude.READ_ONLY,
    observer: Callable[[dict], None] | None = None,
    advisor: str | None = None,
    job: str = "chat",
) -> AsyncIterator[tuple[str, Any]]:
    """``delta`` and ``interaction`` events, then ``("done", RunResult)``; failures raise.

    The time limit does not run while the owner is being asked.
    """
    launch = Launch(
        cwd=cwd or settings.ask_dir,
        model=model,
        effort=effort,
        context=context,
        policy=policy,
        advisor=advisor,
    )
    live = await pool.session_for(session_id, launch, resume=resume, name=name)
    if on_process is not None:
        on_process(live)
    turn = await live.send(prompt, answering=answering)
    final: ResultMessage | None = None
    loop = asyncio.get_running_loop()
    deadline = loop.time() + settings.ask_timeout
    try:
        while final is None:
            if turn.waiting:
                # The owner is being asked: no time limit, but a sign of life now and then,
                # so that a reader with a read timeout (the relay) keeps the stream open.
                try:
                    item = await asyncio.wait_for(turn.queue.get(), KEEPALIVE_SECONDS)
                except TimeoutError:
                    yield "keepalive", {}
                    continue
            else:
                left = deadline + turn.waited - loop.time()
                if left <= 0:
                    raise TimeoutError
                item = await asyncio.wait_for(turn.queue.get(), left)
            if isinstance(item, ClaudeError):
                raise item
            if isinstance(item, tuple):  # ("interaction" | "interaction_closed", data)
                yield item
            elif isinstance(item, ResultMessage):
                final = item
            elif isinstance(item, UserMessage) and observer is not None:
                observer(observed(item))  # may raise ClaudeError to stop the run
            elif isinstance(item, StreamEvent):
                inner = item.event or {}
                delta = inner.get("delta") or {}
                if inner.get("type") == "content_block_delta" and (
                    delta.get("type") == "text_delta"
                ):
                    yield "delta", {"text": str(delta.get("text") or "")}
    except TimeoutError:
        await live.stop(grace, turn)
        raise ClaudeError("timeout", 504, "the run took too long and was stopped") from None
    except ClaudeError:
        await live.stop(grace, turn)  # an observer stopped it, or the process ended
        raise
    except BaseException:
        # Abandoned (the client went away): end the turn in the background, so
        # the next message never meets a turn that is still writing.
        pool.spawn(live.stop(grace, turn))
        raise
    payload = result_payload(final)
    routing.record(job, payload, resumed=resume)  # also for a failed run: it used the plan too
    result = claude.parse_result(payload)
    yield "done", result


async def stream(
    pool: SdkPool,
    settings: Settings,
    prompt: str,
    *,
    session_id: str,
    resume: bool,
    cwd: Path | None = None,
    name: str | None = None,
    grace: float = GRACE,
    context: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    advisor: str | None = None,
    on_process: Callable[[LiveSession], None] | None = None,
    policy: claude.Policy = claude.READ_ONLY,
    observer: Callable[[dict], None] | None = None,
    answering: bool = True,
    job: str = "chat",
) -> AsyncIterator[tuple[str, dict]]:
    """Same events as ``claude.stream``: ``delta``s, then one ``done`` or ``error``.

    Plus ``interaction`` (a question or approval waiting for the owner, see
    ``interactions``): the client streaming the answer can answer it.
    """
    events = _events(
        pool, settings, prompt, session_id=session_id, resume=resume, cwd=cwd, name=name,
        grace=grace, context=context, model=model, effort=effort, on_process=on_process,
        answering=answering, policy=policy, observer=observer,
        advisor=advisor, job=job,
    )  # fmt: skip
    try:
        # Closed at once when the caller goes away, so an abandoned turn is ended now.
        async with contextlib.aclosing(events):
            async for event, payload in events:
                if event in ("delta", "interaction", "interaction_closed", "keepalive"):
                    yield event, payload
                    continue
                result: RunResult = payload
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
    except ClaudeError as error:
        yield "error", {"error": error.code, "message": error.message}


async def run(
    pool: SdkPool,
    settings: Settings,
    prompt: str,
    *,
    session_id: str,
    resume: bool,
    cwd: Path | None = None,
    name: str | None = None,
    grace: float = GRACE,
    context: str | None = None,
    model: str | None = None,
    effort: str | None = None,
    advisor: str | None = None,
    on_process: Callable[[LiveSession], None] | None = None,
    policy: claude.Policy = claude.READ_ONLY,
    observer: Callable[[dict], None] | None = None,
    answering: bool = False,  # accepted for symmetry: nobody can answer a run that is not streamed
    job: str = "chat",
) -> RunResult:
    """Same as ``claude.run``: the whole answer, or a ClaudeError."""
    events = _events(
        pool, settings, prompt, session_id=session_id, resume=resume, cwd=cwd, name=name,
        grace=grace, context=context, model=model, effort=effort, on_process=on_process,
        policy=policy, observer=observer,
        advisor=advisor, job=job,
    )  # fmt: skip
    async with contextlib.aclosing(events):
        async for event, payload in events:
            if event == "done":
                return payload
    raise ClaudeError("bad_output", 502, "claude returned an unexpected answer format")
