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
    allowed,
    capable,
    claude,
    contextmap,
    memory,
    reminders,
    session_meta,
    sessions,
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
                await asyncio.sleep(reminders.CHECK_INTERVAL_SECONDS)

        task = asyncio.create_task(watch_reminders())
        try:
            yield
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task

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

    async def run_prompt(data: dict, *, session_id: str, resume: bool, cwd: Path):
        """Shared by /ask and session resume: limits, in-use check, run or stream."""
        prompt = read_prompt(data)
        is_capable = read_mode(data) == "capable"
        run_args = {"session_id": session_id, "resume": resume, "cwd": cwd}
        if cwd.resolve() == settings.ask_dir.resolve():
            # Conversations in the memory folder start with the memory loaded.
            run_args["context"] = await asyncio.to_thread(memory.context_for_prompt, cwd)
        streaming = bool(data.get("stream"))
        watch = None
        slots.acquire(session_id)
        started = False
        try:
            # Never write into a conversation that is open somewhere else.
            if resume and session_id in await claude.sessions_in_use(settings):
                raise ClaudeError(
                    "session_in_use", 409, "this session is open on the computer right now"
                )
            if is_capable:
                await capable.check_available(settings, cwd)
                watch = capable.AuditWatch(session_id)
                run_args |= {"policy": capable.build_policy(settings, cwd), "observer": watch}
            elif not streaming:
                result = await claude.run(settings, prompt, **run_args)
                return {
                    "answer": result.answer,
                    "session_id": result.session_id or session_id,
                    "duration_ms": result.duration_ms,
                    "num_turns": result.num_turns,
                    "denied_tools": result.denied_tools,
                }
            started = True
        finally:
            if not started:
                slots.release(session_id)

        async def run_events() -> AsyncIterator[tuple[str, dict]]:
            """The run as events; releases the slot when it ends or is abandoned."""
            try:
                async for event, payload in claude.stream(settings, prompt, **run_args):
                    if event == "done" and watch is not None:
                        payload["refused_actions"] = watch.refused_actions()
                    yield event, payload
            except ClaudeError as error:
                yield "error", {"error": error.code, "message": error.message}
            finally:
                slots.release(session_id)

        if not streaming:  # capable run for a client that wants one JSON answer
            final: tuple[str, dict] = ("error", {"error": "bad_output", "message": ""})
            async for event, payload in run_events():
                if event in ("done", "error"):
                    final = (event, payload)
            if final[0] == "error":
                raise ClaudeError(
                    final[1]["error"],
                    504 if final[1]["error"] == "timeout" else 502,
                    final[1].get("message", ""),
                )
            return final[1]

        async def events() -> AsyncIterator[bytes]:
            yield sse("session", {"session_id": session_id})
            async for event, payload in run_events():
                yield sse(event, payload)

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    @app.post("/ask")
    async def ask(request: Request):
        """Run a prompt through headless Claude in the configured folder.

        Body: ``{"prompt": str, "session_id": str?, "stream": bool?}``.
        ``session_id`` continues an earlier ``/ask`` conversation.
        """
        data = await read_json_object(request)
        read_prompt(data)
        read_mode(data)
        resume = data.get("session_id") is not None
        if resume:
            session_id = data["session_id"]
            if not isinstance(session_id, str) or not claude.session_exists(
                settings.ask_dir, session_id
            ):
                raise ClaudeError("session_not_found", 404, "unknown session")
        else:
            # No --name: a fixed name would hide the first prompt, which is the
            # more useful title in the session list.
            session_id = str(uuid.uuid4())
        return await run_prompt(data, session_id=session_id, resume=resume, cwd=settings.ask_dir)

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
        """Quick capture into the inbox. Body: ``{"text": str}``."""
        data = await read_json_object(request)
        return await asyncio.to_thread(memory.add_note, brain, data.get("text"))

    @app.get("/tasks")
    async def list_tasks():
        """Open tasks, in the order of the file."""
        return {"tasks": [t.as_json() for t in await asyncio.to_thread(memory.list_tasks, brain)]}

    @app.post("/tasks", status_code=201)
    async def add_task(request: Request):
        """Body: ``{"title": str, "due"?: "YYYY-MM-DD", "project"?: str, "course"?: str}``."""
        data = await read_json_object(request)
        return (await asyncio.to_thread(memory.add_task, brain, data)).as_json()

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

    @app.get("/agenda")
    async def agenda(day: str | None = None, days: int = 7):
        """Today's events and tasks, overdue tasks and the next ``days`` days."""
        days = max(1, min(days, 60))
        return await asyncio.to_thread(memory.agenda, brain, read_day(day, "day"), days)

    return app
