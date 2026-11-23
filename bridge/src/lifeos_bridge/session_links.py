"""Which chat a new chat was started from (#187), so both can say so in their history.

A switch that starts a new chat can carry the owner's request: the app sends it
as the new chat's first message, with ``continued_from`` naming the chat the
switch came from. The bridge then keeps one small record: the old chat, the
exchange of the old chat whose answer asked for the switch (its last one at
that moment), and the new chat. **Ids only, never a message or a folder.**

The old chat's history shows "continued in …" under that answer, the new
chat's history "started from …"; both are described afresh from the bridge's
own lists, so a chat that is gone (or out of reach) is simply not named.
"""

from __future__ import annotations

import threading
from datetime import UTC, datetime

from . import store
from .claude import is_session_id
from .config import config_dir

FILE_NAME = "session-links.json"
MAX_LINKS = 500
_lock = threading.Lock()


def _links() -> list[dict]:
    data = store.read_json(config_dir() / FILE_NAME, {})
    links = data.get("links") if isinstance(data, dict) else None
    if not isinstance(links, list):
        return []
    return [
        link
        for link in links
        if isinstance(link, dict)
        and all(isinstance(link.get(key), str) for key in ("from", "to", "exchange"))
    ]


def record(from_id: str, exchange_id: str, to_id: str) -> None:
    """The chat ``to_id`` was started from the answer ``exchange_id`` of ``from_id``."""
    if not (is_session_id(from_id) and is_session_id(to_id)) or from_id == to_id:
        return
    if not isinstance(exchange_id, str) or not exchange_id:
        return  # an answer the history cannot name
    with _lock:
        links = [link for link in _links() if link["to"] != to_id]  # a repeated start: once
        stamp = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        links.append({"from": from_id, "exchange": exchange_id, "to": to_id, "at": stamp})
        store.write_json(config_dir() / FILE_NAME, {"links": links[-MAX_LINKS:]})


def continued_in(session_id: str) -> dict[str, list[str]]:
    """Exchange id of ``session_id`` → the chats started from that answer, oldest first."""
    found: dict[str, list[str]] = {}
    for link in _links():
        if link["from"] == session_id:
            found.setdefault(link["exchange"], []).append(link["to"])
    return found


def continued_from(session_id: str) -> str | None:
    """The chat ``session_id`` was started from, if any."""
    for link in reversed(_links()):
        if link["to"] == session_id:
            return link["from"]
    return None
