"""The ASGI application."""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import hashlib
import hmac
import json
import time
import uuid
from collections.abc import AsyncIterator
from datetime import date, datetime, timedelta
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from . import (
    __version__,
    actions,
    activity,
    allowed,
    approvals,
    audit,
    capable,
    chats,
    claude,
    coach,
    coach_chat,
    command_bar,
    commands,
    consolidation,
    contextmap,
    conversations,
    course_folders,
    files,
    folder_requests,
    folders,
    guardcheck,
    history,
    live,
    mcp_servers,
    memory,
    moodle_api,
    moodle_sync,
    moodle_watch,
    moves,
    network,
    permission,
    program,
    projects,
    push,
    relay,
    release,
    reminders,
    resources,
    routing,
    runcontrol,
    sdk_runner,
    session_links,
    session_meta,
    sessions,
    skill_installs,
    skills,
    updates,
)
from .claude import ClaudeError
from .config import Settings

MAX_PROMPT_CHARS = 100_000
MAX_BODY_BYTES = 1_000_000

# The only paths reachable without the bearer token. Everything else,
# including unknown paths, is rejected before routing (default deny).
PUBLIC_PATHS = frozenset({"/health"})


def _token_matches(header: str | None, token: str) -> bool:
    if not header:
        return False
    scheme, _, presented = header.partition(" ")
    if scheme.lower() != "bearer":
        return False
    return hmac.compare_digest(presented.strip().encode(), token.encode())


class RunSlots:
    """Limits how many Claude processes run at once, and one per session."""

    def __init__(self, limit: int) -> None:
        self.limit = limit
        self.active: set[str] = set()

    def acquire(self, session_id: str) -> None:
        if session_id in self.active:
            raise ClaudeError("session_in_use", 409, "this session is already answering a prompt")
        if len(self.active) >= self.limit:
            raise ClaudeError("busy", 429, "too many prompts are running; try again shortly")
        self.active.add(session_id)

    def release(self, session_id: str) -> None:
        self.active.discard(session_id)


def error_response(error: ClaudeError) -> JSONResponse:
    return JSONResponse({"error": error.code, "message": error.message}, status_code=error.status)


async def read_json_object(request: Request) -> dict:
    """Read a small JSON object body, with clear errors instead of framework ones."""
    declared = request.headers.get("content-length", "")
    if declared.isdigit() and int(declared) > MAX_BODY_BYTES:
        raise ClaudeError("too_large", 413, "the request is too large")
    body = b""
    async for chunk in request.stream():
        body += chunk
        if len(body) > MAX_BODY_BYTES:
            raise ClaudeError("too_large", 413, "the request is too large")
    try:
        data = json.loads(body)
    except ValueError as exc:
        raise ClaudeError("bad_request", 400, "the request body must be JSON") from exc
    if not isinstance(data, dict):
        raise ClaudeError("bad_request", 400, "the request body must be a JSON object")
    # Keys starting with "_" are the bridge's own markers (a queued message, an
    # admitted run); a client never sets them.
    return {key: value for key, value in data.items() if not str(key).startswith("_")}


def read_prompt(data: dict) -> str:
    prompt = data.get("prompt")
    if not isinstance(prompt, str) or not prompt.strip():
        raise ClaudeError("bad_request", 400, "`prompt` must be a non-empty string")
    if len(prompt) > MAX_PROMPT_CHARS:
        raise ClaudeError(
            "prompt_too_large", 413, f"the prompt is longer than {MAX_PROMPT_CHARS} characters"
        )
    return prompt


def read_mode(data: dict) -> str:
    """``read_only`` unless the request explicitly asks for ``capable``."""
    mode = data.get("mode", "read_only")
    if mode not in ("read_only", "capable"):
        raise ClaudeError("bad_request", 400, "`mode` must be `read_only` or `capable`")
    return mode


def sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode()


KEEPALIVE_SECONDS = 20.0  # a run that is quiet this long (a long tool call) sends a sign of life
HELD_SECONDS = 10.0  # how long the list of sessions open elsewhere is reused
CHAT_SWEEP_SECONDS = 60.0  # how often chat folders without files are looked for


async def with_keepalives(events: AsyncIterator, every: float | None = None) -> AsyncIterator:
    """The events of a run, plus ``("keepalive", {})`` whenever none came for a while.

    One task reads the run from start to end (the run's own time limit is bound
    to that task); a keep-alive only fills the silence. Closing this generator
    (the client went away) cancels that task, which stops the run as before.
    """
    queue: asyncio.Queue = asyncio.Queue()

    async def pump() -> None:
        try:
            async for item in events:
                await queue.put(("item", item))
            await queue.put(("end", None))
        except Exception as error:  # handed to the reader, raised there
            await queue.put(("error", error))

    reader = asyncio.create_task(pump())
    try:
        while True:
            try:
                kind, value = await asyncio.wait_for(queue.get(), every or KEEPALIVE_SECONDS)
            except TimeoutError:
                yield "keepalive", {}
                continue
            if kind == "end":
                return
            if kind == "error":
                raise value
            yield value
    finally:
        if not reader.done():
            reader.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await reader


