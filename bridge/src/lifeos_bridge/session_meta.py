"""The owner's own organisation of sessions: titles, pins, reply notifications, choices.

Everything lives in one file in the private config folder. Claude Code's own
session files are only ever read (``sessions.py``); nothing here writes them.

Custom session groups were removed (#164): the app groups sessions by their
folder (``folders.py``). A file written before that still loads: its
``groups`` list and the ``group`` of an entry are ignored, and dropped the
next time the file is written.
"""

from __future__ import annotations

import re
import threading
from pathlib import Path

from . import store
from .claude import ClaudeError
from .config import config_dir
from .sessions import SessionInfo

FILE_NAME = "sessions-meta.json"
MAX_TITLE = 120
DEFAULTS = {
    "title": None,
    "pinned": False,
    "notify_replies": True,
    "model": None,
    "effort": None,
    # Capable runs may use the skills and commands of the session's folder (#91).
    "skills": False,
}
CHOICE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._\[\]-]{0,63}")


def meta_path() -> Path:
    return config_dir() / FILE_NAME


def load() -> dict:
    data = store.read_json(meta_path(), {})
    entries = data.get("sessions", {})
    entries = entries if isinstance(entries, dict) else {}
    # Only the fields that still exist: an old entry's ``group`` is left behind here.
    return {
        "sessions": {
            session_id: {key: value for key, value in entry.items() if key in DEFAULTS}
            for session_id, entry in entries.items()
            if isinstance(entry, dict)
        }
    }


def _entry(stored: dict | None) -> dict:
    return {**DEFAULTS, **(stored or {})}


def _save(data: dict) -> None:
    store.write_json(meta_path(), data)


def bad(message: str) -> ClaudeError:
    return ClaudeError("bad_request", 400, message)


def _clean(value: object, field: str, limit: int) -> str:
    if not isinstance(value, str) or not value.strip() or "\n" in value:
        raise bad(f"`{field}` must be a non-empty single line of text")
    value = value.strip()
    if len(value) > limit:
        raise bad(f"`{field}` is longer than {limit} characters")
    return value


def update_session(session_id: str, changes: dict) -> dict:
    """Apply ``title``, ``pinned``, ``notify_replies`` and the choices; ``None`` clears."""
    unknown = set(changes) - set(DEFAULTS)
    if unknown or not changes:
        raise bad("allowed fields: title, pinned, notify_replies, model, effort, skills")
    data = load()
    entry = _entry(data["sessions"].get(session_id))
    if "title" in changes:
        title = changes["title"]
        entry["title"] = None if title is None else _clean(title, "title", MAX_TITLE)
    for choice in ("model", "effort"):
        if choice in changes:
            value = changes[choice]
            if value is not None and (not isinstance(value, str) or not CHOICE.fullmatch(value)):
                raise bad(f"`{choice}` must be a name or null")
            entry[choice] = value
    for flag in ("pinned", "notify_replies", "skills"):
        if flag in changes:
            if not isinstance(changes[flag], bool):
                raise bad(f"`{flag}` must be true or false")
            entry[flag] = changes[flag]
    if entry == DEFAULTS:
        data["sessions"].pop(session_id, None)
    else:
        data["sessions"][session_id] = entry
    _save(data)
    return entry


def entry_for(session_id: str, data: dict | None = None) -> dict:
    data = load() if data is None else data
    return _entry(data["sessions"].get(session_id))


def describe(info: SessionInfo, *, in_use: bool, data: dict) -> dict:
    """A session as the phone shows it: the transcript's facts plus the owner's choices."""
    entry = entry_for(info.id, data)
    item = info.as_json(in_use=in_use)
    item["detected_title"] = item["title"]
    item["title"] = entry["title"] or item["title"]
    item["pinned"] = entry["pinned"]
    item["notify_replies"] = entry["notify_replies"]
    item["model"] = entry["model"]
    item["effort"] = entry["effort"]
    item["skills"] = entry["skills"]
    return item


# --------------------------------------------------------------------------- phone-chosen ids

NEW_SESSIONS_FILE = "new-sessions.json"
MAX_NEW_SESSIONS = 200
_NEW = threading.Lock()


def claim_new_session(session_id: str, fingerprint: str) -> str:
    """A new conversation named by the phone: ``new``, ``repeat`` or ``conflict``.

    The phone chooses the id of a new conversation, so a first message whose
    answer was lost can be sent again: the same id with the same first message
    and folder is a ``repeat`` and is not run twice; the same id with anything
    else is a ``conflict``. Ids and a fingerprint only, never the message.
    """
    path = config_dir() / NEW_SESSIONS_FILE
    with _NEW:
        data = store.read_json(path, {})
        known = data.get("ids") if isinstance(data.get("ids"), dict) else {}
        if session_id in known:
            return "repeat" if known[session_id] == fingerprint else "conflict"
        known[session_id] = fingerprint
        store.write_json(path, {"ids": dict(list(known.items())[-MAX_NEW_SESSIONS:])})
    return "new"


def release_new_session(session_id: str, fingerprint: str) -> None:
    """Forget a claim whose first message was refused before its run was admitted, so
    sending it again is a new start, not a ``repeat`` with nothing running."""
    path = config_dir() / NEW_SESSIONS_FILE
    with _NEW:
        data = store.read_json(path, {})
        known = data.get("ids") if isinstance(data.get("ids"), dict) else {}
        if known.get(session_id) == fingerprint:
            del known[session_id]
            store.write_json(path, {"ids": known})
