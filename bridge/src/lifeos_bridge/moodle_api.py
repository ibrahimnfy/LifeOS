"""Bridge endpoints for the university Moodle: ``/moodle/...`` and ``/courses/...``.

``/moodle``: read-only views over :mod:`moodle`. Answers are kept in memory
for ten minutes per call, to be gentle with the site; ``?refresh=true`` asks
the site again. File addresses never leave the bridge there: a file is named
by an opaque ``key`` that :meth:`MoodleApi.find_file` resolves again.

``/courses``: what the app's Courses section shows, read from the state that
:mod:`moodle_sync` keeps, so the answers are fast and still there while the
site cannot be reached. Only ``POST /courses/refresh`` contacts the site (in
the background). No endpoint here starts a model run.

One answer is as sensitive as the Moodle token: the download address of
``POST /courses/{id}/files/{key}/address``. It is never logged or stored.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from . import audit, moodle, moodle_sync, moodle_watch
from .claude import ClaudeError

CACHE_SECONDS = 600
MAX_DAYS = 365

ERRORS = {
    "not_configured": ("moodle_not_configured", 503),
    "token_expired": ("moodle_sign_in_needed", 503),
    "unreachable": ("moodle_unreachable", 502),
}
SIGN_IN_MESSAGE = "Run scripts/moodle-login"
SUMMARY_DAYS = 14
ADDRESS_SECONDS = 600  # how long the phone may keep a download address before discarding it


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


# ---- /courses: views of the synced state ----------------------------------------------


def _envelope(state: dict) -> dict:
    """When the data was read from the site, and whether it may be out of date."""
    return {
        "last_sync": state.get("last_sync"),
        "stale": moodle_sync.is_stale(state, moodle_sync.now_utc()),
    }


def _record(state: dict, course_id: int) -> dict:
    record = state["courses"].get(str(course_id))
    if not isinstance(record, dict) or not record.get("listed", True):
        raise ClaudeError("course_not_found", 404, "no synced course has this id")
    return record


def _records(state: dict) -> list[dict]:
    return sorted(moodle_sync.listed(state), key=lambda record: str(record.get("name", "")))


def _upcoming(record: dict, now_ts: int, until_ts: int | None = None) -> list[dict]:
    """Deadlines of a course that are still ahead, nearest first."""
    found = [
        {"name": item["name"], "due": item["at"], "due_iso": moodle.iso(item["at"])}
        for item in (record.get("deadlines") or {}).values()
        if item["at"] >= now_ts and (until_ts is None or item["at"] <= until_ts)
    ]
    return sorted(found, key=lambda item: item["due"])


def _new(record: dict) -> dict:
    counts = record.get("new") if isinstance(record.get("new"), dict) else {}
    return {kind: int(counts.get(kind) or 0) for kind in moodle_sync.KINDS}


def _folder(record: dict) -> Path | None:
    root = moodle_sync.download_dir()
    return root / record["folder"] if root is not None else None


def _local_copy(record: dict, item: dict) -> Path | None:
    """The computer's copy of a file, when it was downloaded and is still there."""
    folder = _folder(record)
    if folder is None or not item.get("local"):
        return None
    path = folder / (item.get("dir") or "materials") / item["local"]
    if not path.is_file() or path.is_symlink():
        return None
    return path if path.resolve().is_relative_to(folder.resolve()) else None


def summary_view(state: dict, now_ts: int) -> dict:
    until = now_ts + SUMMARY_DAYS * 86400
    deadlines = [
        {"course_id": record["id"], "course": record["name"], **item}
        for record in _records(state)
        for item in _upcoming(record, now_ts, until)
    ]
    return {
        "configured": moodle_sync.configured(),
        "deadlines": sorted(deadlines, key=lambda item: item["due"]),
        "new_total": sum(sum(_new(record).values()) for record in _records(state)),
        **_envelope(state),
    }


def list_view(state: dict, now_ts: int) -> dict:
    courses = []
    for record in _records(state):
        folder, new = _folder(record), _new(record)
        courses.append(
            {
                "id": record["id"],
                "name": record["name"],
                "shortname": record.get("shortname"),
                "next_deadline": next(iter(_upcoming(record, now_ts)), None),
                "new": new,
                "has_new": any(new.values()),
                "folder_exists": folder is not None and folder.is_dir(),
            }
        )
    return {"courses": courses, **_envelope(state)}


