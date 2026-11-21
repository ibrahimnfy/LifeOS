"""Approvals that wait for the owner outside a run: skill installs (#92), MCP servers (#108).

They appear in the same list as the questions and approvals of a running
turn (``GET /sessions/{id}/interactions``) and are answered the same way
(``POST /sessions/{id}/interactions/{iid}`` with ``{"approve": true|false}``),
so the phone has one approval card for all of them. Unlike those, they wait
across runs and bridge restarts: they are kept in a small file in the
private settings folder (mode 600) until they are answered or expire. No
answer means no.
"""

from __future__ import annotations

import time
import uuid
from datetime import UTC, datetime

from . import store
from .claude import ClaudeError
from .config import config_dir

FILE_NAME = "pending-approvals.json"
TTL_SECONDS = 24 * 3600


def _path():
    return config_dir() / FILE_NAME


def _stamp(epoch: float) -> str:
    return datetime.fromtimestamp(epoch, UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def load() -> dict[str, dict]:
    data = store.read_json(_path(), {})
    return {k: v for k, v in data.items() if isinstance(v, dict)} if isinstance(data, dict) else {}


def _save(data: dict) -> None:
    store.write_json(_path(), data)


def add(kind: str, session_id: str, folder: str, data: dict, ttl: float = TTL_SECONDS) -> dict:
    now = time.time()
    entry = {
        "id": "a" + uuid.uuid4().hex[:11],
        "kind": kind,
        "session_id": session_id,
        "folder": folder,
        **data,
        "created_at": _stamp(now),
        "expires_at": _stamp(now + ttl),
        "expires": now + ttl,
    }
    items = load()
    items[entry["id"]] = entry
    _save(items)
    return entry


def update(entry_id: str, changes: dict) -> dict | None:
    items = load()
    if entry_id not in items:
        return None
    items[entry_id] |= changes
    _save(items)
    return items[entry_id]


def public(entry: dict) -> dict:
    return {k: v for k, v in entry.items() if k not in ("expires", "quarantine", "definition_hash")}


def take_expired(now: float | None = None) -> list[dict]:
    """Remove and return the entries whose time is up."""
    now = time.time() if now is None else now
    items = load()
    expired = [e for e in items.values() if e.get("expires", 0) <= now]
    if expired:
        _save({k: v for k, v in items.items() if v.get("expires", 0) > now})
    return expired


def pending(session_id: str) -> list[dict]:
    return [public(e) for e in load().values() if e.get("session_id") == session_id]


def get(session_id: str, entry_id: str) -> dict | None:
    entry = load().get(entry_id)
    return entry if entry is not None and entry.get("session_id") == session_id else None


def take(session_id: str, entry_id: str) -> dict:
    """Remove and return one entry of the session, or 404."""
    items = load()
    entry = items.get(entry_id)
    if entry is None or entry.get("session_id") != session_id:
        raise ClaudeError("not_pending", 404, "this approval is no longer waiting")
    del items[entry_id]
    _save(items)
    return entry


def read_approve(body: dict) -> bool:
    if not isinstance(body.get("approve"), bool):
        raise ClaudeError("bad_request", 400, "`approve` must be true or false")
    return body["approve"]
