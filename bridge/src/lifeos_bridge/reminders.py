"""Per-session reminders: "remind me about this session at <time>".

Reminders are stored in the private config folder. A background task in the
bridge fires the due ones through ``scripts/notify.sh``; the notification
text never contains the note, the session title or a path. The note is only
returned through the authenticated API. Once phone push exists, the same
firing step also wakes the phone, which then opens the session.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import notifications, store
from .claude import ClaudeError
from .config import config_dir

FILE_NAME = "reminders.json"
REPEATS = {"none": None, "daily": timedelta(days=1), "weekly": timedelta(days=7)}
MAX_NOTE = 500
MAX_REMINDERS = 200
CHECK_INTERVAL_SECONDS = 30
PAST_TOLERANCE = timedelta(minutes=1)

Clock = Callable[[], datetime]


def now_utc() -> datetime:
    return datetime.now(UTC)


def reminders_path() -> Path:
    return config_dir() / FILE_NAME


def load() -> list[dict]:
    return [r for r in store.read_json(reminders_path(), []) if isinstance(r, dict) and "id" in r]


def _save(items: list[dict]) -> None:
    store.write_json(reminders_path(), items)


def bad(message: str) -> ClaudeError:
    return ClaudeError("bad_request", 400, message)


def _iso(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _read_time(value: object) -> datetime:
    if not isinstance(value, str):
        raise bad("`at` must be a time such as 2027-01-15T09:00:00+03:00")
    try:
        moment = _parse(value)
    except ValueError as exc:
        raise bad("`at` must be a time such as 2027-01-15T09:00:00+03:00") from exc
    if moment.tzinfo is None:
        raise bad("`at` must include a time-zone offset")
    return moment


def _read_fields(data: dict, *, partial: bool) -> dict:
    unknown = set(data) - {"session_id", "at", "note", "repeat", "timezone"}
    if unknown:
        raise bad("allowed fields: session_id, at, note, repeat, timezone")
    fields: dict = {}
    if "at" in data or not partial:
        fields["at"] = _read_time(data.get("at"))
    if "note" in data:
        note = data["note"]
        if note is not None and (not isinstance(note, str) or len(note) > MAX_NOTE):
            raise bad(f"`note` must be text of at most {MAX_NOTE} characters")
        fields["note"] = (note or "").strip() or None
    if "repeat" in data:
        if data["repeat"] not in REPEATS:
            raise bad("`repeat` must be none, daily or weekly")
        fields["repeat"] = data["repeat"]
    if "timezone" in data:
        zone = data["timezone"]
        if zone is not None:
            try:
                ZoneInfo(zone if isinstance(zone, str) else "")
            except (ZoneInfoNotFoundError, ValueError) as exc:
                raise bad("`timezone` must be a zone name such as Europe/Istanbul") from exc
        fields["timezone"] = zone
    return fields


def create(session_id: str, data: dict, clock: Clock = now_utc) -> dict:
    fields = _read_fields(data, partial=False)
    items = load()
    if len(items) >= MAX_REMINDERS:
        raise bad(f"at most {MAX_REMINDERS} reminders")
    repeat = fields.get("repeat", "none")
    if repeat == "none" and fields["at"] < clock() - PAST_TOLERANCE:
        raise bad("`at` is in the past")
    reminder = {
        "id": uuid.uuid4().hex[:12],
        "session_id": session_id,
        "at": _iso(fields["at"]),
        "note": fields.get("note"),
        "repeat": repeat,
        "timezone": fields.get("timezone"),
        "state": "scheduled",
        "last_fired": None,
    }
    items.append(reminder)
    _save(items)
    return reminder


def _find(items: list[dict], reminder_id: str) -> dict:
    for reminder in items:
        if reminder["id"] == reminder_id:
            return reminder
    raise ClaudeError("reminder_not_found", 404, "unknown reminder")


def update(reminder_id: str, data: dict) -> dict:
    """Change time, note, repeat or zone. A new time schedules the reminder again."""
    if "session_id" in data or not data:
        raise bad("allowed fields: at, note, repeat, timezone")
    fields = _read_fields(data, partial=True)
    items = load()
    reminder = _find(items, reminder_id)
    if "at" in fields:
        reminder["at"] = _iso(fields.pop("at"))
        reminder["state"] = "scheduled"
    reminder.update(fields)
    _save(items)
    return reminder


def delete(reminder_id: str) -> None:
    items = load()
    items.remove(_find(items, reminder_id))
    _save(items)


def listing(session_id: str | None = None, state: str | None = None) -> list[dict]:
    items = load()
    if session_id is not None:
        items = [r for r in items if r.get("session_id") == session_id]
    if state is not None:
        items = [r for r in items if r.get("state") == state]
    return sorted(items, key=lambda r: r.get("at", ""))


def _next_time(reminder: dict, now: datetime) -> datetime:
    """The first occurrence after ``now``; missed occurrences collapse into one."""
    step = REPEATS[reminder["repeat"]]
    moment = _parse(reminder["at"])
    zone = reminder.get("timezone")
    if zone:
        # Step in local wall-clock time so "09:00" stays 09:00 across clock changes.
        local = moment.astimezone(ZoneInfo(zone)).replace(tzinfo=None)
        while moment <= now:
            local += step
            moment = local.replace(tzinfo=ZoneInfo(zone)).astimezone(UTC)
        return moment
    while moment <= now:
        moment += step
    return moment


def fire_due(clock: Clock = now_utc, notify_script: Path | None = None) -> list[dict]:
    """Fire every scheduled reminder whose time has come, once. Returns those fired.

    The new state is stored *before* notifying, so a crash cannot make a
    reminder fire twice; reminders missed while the bridge was down fire at
    the next check.
    """
    now = clock()
    items = load()
    fired = []
    for reminder in items:
        if reminder.get("state") != "scheduled" or reminder.get("repeat") not in REPEATS:
            continue
        try:
            due = _parse(reminder["at"]) <= now
        except (KeyError, ValueError, TypeError):
            continue
        if not due:
            continue
        reminder["last_fired"] = _iso(now)
        if reminder["repeat"] == "none":
            reminder["state"] = "fired"
        else:
            reminder["at"] = _iso(_next_time(reminder, now))
        fired.append(dict(reminder))
    if fired:
        _save(items)
        for _ in fired:
            notifications.send(
                "LifeOS: session reminder",
                "A reminder for one of your sessions is due.",
                "",
                notify_script,
            )
    return fired
