"""The ASGI application."""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import json
import uuid
from collections.abc import AsyncIterator
from datetime import date, timedelta
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from . import (
    __version__,
    activity,
    allowed,
    approvals,
    capable,
    claude,
    coach,
    coach_chat,
    commands,
    contextmap,
    files,
    guardcheck,
    history,
    memory,
    moodle_api,
    moodle_sync,
    moodle_watch,
    push,
    relay,
    reminders,
    resources,
    routing,
    runcontrol,
    sdk_runner,
    session_meta,
    sessions,
    skill_installs,
    skills,
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
    return data


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


def create_app(settings: Settings) -> FastAPI:
    # No interactive docs or schema endpoints: less surface, nothing to leak.
    @contextlib.asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        async def watch_reminders() -> None:
            while True:
                with contextlib.suppress(Exception):  # the watcher must survive a bad file
                    await asyncio.to_thread(
                        reminders.fire_due, notify_script=settings.notify_script
                    )
                with contextlib.suppress(Exception):  # the coach never stops the reminders
                    await coach_tick()
                with contextlib.suppress(Exception):  # at most one request to the site per day
                    await asyncio.to_thread(
                        moodle_watch.check, notify_script=settings.notify_script
                    )
                with contextlib.suppress(Exception):  # the course sync, every six hours
                    await course_sync_tick()
                with contextlib.suppress(Exception):  # resources of a new syllabus, one at a time
                    await resources_tick()
                await asyncio.sleep(reminders.CHECK_INTERVAL_SECONDS)

        coach_runs: set[asyncio.Task] = set()

        async def coach_tick() -> None:
            """The morning plan (as its own task: a run takes a while) and its notification."""
            if not coach_runs and await asyncio.to_thread(coach.due_for_morning_run):
                run = asyncio.create_task(coach.morning_run(settings))
                coach_runs.add(run)
                run.add_done_callback(coach_runs.discard)
            await asyncio.to_thread(coach.send_daily_notification, settings)

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
            if not resource_runs and await asyncio.to_thread(resources.due):
                run = asyncio.create_task(resources.run_next(settings))
                resource_runs.add(run)
                run.add_done_callback(resource_runs.discard)

        task = asyncio.create_task(watch_reminders())
        # SDK runner: live sessions close after a while without messages.
        reaper = asyncio.create_task(sdk_pool.reap_forever()) if sdk_pool is not None else None
        # After an update of Claude Code or the Agent SDK the guardrail check reruns by itself.
        guard = guardcheck.GuardCheck(settings)
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
            running = (task, *coach_runs, *course_syncs, *resource_runs)
            for pending in (*running, *([reaper] if reaper else [])):
                pending.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await pending
            guarding.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await guarding
            await guard.close()  # a running check is stopped; the mode stays off
            if sdk_pool is not None:
                await sdk_pool.close_all()  # the transcripts keep every conversation

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

    def expire_approvals() -> None:
        for entry in approvals.take_expired():
            if entry.get("kind") == skill_installs.KIND:
                skill_installs.expire(entry)

    def with_files(payload: dict, session_id: str, cwd: Path) -> dict:
        """Turn file-offer lines of a finished answer into cards."""
        text, cards, refused = offers.from_answer(str(payload.get("answer") or ""), session_id, cwd)
        payload["answer"] = text
        payload["files"] = cards
        if refused:
            payload["files_refused"] = refused
        return payload

    control = runcontrol.RunControl()
    app.state.control = control
    # One live SDK client per active session when the SDK runner is selected.
    sdk_pool = sdk_runner.SdkPool(settings) if settings.runner == "sdk" else None
    app.state.sdk_pool = sdk_pool
    drains: set[asyncio.Task] = set()
    app.state.drains = drains
    silent_runs: set[str] = set()  # sessions whose current run must not wake the phone

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

    async def capable_on_sdk(cwd: Path) -> bool:
        """Which runner a capable run uses; raises when neither passed the guardrail check."""
        if sdk_pool is not None:
            try:
                await capable.check_available(settings, cwd, runner="sdk")
                return True
            except ClaudeError:
                pass  # the claude -p path stays the fallback until the SDK path passed
        await capable.check_available(settings, cwd, runner="cli")
        return False

    def finish(session_id: str) -> None:
        """A run of the session ended: free it and send what is waiting, if anything."""
        slots.release(session_id)
        control.finished(session_id)
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
                {"prompt": entry["prompt"], "mode": entry["mode"], "_queued": True},
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
            entry = control.add(session_id, prompt, mode, cwd, front=when == "now")
            if when == "now":
                control.unpause(session_id)
                await control.stop(session_id)
            return JSONResponse(
                {
                    "queued": True,
                    "queue_id": entry["id"],
                    "position": control.queue(session_id).index(entry) + 1
                    if entry in control.queue(session_id)
                    else 0,
                    "interrupted_current_run": when == "now",
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
        instructions = phone_instructions
        if is_coach:
            instructions += "\n\n" + await asyncio.to_thread(coach_chat.context)
        run_args["context"] = instructions
        if cwd.resolve() == settings.ask_dir.resolve():
            # Conversations in the memory folder start with the memory loaded.
            loaded = await asyncio.to_thread(memory.context_for_prompt, cwd)
            if loaded:
                run_args["context"] = instructions + "\n\n" + loaded
        streaming = bool(data.get("stream"))
        use_sdk = sdk_pool is not None
        watch = None
        slots.acquire(session_id)
        control.take_stopped(session_id)  # a stop request for an earlier run is not ours
        if not data.get("_queued"):
            # A message the owner sends directly ends the hold a cancel put on the queue.
            control.unpause(session_id, only="cancel")
        started = False
        try:
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
                # Decision #84: on the SDK runner only after the guardrail check passed
                # there; until then the claude -p path is the fallback, if it passed.
                use_sdk = await capable_on_sdk(cwd)
                if not use_sdk and sdk_pool is not None:
                    await sdk_pool.drop(session_id)  # never two kinds of writer at once
                watch = capable.AuditWatch(session_id)
                skills_dir = None
                if chosen["skills"]:
                    # The folder's own skills (#91): only after the check with the skill
                    # probes; never silently without them (switch them off instead).
                    runner = "sdk" if use_sdk else "cli"
                    await capable.skills_available(settings, cwd, runner=runner)
                    prompt = skills.check_prompt(prompt, cwd)
                    skills_dir = await asyncio.to_thread(skills.snapshot, cwd, session_id)
                policy = capable.build_policy(
                    settings, cwd, ask_host=use_sdk, skills_dir=skills_dir
                )
                run_args |= {"policy": policy, "observer": watch}
            if use_sdk:
                # Questions and approvals wait only for a client that streams the answer
                # and said it can show them; anything else is refused at once, as before.
                run_args["answering"] = streaming and data.get("interactive") is True
            if not is_capable and not streaming:
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
                return with_files(
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
            started = True
        finally:
            if not started:
                finish(session_id)

        async def run_events() -> AsyncIterator[tuple[str, dict]]:
            """The run as events; frees the session when it ends or is abandoned."""
            try:
                runner = (
                    sdk_runner.stream(sdk_pool, settings, prompt, **run_args)
                    if use_sdk and sdk_pool is not None
                    else claude.stream(settings, prompt, **run_args)
                )
                async for event, payload in runner:
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
                if event == "keepalive":
                    yield b": keep-alive\n\n"  # a comment: keeps the relay's read open
                    continue
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
        """
        data = await read_json_object(request)
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
        """
        stopped = await control.stop(session_id)
        if stopped:
            control.pause(session_id, "cancel")
        return {"cancelled": stopped, "queue_paused": session_id in control.paused,
                "note": runcontrol.STOP_NOTE if stopped else "nothing was running"}  # fmt: skip

    @app.get("/sessions/{session_id}/interactions")
    async def list_interactions(session_id: str):
        """Questions and approvals that wait for the owner: those of a running turn (SDK
        runner), and those that wait across runs (skill installs, MCP servers)."""
        await asyncio.to_thread(expire_approvals)
        live = sdk_pool.asking.pending(session_id) if sdk_pool is not None else []
        return {"pending": live + await asyncio.to_thread(approvals.pending, session_id)}

    @app.post("/sessions/{session_id}/interactions/{interaction_id}")
    async def answer_interaction(session_id: str, interaction_id: str, request: Request):
        """Answer a waiting question or approval; see ``interactions.parse_answer``."""
        await asyncio.to_thread(expire_approvals)
        if await asyncio.to_thread(approvals.get, session_id, interaction_id) is not None:
            approve = approvals.read_approve(await read_json_object(request))
            entry = await asyncio.to_thread(approvals.take, session_id, interaction_id)
            if entry["kind"] == skill_installs.KIND:
                done = await asyncio.to_thread(skill_installs.answer, entry, approve)
            else:
                raise ClaudeError("not_pending", 404, "this approval is no longer waiting")
            return {"answered": True, "id": interaction_id, **done}
        if sdk_pool is None:
            raise ClaudeError("not_pending", 404, "this question is no longer waiting")
        data = await read_json_object(request)
        return sdk_pool.asking.answer(session_id, interaction_id, data)

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
        """Send the waiting messages (after a cancel, an error or a restart)."""
        control.unpause(session_id)
        await drain(session_id)
        return {"resumed": True, "queue": [control.public(e) for e in control.queue(session_id)]}

    def chosen_folder(data: dict) -> Path:
        """The folder of a new conversation: the memory folder, or an allowed folder by name."""
        folder = data.get("folder")
        if folder is None:
            return settings.ask_dir
        if not isinstance(folder, str) or not folder.strip() or len(folder) > 4096:
            raise ClaudeError("bad_request", 400, "`folder` must be one of the folders of /folders")
        wanted = Path(folder).expanduser()
        for root in settings.allowed_roots:
            if wanted == root or (wanted.is_absolute() and wanted.resolve() == root):
                return root
        raise ClaudeError("folder_not_allowed", 403, "this folder is not an allowed folder")

    @app.get("/folders")
    async def list_folders():
        """Where a new conversation may run: the memory folder and the allowed folders."""
        memory_dir = settings.ask_dir.resolve()
        return {
            "folders": [
                {
                    "path": str(root),
                    "label": root.name,
                    "kind": "memory" if root == memory_dir else "allowed",
                    "default": root == memory_dir,
                }
                for root in settings.allowed_roots
                if root.is_dir()
            ]
        }

    @app.post("/ask")
    async def ask(request: Request):
        """Run a prompt through headless Claude.

        Body: ``{"prompt": str, "session_id": str?, "folder": str?, "stream": bool?}``.
        A new conversation runs in the memory folder, or in the allowed folder
        named by ``folder``. ``session_id`` continues an earlier conversation in
        the folder it was started in.
        """
        data = await read_json_object(request)
        read_prompt(data)
        read_mode(data)
        resume = data.get("session_id") is not None
        if resume:
            session_id = data["session_id"]
            if not isinstance(session_id, str):
                raise ClaudeError("session_not_found", 404, "unknown session")
            if claude.session_exists(settings.ask_dir, session_id):
                cwd = settings.ask_dir
            else:
                info = sessions.find_session(session_id, settings.allowed_roots)
                if info is None or not info.folder.is_dir():
                    raise ClaudeError("session_not_found", 404, "unknown session")
                cwd = info.folder
        else:
            cwd = chosen_folder(data)
            # No --name: a fixed name would hide the first prompt, which is the
            # more useful title in the session list.
            session_id = str(uuid.uuid4())
        return await run_prompt(data, session_id=session_id, resume=resume, cwd=cwd)

    @app.post("/sessions/{session_id}/messages")
    async def resume_session(session_id: str, request: Request):
        """Continue a listed session with a new message, in its original folder.

        Body: ``{"prompt": str, "stream": bool?}``.
        """
        data = await read_json_object(request)
        read_prompt(data)
        read_mode(data)
        info = sessions.find_session(session_id, settings.allowed_roots)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        if not info.folder.is_dir():
            raise ClaudeError("folder_missing", 409, "the session's folder no longer exists")
        return await run_prompt(data, session_id=info.id, resume=True, cwd=info.folder)

    background: set[asyncio.Task] = set()

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
        """Claude Code sessions in the allowed folders: pinned first, then newest first."""
        limit = max(1, min(limit, 200))
        found = sessions.list_sessions(settings.allowed_roots)
        meta = session_meta.load()
        items = [session_meta.describe(s, in_use=s.id in slots.active, data=meta) for s in found]
        items.sort(key=lambda item: not item["pinned"])  # stable: keeps newest first
        return {"sessions": items[:limit], "total": len(found), "groups": meta["groups"]}

    @app.patch("/sessions/{session_id}")
    async def update_session(session_id: str, request: Request):
        """Set the owner's title, group, pin or reply-notification switch for a session.

        Body: any of ``{"title": str|null, "group": id|null, "pinned": bool,
        "notify_replies": bool}``. Only the bridge's own metadata changes.
        """
        changes = await read_json_object(request)
        read_model_choice(changes)  # refuses unknown models and effort levels
        info = sessions.find_session(session_id, settings.allowed_roots)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        session_meta.update_session(info.id, changes)
        return session_meta.describe(info, in_use=info.id in slots.active, data=session_meta.load())

    @app.get("/session-groups")
    async def list_groups():
        return {"groups": session_meta.load()["groups"]}

    @app.post("/session-groups", status_code=201)
    async def create_group(request: Request):
        """Body: ``{"name": str}``."""
        return session_meta.create_group((await read_json_object(request)).get("name"))

    @app.patch("/session-groups/{group_id}")
    async def rename_group(group_id: str, request: Request):
        """Body: ``{"name": str}``."""
        return session_meta.rename_group(group_id, (await read_json_object(request)).get("name"))

    @app.delete("/session-groups/{group_id}")
    async def delete_group(group_id: str):
        """Sessions of the group become ungrouped; no session is deleted."""
        session_meta.delete_group(group_id)
        return {"deleted": True}

    @app.get("/reminders")
    async def list_reminders(session_id: str | None = None, state: str | None = None):
        """Reminders, soonest first. ``state``: ``scheduled`` or ``fired``."""
        return {"reminders": reminders.listing(session_id, state)}

    @app.post("/reminders", status_code=201)
    async def create_reminder(request: Request):
        """Body: ``{"session_id", "at", "note"?, "repeat"?: none|daily|weekly, "timezone"?}``."""
        data = await read_json_object(request)
        session_id = data.pop("session_id", None)
        info = (
            sessions.find_session(session_id, settings.allowed_roots)
            if isinstance(session_id, str)
            else None
        )
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
        """Quick capture into the inbox. Body: ``{"text": str, "client_id"?: str}``.

        A repeat with a known ``client_id`` answers 200 with the stored note and
        writes nothing; the same ``client_id`` with other text answers 409.
        """
        data = await read_json_object(request)
        saved = await asyncio.to_thread(
            memory.add_note, brain, data.get("text"), None, data.get("client_id")
        )
        return JSONResponse(saved, status_code=200 if saved["repeat"] else 201)

    @app.get("/notes")
    async def list_notes(query: str = "", limit: int = 100):
        """Captured notes (newest first) and topic notes, each with a preview."""
        return {"notes": await asyncio.to_thread(memory.list_notes, brain, query, limit)}

    @app.get("/notes/{note_id}")
    async def get_note(note_id: str):
        return await asyncio.to_thread(memory.get_note, brain, note_id)

    @app.patch("/notes/{note_id}")
    async def update_note(note_id: str, request: Request):
        """Change the text of a captured note. Body: ``{"text": str}``."""
        data = await read_json_object(request)
        return await asyncio.to_thread(memory.update_note, brain, note_id, data.get("text"))

    @app.patch("/tasks/{task_id}")
    async def update_task(task_id: str, request: Request):
        """Change a task. Body: any of ``{"title": str, "due": "YYYY-MM-DD" | null}``."""
        data = await read_json_object(request)
        return (await asyncio.to_thread(memory.update_task, brain, task_id, data)).as_json()

    @app.get("/tasks")
    async def list_tasks():
        """Open tasks, in the order of the file."""
        return {"tasks": [t.as_json() for t in await asyncio.to_thread(memory.list_tasks, brain)]}

    @app.post("/tasks", status_code=201)
    async def add_task(request: Request):
        """Body: ``{"title", "due"?: "YYYY-MM-DD", "project"?, "course"?, "client_id"?}``.

        A repeat with a known ``client_id`` answers 200 with the stored task and
        writes nothing; the same ``client_id`` with other fields answers 409.
        """
        data = await read_json_object(request)
        saved = await asyncio.to_thread(memory.save_task, brain, data)
        return JSONResponse(saved, status_code=200 if saved["repeat"] else 201)

    @app.post("/tasks/{task_id}/complete")
    async def complete_task(task_id: str):
        return (await asyncio.to_thread(memory.complete_task, brain, task_id)).as_json()

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
            with contextlib.suppress(ClaudeError):
                prompt = await asyncio.to_thread(coach_chat.compact_prompt, settings.ask_dir)
                silent_runs.add(session_id)
                result = await run_prompt(
                    {"prompt": prompt}, session_id=session_id, resume=True, cwd=settings.ask_dir
                )
                answer = result.get("answer") if isinstance(result, dict) else None
            silent_runs.discard(session_id)
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
        compacted = None
        async with coach_compacting:  # never into a session that is being compacted
            session_id = (await asyncio.to_thread(coach_chat.state))["session_id"]
            if session_id not in slots.active and await asyncio.to_thread(
                coach_chat.too_long, settings
            ):
                compacted = await compact_coach()
                session_id = compacted["session_id"]
        resume = await asyncio.to_thread(coach_chat.started, settings, session_id)
        answer = await run_prompt(data, session_id=session_id, resume=resume, cwd=settings.ask_dir)
        if compacted is not None and isinstance(answer, dict):
            answer["compacted"] = compacted
        return answer

    @app.post("/coach/chat/compact")
    async def compact_coach_conversation():
        """Compact now (one run): facts to the memory, summary kept, a new session."""
        session_id = (await asyncio.to_thread(coach_chat.state))["session_id"]
        if not await asyncio.to_thread(coach_chat.started, settings, session_id):
            raise ClaudeError("nothing_to_compact", 409, "the coach conversation is empty")
        async with coach_compacting:
            return await compact_coach()

    def listed_session(session_id: str) -> sessions.SessionInfo:
        info = sessions.find_session(session_id, settings.allowed_roots)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        return info

    @app.get("/sessions/{session_id}/history")
    def session_history(session_id: str, limit: int = history.DEFAULT_LIMIT, before: str = ""):
        """A page of the session's earlier exchanges, oldest first; ``before`` pages back."""
        info = listed_session(session_id)
        path = history.transcript_path(info, settings.allowed_roots)
        if path is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        return history.read_page(path, limit, before or None)

    @app.get("/sessions/{session_id}/commands")
    async def session_commands(session_id: str):
        """Skills and commands of the session's folder, and whether its capable runs may use them.

        ``enabled`` is true only when the owner switched skills on for the session
        and the guardrail check with the skill probes passed; ``reason`` says why not.
        """
        info = listed_session(session_id)
        switched = (await asyncio.to_thread(session_meta.entry_for, info.id))["skills"]
        reason = None if switched else "skills are switched off for this session"
        if reason is None:
            try:
                use_sdk = await capable_on_sdk(info.folder)
                runner = "sdk" if use_sdk else "cli"
                await capable.skills_available(settings, info.folder, runner=runner)
            except ClaudeError as error:
                reason = error.message
        found = await asyncio.to_thread(commands.list_commands, info.folder)
        return {
            "enabled": reason is None,
            "switched_on": switched,
            "reason": reason,
            "capable_only": True,
            "prefix": skills.PLUGIN + ":",
            "commands": found,
        }

    # ---- the university Moodle: status, courses, assignments, deadlines ----
    app.include_router(moodle_api.router())

    return app
