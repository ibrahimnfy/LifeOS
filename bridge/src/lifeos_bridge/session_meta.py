"""The owner's own organisation of sessions: groups, titles, pins, reply notifications.

Everything lives in one file in the private config folder. Claude Code's own
session files are only ever read (``sessions.py``); nothing here writes them.
"""

from __future__ import annotations

import re
import threading
import uuid
from pathlib import Path

from . import store
from .claude import ClaudeError
from .config import config_dir
from .sessions import SessionInfo

FILE_NAME = "sessions-meta.json"
MAX_NAME = 60
MAX_TITLE = 120
MAX_GROUPS = 50
DEFAULTS = {
    "title": None,
    "group": None,
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
    groups = [
        g for g in data.get("groups", []) if isinstance(g, dict) and "id" in g and "name" in g
    ]
    entries = data.get("sessions", {})
    return {"groups": groups, "sessions": entries if isinstance(entries, dict) else {}}


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


def _unique(data: dict, name: str, skip: str | None = None) -> None:
    if any(g["name"].casefold() == name.casefold() and g["id"] != skip for g in data["groups"]):
        raise ClaudeError("group_exists", 409, "a group with this name already exists")


def _group(data: dict, group_id: str) -> dict:
    for group in data["groups"]:
        if group["id"] == group_id:
            return group
    raise ClaudeError("group_not_found", 404, "unknown group")


def create_group(name: object) -> dict:
    data = load()
    name = _clean(name, "name", MAX_NAME)
    _unique(data, name)
    if len(data["groups"]) >= MAX_GROUPS:
        raise bad(f"at most {MAX_GROUPS} groups")
    group = {"id": uuid.uuid4().hex[:12], "name": name}
    data["groups"].append(group)
    _save(data)
    return group


def rename_group(group_id: str, name: object) -> dict:
    data = load()
    group = _group(data, group_id)
    name = _clean(name, "name", MAX_NAME)
    _unique(data, name, skip=group_id)
    group["name"] = name
    _save(data)
    return group


def delete_group(group_id: str) -> None:
    """Remove a group; its sessions become ungrouped (they are never deleted)."""
    data = load()
    data["groups"].remove(_group(data, group_id))
    for entry in data["sessions"].values():
        if entry.get("group") == group_id:
            entry["group"] = None
    _save(data)


def update_session(session_id: str, changes: dict) -> dict:
    """Apply ``title``, ``group``, ``pinned`` and ``notify_replies``; ``None`` clears."""
    unknown = set(changes) - set(DEFAULTS)
    if unknown or not changes:
        raise bad("allowed fields: title, group, pinned, notify_replies, model, effort, skills")
    data = load()
    entry = {**DEFAULTS, **data["sessions"].get(session_id, {})}
    if "title" in changes:
        title = changes["title"]
        entry["title"] = None if title is None else _clean(title, "title", MAX_TITLE)
    if "group" in changes:
        group = changes["group"]
        if group is not None:
            if not isinstance(group, str):
                raise bad("`group` must be a group id or null")
            _group(data, group)
        entry["group"] = group
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
    entry = {**DEFAULTS, **data["sessions"].get(session_id, {})}
    if entry["group"] is not None and all(g["id"] != entry["group"] for g in data["groups"]):
        entry["group"] = None
    return entry


def describe(info: SessionInfo, *, in_use: bool, data: dict) -> dict:
    """A session as the phone shows it: the transcript's facts plus the owner's choices."""
    entry = entry_for(info.id, data)
    item = info.as_json(in_use=in_use)
    item["detected_title"] = item["title"]
    item["title"] = entry["title"] or item["title"]
    item["group"] = entry["group"]
    # Sessions without a group are shown grouped by their folder.
    item["auto_group"] = info.folder.name
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
