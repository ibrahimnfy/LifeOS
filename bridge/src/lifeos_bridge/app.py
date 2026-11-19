"""The ASGI application."""

from __future__ import annotations

import hmac

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import __version__
from .config import Settings

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

    return app
