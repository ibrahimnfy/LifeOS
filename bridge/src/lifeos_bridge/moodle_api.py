"""Bridge endpoints for the university Moodle: ``/moodle/...``.

Read-only views over :mod:`moodle`. Answers are kept in memory for ten
minutes per call, to be gentle with the site; ``?refresh=true`` asks the
site again. File addresses never leave the bridge: a file is named by an
opaque ``key`` that :meth:`MoodleApi.find_file` resolves again. Nothing is
downloaded here.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from typing import Any

from fastapi import APIRouter

from . import moodle, moodle_watch
from .claude import ClaudeError

CACHE_SECONDS = 600
MAX_DAYS = 365

ERRORS = {
    "not_configured": ("moodle_not_configured", 503),
    "token_expired": ("moodle_sign_in_needed", 503),
    "unreachable": ("moodle_unreachable", 502),
}
SIGN_IN_MESSAGE = "Run scripts/moodle-login"


def as_claude_error(error: moodle.MoodleError) -> ClaudeError:
    code, status = ERRORS.get(error.code, ("moodle_error", 502))
    message = SIGN_IN_MESSAGE if error.code == "token_expired" else error.message
    return ClaudeError(code, status, message)


class MoodleApi:
    """The cached calls behind the endpoints. One per bridge."""

    def __init__(self, timer: Callable[[], float] = time.monotonic) -> None:
        self._timer = timer
        self._lock = threading.Lock()
        self._cache: dict[tuple, tuple[float, Any]] = {}

    def _cached(self, key: tuple, refresh: bool, load: Callable[[moodle.Client], Any]) -> Any:
        """The stored answer when it is fresh, else a new one. Errors are never stored."""
        try:
            client = moodle.from_env()  # first: not configured means no stored answer either
            with self._lock:
                hit = self._cache.get(key)
            if hit is not None and not refresh and self._timer() - hit[0] < CACHE_SECONDS:
                return hit[1]
            value = load(client)
        except moodle.MoodleError as error:
            if error.code in ("not_configured", "token_expired"):
                with self._lock:
                    self._cache.clear()
            raise as_claude_error(error) from None
        with self._lock:
            self._cache[key] = (self._timer(), value)
        return value

    def courses(self, refresh: bool = False) -> list[dict]:
        return self._cached(("courses",), refresh, lambda client: client.courses())

    def contents(self, course_id: int, refresh: bool = False) -> list[dict]:
        """Sections as the client returns them, file addresses included. Not for responses."""
        return self._cached(
            ("contents", course_id), refresh, lambda client: client.contents(course_id)
        )

    def assignments(self, course_id: int | None = None, refresh: bool = False) -> list[dict]:
        ids = [course_id] if course_id is not None else None
        return self._cached(
            ("assignments", course_id), refresh, lambda client: client.assignments(ids)
        )

    def deadlines(self, days: int, refresh: bool = False) -> dict:
        def load(client: moodle.Client) -> dict:
            start = int(time.time())
            end = start + days * 86400
            return {
                "from": moodle.iso(start),
                "to": moodle.iso(end),
                "deadlines": client.deadlines(start, end),
            }

        return self._cached(("deadlines", days), refresh, load)

    def find_file(self, course_id: int, key: str, refresh: bool = False) -> dict | None:
        """The file a ``key`` of ``/moodle/courses/{id}`` stands for, with its address."""
        for section in self.contents(course_id, refresh):
            for module in section["modules"]:
                for entry in module["files"]:
                    if entry["key"] == key:
                        return entry
        return None


def without_addresses(sections: list[dict]) -> list[dict]:
    """A copy of course contents in which no file carries its address."""
    return [
        {
            **section,
            "modules": [
                {
                    **module,
                    "files": [
                        {name: value for name, value in entry.items() if name != "fileurl"}
                        for entry in module["files"]
                    ],
                }
                for module in section["modules"]
            ],
        }
        for section in sections
    ]


def router(api: MoodleApi | None = None) -> APIRouter:
    """The ``/moodle`` routes. They sit behind the bridge's bearer-token check."""
    api = api or MoodleApi()
    routes = APIRouter(prefix="/moodle")

    @routes.get("/status")
    def moodle_status() -> dict:
        """Whether Moodle is set up and how old the sign-in is. Never contacts the site."""
        return moodle_watch.status()

    @routes.get("/courses")
    def moodle_courses(refresh: bool = False) -> dict:
        return {"courses": api.courses(refresh)}

    @routes.get("/courses/{course_id}")
    def moodle_course(course_id: int, refresh: bool = False) -> dict:
        """Sections, modules and files of one course. Files carry a ``key``, no address."""
        return {
            "course_id": course_id,
            "sections": without_addresses(api.contents(course_id, refresh)),
        }

    @routes.get("/assignments")
    def moodle_assignments(course: int | None = None, refresh: bool = False) -> dict:
        return {"assignments": api.assignments(course, refresh)}

    @routes.get("/deadlines")
    def moodle_deadlines(days: int = 30, refresh: bool = False) -> dict:
        """What is due in the next ``days`` days (1 to 365)."""
        return api.deadlines(max(1, min(days, MAX_DAYS)), refresh)

    return routes
