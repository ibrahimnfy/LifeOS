"""Command-line entry point: ``lifeos-bridge``."""

from __future__ import annotations

import sys

import uvicorn

from .app import create_app
from .config import ConfigError, load_settings


def main() -> int:
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"lifeos-bridge: configuration error: {exc}", file=sys.stderr)
        return 2
    # The access log records method, path and status only. Prompts travel in
    # request bodies and are never logged.
    uvicorn.run(create_app(settings), host=settings.host, port=settings.port, log_level="info")
    return 0


if __name__ == "__main__":
    sys.exit(main())