def create_app(settings: Settings) -> FastAPI:
    # Everything the bridge runs by itself, as it is now (#180): every module imported,
    # private copies of the gate and of notify.sh, the fingerprint of the code.
    program.pin()

    # No interactive docs or schema endpoints: less surface, nothing to leak.
    @contextlib.asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        async def watch_reminders() -> None:
            while True:
                with contextlib.suppress(Exception):  # the watcher must survive a bad file
                    await asyncio.to_thread(
                        reminders.fire_due, notify_script=settings.notify_script
                    )
                held = applier.holding  # an approved update is being applied (#180)
                with contextlib.suppress(Exception):  # the coach never stops the reminders
                    if not held:
                        await coach_tick()
                with contextlib.suppress(Exception):  # at most one request to the site per day
                    await asyncio.to_thread(
                        moodle_watch.check, notify_script=settings.notify_script
                    )
                with contextlib.suppress(Exception):  # the course sync, every six hours
                    if not held:
                        await course_sync_tick()
                with contextlib.suppress(Exception):  # resources of a new syllabus, one at a time
                    if not held:
                        await resources_tick()
                with contextlib.suppress(Exception):  # the nightly consolidation proposal
                    if not held:
                        await consolidation_tick()
                await asyncio.sleep(reminders.CHECK_INTERVAL_SECONDS)

        coach_runs: set[asyncio.Task] = set()

        async def coach_tick() -> None:
            """The morning plan (as its own task: a run takes a while) and its notification."""
            if not coach_runs and await asyncio.to_thread(coach.due_for_morning_run):
                run = asyncio.create_task(coach.morning_run(settings))
                coach_runs.add(run)
                run.add_done_callback(coach_runs.discard)
            await asyncio.to_thread(coach.send_daily_notification, settings)
            await asyncio.to_thread(coach.send_nudges, settings)  # within the daily cap

        course_syncs: set[asyncio.Task] = set()

        async def course_sync_tick() -> None:
            """Courses from the university Moodle (as its own task: downloads take a while)."""
            if not course_syncs and await asyncio.to_thread(moodle_sync.due):
                run = asyncio.create_task(asyncio.to_thread(moodle_sync.run, settings))
                course_syncs.add(run)
                run.add_done_callback(course_syncs.discard)

        resource_runs: set[asyncio.Task] = set()

        async def resources_tick() -> None:
            """A syllabus is new, changed or asked for: one course-resources run, apart
            from the six-hour sync (which never starts a run)."""
            if resource_runs:
                return
            if await asyncio.to_thread(resources.due):
                run = asyncio.create_task(resources.run_next(settings))
                resource_runs.add(run)
                run.add_done_callback(resource_runs.discard)
                return
            # Nothing running or waiting: the day's one summary of what was downloaded.
            await asyncio.to_thread(resources.send_summary, settings.notify_script)

        consolidation_runs: set[asyncio.Task] = set()

        async def consolidation_tick() -> None:
            """Nightly: a proposal to tidy the memory; nothing is applied by itself."""
            if not consolidation_runs and await asyncio.to_thread(consolidation.due, settings):
                start_consolidation()

        def start_consolidation() -> None:
            run = asyncio.create_task(consolidation.run(settings))
            consolidation_runs.add(run)
            run.add_done_callback(consolidation_runs.discard)

        async def sweep_chats_forever() -> None:
            """Chat folders without files go once nothing runs in them (an SDK session
            that was closed for being idle, a first message that was refused)."""
            while True:
                with contextlib.suppress(Exception):
                    sweep_chats()
                await asyncio.sleep(CHAT_SWEEP_SECONDS)

        _app.state.start_consolidation = start_consolidation
        # Background jobs an update waits for (#180).
        _app.state.jobs = {
            "coach": coach_runs, "course sync": course_syncs,
            "course resources": resource_runs, "consolidation": consolidation_runs,
        }  # fmt: skip
        # An update that was under way when the bridge stopped: applied or interrupted.
        with contextlib.suppress(Exception):
            await asyncio.to_thread(updates.after_start)
        task = asyncio.create_task(watch_reminders())
        sweeping = asyncio.create_task(sweep_chats_forever())
        # SDK runner: live sessions close after a while without messages.
        reaper = asyncio.create_task(sdk_pool.reap_forever()) if sdk_pool is not None else None
        # The guardrail check runs by itself (#181: core set after updates, monthly full).
        guard = guardcheck.GuardCheck(settings)
        guard.held = lambda: applier.holding
        # The monthly full check waits for a quiet moment (#181): nothing else running.
        guard.busy = lambda: any(found != "guardrail check" for found in update_blockers())
        _app.state.guard = guard
        guarding = asyncio.create_task(guard.run_forever())
        # Phones reach the bridge through the relay; without the owner's Firebase
        # files the bridge is local only.
        phone_relay = await asyncio.to_thread(
            relay.start_if_configured,
            settings,
            getattr(_app.state, "offers", None),
            relay.route_table(_app),
        )
        try:
            yield
        finally:
            if phone_relay is not None:
                phone_relay.close()
            moodle_sync.cancel()  # a running course sync starts no further download
            running = (
                task, sweeping, *coach_runs, *course_syncs, *resource_runs, *consolidation_runs
            )  # fmt: skip
            updating = [applier.task] if applier.task is not None else []
            for pending in (*running, *updating, *([reaper] if reaper else [])):
                pending.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await pending
            guarding.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await guarding
            await guard.close()  # a running check is stopped: not run, the mode as it was
            if sdk_pool is not None:
                await sdk_pool.close_all()  # the transcripts keep every conversation
            with contextlib.suppress(Exception):
                sweep_chats()  # nothing runs any more

    app = FastAPI(
        title="LifeOS bridge",
        version=__version__,
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings

    @app.middleware("http")
    async def require_bearer_token(request: Request, call_next):
        if request.url.path not in PUBLIC_PATHS and not _token_matches(
            request.headers.get("authorization"), settings.token
        ):
            return JSONResponse(
                {"error": "unauthorized"},
                status_code=401,
                headers={"WWW-Authenticate": "Bearer"},
            )
        return await call_next(request)

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/whoami")
    async def whoami() -> dict[str, str]:
        """Authenticated no-op: lets a client verify its token."""
        return {"status": "ok", "version": __version__}

    slots = RunSlots(settings.max_concurrent)
    app.state.slots = slots

    # Requests being answered now; an update waits for them (#180). A streamed answer counts
    # until its first byte; the run behind it is in ``slots``.
    in_flight = [0]

    @app.middleware("http")
    async def count_requests(request: Request, call_next):
        in_flight[0] += 1
        try:
            return await call_next(request)
        finally:
            in_flight[0] -= 1

    @app.exception_handler(ClaudeError)
    async def claude_error_handler(request: Request, error: ClaudeError) -> JSONResponse:
        return error_response(error)

    offers = files.Offers(settings)
    app.state.offers = offers
    # Standing instructions for every run that answers the phone.
    phone_instructions = (
        "You are answering the owner through their phone app. "
        + files.INSTRUCTIONS
        + " When you need the owner to decide something, ask one clear question at a time and "
        "give the choices as a short lettered list (A, B, C), so that a one-letter reply is "
        "enough. " + skill_installs.INSTRUCTIONS
    )
    if settings.runner == "sdk":
        phone_instructions += (
            " If the question tool is available you may use it instead for a choice between a few "
            "options; when it is refused, ask in your reply as described."
        )

    def known_sessions() -> list[sessions.SessionInfo]:
        """The sessions the bridge lists: in the allowed folders, and the blank chats."""
        found = sessions.list_sessions(settings.session_roots)
        roots = settings.allowed_roots

        def shown(info: sessions.SessionInfo) -> bool:
            # Never the sessions of other agents' worktrees in the program folder (#180).
            return chats.session_ok(settings, info.folder) and not program.out_of_reach(
                info.folder, roots
            )

        return [info for info in found if shown(info)]

    app.state.known_sessions = known_sessions

    def known_session(session_id: object) -> sessions.SessionInfo | None:
        if not isinstance(session_id, str):
            return None
        info = sessions.find_session(session_id, settings.session_roots)
        if info is None or program.out_of_reach(info.folder, settings.allowed_roots):
            return None
        return info if chats.session_ok(settings, info.folder) else None

    # Moving between conversations by asking (#164): where the index is, and the answer.
    phone_instructions += " " + conversations.instructions(settings)

    def with_switch(
        payload: dict, session_id: str, owner: bool = False, mode: str = "read_only"
    ) -> None:
        """A fresh answer that asks to open, offer or start a conversation: the ``switch``
        item. Everything it names is checked against the bridge's own lists.

        ``mode`` is the mode of the run that answered. A new chat takes it over (#190):
        a folder of the item says ``"mode": "capable"`` when a chat there starts with it,
        clamped by ``permission.granted``, and only in the answer to a message the owner
        sent; without it the new chat is read-only.

        Folders by asking (#179) change the allowed folders without an approval prompt,
        so they act only in the answer to a message the owner sent (``owner``); a find
        that led somewhere wins over a conversation line of the same answer."""
        answer = str(payload.get("answer") or "")
        if "[[switch" not in answer:
            return
        answer, folder_switch = folder_requests.from_answer(settings, answer, owner=owner)
        found = known_sessions()
        # A request carried to a new chat (#187) only for the owner's message, like a find.
        text, switch = conversations.from_answer(settings, answer, session_id, found, owner=owner)
        payload["answer"] = text
        item = folder_switch or switch
        if item is None:
            return
        payload["switch"] = item
        if isinstance(item.get("forward"), str):
            permission.remember(item["forward"])  # that first message is not the owner's own
        if not owner or mode != "capable":
            return  # no ``mode`` on a folder: a chat there starts read-only
        for folder in [item.get("folder"), *(item.get("folders") or [])]:
            if isinstance(folder, dict) and folder.get("path"):
                kind = folder.get("kind")
                if permission.granted(settings, Path(folder["path"]), mode, kind) == "capable":
                    folder["mode"] = "capable"

    def described(session_id: str | None) -> dict | None:
        """A conversation as a ``switch`` item names it, read fresh; ``None`` when not listed."""
        info = known_session(session_id) if session_id else None
        if info is None:
            return None
        return conversations.session_item(info, folders.Folders(settings), session_meta.load())

    def link_origin(origin: str, new_id: str) -> None:
        """A new chat started from a switch (#187): tie it to the old chat's last answer,
        the one that asked for the switch. Ids only; best effort."""
        info = known_session(origin)
        path = history.transcript_path(info, settings.session_roots) if info else None
        if path is None or origin == new_id:
            return
        exchanges = history.read_page(path, 1)["exchanges"]
        if exchanges:
            with contextlib.suppress(OSError):
                session_links.record(origin, exchanges[-1]["id"], new_id)

    def with_installs(payload: dict, session_id: str, cwd: Path) -> None:
        """Skill-install proposals of a capable run: cards, then a download into quarantine."""
        text, cards = skill_installs.from_answer(str(payload.get("answer") or ""), session_id, cwd)
        if not cards:
            return
        payload["answer"] = text
        payload["skill_installs"] = cards
        for card in cards:
            if card.get("state") == "downloading":
                task = asyncio.create_task(asyncio.to_thread(skill_installs.prepare, card["id"]))
                background.add(task)
                task.add_done_callback(background.discard)
                wake = asyncio.create_task(
                    asyncio.to_thread(push.send_hint, "approval", session_id)
                )
                background.add(wake)
                wake.add_done_callback(background.discard)

    def remember_servers(session_id: str, names: list[str], started: float) -> None:
        """Tie the servers a finished run had to its exchange in the history (#157).

        Runs of one session never overlap, so the transcript's last exchange is
        this run's, if it began after the run started (a prompt the history does
        not count as the owner's, such as a command, has none: nothing is tied).
        Best effort: the answer is not held up by a failure here.
        """
        try:
            info = known_session(session_id)
            path = history.transcript_path(info, settings.session_roots) if info else None
            page = history.read_page(path, 1) if path is not None else {"exchanges": []}
            if not page["exchanges"]:
                return
            last = page["exchanges"][-1]
            at = last.get("at")
            if at is not None:  # Claude Code stamps its lines; a line without one is taken
                when = datetime.fromisoformat(at.replace("Z", "+00:00")).timestamp()
                if when < started - 2:  # an earlier exchange: not this run's
                    return
            mcp_servers.remember_used(session_id, last["id"], names)
        except Exception:  # noqa: S110 - a missing note in the history only
            pass

    def clear_notification(session_id: str) -> None:
        """No card of the session waits any more: the phone removes its approval notification
        (#177). Called after an answer or an expiry of a stored card (installs, MCP servers,
        file moves, updates); the questions and approvals of a run clear their own."""
        if approvals.pending(session_id):
            return
        if sdk_pool is not None and sdk_pool.asking.pending(session_id):
            return
        push.send_hint("cleared", session_id)

    def expire_approvals() -> None:
        expired = approvals.take_expired()
        for entry in expired:
            if entry.get("kind") == skill_installs.KIND:
                skill_installs.expire(entry)
            elif entry.get("kind") == moves.KIND:
                moves.expire(entry)
            elif entry.get("kind") == updates.KIND:
                updates.expire(entry)
        for session_id in {str(entry.get("session_id") or "") for entry in expired} - {""}:
            clear_notification(session_id)

    def with_files(payload: dict, session_id: str, cwd: Path) -> dict:
        """Turn file-offer lines of a finished answer into cards."""
        text, cards, refused = offers.from_answer(str(payload.get("answer") or ""), session_id, cwd)
        payload["answer"] = text
        payload["files"] = cards
        if refused:
            payload["files_refused"] = refused
        return payload

    def with_moves(payload: dict, session_id: str, cwd: Path) -> None:
        """Proposals of a blank chat to move its files into a project: approval cards."""
        text, cards = moves.from_answer(settings, str(payload.get("answer") or ""), session_id, cwd)
        if not cards:
            return
        payload["answer"] = text
        payload["file_moves"] = cards
        if any(card.get("state") == "ready" for card in cards):
            wake = asyncio.create_task(asyncio.to_thread(push.send_hint, "approval", session_id))
            background.add(wake)
            wake.add_done_callback(background.discard)

    def with_update(payload: dict, session_id: str, cwd: Path, owner: bool) -> None:
        """A capable run in the program folder asks for its changes to be applied (#180).

        Since #183 no card: in an answer to the owner's own message the update is
        scheduled at once; the applier waits until this run and every other has ended."""
        answer = str(payload.get("answer") or "")
        if "[[apply-update" not in answer:
            return
        text, refused, scheduled = updates.from_answer(
            settings, answer, session_id, cwd, owner=owner
        )
        payload["answer"] = text
        if refused:
            payload["update_requests"] = refused
        if scheduled:
            applier.start()

    # Blank chats (#163): the folder of every chat a run is using right now, so that it is
    # never removed under a run. A live SDK session keeps its folder too (it is the
    # working directory of its process).
    chat_runs: dict[str, Path] = {}

    def chat_folders_in_use() -> set[Path]:
        held = set(chat_runs.values())
        if sdk_pool is not None:
            held |= {live.launch.cwd for live in sdk_pool.live.values() if not live.dead}
        return held

    def tidy_chat(folder: Path) -> None:
        """Remove a chat's folder when it holds no files and nothing uses it."""
        if folder.resolve() not in {held.resolve() for held in chat_folders_in_use()}:
            chats.remove_if_empty(settings, folder)

    def sweep_chats() -> list[Path]:
        return chats.sweep(settings, chat_folders_in_use())

    app.state.sweep_chats = sweep_chats

    control = runcontrol.RunControl()
    app.state.control = control
    live_runs = live.Live()  # what the current run of a session says, for followers
    app.state.live = live_runs
    # One live SDK client per active session when the SDK runner is selected.
    sdk_pool = sdk_runner.SdkPool(settings) if settings.runner == "sdk" else None
    app.state.sdk_pool = sdk_pool
    drains: set[asyncio.Task] = set()
    app.state.drains = drains
    silent_runs: set[str] = set()  # sessions whose current run must not wake the phone
    # Sessions whose current run is a coach compaction: it is never interrupted, so a
    # cancel can never renew the coach session without its facts and summary.
    compacting: set[str] = set()

    def compaction_refusal(session_id: str) -> None:
        if session_id in compacting:
            raise ClaudeError(
                "compaction_running", 409,
                "the coach conversation is being compacted; it cannot be stopped, and your "
                "message is answered as soon as it is done",
            )  # fmt: skip

    def read_choice(data: dict, name: str, allowed: tuple[str, ...]) -> str | None:
        value = data.get(name)
        if value is None:
            return None
        if not isinstance(value, str) or not (
            value in allowed or (name == "model" and value.startswith("claude-"))
        ):
            raise ClaudeError("bad_request", 400, f"`{name}` must be one of: {', '.join(allowed)}")
        return value

    def read_model_choice(data: dict) -> dict:
        """``model`` and ``effort`` of a request, validated; only the ones that were given."""
        chosen = {"model": read_choice(data, "model", settings.models), "effort": None}
        if data.get("effort") is not None:  # the CLI is asked only when needed
            chosen["effort"] = read_choice(data, "effort", claude.effort_levels(settings))
        return {name: value for name, value in chosen.items() if value is not None}

    def finish(session_id: str) -> None:
        """A run of the session ended: free it and send what is waiting, if anything."""
        slots.release(session_id)
        control.finished(session_id)
        chat_folder = chat_runs.pop(session_id, None)
        if chat_folder is not None:
            with contextlib.suppress(Exception):
                tidy_chat(chat_folder)  # a chat that made no files leaves no folder behind
        silent = session_id in silent_runs  # the bridge's own turn (a coach compaction)
        silent_runs.discard(session_id)
        if not silent and session_meta.entry_for(session_id)["notify_replies"]:
            # The owner need not keep the app open: a sealed hint says an answer is there.
            wake = asyncio.create_task(asyncio.to_thread(push.send_hint, "answer", session_id))
            drains.add(wake)
            wake.add_done_callback(drains.discard)
        task = asyncio.create_task(drain(session_id))
        drains.add(task)
        task.add_done_callback(drains.discard)

    async def drain(session_id: str) -> None:
        """Send the session's next queued message, in the background."""
        if session_id in slots.active:
            return
        entry = control.pop_next(session_id)
        if entry is None:
            return
        outcome = {"queue_id": entry["id"], "state": "done"}
        try:
            await run_prompt(
                {
                    "prompt": entry["prompt"],
                    "mode": entry["mode"],
                    "_queued": True,
                    "_queue_id": entry["id"],
                    "_owner": True,  # a message the owner sent, queued
                    "relayed": entry.get("relayed") is True,  # unless the app sent it itself
                    "interactive": entry.get("interactive") is True,
                },
                session_id=session_id,
                resume=True,
                cwd=Path(entry["cwd"]),
            )
        except ClaudeError as error:
            # Whatever went wrong, the rest of the queue waits for the owner.
            outcome = {"queue_id": entry["id"], "state": "error", "error": error.code}
            control.pause(session_id, "error")
        control.outcomes[session_id] = outcome

    async def run_prompt(data: dict, *, session_id: str, resume: bool, cwd: Path):
        """Shared by /ask and session resume: limits, in-use check, run or stream."""
        prompt = read_prompt(data)
        mode = read_mode(data)
        is_capable = mode == "capable"
        # A message the app sent by itself in a model's words (#190, ``permission``) is not
        # one the owner sent: nothing that needs the owner's own message follows from it.
        relayed = data.get("relayed") is True or permission.was_relayed(prompt)
        owner = data.get("_owner") is True and not relayed  # not a bridge turn either
        # The first message of a chat a switch started never applies an update by itself.
        may_update = owner and data.get("_continued") is not True
        # The coach conversation runs through this same path, on its own job and
        # instructions, read-only (coach_chat.py).
        is_coach = session_id == await asyncio.to_thread(coach_chat.current_id)
        if is_coach and (is_capable or read_model_choice(data)):
            raise ClaudeError(
                "bad_request", 400, "the coach conversation is read-only and uses its own model"
            )
        when = data.get("when", "queue")
        if when not in ("queue", "now"):
            raise ClaudeError("bad_request", 400, "`when` must be `queue` or `now`")
        choice = read_model_choice(data)
        if session_id in slots.active:
            # The session is answering: never a second writer. The message waits,
            # or (send now) the current run is interrupted and this one goes next.
            entry = control.add(
                session_id, prompt, mode, cwd, front=when == "now",
                interactive=data.get("interactive") is True, relayed=relayed,
            )  # fmt: skip
            interrupted = False
            if when == "now":
                control.unpause(session_id)
                # A coach compaction is never interrupted: the message goes first after it.
                if session_id not in compacting:
                    await control.stop(session_id)
                    interrupted = True  # as before: the run is asked to stop
            return JSONResponse(
                {
                    "queued": True,
                    "queue_id": entry["id"],
                    "position": control.queue(session_id).index(entry) + 1
                    if entry in control.queue(session_id)
                    else 0,
                    "interrupted_current_run": interrupted,
                },
                status_code=202,
            )
        if choice:
            await asyncio.to_thread(session_meta.update_session, session_id, choice)
        chosen = await asyncio.to_thread(session_meta.entry_for, session_id)
        # The session's own choice, else LIFEOS_MODEL_ROUTING's chat=…, else the defaults.
        # The coach conversation always runs on the coach_chat job (counted apart).
        default = routing.run_options(settings, "coach_chat" if is_coach else "chat")
        if is_coach:
            chosen = {**chosen, "model": None, "effort": None}
        run_args = {
            "session_id": session_id,
            "resume": resume,
            "cwd": cwd,
            "model": chosen["model"] or default["model"],
            "effort": chosen["effort"] or default["effort"],
            "advisor": None if chosen["model"] else default["advisor"],
            "job": default["job"],
            "on_process": control.tracker(session_id),
        }
        # Clean chats (#163): only the coach conversation gets personal context. A blank
        # chat gets one fixed instruction (where the memory is, used only when asked); a
        # conversation in any other folder gets nothing more than that folder in reach.
        is_chat = chats.is_chat_folder(settings, cwd)
        instructions = phone_instructions
        read_only_policy = claude.READ_ONLY
        if is_coach:
            instructions += "\n\n" + await asyncio.to_thread(coach_chat.context)
            loaded = await asyncio.to_thread(memory.context_for_prompt, settings.ask_dir)
            if loaded:
                instructions += "\n\n" + loaded
        elif is_chat:
            instructions += " " + chats.context(settings)
            # The memory folder is in reach (read on request); no memory file is loaded.
            read_only_policy = chats.read_only_policy(settings)
        elif cwd.resolve() == program.program_dir(settings.allowed_roots):
            # A chat in the LifeOS program folder (#180): never a restart or reinstall by
            # the run itself; it asks for its committed changes to be applied.
            instructions += " " + updates.INSTRUCTIONS
        # The owner's own folder of this course (#179): read-only context of the course's
        # chats, in reach of the file tools; never written, nothing in it loaded.
        course_dirs = (
            []
            if is_coach or is_chat
            else await asyncio.to_thread(course_folders.for_folder, settings, cwd)
        )
        if course_dirs:
            instructions += " " + course_folders.context(course_dirs)
            read_only_policy = dataclasses.replace(
                read_only_policy, add_dirs=(*read_only_policy.add_dirs, *course_dirs)
            )
        run_args["context"] = instructions
        streaming = bool(data.get("stream"))
        use_sdk = sdk_pool is not None
        watch = None
        used_servers: list[tuple[str, str]] = []
        if applier.holding:
            # Nothing runs from the moment an approved update found the bridge idle until
            # it restarts (#180). No await lies between this check and the slot.
            raise ClaudeError(
                "update_in_progress", 503,
                "an approved update is being applied and the bridge restarts in a few "
                "minutes; send the message again then",
            )  # fmt: skip
        slots.acquire(session_id)
        control.take_stopped(session_id)  # a stop request for an earlier run is not ours
        if not data.get("_queued"):
            # A message the owner sends directly ends the hold a cancel put on the queue.
            control.unpause(session_id, only="cancel")
        started = False
        try:
            if is_chat:
                # Held from here to the end of the run; a folder that was removed for
                # holding no files is created again at the same path (the conversation
                # is bound to that path).
                chat_runs[session_id] = cwd
                chats.ensure(settings, cwd)
            # The conversation index is written anew before every run (#164) and given
            # to the run as a folder its file tools may read.
            listed = await asyncio.to_thread(known_sessions)
            index = await asyncio.to_thread(conversations.refresh, settings, listed)
            read_only_policy = conversations.with_index(read_only_policy, index)
            # Never write into a conversation that is open somewhere else. The
            # bridge's own live SDK processes are not "somewhere else".
            own = sdk_pool.pids() if sdk_pool is not None else frozenset()
            if resume and session_id in await claude.sessions_in_use(settings, ignore_pids=own):
                if sdk_pool is not None:
                    await sdk_pool.drop(session_id)  # the other writer wins; resume later
                raise ClaudeError(
                    "session_in_use", 409, "this session is open on the computer right now"
                )
            if is_capable:
                # The SDK runner only (#181), after a guardrail check passed there.
                await capable.check_available(settings, cwd)
                watch = capable.AuditWatch(session_id)
                # The owner's personal skills and MCP servers (#162): in every capable
                # run, once the check with their probes has passed.
                personal = capable.personal_available()
                if chosen["skills"]:
                    # The folder's own skills (#91): only after the check with the skill
                    # probes; never silently without them (switch them off instead).
                    await capable.skills_available(settings, cwd)
                offer = await asyncio.to_thread(
                    skills.for_run, settings, cwd, session_id,
                    folder_skills=chosen["skills"], personal=personal,
                )  # fmt: skip
                if offer.any:
                    # Slash commands are live in this run: only the offered ones may
                    # be named, never one of Claude Code's own.
                    prompt = skills.check_prompt(prompt, offer.names)
                # MCP servers the owner approved (#108, #162): exactly those, unchanged,
                # and only after the check with the MCP probes.
                servers = await asyncio.to_thread(
                    mcp_servers.for_run, cwd, settings.personal_mcp_file if personal else None
                )
                if servers:
                    await capable.mcp_available(settings, cwd)
                    scopes = await asyncio.to_thread(mcp_servers.scopes_for_run, cwd, servers)
                    try:
                        detail = {"session": session_id, "folder": str(cwd),
                                  "names": sorted(servers), "scopes": scopes}  # fmt: skip
                        audit.record("mcp_servers_used", detail)
                    except audit.AuditUnavailable as exc:
                        raise ClaudeError(
                            "audit_unavailable", 503, "the audit log could not be written"
                        ) from exc
                    used_servers = [(name, scopes[name]) for name in sorted(servers)]
                # Its course folder only after a check whose course-folder probes passed.
                readable = course_dirs if capable.course_folders_available() else []
                # The open network (#182), once a check proved its probes; the owner's
                # GitHub token is read outside the sandbox and masked inside it.
                open_network = capable.open_network_available()
                token = await asyncio.to_thread(network.github_token) if open_network else None
                policy = capable.build_policy(
                    settings, cwd, ask_host=True, skills_dir=offer.folder_dir,
                    personal_skills_dir=offer.personal_dir, mcp=servers, course_dirs=readable,
                    open_network=open_network, github_token=token,
                )  # fmt: skip
                run_args |= {"policy": policy, "observer": watch}
            elif read_only_policy is not claude.READ_ONLY:
                run_args["policy"] = read_only_policy
            if use_sdk:
                # Questions and approvals wait only for a client that streams the answer
                # and said it can show them; anything else is refused at once, as before.
                # A queued message may ask if its client said so: a follower of
                # /sessions/{id}/live answers.
                followed = streaming or bool(data.get("_queued"))
                run_args["answering"] = followed and data.get("interactive") is True
            # Queued messages take the event path too, so a client can follow them live.
            if isinstance(data.get("_admitted"), dict):
                data["_admitted"]["run"] = True  # every check passed: the run begins now
            if not is_capable and not streaming and not data.get("_queued"):
                try:
                    if use_sdk:
                        result = await sdk_runner.run(sdk_pool, settings, prompt, **run_args)
                    else:
                        result = await claude.run(settings, prompt, **run_args)
                except ClaudeError:
                    if control.take_stopped(session_id):
                        raise runcontrol.stopped_error() from None
                    raise
                if control.take_stopped(session_id):
                    raise runcontrol.stopped_error()
                answer = with_files(
                    {
                        "answer": result.answer,
                        "session_id": result.session_id or session_id,
                        "duration_ms": result.duration_ms,
                        "num_turns": result.num_turns,
                        "denied_tools": result.denied_tools,
                    },
                    session_id,
                    cwd,
                )
                if is_chat:
                    with_moves(answer, session_id, cwd)
                await asyncio.to_thread(with_switch, answer, session_id, owner, mode)
                return answer
            started = True
        finally:
            if not started:
                finish(session_id)

        async def run_events() -> AsyncIterator[tuple[str, dict]]:
            """The run as events; frees the session when it ends or is abandoned.

            Every event is also published for followers (``/sessions/{id}/live``).
            """
            async for event, payload in published(raw_events()):
                yield event, payload

        async def published(events: AsyncIterator) -> AsyncIterator[tuple[str, dict]]:
            token = live_runs.begin(session_id, data.get("_queue_id"))
            try:
                async for event, payload in events:
                    live_runs.publish(session_id, event, payload)
                    yield event, payload
            finally:
                live_runs.end(session_id, token)

        async def raw_events() -> AsyncIterator[tuple[str, dict]]:
            open_interactions: set[str] = set()
            started = time.time()
            try:
                runner = (
                    sdk_runner.stream(sdk_pool, settings, prompt, **run_args)
                    if use_sdk and sdk_pool is not None
                    else claude.stream(settings, prompt, **run_args)
                )
                async for event, payload in with_keepalives(runner):
                    if event == "interaction":
                        open_interactions.add(str(payload.get("id")))
                    elif event == "interaction_closed":
                        open_interactions.discard(str(payload.get("id")))
                    if event in ("done", "error"):
                        # The run is over: whatever still waited for the owner is closed.
                        for interaction_id in sorted(open_interactions):
                            yield (
                                "interaction_closed",
                                {"id": interaction_id, "reason": "run_ended"},
                            )
                        open_interactions.clear()
                    if event in ("done", "error") and control.take_stopped(session_id):
                        stopped = runcontrol.stopped_error()
                        yield "error", {"error": stopped.code, "message": stopped.message}
                        return
                    if event == "done":
                        with_files(payload, session_id, cwd)
                        if watch is not None:
                            payload["refused_actions"] = watch.refused_actions()
                        if is_capable:
                            with_installs(payload, session_id, cwd)
                            with_update(payload, session_id, cwd, may_update)
                        if is_chat:
                            with_moves(payload, session_id, cwd)
                        await asyncio.to_thread(with_switch, payload, session_id, owner, mode)
                        if used_servers:
                            payload["mcp_servers"] = [
                                {"name": name, "scope": scope, "outside_sandbox": True}
                                for name, scope in used_servers
                            ]
                            payload["mcp_note"] = mcp_servers.OUTSIDE_SANDBOX
                            await asyncio.to_thread(
                                remember_servers, payload.get("session_id") or session_id,
                                [name for name, _scope in used_servers], started,
                            )  # fmt: skip
                    yield event, payload
            except ClaudeError as error:
                yield "error", {"error": error.code, "message": error.message}
            finally:
                finish(session_id)

        if not streaming:  # capable run for a client that wants one JSON answer
            final: tuple[str, dict] = ("error", {"error": "bad_output", "message": ""})
            async for event, payload in run_events():
                if event in ("done", "error"):
                    final = (event, payload)
            if final[0] == "error":
                code = final[1]["error"]
                status = {"timeout": 504, "cancelled": 409}.get(code, 502)
                raise ClaudeError(code, status, final[1].get("message", ""))
            return final[1]

        async def events() -> AsyncIterator[bytes]:
            yield sse("session", {"session_id": session_id})
            async for event, payload in run_events():
                # Keep-alives are a real (tiny) event, so the relay forwards them sealed and
                # the phone can tell a working run from a dead connection.
                yield sse(event, payload)

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    @app.get("/files/{offer_id}")
    async def download_file(offer_id: str, session_id: str):
        """Send an offered file. Only for an offer of this session that has not expired."""
        offer = await asyncio.to_thread(offers.take, offer_id, session_id)
        return StreamingResponse(
            files.chunks(offer),
            media_type=offer.type,
            headers={
                "Content-Length": str(offer.size),
                "Content-Disposition": "attachment",
                "Cache-Control": "no-store",
            },
        )

    @app.post("/push/notify")
    async def push_notice(request: Request):
        """Wake the paired phones with a notice (used by scripts/notify.sh).

        Body: ``{"title": str?, "link": str?}``; both travel only inside the sealed hint.
        ``{"event": "update", "ref": "<version code>"}`` is the new-release hint of
        ``lifeos-bridge release``: other processes push through here, because only the
        running bridge may advance a phone's message counters.
        """
        data = await read_json_object(request)
        if data.get("event") == "update":
            ref = str(data.get("ref") or "")[:20]
            reached = await asyncio.to_thread(push.send_hint, "update", ref)
            return {"phones_reached": reached}
        if data.get("event") not in (None, "notice"):
            raise ClaudeError("bad_request", 400, "`event` must be `notice` or `update`")
        title = str(data.get("title") or "")[:200]
        link = str(data.get("link") or "")[:300]
        reached = await asyncio.to_thread(push.send_hint, "notice", "", t=title, l=link)
        return {"phones_reached": reached}

    @app.get("/models")
    async def list_models():
        """Models and effort levels the app may offer, and the defaults."""
        chat = routing.route(settings, "chat")
        return {
            "models": list(settings.models),
            "efforts": list(await asyncio.to_thread(claude.effort_levels, settings)),
            "default_model": settings.claude_model or chat.model or settings.default_model,
            "default_effort": chat.effort or settings.default_effort,
        }

    @app.get("/models/routing")
    async def model_routing():
        """Each model job: its model, effort and advisor, and the plan usage of its runs
        today and this week. Never starts a run."""
        return await asyncio.to_thread(routing.overview, settings)

    @app.get("/app/release")
    async def app_release():
        """The registered release of the app (``lifeos-bridge release``); 404 when none."""
        return await asyncio.to_thread(release.view)

    @app.get("/app/update")
    async def app_update_state():
        """Where an approved update stands (#180): ``state`` none, waiting, checking,
        testing, building, restarting, applied, failed or interrupted; the failed step."""
        return await asyncio.to_thread(updates.public_state)

    @app.post("/app/release/offer")
    async def offer_app_release():
        """Offer the release APK, fetched with ``GET /files/{offer}?session_id=app-release``
        (through the relay: sealed chunks). The phone checks hash and certificate."""
        current = await asyncio.to_thread(release.view)
        stored = await asyncio.to_thread(release.current)
        path = await asyncio.to_thread(release.apk_path, stored)
        offer = await asyncio.to_thread(
            offers.for_release, path, release.SESSION, current["version_code"]
        )
        return {**offer.card(), "session_id": release.SESSION, **current}

    @app.put("/models/routing")
    async def change_model_routing(request: Request):
        """Change one job's model, effort or advisor (kept in the private config folder,
        applied from the next run, audited). Body: ``{"job", "model"?, "effort"?,
        "advisor"?}`` (``null``: back to the default; advisor ``"none"``: off) or
        ``{"job", "reset": true}``. Answers the overview of ``GET`` plus ``"changed"``."""
        data = await read_json_object(request)
        efforts = await asyncio.to_thread(claude.effort_levels, settings)
        return await asyncio.to_thread(routing.change, settings, data, efforts)

    @app.post("/sessions/{session_id}/cancel")
    async def cancel_run(session_id: str):
        """Stop the session's current run: interrupt first, terminate only if needed.

        The queue is kept but paused. Changes the run already made are not undone.
        A coach compaction is not stopped (``409 compaction_running``).
        """
        compaction_refusal(session_id)
        stopped = await control.stop(session_id)
        if stopped:
            control.pause(session_id, "cancel")
        return {"cancelled": stopped, "queue_paused": session_id in control.paused,
                "note": runcontrol.STOP_NOTE if stopped else "nothing was running"}  # fmt: skip

    @app.get("/sessions/{session_id}/interactions")
    async def list_interactions(session_id: str):
        """Questions and approvals that wait for the owner: those of a running turn (SDK
        runner), and those that wait across runs (skill installs, MCP servers, file moves)."""
        await asyncio.to_thread(expire_approvals)
        live = sdk_pool.asking.pending(session_id) if sdk_pool is not None else []
        return {"pending": live + await asyncio.to_thread(approvals.pending, session_id)}

    @app.post("/sessions/{session_id}/interactions/{interaction_id}")
    async def answer_interaction(session_id: str, interaction_id: str, request: Request):
        """Answer a waiting question or approval; see ``interactions.parse_answer``."""
        await asyncio.to_thread(expire_approvals)
        if await asyncio.to_thread(approvals.get, session_id, interaction_id) is not None:
            approve = approvals.read_approve(await read_json_object(request))
            update_approved = False

            def handle(entry: dict) -> dict:
                nonlocal update_approved
                if entry["kind"] == updates.KIND:
                    done = updates.answer(entry, approve)
                    update_approved = done.get("approved") is True
                    return done
                if entry["kind"] == skill_installs.KIND:
                    return skill_installs.answer(entry, approve)
                if entry["kind"] == mcp_servers.KIND:
                    return mcp_servers.answer(entry, approve, settings.personal_mcp_file)
                if entry["kind"] == moves.KIND:
                    return moves.answer(settings, entry, approve)
                raise ClaudeError("not_pending", 404, "this approval is no longer waiting")

            # A refused answer (409, 503) leaves the card waiting (#157).
            done = await asyncio.to_thread(approvals.settle, session_id, interaction_id, handle)
            await asyncio.to_thread(clear_notification, session_id)
            if update_approved:
                applier.start()  # waits for every run to end, then tests, builds, restarts
            if "moved" in done:
                with contextlib.suppress(Exception):
                    sweep_chats()  # a chat whose files all moved away leaves no folder
            return {"answered": True, "id": interaction_id, **done}
        if sdk_pool is None:
            raise ClaudeError("not_pending", 404, "this question is no longer waiting")
        data = await read_json_object(request)
        return sdk_pool.asking.answer(session_id, interaction_id, data)

    @app.get("/sessions/{session_id}/mcp-servers")
    async def list_mcp_servers(session_id: str):
        """The MCP servers of the session's folder, then the owner's personal ones
        (``scope``); nothing is started."""
        info = listed_session(session_id)
        found = await asyncio.to_thread(
            mcp_servers.listing, info.folder, settings.personal_mcp_file
        )
        return {"servers": found, "capable_only": True, "note": mcp_servers.OUTSIDE_SANDBOX}

    def mcp_scope(folder: Path, name: str, scope: str | None) -> str:
        return mcp_servers.resolve_scope(folder, name, scope, settings.personal_mcp_file)

    @app.post("/sessions/{session_id}/mcp-servers/{name}/approval")
    async def request_mcp_approval(session_id: str, name: str, scope: str | None = None):
        """Ask the owner to approve exactly this server: an approval card (#108).

        ``?scope=folder|personal`` says which one; without it the folder's own server
        of that name is meant, else the owner's personal one.
        """
        info = listed_session(session_id)
        await asyncio.to_thread(expire_approvals)
        chosen_scope = await asyncio.to_thread(mcp_scope, info.folder, name, scope)
        card = await asyncio.to_thread(
            mcp_servers.request, info.id, info.folder, name, chosen_scope,
            settings.personal_mcp_file,
        )  # fmt: skip
        if not card["already_waiting"]:  # the same definition asked again: no second push
            wake = asyncio.create_task(asyncio.to_thread(push.send_hint, "approval", info.id))
            background.add(wake)
            wake.add_done_callback(background.discard)
        return card

    @app.delete("/sessions/{session_id}/mcp-servers/{name}")
    async def revoke_mcp_server(session_id: str, name: str, scope: str | None = None):
        """Take an approval back. ``?scope=folder|personal`` says which one; without it
        the folder's approval of that name, else the owner's personal one."""
        info = listed_session(session_id)
        if scope is not None and scope not in mcp_servers.SCOPES:
            raise ClaudeError("bad_request", 400, "scope must be folder or personal")
        for one in (scope,) if scope else mcp_servers.SCOPES:
            revoked = await asyncio.to_thread(
                mcp_servers.revoke, info.folder, name, info.id, one, settings.personal_mcp_file
            )
            if revoked:
                return {"revoked": True}
        return {"revoked": False}

    @app.get("/sessions/{session_id}/queue")
    async def show_queue(session_id: str):
        """Whether the session is answering, and the messages waiting for it."""
        return {
            "running": session_id in slots.active,
            "paused": control.paused.get(session_id),
            "queue": [control.public(e) for e in control.queue(session_id)],
            "last": control.outcomes.get(session_id),
        }

    @app.patch("/sessions/{session_id}/queue/{queue_id}")
    async def edit_queued(session_id: str, queue_id: str, request: Request):
        """Change the text of a waiting message. Body: ``{"prompt": str}``."""
        prompt = read_prompt(await read_json_object(request))
        return control.public(control.edit(session_id, queue_id, prompt))

    @app.delete("/sessions/{session_id}/queue/{queue_id}")
    async def remove_queued(session_id: str, queue_id: str):
        control.remove(session_id, queue_id)
        return {"removed": True}

    @app.post("/sessions/{session_id}/queue/resume")
    async def resume_queue(session_id: str):
        """Send the waiting messages (after a cancel, an error or a restart).

        Answers at once; the queued runs go on in the background (follow them with
        ``GET /sessions/{id}/live``).
        """
        control.unpause(session_id)
        start_drain(session_id)
        return {"resumed": True, "queue": [control.public(e) for e in control.queue(session_id)]}

    def start_drain(session_id: str) -> None:
        task = asyncio.create_task(drain(session_id))
        drains.add(task)
        task.add_done_callback(drains.discard)

    @app.post("/sessions/{session_id}/queue/{queue_id}/send-now")
    async def send_queued_now(session_id: str, queue_id: str):
        """Make a waiting message the next one and send it now: the current run is
        interrupted, as with ``"when": "now"`` (changes it made are not undone)."""
        control.promote(session_id, queue_id)
        control.unpause(session_id)
        interrupted = False
        if session_id in compacting:
            pass  # a coach compaction is never interrupted; the message goes next after it
        elif session_id in slots.active:
            interrupted = await control.stop(session_id)  # its end sends the next message
        else:
            start_drain(session_id)
        return {
            "promoted": True,
            "queue_id": queue_id,
            "interrupted_current_run": interrupted,
            "queue": [control.public(e) for e in control.queue(session_id)],
        }

    @app.get("/sessions/{session_id}/live")
    async def follow_session(session_id: str):
        """The session's current run as server-sent events, also when it started earlier:
        what it said so far, then live, until it ends. Never a second writer."""
        listed_session(session_id)
        current = live_runs.running(session_id)

        async def events() -> AsyncIterator[bytes]:
            yield sse("live", {"session_id": session_id, "running": session_id in slots.active,
                               "followable": current is not None,
                               "queue_id": (current or {}).get("queue_id")})  # fmt: skip
            async for event, payload in live_runs.follow(session_id):
                yield sse(event, payload)

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    @app.get("/sessions/{session_id}/files")
    async def session_files(session_id: str):
        """The session's file offers that can still be fetched (cards after a re-read)."""
        return {"files": offers.listing(session_id)}

    def chosen_folder(data: dict) -> Path | None:
        """The folder of a new conversation. ``None``: a blank chat, which gets a new folder
        of its own (no ``folder``, or the chat root itself); else an allowed folder by path."""
        folder = data.get("folder")
        if folder is None:
            return None
        if not isinstance(folder, str) or not folder.strip() or len(folder) > 4096:
            raise ClaudeError("bad_request", 400, "`folder` must be one of the folders of /folders")
        wanted = Path(folder).expanduser()
        if wanted.is_absolute() and wanted.resolve() == chats.root(settings).resolve():
            return None
        roots = settings.allowed_roots
        for root in roots:
            if wanted == root or (wanted.is_absolute() and wanted.resolve() == root):
                return root
        # A course's own folder of the Moodle sync (where a switch to a course leads, #179).
        if wanted.is_absolute() and wanted.is_dir() and course_folders.is_sync_folder(wanted):
            resolved = wanted.resolve()
            if any(resolved.is_relative_to(root) for root in roots):
                return resolved
        raise ClaudeError("folder_not_allowed", 403, "this folder is not an allowed folder")

    def new_chat_folder() -> Path:
        return chats.new_folder(settings, memory.now_local().date())

    def session_folder(info: sessions.SessionInfo) -> Path | None:
        """The folder a listed session continues in. A blank chat's folder may have been
        removed for holding no files: the run creates the same path again."""
        if info.folder.is_dir() or chats.is_chat_folder(settings, info.folder):
            return info.folder
        return None

    @app.get("/folders")
    async def list_folders():
        """Where a new conversation may run: a blank chat (the default: its own new, empty
        folder under the chat root), the memory folder and the allowed folders. ``usable``
        is false, with ``reason``, when blank chats are refused on this computer."""
        known = await asyncio.to_thread(folders.Folders, settings)
        reason = await asyncio.to_thread(chats.root_refusal, settings)
        if reason is not None and not chats.root(settings).exists():
            reason = None  # the bridge creates the chat root with the first blank chat
        listed = []
        for entry in known.entries:
            if entry["kind"] == "coach" or (
                entry["kind"] != "chat" and not Path(entry["path"]).is_dir()
            ):
                continue
            item = {
                "path": entry["path"],
                "label": entry["name"],
                "kind": entry["kind"],
                "default": entry["kind"] == "chat",
                "colour": entry["colour"],
            }
            if entry["kind"] == "chat":
                item |= {"usable": reason is None, "reason": reason}
            listed.append(item)
        return {"folders": listed}

    @app.post("/folders/open")
    async def open_folder(request: Request):
        """The folder the owner picked on a "choose_folder" card (#179): added to the
        allowed folders when needed, by the same rules as a find in a chat. Body:
        ``{"path": "<a folder of the card>"}``. Answers ``{"added": bool, "folder":
        {"path", "label", "kind", "colour"}}``; the app then starts a new chat there."""
        data = await read_json_object(request)
        return await asyncio.to_thread(folder_requests.open_folder, settings, data.get("path"))

    @app.post("/ask")
    async def ask(request: Request):
        """Run a prompt through headless Claude.

        Body: ``{"prompt": str, "session_id": str?, "folder": str?, "stream": bool?}``.
        A new conversation without ``folder`` is a blank chat: it runs in its own
        new, empty folder under the chat root, with no personal context (#163).
        ``folder`` names the memory folder or an allowed folder instead (or a
        course's own folder of the Moodle sync, #179).
        ``session_id`` continues an earlier conversation in
        the folder it was started in. ``new_session_id`` (a UUID the phone chose)
        names a new conversation, so its first message can be sent again safely:
        a repeat answers ``{"repeat": true, "session_id", "running"}`` and runs
        nothing; the same id with another message or folder answers 409.
        ``continued_from`` (with ``new_session_id``) names the chat whose switch
        started this one (#187): both histories then say so, and the answer to this
        first message never applies an update by itself (#190). ``relayed: true``: the
        app sent the message by itself (a request a switch or the command bar carried),
        so it is not a message the owner sent (``permission``).
        """
        data = await read_json_object(request)
        prompt = read_prompt(data)
        read_mode(data)
        chosen_id = data.get("new_session_id")
        if chosen_id is not None:
            if data.get("session_id") is not None:
                raise ClaudeError("bad_request", 400, "give `session_id` or `new_session_id`")
            if not isinstance(chosen_id, str) or not claude.is_session_id(chosen_id):
                raise ClaudeError("bad_request", 400, "`new_session_id` must be a lowercase UUID")
            origin = data.get("continued_from")
            if origin is not None and not (
                isinstance(origin, str) and claude.is_session_id(origin)
            ):
                raise ClaudeError("bad_request", 400, "`continued_from` must be a session id")
            chosen = chosen_folder(data)
            # A blank chat has no folder yet: a repeat must not look like another start
            # (and must not make a second folder), so its fingerprint names no path.
            place = "blank chat" if chosen is None else str(chosen)
            fingerprint = hashlib.sha256(f"{place}\n{prompt}".encode()).hexdigest()[:16]
            claimed = await asyncio.to_thread(
                session_meta.claim_new_session, chosen_id, fingerprint
            )
            exists = claimed == "new" and (
                (chosen is not None and claude.session_exists(chosen, chosen_id))
                or known_session(chosen_id) is not None
            )
            if claimed == "conflict" or exists:
                if exists:
                    await asyncio.to_thread(
                        session_meta.release_new_session, chosen_id, fingerprint
                    )
                raise ClaudeError(
                    "session_id_conflict", 409, "this session id is already used for another start"
                )
            if claimed == "repeat":
                return {"repeat": True, "session_id": chosen_id,
                        "running": chosen_id in slots.active}  # fmt: skip
            if origin is not None:
                await asyncio.to_thread(link_origin, origin, chosen_id)
            admitted: dict = {}
            cwd = chosen
            try:
                if cwd is None:
                    cwd = new_chat_folder()
                return await run_prompt(
                    {
                        **data,
                        "_admitted": admitted,
                        "_owner": True,
                        "_continued": origin is not None,
                    },
                    session_id=chosen_id,
                    resume=False,
                    cwd=cwd,
                )
            except ClaudeError:
                if not admitted:  # refused before its run began (busy, checks): not claimed
                    await asyncio.to_thread(
                        session_meta.release_new_session, chosen_id, fingerprint
                    )
                    if chosen is None and cwd is not None:
                        tidy_chat(cwd)  # the folder made for a chat that never began
                raise
        resume = data.get("session_id") is not None
        if resume:
            session_id = data["session_id"]
            if not isinstance(session_id, str):
                raise ClaudeError("session_not_found", 404, "unknown session")
            if claude.session_exists(settings.ask_dir, session_id):
                cwd = settings.ask_dir
            else:
                info = known_session(session_id)
                found = session_folder(info) if info is not None else None
                if found is None:
                    raise ClaudeError("session_not_found", 404, "unknown session")
                cwd = found
            return await run_prompt(
                {**data, "_owner": True, "_continued": False},
                session_id=session_id, resume=True, cwd=cwd,
            )  # fmt: skip
        chosen = chosen_folder(data)
        cwd = chosen if chosen is not None else new_chat_folder()
        # No --name: a fixed name would hide the first prompt, which is the
        # more useful title in the session list.
        session_id = str(uuid.uuid4())
        try:
            return await run_prompt(
                {**data, "_owner": True, "_continued": False},
                session_id=session_id, resume=False, cwd=cwd,
            )  # fmt: skip
        except ClaudeError:
            if chosen is None:
                tidy_chat(cwd)  # refused before it ran: no empty folder is left behind
            raise

    @app.post("/sessions/{session_id}/messages")
    async def resume_session(session_id: str, request: Request):
        """Continue a listed session with a new message, in its original folder.

        Body: ``{"prompt": str, "stream": bool?}``.
        """
        data = await read_json_object(request)
        read_prompt(data)
        read_mode(data)
        info = known_session(session_id)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        if session_folder(info) is None:
            raise ClaudeError("folder_missing", 409, "the session's folder no longer exists")
        return await run_prompt(
            {**data, "_owner": True, "_continued": False},
            session_id=info.id, resume=True, cwd=info.folder,
        )  # fmt: skip

    background: set[asyncio.Task] = set()

    def update_blockers() -> list[str]:
        """What an approved update waits for (#180); empty when nothing runs."""
        found = []
        if slots.active:  # runs of both runners, live SDK sessions answering, compactions
            found.append("runs")
        if any(control.queue(sid) and sid not in control.paused for sid in list(control.queues)):
            found.append("queued messages")  # a paused queue waits for the owner, as a draft
        for name, tasks in getattr(app.state, "jobs", {}).items():
            if any(not task.done() for task in tasks):
                found.append(name)
        if any(not task.done() for task in (*background, *drains)):
            found.append("background work")
        guard = getattr(app.state, "guard", None)
        if guard is not None and guard.task is not None and not guard.task.done():
            found.append("guardrail check")
        if in_flight[0]:
            found.append("requests")
        return found

    applier = updates.Applier(settings, update_blockers)
    app.state.applier = applier

    async def map_in_background(folder: Path) -> None:
        with contextlib.suppress(ClaudeError, OSError):
            await contextmap.refresh_folder(settings, folder)

    def allowed_file() -> Path:
        if settings.allowed_paths_file is None:
            raise ClaudeError("not_configured", 503, "no allowed-paths file is configured")
        return settings.allowed_paths_file

    def read_path(data: dict) -> str:
        path = data.get("path")
        if not isinstance(path, str) or not path.strip() or "\n" in path or len(path) > 4096:
            raise ClaudeError("bad_request", 400, "`path` must be a folder path")
        return path.strip()

    @app.get("/allowed-paths")
    async def list_allowed_paths():
        """The folders Claude may work in besides the memory folder."""
        return {"paths": allowed.listing(allowed_file())}

    @app.post("/allowed-paths")
    async def add_allowed_path(request: Request):
        """Allow a folder. Body: ``{"path": "/home/.../folder"}``."""
        path = read_path(await read_json_object(request))
        added = allowed.add(allowed_file(), path, settings.notify_script)
        if added:
            # The context map grows with the list; a failure here never fails the request.
            task = asyncio.create_task(map_in_background(Path(path).expanduser()))
            background.add(task)
            task.add_done_callback(background.discard)
        return {"added": added, "paths": allowed.listing(allowed_file())}

    @app.delete("/allowed-paths")
    async def remove_allowed_path(request: Request):
        """Stop allowing a folder. Body: ``{"path": "/home/.../folder"}``."""
        path = read_path(await read_json_object(request))
        allowed.remove(allowed_file(), path, settings.notify_script)
        return {"removed": True, "paths": allowed.listing(allowed_file())}

    @app.get("/sessions")
    async def list_sessions(limit: int = 50):
        """Claude Code sessions in the allowed folders and the blank chats: pinned first,
        then newest first.

        ``running_here``: the bridge is answering a message of it (same as ``in_use``).
        ``open_elsewhere``: another Claude Code process on the computer holds it
        (a terminal, the desktop app); ``null`` when that could not be checked.
        ``folder_key``, ``folder_label``, ``kind`` and ``colour`` say which folder a
        session belongs to; ``folders`` has one entry per folder (all blank chats are
        one), counted over all sessions, not only the listed ones (``folders.py``).
        """
        limit = max(1, min(limit, 200))
        found = known_sessions()
        meta = session_meta.load()
        held = await held_elsewhere()
        known = await asyncio.to_thread(folders.Folders, settings)
        items = []
        for info in found:
            item = session_meta.describe(info, in_use=info.id in slots.active, data=meta)
            item |= known.describe(info)
            item["running_here"] = item["in_use"]
            item["open_elsewhere"] = (
                None if held is None else info.id in held and info.id not in slots.active
            )
            items.append(item)
        items.sort(key=lambda item: not item["pinned"])  # stable: keeps newest first
        return {"sessions": items[:limit], "total": len(found), "folders": known.summary(found)}

    held_cache: dict = {"at": -1e9, "held": None}

    async def held_elsewhere() -> set[str] | None:
        """Sessions a Claude Code process other than the bridge's holds (10 s copy)."""
        loop = asyncio.get_running_loop()
        if loop.time() - held_cache["at"] < HELD_SECONDS:
            return held_cache["held"]
        own = sdk_pool.pids() if sdk_pool is not None else frozenset()
        try:
            held = await claude.sessions_in_use(settings, timeout=5.0, ignore_pids=own)
        except ClaudeError:
            held = None  # unknown, not "free"
        held_cache.update(at=loop.time(), held=held)
        return held

    @app.get("/sessions/{session_id}")
    async def read_session(session_id: str):
        """One session as in the list, read fresh: title, folder, model, effort, switches,
        ``running_here``, ``open_elsewhere``, and its waiting messages."""
        info = known_session(session_id)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        item = session_meta.describe(info, in_use=info.id in slots.active, data=session_meta.load())
        item |= (await asyncio.to_thread(folders.Folders, settings)).describe(info)
        held = await held_elsewhere()
        item["running_here"] = item["in_use"]
        item["open_elsewhere"] = None if held is None else (info.id in held and not item["in_use"])
        item["followable"] = live_runs.running(info.id) is not None
        item["queue"] = [control.public(e) for e in control.queue(info.id)]
        item["queue_paused"] = control.paused.get(info.id)
        return item

    @app.patch("/sessions/{session_id}")
    async def update_session(session_id: str, request: Request):
        """Set the owner's title, pin or reply-notification switch for a session.

        Body: any of ``{"title": str|null, "pinned": bool, "notify_replies": bool,
        "model", "effort", "skills"}``. Only the bridge's own metadata changes.
        """
        changes = await read_json_object(request)
        read_model_choice(changes)  # refuses unknown models and effort levels
        info = known_session(session_id)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        session_meta.update_session(info.id, changes)
        item = session_meta.describe(info, in_use=info.id in slots.active, data=session_meta.load())
        return item | (await asyncio.to_thread(folders.Folders, settings)).describe(info)

    @app.get("/reminders")
    async def list_reminders(
        session_id: str | None = None, state: str | None = None, note_id: str | None = None
    ):
        """Reminders, soonest first. ``state``: ``scheduled`` or ``fired``."""
        return {"reminders": reminders.listing(session_id, state, note_id)}

    @app.post("/reminders", status_code=201)
    async def create_reminder(request: Request):
        """Body: ``{"session_id" | "note_id", "at", "note"?, "repeat"?, "timezone"?}``.

        ``repeat``: none, daily or weekly. A note's reminder (#185) names the note by its id;
        a note has one reminder to come, so a second time moves it (200 instead of 201).
        """
        data = await read_json_object(request)
        session_id = data.pop("session_id", None)
        note_id = data.pop("note_id", None)
        if isinstance(note_id, str) and session_id is None:
            await asyncio.to_thread(memory.get_note, brain, note_id)  # 404 for an unknown note
            reminder, moved = reminders.set_for_note(note_id, data)
            return JSONResponse(reminder, status_code=201 if moved is None else 200)
        info = known_session(session_id) if isinstance(session_id, str) else None
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        return reminders.create(info.id, data)

    @app.patch("/reminders/{reminder_id}")
    async def update_reminder(reminder_id: str, request: Request):
        return reminders.update(reminder_id, await read_json_object(request))

    @app.delete("/reminders/{reminder_id}")
    async def delete_reminder(reminder_id: str):
        reminders.delete(reminder_id)
        return {"deleted": True}

    @app.get("/context-map")
    async def list_context_map():
        """The allowed folders and the memory file that describes each (if written yet)."""
        return {"folders": await asyncio.to_thread(contextmap.listing, settings)}

    @app.post("/context-map/refresh")
    async def refresh_context_map(request: Request):
        """Rewrite the map files. Body: ``{}`` for all folders or ``{"path": "/folder"}``."""
        data = await read_json_object(request)
        slots.acquire("context-map")
        try:
            if data.get("path") is None:
                return {"results": await contextmap.refresh_all(settings)}
            folder = Path(read_path(data)).expanduser()
            return {"results": [{"ok": True, **await contextmap.refresh_folder(settings, folder)}]}
        finally:
            slots.release("context-map")

    # ---- memory: quick capture, tasks, calendar, agenda -------------------
    brain = settings.ask_dir

    def read_day(value: str | None, name: str) -> date:
        if value is None:
            return memory.now_local().date()
        return memory.read_date(value, name)

    @app.post("/notes", status_code=201)
    async def add_note(request: Request):
        """Quick capture into the inbox.

        Body: ``{"text": str, "client_id"?: str, "remind_at"?: str, "timezone"?: str}``.
        A repeat with a known ``client_id`` answers 200 with the stored note and
        writes nothing; the same ``client_id`` with other text answers 409.
        ``remind_at`` (a time with its offset) gives the note a reminder (#185).
        """
        data = await read_json_object(request)
        remind = None
        if data.get("remind_at") is not None:
            remind = {"at": data["remind_at"]}
            if data.get("timezone") is not None:
                remind["timezone"] = data["timezone"]
        saved = await asyncio.to_thread(
            memory.add_note, brain, data.get("text"), None, data.get("client_id"), remind
        )
        return JSONResponse(saved, status_code=200 if saved["repeat"] else 201)

    @app.get("/notes")
    async def list_notes(query: str = "", limit: int = 100):
        """Captured notes (newest first) and topic notes, each with a preview."""
        return {"notes": await asyncio.to_thread(memory.list_notes, brain, query, limit)}

    @app.get("/notes/{note_id}")
    async def get_note(note_id: str):
        """One note in full, with its reminder (#185) or ``null``."""
        note = await asyncio.to_thread(memory.get_note, brain, note_id)
        return note | {"reminder": reminders.of_note(note_id)}

    @app.patch("/notes/{note_id}")
    async def update_note(note_id: str, request: Request):
        """Change the text of a note. Body: ``{"text": str}``. A capture gets a new id
        with its text; a topic note keeps its id and its front matter (#202)."""
        data = await read_json_object(request)
        return await asyncio.to_thread(memory.update_note, brain, note_id, data.get("text"))

    @app.delete("/notes/{note_id}")
    async def delete_note(note_id: str):
        """Move a captured or topic note to the memory folder's trash (one commit)."""
        return await asyncio.to_thread(memory.delete_note, brain, note_id)

    @app.post("/notes/{note_id}/restore")
    async def restore_note(note_id: str):
        """Bring a deleted note back from the trash under the same id (undo)."""
        return await asyncio.to_thread(memory.restore_note, brain, note_id)

    @app.patch("/tasks/{task_id}")
    async def update_task(task_id: str, request: Request):
        """Change a task. Body: any of ``{"title", "due": "YYYY-MM-DD" | null, "note"}``.

        A ``null`` (or blank) ``note`` removes the note, as ``null`` removes the date.
        """
        data = await read_json_object(request)
        return (await asyncio.to_thread(memory.update_task, brain, task_id, data)).as_json()

    @app.get("/tasks")
    async def list_tasks():
        """Open tasks, in the order of the file."""
        return {"tasks": [t.as_json() for t in await asyncio.to_thread(memory.list_tasks, brain)]}

    @app.post("/tasks", status_code=201)
    async def add_task(request: Request):
        """Body: ``{"title", "due"?: "YYYY-MM-DD", "project"?, "course"?, "note"?, "client_id"?}``.

        A repeat with a known ``client_id`` answers 200 with the stored task and
        writes nothing; the same ``client_id`` with other fields answers 409.
        """
        data = await read_json_object(request)
        saved = await asyncio.to_thread(memory.save_task, brain, data)
        return JSONResponse(saved, status_code=200 if saved["repeat"] else 201)

    @app.post("/tasks/{task_id}/complete")
    async def complete_task(task_id: str):
        return (await asyncio.to_thread(memory.complete_task, brain, task_id)).as_json()

    @app.delete("/tasks/{task_id}")
    async def delete_task(task_id: str):
        """Move an open task to the memory folder's trash (#197); restore brings it back."""
        return (await asyncio.to_thread(memory.delete_task, brain, task_id)).as_json()

    @app.post("/tasks/{task_id}/restore")
    async def restore_task(task_id: str):
        """Bring a deleted task back among the open ones (undo)."""
        return (await asyncio.to_thread(memory.restore_task, brain, task_id)).as_json()

    @app.post("/tasks/{task_id}/reopen")
    async def reopen_task(task_id: str):
        """A finished task back among the open ones (the undo of a tick that already left)."""
        return (await asyncio.to_thread(memory.reopen_task, brain, task_id)).as_json()

    @app.get("/events")
    async def list_events(start: str | None = None, end: str | None = None):
        """Events from ``start`` to ``end`` (dates, inclusive; default: today and 30 days)."""
        first = read_day(start, "start")
        last = memory.read_date(end, "end") if end is not None else first + timedelta(days=30)
        if last < first:
            raise ClaudeError("bad_request", 400, "`end` is before `start`")
        found = await asyncio.to_thread(memory.list_events, brain, first, last)
        return {"events": [e.as_json() for e in found]}

    @app.post("/events", status_code=201)
    async def create_event(request: Request):
        """Body: ``{"title", "date", "end_date"?, "start"?: "HH:MM", "end"?, "note"?}``."""
        data = await read_json_object(request)
        return (await asyncio.to_thread(memory.create_event, brain, data)).as_json()

    @app.patch("/events/{event_id}")
    async def update_event(event_id: str, request: Request):
        data = await read_json_object(request)
        return (await asyncio.to_thread(memory.update_event, brain, event_id, data)).as_json()

    @app.post("/activity/usage", status_code=201)
    async def record_usage(request: Request):
        """One day of screen time, unlocks and app usage from the phone."""
        data = await read_json_object(request)
        return await asyncio.to_thread(activity.record_usage, brain, data)

    @app.post("/activity/sleep", status_code=201)
    async def record_sleep(request: Request):
        """One night's sleep: entered by the owner, or estimated by the phone."""
        data = await read_json_object(request)
        return await asyncio.to_thread(activity.record_sleep, brain, data)

    @app.get("/activity")
    async def read_activity(start: str | None = None, end: str | None = None):
        """Recorded days (default: the last 30)."""
        last = read_day(end, "end")
        first = memory.read_date(start, "start") if start is not None else last - timedelta(days=29)
        if first > last or (last - first).days > 400:
            raise ClaudeError("bad_request", 400, "`start` and `end` must span at most 400 days")
        return {"days": await asyncio.to_thread(activity.read, brain, first, last)}

    # ---- projects: from the context map and the memory (no model run) --------------

    @app.get("/projects")
    async def list_projects():
        """Projects with status, state, last activity, open tasks and recent sessions."""
        return {"projects": await asyncio.to_thread(projects.collect, settings)}

    @app.get("/projects/{project_id}")
    async def get_project(project_id: str):
        return await asyncio.to_thread(projects.one, settings, project_id)

    # ---- memory consolidation: propose, then approve (memory-design.md §6) --------

    @app.get("/memory/consolidation")
    async def consolidation_view():
        """The waiting proposal and its operations. Never starts a run."""
        return await asyncio.to_thread(consolidation.view)

    @app.post("/memory/consolidation/approve")
    async def consolidation_approve(request: Request):
        """Body: ``{"operations": ["op1", ...]}`` or ``{"all": true}``: applied in one commit."""
        body = await read_json_object(request)
        return await asyncio.to_thread(consolidation.approve, settings, body)

    @app.post("/memory/consolidation/reject")
    async def consolidation_reject(request: Request):
        """Body as for approve. Rejected operations are not proposed again unchanged."""
        return await asyncio.to_thread(consolidation.reject, await read_json_object(request))

    @app.post("/memory/consolidation/run")
    async def consolidation_run(request: Request):
        """Prepare a proposal now (one run, in the background); 409 while one waits."""
        outcome = await asyncio.to_thread(consolidation.request)
        if outcome != "ok":
            status = {"waiting": 409, "running": 409, "too_soon": 429}[outcome]
            return JSONResponse({"started": False, "reason": outcome}, status_code=status)
        starter = getattr(request.app.state, "start_consolidation", None)
        if starter is None:  # the bridge's loop is not running (a test client without lifespan)
            run = asyncio.create_task(consolidation.run(settings))
            background.add(run)
            run.add_done_callback(background.discard)
        else:
            starter()
        return JSONResponse({"started": True}, status_code=202)

    @app.get("/agenda")
    async def agenda(day: str | None = None, days: int = 7):
        """Today's events and tasks, overdue tasks and the next ``days`` days."""
        days = max(1, min(days, 60))
        return await asyncio.to_thread(memory.agenda, brain, read_day(day, "day"), days)

    # ---- coach: daily plan, the open question, settings -------------------

    @app.get("/coach/plan")
    async def coach_plan():
        """The last prepared plan against the tasks open now. Never starts a run."""
        return await asyncio.to_thread(coach.current_plan, settings)

    @app.post("/coach/plan/refresh")
    async def refresh_coach_plan():
        """Prepare a new plan now (one run; at most one in ten minutes)."""
        return await coach.refresh(settings)

    @app.get("/coach/question")
    async def coach_question():
        return {"question": await asyncio.to_thread(coach.open_question, settings)}

    @app.post("/coach/question/answer")
    async def answer_coach_question(request: Request):
        """Body: ``{"text": str}``: the owner's answer in their own words."""
        data = await read_json_object(request)
        return await coach.answer_question(settings, data.get("text"))

    @app.post("/coach/question/skip")
    async def skip_coach_question():
        return await asyncio.to_thread(coach.skip_question, settings)

    @app.get("/coach/settings")
    async def coach_settings():
        return coach.settings_view()

    @app.put("/coach/settings")
    async def update_coach_settings(request: Request):
        """Body: any of ``{"frequency": "off"|"1"|"3", "quiet_start", "quiet_end", "questions"}``.

        Fields left out keep their value.
        """
        return coach.update_settings(await read_json_object(request))

    # ---- coach conversation: one persistent session, compacted when long ----------

    @app.get("/coach/chat")
    async def coach_conversation():
        """The coach session, its size, summary, model and plan usage (apart from the
        morning run). Never starts a run. Earlier turns: ``/sessions/{id}/history``."""
        view = await asyncio.to_thread(coach_chat.view, settings)
        jobs = {j["job"]: j for j in (await asyncio.to_thread(routing.overview, settings))["jobs"]}
        chat = jobs["coach_chat"]
        return {
            **view,
            "model": chat["model"],
            "effort": chat["effort"],
            "usage": chat["usage"],
            "morning_usage": jobs["coach_plan"]["usage"],
        }

    coach_compacting = asyncio.Lock()  # a coach message waits while the session is renewed

    async def compact_coach() -> dict:
        """One turn in the old session proposes facts and a summary; then a new session.

        The caller holds ``coach_compacting``. The turn wakes no phone. When the
        memory cannot be written, no turn is paid for; the session is renewed anyway.
        """
        session_id = (await asyncio.to_thread(coach_chat.state))["session_id"]
        if session_id in slots.active:
            raise ClaudeError("session_in_use", 409, "the coach is answering; try again shortly")
        answer = None
        try:
            await asyncio.to_thread(memory.check_repo, settings.ask_dir)
            usable = True
        except ClaudeError:
            usable = False
        if usable:
            compacting.add(session_id)  # never interrupted (cancel, send now)
            try:
                with contextlib.suppress(ClaudeError):
                    prompt = await asyncio.to_thread(coach_chat.compact_prompt, settings.ask_dir)
                    silent_runs.add(session_id)
                    result = await run_prompt(
                        {"prompt": prompt}, session_id=session_id, resume=True,
                        cwd=settings.ask_dir,
                    )  # fmt: skip
                    answer = result.get("answer") if isinstance(result, dict) else None
            finally:
                silent_runs.discard(session_id)
                compacting.discard(session_id)
        return await asyncio.to_thread(
            coach_chat.apply_compaction, settings, answer, memory.now_local()
        )

    @app.post("/coach/chat/messages")
    async def coach_conversation_message(request: Request):
        """Body as for a session message: ``{"prompt", "stream"?, "interactive"?, "when"?}``.

        Read-only, on the coach's own model. A conversation that grew too long is
        compacted first (one more run), and the message goes to the new session.
        """
        data = await read_json_object(request)
        read_prompt(data)
        if data.get("mode", "read_only") != "read_only" or read_model_choice(data):
            raise ClaudeError(
                "bad_request", 400, "the coach conversation is read-only and uses its own model"
            )
        # Decided before the compaction starts, so its event is never lost.
        streamed_compaction = bool(data.get("stream")) and await asyncio.to_thread(
            coach_chat.too_long, settings
        )
        prepare = asyncio.create_task(prepare_coach_session())
        drains.add(prepare)  # finished even if the client goes away: a compaction is kept whole
        prepare.add_done_callback(drains.discard)
        if streamed_compaction:
            # A compaction comes first: one stream with keep-alives while it runs, then
            # `compacted`, then the message's own events.
            return StreamingResponse(
                coach_stream_after_compaction(prepare, data),
                media_type="text/event-stream",
                headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
            )
        session_id, compacted = await asyncio.shield(prepare)
        answer = await coach_message(data, session_id)
        if compacted is None:
            return answer
        # The compaction was decided only now (the conversation grew meanwhile): the
        # answer says so in every form (#157).
        if isinstance(answer, StreamingResponse):
            return StreamingResponse(
                with_compacted(compacted, answer.body_iterator),
                status_code=answer.status_code,
                media_type="text/event-stream",
                headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
            )
        if isinstance(answer, JSONResponse):  # it waits in the queue
            body = json.loads(answer.body)
            if isinstance(body, dict):
                body["compacted"] = compacted
            return JSONResponse(body, status_code=answer.status_code)
        if isinstance(answer, dict):
            answer["compacted"] = compacted
        return answer

    async def with_compacted(compacted: dict, body: AsyncIterator) -> AsyncIterator[bytes]:
        yield sse("compacted", compacted)
        async for chunk in body:
            yield chunk if isinstance(chunk, bytes) else chunk.encode()

    async def prepare_coach_session() -> tuple[str, dict | None]:
        """The coach session a message goes to, compacted first when it grew too long."""
        async with coach_compacting:  # never into a session that is being compacted
            session_id = (await asyncio.to_thread(coach_chat.state))["session_id"]
            if session_id not in slots.active and await asyncio.to_thread(
                coach_chat.too_long, settings
            ):
                compacted = await compact_coach()
                return compacted["session_id"], compacted
            return session_id, None

    async def coach_message(data: dict, session_id: str):
        resume = await asyncio.to_thread(coach_chat.started, settings, session_id)
        return await run_prompt(
            {**data, "_owner": True}, session_id=session_id, resume=resume, cwd=settings.ask_dir
        )

    async def coach_stream_after_compaction(prepare: asyncio.Task, data: dict):
        while True:
            done, _ = await asyncio.wait({prepare}, timeout=KEEPALIVE_SECONDS)
            if done:
                break
            yield sse("keepalive", {})
        try:
            session_id, compacted = prepare.result()
            if compacted is not None:
                yield sse("compacted", compacted)
            answer = await coach_message(data, session_id)
        except ClaudeError as error:
            yield sse("error", {"error": error.code, "message": error.message})
            return
        if isinstance(answer, StreamingResponse):
            async for chunk in answer.body_iterator:
                yield chunk if isinstance(chunk, bytes) else chunk.encode()
        elif isinstance(answer, JSONResponse):  # it had to wait in the queue
            yield sse("queued", json.loads(answer.body))
        else:
            yield sse("done", answer)

    @app.post("/coach/chat/compact")
    async def compact_coach_conversation():
        """Compact now (one run): facts to the memory, summary kept, a new session."""
        session_id = (await asyncio.to_thread(coach_chat.state))["session_id"]
        if not await asyncio.to_thread(coach_chat.started, settings, session_id):
            raise ClaudeError("nothing_to_compact", 409, "the coach conversation is empty")
        async with coach_compacting:
            return await compact_coach()

    def listed_session(session_id: str) -> sessions.SessionInfo:
        info = known_session(session_id)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        return info

    @app.get("/sessions/{session_id}/history")
    def session_history(session_id: str, limit: int = history.DEFAULT_LIMIT, before: str = ""):
        """A page of the session's earlier exchanges, oldest first; ``before`` pages back."""
        info = listed_session(session_id)
        path = history.transcript_path(info, settings.session_roots)
        if path is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        page = history.read_page(path, limit, before or None)
        used = mcp_servers.used_in(info.id)  # the servers an answer had (#157)
        # A chat started from an answer's switch (#187), and the chat this one came from:
        # described afresh, so one that is gone or out of reach is not named.
        moved = session_links.continued_in(info.id)
        page["continued_from"] = described(session_links.continued_from(info.id))
        for exchange in page["exchanges"]:
            names = used.get(exchange["id"], [])
            exchange["mcp_servers"] = [{"name": n, "outside_sandbox": True} for n in names]
            targets = [described(target) for target in moved.get(exchange["id"], [])]
            exchange["continued_in"] = next((t for t in reversed(targets) if t), None)
            # A switch line of an earlier answer is not shown and never switches (#164).
            exchange["assistant"] = conversations.strip(exchange["assistant"])
            # Nor does an earlier request to apply an update (#180).
            exchange["assistant"] = updates.strip(exchange["assistant"])
        return page

    @app.get("/sessions/{session_id}/commands")
    async def session_commands(session_id: str):
        """Skills and commands a capable run of the session may use: the folder's own
        and the owner's personal ones (``source`` says which).

        ``enabled`` is about the folder's own: true only when the owner switched
        skills on for the session and the guardrail check with the skill probes
        passed; ``reason`` says why not. ``personal`` says the same for the owner's
        personal ones, which need no switch (#162).
        """
        info = listed_session(session_id)
        switched = (await asyncio.to_thread(session_meta.entry_for, info.id))["skills"]
        reason = None if switched else "skills are switched off for this session"
        personal_reason = None
        try:
            await capable.check_available(settings, info.folder)
            if not capable.personal_available():
                personal_reason = capable.PERSONAL_UNAVAILABLE_REASON
            if reason is None:
                await capable.skills_available(settings, info.folder)
        except ClaudeError as error:
            reason = reason or error.message
            personal_reason = personal_reason or error.message
        found = await asyncio.to_thread(
            commands.list_commands, info.folder, settings.personal_claude_dir
        )
        own = await asyncio.to_thread(commands.list_personal, settings.personal_claude_dir)
        return {
            "enabled": reason is None,
            "switched_on": switched,
            "reason": reason,
            "capable_only": True,
            "prefix": skills.PLUGIN + ":",
            "personal": {
                "enabled": personal_reason is None,
                "reason": personal_reason,
                "prefix": skills.PERSONAL + ":",
            },
            "commands": [entry | {"source": skills.PLUGIN} for entry in found]
            + [entry | {"source": skills.PERSONAL} for entry in own],
        }

    # ---- the command bar (#185): a request turned into actions of the registry ----

    def command_context(now: datetime, zone: str | None) -> actions.Context:
        loop = asyncio.get_running_loop()

        def busy() -> set[str] | None:
            """Sessions that cannot take a message now (asked from an action's worker
            thread, and only by an action that wants to send one): answering here, or
            held by another Claude Code process. ``None``: unknown, so nothing is sent."""
            try:
                held = asyncio.run_coroutine_threadsafe(held_elsewhere(), loop).result(8.0)
            except Exception:
                return None
            return None if held is None else set(held) | set(slots.active)

        return actions.Context(settings, now, zone, known_sessions, busy)

    def start_plan() -> None:
        """ "Prepare today's plan again" from the bar: a run of its own, not waited for."""

        async def plan() -> None:
            with contextlib.suppress(Exception):  # busy or too soon: the coach says so itself
                await coach.refresh(settings)

        run = asyncio.create_task(plan())
        background.add(run)
        run.add_done_callback(background.discard)

    @app.post("/command")
    async def command(request: Request):
        """Body: ``{"request_id", "text" | "choice", "language"?, "now"?, "timezone"?,
        "previous"?, "coach_said"?}``. Done actions with undo tokens, app actions, or one
        question; with what was understood, the part that is talk for the coach, and what
        cannot be done."""
        body = await read_json_object(request)
        # No chat run slot: the bar's short runs have their own limit (command_bar._RUNS).
        return await command_bar.handle(
            settings, body, context=command_context, start_plan=start_plan
        )

    @app.post("/command/undo")
    async def command_undo(request: Request):
        """Body: ``{"undo": "<token>"}``: takes one action of the bar back, once."""
        body = await read_json_object(request)
        return await asyncio.to_thread(actions.undo, body.get("undo"))

    @app.get("/command/actions")
    async def command_actions():
        """The action registry: every feature of the app as an action."""
        return {"actions": actions.catalogue()}

    # ---- the university Moodle: status, courses, assignments, deadlines ----
    app.include_router(moodle_api.router())

    return app
