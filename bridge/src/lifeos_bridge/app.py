"""The ASGI application."""

from __future__ import annotations

import hmac
import json
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from . import __version__, claude, sessions
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


def sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n".encode()


def create_app(settings: Settings) -> FastAPI:
    # No interactive docs or schema endpoints: less surface, nothing to leak.
    app = FastAPI(
        title="LifeOS bridge",
        version=__version__,
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
        run_args = {"session_id": session_id, "resume": resume, "cwd": cwd}
        streaming = bool(data.get("stream"))
        slots.acquire(session_id)
        try:
            # Never write into a conversation that is open somewhere else.
            if resume and session_id in await claude.sessions_in_use(settings):
                raise ClaudeError(
                    "session_in_use", 409, "this session is open on the computer right now"
                )
            if not streaming:
                result = await claude.run(settings, prompt, **run_args)
                return {
                    "answer": result.answer,
                    "session_id": result.session_id or session_id,
                    "duration_ms": result.duration_ms,
                    "num_turns": result.num_turns,
                    "denied_tools": result.denied_tools,
                }
        except BaseException:
            streaming = False  # nothing will stream; release below
            raise
        finally:
            if not streaming:
                slots.release(session_id)

        async def events() -> AsyncIterator[bytes]:
            try:
                yield sse("session", {"session_id": session_id})
                try:
                    async for event, payload in claude.stream(settings, prompt, **run_args):
                        yield sse(event, payload)
                except ClaudeError as error:
                    yield sse("error", {"error": error.code, "message": error.message})
            finally:
                slots.release(session_id)

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
        info = sessions.find_session(session_id, settings.allowed_roots)
        if info is None:
            raise ClaudeError("session_not_found", 404, "unknown session")
        if not info.folder.is_dir():
            raise ClaudeError("folder_missing", 409, "the session's folder no longer exists")
        return await run_prompt(data, session_id=info.id, resume=True, cwd=info.folder)

    @app.get("/sessions")
    async def list_sessions(limit: int = 50):
        """Claude Code sessions in the allowed folders, newest first."""
        limit = max(1, min(limit, 200))
        found = sessions.list_sessions(settings.allowed_roots)
        return {
            "sessions": [s.as_json(in_use=s.id in slots.active) for s in found[:limit]],
            "total": len(found),
        }

    return app