def _file_view(record: dict, key: str, item: dict) -> dict:
    return {
        "key": key,
        "name": item["name"],
        "type": item.get("type"),
        "size": item.get("size"),
        "modified": item.get("modified"),
        "modified_iso": moodle.iso(item.get("modified")),
        "new": bool(item.get("new")),
        "downloaded": _local_copy(record, item) is not None,
        "skipped": item.get("skipped"),
    }


def materials_view(state: dict, record: dict) -> dict:
    files = record.get("files") or {}
    sections = [
        {
            "name": section["name"],
            "files": [
                _file_view(record, key, files[key]) for key in section["files"] if key in files
            ],
        }
        for section in record.get("sections") or []
    ]
    return {
        "course_id": record["id"],
        "name": record["name"],
        "sections": sections,
        # The course page's links, then those found in its pages and books ("source").
        "links": [*(record.get("links") or []), *moodle_sync.text_links(record)],
        **_envelope(state),
    }


def announcements_view(state: dict, record: dict) -> dict:
    items = [
        {
            "id": item["id"],
            "subject": item["subject"],
            "text": item["text"],
            "created": item["created"],
            "created_iso": moodle.iso(item["created"]),
            "modified_iso": moodle.iso(item["modified"]),
        }
        for item in record.get("announcements") or []
    ]
    return {"course_id": record["id"], "announcements": items, **_envelope(state)}


def _assignment_summary(item: dict) -> dict:
    return {
        "id": item["id"],
        "name": item["name"],
        "opens": item.get("opens"),
        "opens_iso": moodle.iso(item.get("opens")),
        "due": item["due"],
        "due_iso": moodle.iso(item["due"]),
        "cutoff": item.get("cutoff"),
        "cutoff_iso": moodle.iso(item.get("cutoff")),
        "status": item.get("status"),
        "submitted": (item.get("status") or {}).get("submission") == "submitted",
        "files": len(item.get("files") or []),
    }


def assignments_view(state: dict, record: dict) -> dict:
    items = [_assignment_summary(item) for item in record.get("assignments") or []]
    items.sort(key=lambda item: (item["due"] is None, item["due"] or 0))
    return {"course_id": record["id"], "assignments": items, **_envelope(state)}


def assignment_view(state: dict, record: dict, assignment_id: int) -> dict:
    """One assignment in full; its files download through the course file routes."""
    item = next((a for a in record.get("assignments") or [] if a["id"] == assignment_id), None)
    if item is None:
        raise ClaudeError("assignment_not_found", 404, "the course has no assignment with this id")
    files = record.get("files") or {}
    return {
        "course_id": record["id"],
        "course": record["name"],
        **_assignment_summary(item),
        "grading_due": item.get("grading_due"),
        "grading_due_iso": moodle.iso(item.get("grading_due")),
        "modified_iso": moodle.iso(item.get("modified")),
        "description": item.get("description") or "",
        "files": [
            _file_view(record, key, files[key])
            for key in item.get("files") or []
            if key in files and not files[key].get("gone")
        ],
        **_envelope(state),
    }


def _grades(record: dict) -> dict:
    items = [
        {**item, "graded_at_iso": moodle.iso(item.get("graded_at"))}
        for item in record.get("grades") or []
    ]
    return {"overall": record.get("overall"), "items": items}


def _synced_file(state: dict, course_id: int, key: str) -> tuple[dict, dict]:
    record = _record(state, course_id)
    item = (record.get("files") or {}).get(key)
    if not isinstance(item, dict) or item.get("gone"):
        raise ClaudeError("file_not_found", 404, "the course has no file with this key")
    return record, item


def download_address(state: dict, course_id: int, key: str) -> dict:
    """Where the phone can fetch a file directly from the site. **Contains the token.**

    Recorded in the audit log first (course, name, size; never the address);
    when the record cannot be written, no address is handed out.
    """
    item = _synced_file(state, course_id, key)[1]
    try:
        address = moodle.from_env().address_for(item.get("url") or "")
    except moodle.MoodleError as error:
        if error.code == "bad_file_url":
            raise ClaudeError("file_unavailable", 409, error.message) from None
        raise as_claude_error(error) from None
    try:
        audit.record(
            "CourseFileAddress",
            {"course_id": course_id, "name": item["name"], "size": item.get("size")},
            session_id=f"course-{course_id}",
        )
    except audit.AuditUnavailable:
        del address  # never handed out without its record
        raise ClaudeError(
            "audit_unavailable", 503, "the audit log cannot be written; no address is given"
        ) from None
    return {
        "url": address,
        "name": item["name"],
        "size": item.get("size"),
        "type": item.get("type"),
        "expires_in": ADDRESS_SECONDS,
    }


