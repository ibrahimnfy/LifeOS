"""Which folder a session belongs to, for the app's session list and its folder tiles (#163).

Every session gets a ``folder_key``, a ``folder_label``, a ``kind`` and a
``colour``; the list also carries one summary entry per folder.

| kind | what | key |
|---|---|---|
| ``chat`` | every blank chat (all of them form one entry) | ``chats`` |
| ``coach`` | the coach conversation and its earlier sessions | ``coach`` |
| ``memory`` | other sessions in the memory folder | the folder's path |
| ``course`` | sessions in the course folder of the Moodle sync | the folder's path |
| ``project`` | sessions in any other allowed folder | the folder's path |

A session in a sub-folder belongs to the root that contains it.

**Colours** are small numbers, kept in ``folder-colours.json`` in the private
settings folder. A folder keeps the number it was first given for as long as
it exists; a folder that is gone frees its number; a new folder takes the
lowest free one. The app maps a number to a colour of its palette.
"""

from __future__ import annotations

import threading
from pathlib import Path

from . import chats, coach_chat, moodle_sync, store
from .config import Settings, config_dir
from .sessions import SessionInfo

COLOURS_FILE = "folder-colours.json"
CHATS_KEY, COACH_KEY = "chats", "coach"
COACH_LABEL = "Coach"
_LOCK = threading.Lock()


def coach_ids() -> set[str]:
    """The coach session and its earlier ones (never creates one)."""
    data = store.read_json(coach_chat._path(), {})
    ids = [data.get("session_id"), *(data.get("earlier") or [])]
    return {value for value in ids if isinstance(value, str)}


def catalogue(settings: Settings, *, coach: bool = False) -> list[dict]:
    """Every folder a session can belong to: ``key``, ``name``, ``kind``, ``path``."""
    memory = settings.ask_dir.resolve()
    course_root = moodle_sync.download_dir()
    course = course_root.expanduser().resolve() if course_root is not None else None
    entries = [
        {"key": CHATS_KEY, "name": chats.LABEL, "kind": "chat",
         "path": str(chats.root(settings))}
    ]  # fmt: skip
    if coach:
        entries.append({"key": COACH_KEY, "name": COACH_LABEL, "kind": "coach",
                        "path": str(memory)})  # fmt: skip
    for root in settings.allowed_roots:
        kind = "memory" if root == memory else "course" if root == course else "project"
        entries.append({"key": str(root), "name": root.name, "kind": kind, "path": str(root)})
    return entries


def colours(keys: list[str]) -> dict[str, int]:
    """The colour number of each key, stored: kept while the key exists, else the lowest free."""
    path = config_dir() / COLOURS_FILE
    with _LOCK:
        stored = store.read_json(path, {})
        kept = {
            key: value
            for key, value in stored.items()
            if key in keys and isinstance(value, int) and not isinstance(value, bool) and value >= 0
        }
        for key in keys:
            if key not in kept:
                taken = set(kept.values())
                kept[key] = next(number for number in range(len(taken) + 1) if number not in taken)
        if kept != stored:
            store.write_json(path, kept)
    return kept


class Folders:
    """The folders as they are now, to look sessions up in (one per request)."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.coach = coach_ids()
        self.entries = catalogue(settings, coach=bool(self.coach))
        numbers = colours([entry["key"] for entry in self.entries])
        for entry in self.entries:
            entry["colour"] = numbers[entry["key"]]
        self.by_key = {entry["key"]: entry for entry in self.entries}
        # Longest path first, so a root inside another root wins for its own sessions.
        self.roots = sorted(
            ((Path(entry["path"]), entry) for entry in self.entries
             if entry["key"] not in (CHATS_KEY, COACH_KEY)),
            key=lambda pair: len(pair[0].parts), reverse=True,
        )  # fmt: skip

    def of(self, folder: Path, session_id: str | None = None) -> dict | None:
        """The entry a session in ``folder`` belongs to (``None``: none of the known ones)."""
        if session_id is not None and session_id in self.coach:
            return self.by_key[COACH_KEY]
        if chats.in_chat_root(self.settings, folder):
            return self.by_key[CHATS_KEY]
        resolved = folder.resolve()
        for root, entry in self.roots:
            if resolved == root or resolved.is_relative_to(root):
                return entry
        return None

    def describe(self, info: SessionInfo) -> dict:
        """The folder fields of one session of the list."""
        entry = self.of(info.folder, info.id)
        if entry is None:
            return {"folder_key": None, "folder_label": info.folder.name, "kind": "project",
                    "colour": None}  # fmt: skip
        return {"folder_key": entry["key"], "folder_label": entry["name"], "kind": entry["kind"],
                "colour": entry["colour"]}  # fmt: skip

    def summary(self, found: list[SessionInfo]) -> list[dict]:
        """One entry per folder for the app's tiles: count and last activity of its sessions."""
        counts: dict[str, int] = {}
        latest: dict[str, str] = {}
        for info in found:
            entry = self.of(info.folder, info.id)
            if entry is None:
                continue
            key = entry["key"]
            counts[key] = counts.get(key, 0) + 1
            stamp = info.last_activity.isoformat().replace("+00:00", "Z")
            latest[key] = max(latest.get(key, ""), stamp)
        return [
            {**entry, "count": counts.get(entry["key"], 0),
             "last_activity": latest.get(entry["key"])}
            for entry in self.entries
        ]  # fmt: skip