def courses_router() -> APIRouter:
    """The ``/courses`` routes. They sit behind the bridge's bearer-token check."""
    routes = APIRouter(prefix="/courses")

    def now_ts() -> int:
        return int(time.time())

    @routes.get("/summary")
    def courses_summary() -> dict:
        """Deadlines of all courses in the next two weeks, and how much is new."""
        return summary_view(moodle_sync.load_state(), now_ts())

    @routes.get("")
    def courses_list() -> dict:
        return list_view(moodle_sync.load_state(), now_ts())

    @routes.get("/grades")
    def all_grades() -> dict:
        """The grades of every course, for one screen."""
        state = moodle_sync.load_state()
        courses = [
            {"id": record["id"], "name": record["name"], **_grades(record)}
            for record in _records(state)
        ]
        return {"courses": courses, **_envelope(state)}

    @routes.get("/sync")
    def sync_state() -> dict:
        return moodle_sync.status()

    @routes.post("/refresh")
    def refresh(request: Request) -> JSONResponse:
        """Start a sync now, unless one is running or was tried in the last two minutes."""
        outcome = moodle_sync.start(request.app.state.settings)
        if outcome == "not_configured":
            raise as_claude_error(moodle.MoodleError("not_configured"))
        body = {"started": outcome == "started", "reason": outcome, **moodle_sync.status()}
        return JSONResponse(body, status_code=202 if outcome == "started" else 429)

    @routes.get("/{course_id}/materials")
    def materials(course_id: int) -> dict:
        state = moodle_sync.load_state()
        return materials_view(state, _record(state, course_id))

    @routes.get("/{course_id}/announcements")
    def announcements(course_id: int) -> dict:
        state = moodle_sync.load_state()
        return announcements_view(state, _record(state, course_id))

    @routes.get("/{course_id}/assignments")
    def assignments(course_id: int) -> dict:
        state = moodle_sync.load_state()
        return assignments_view(state, _record(state, course_id))

    @routes.get("/{course_id}/assignments/{assignment_id}")
    def assignment(course_id: int, assignment_id: int) -> dict:
        state = moodle_sync.load_state()
        return assignment_view(state, _record(state, course_id), assignment_id)

    @routes.get("/{course_id}/grades")
    def grades(course_id: int) -> dict:
        state = moodle_sync.load_state()
        record = _record(state, course_id)
        return {"course_id": record["id"], **_grades(record), **_envelope(state)}

    @routes.post("/{course_id}/seen")
    def seen(course_id: int) -> dict:
        """The owner opened the course: nothing in it counts as new any more."""
        if not moodle_sync.seen(course_id):
            raise ClaudeError("course_not_found", 404, "no synced course has this id")
        return {"seen": True}

    @routes.post("/{course_id}/files/{key}/address")
    def file_address(course_id: int, key: str) -> JSONResponse:
        """A download address on the university site. It holds the token: never log it."""
        body = download_address(moodle_sync.load_state(), course_id, key)
        return JSONResponse(body, headers={"Cache-Control": "no-store"})

    @routes.post("/{course_id}/files/{key}/offer")
    def file_offer(course_id: int, key: str, request: Request) -> dict:
        """Offer the computer's copy instead, to be fetched with ``GET /files/{offer}``."""
        record, item = _synced_file(moodle_sync.load_state(), course_id, key)
        path = _local_copy(record, item)
        if path is None:
            raise ClaudeError("no_local_copy", 404, "the computer has no copy of this file")
        session_id = f"course-{course_id}"
        offer = request.app.state.offers.for_path(path, session_id, {"course_id": course_id})
        return {**offer.card(), "session_id": session_id}

    return routes


def router(api: MoodleApi | None = None) -> APIRouter:
    """The ``/moodle`` and ``/courses`` routes, behind the bridge's bearer-token check."""
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
        """Assignments with their dates; no description, attachments without address.

        The description (as Markdown) is served by ``/courses/{id}/assignments/{id}``.
        """
        return {
            "assignments": [
                {
                    **{name: value for name, value in item.items() if name != "intro"},
                    "attachments": [
                        {name: value for name, value in entry.items() if name != "fileurl"}
                        for entry in item.get("attachments") or []
                    ],
                }
                for item in api.assignments(course, refresh)
            ]
        }

    @routes.get("/deadlines")
    def moodle_deadlines(days: int = 30, refresh: bool = False) -> dict:
        """What is due in the next ``days`` days (1 to 365)."""
        return api.deadlines(max(1, min(days, MAX_DAYS)), refresh)

    both = APIRouter()
    both.include_router(routes)
    both.include_router(courses_router())
    return both
