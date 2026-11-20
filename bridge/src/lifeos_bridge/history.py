"""Read a session's earlier messages in small pages.

The source is Claude Code's own transcript, one JSON-lines file per session
(see :mod:`lifeos_bridge.sessions`). That format is internal and changes
between versions, so this reader is deliberately tolerant: it uses a handful
of fields, skips every line it does not understand and never raises on odd
content. The transcript is only ever opened for reading.

An *exchange* is one message of the owner plus the assistant's answer to
it: the text the assistant wrote until the owner's next message. Tool calls
are not replayed; they are counted per exchange by tool name only (no
inputs, no paths, no outputs). Thinking blocks, sub-agent (sidechain)
entries and entries injected by Claude Code itself are dropped.

Pages are read from the end of the file. One page looks at no more than
``MAX_WINDOW_BYTES`` before its end point; only when that much holds not a
single owner message does the reader look further back, up to
``HARD_WINDOW_BYTES``. Anything older than that is not reachable.

Privacy: the texts are conversation content. They are returned to the
authenticated client and never logged.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import sessions
from .claude import ClaudeError
from .sessions import SessionInfo

DEFAULT_LIMIT = 4
MAX_LIMIT = 20
MAX_TEXT_CHARS = 60_000
FIRST_WINDOW_BYTES = 1024 * 1024
MAX_WINDOW_BYTES = 16 * 1024 * 1024
HARD_WINDOW_BYTES = 128 * 1024 * 1024
MAX_TOOL_NAME = 64

_CURSOR = re.compile(r"p(\d{1,15})")
# Envelopes Claude Code writes into user entries: <system-reminder>, <command-name>, ...
_ENVELOPE = re.compile(r"<[a-z]+([-_][a-z]+)+[ >]")
_INTERRUPTED = "[Request interrupted by user"
_NOT_OWNER_FLAGS = ("isMeta", "isCompactSummary", "isVisibleInTranscriptOnly")


@dataclass
class Exchange:
    """One owner message and what the assistant answered; built while reading."""

    id: str
    offset: int
    at: str | None
    user: str
    parts: list[str] = field(default_factory=list)
    size: int = 0
    tools: dict[str, int] = field(default_factory=dict)
    complete: bool = False

    def add_text(self, text: str) -> None:
        if self.size <= MAX_TEXT_CHARS:  # keep one character more than the cap, at most
            self.parts.append(text)
            self.size += len(text) + 2

    def as_json(self) -> dict:
        user, user_cut = _cap(self.user)
        assistant, assistant_cut = _cap("\n\n".join(self.parts))
        return {
            "id": self.id,
            "at": self.at,
            "user": user,
            "assistant": assistant,
            "tools": [{"name": name, "count": count} for name, count in self.tools.items()],
            "complete": self.complete,
            "truncated": user_cut or assistant_cut,
        }


def _cap(text: str) -> tuple[str, bool]:
    return (text, False) if len(text) <= MAX_TEXT_CHARS else (text[:MAX_TEXT_CHARS], True)


def cursor_for(exchange: Exchange) -> str:
    """Opaque cursor of an exchange: where its owner message starts in the file."""
    return f"p{exchange.offset}"


def transcript_path(
    info: SessionInfo, roots: tuple[Path, ...], home: Path | None = None
) -> Path | None:
    """The transcript file of a session that ``sessions.find_session`` returned."""
    for directory in sessions._candidate_dirs(roots, home):
        path = directory / f"{info.id}.jsonl"
        found = sessions.read_session(path)
        if found is not None and found.folder == info.folder:
            return path
    return None


def _parse(raw: bytes) -> dict | None:
    try:
        value = json.loads(raw)
    except (ValueError, RecursionError):
        return None  # cut-off, foreign or absurd line
    return value if isinstance(value, dict) else None


def _blocks(entry: dict) -> list[dict]:
    """The content blocks of an entry's message; plain text becomes one text block."""
    message = entry.get("message")
    content = message.get("content") if isinstance(message, dict) else None
    if isinstance(content, str):
        return [{"type": "text", "text": content}]
    if isinstance(content, list):
        return [block for block in content if isinstance(block, dict)]
    return []


def _typed_text(text: object) -> str | None:
    """Text the owner typed, or ``None`` for empty text and Claude Code's own envelopes."""
    if not isinstance(text, str):
        return None
    text = text.strip()
    if not text or _ENVELOPE.match(text) or text.startswith(_INTERRUPTED):
        return None
    return text


def _has_tool_result(entry: dict) -> bool:
    return any(block.get("type") == "tool_result" for block in _blocks(entry))


def owner_text(entry: dict) -> str | None:
    """The owner's message in a ``user`` entry, or ``None`` when it is not one.

    Tool results, injected reminders, command wrappers and summaries are
    stored as ``user`` entries too; none of them starts an exchange.
    """
    if entry.get("type") != "user" or any(entry.get(flag) for flag in _NOT_OWNER_FLAGS):
        return None
    if _has_tool_result(entry):
        return None
    blocks = _blocks(entry)
    texts = [
        text
        for block in blocks
        if block.get("type") == "text" and (text := _typed_text(block.get("text")))
    ]
    return "\n\n".join(texts) or None


def _start(entry: dict, offset: int, text: str) -> Exchange:
    uuid, at = entry.get("uuid"), entry.get("timestamp")
    return Exchange(
        id=uuid if isinstance(uuid, str) and uuid else f"line-{offset}",
        offset=offset,
        at=at if isinstance(at, str) and at else None,
        user=text,
    )


def _add_assistant(exchange: Exchange, entry: dict) -> None:
    message = entry.get("message")
    stop = message.get("stop_reason") if isinstance(message, dict) else None
    for block in _blocks(entry):
        kind = block.get("type")
        if kind == "text" and isinstance(block.get("text"), str) and block["text"].strip():
            exchange.add_text(block["text"].strip())
            exchange.complete = stop != "tool_use"
        elif kind == "tool_use":
            name = block.get("name")
            name = name[:MAX_TOOL_NAME] if isinstance(name, str) and name else "unknown"
            exchange.tools[name] = exchange.tools.get(name, 0) + 1
            exchange.complete = False
        # thinking and unknown blocks are dropped


def build_exchanges(lines: list[tuple[int, bytes]]) -> list[Exchange]:
    """Group transcript lines (file offset, raw line) into exchanges, oldest first.

    Lines before the first owner message belong to an exchange that starts
    earlier in the file and are left out.
    """
    exchanges: list[Exchange] = []
    for offset, raw in lines:
        entry = _parse(raw)
        if entry is None or entry.get("isSidechain"):
            continue
        text = owner_text(entry)
        if text is not None:
            exchanges.append(_start(entry, offset, text))
        elif not exchanges:
            continue
        elif entry.get("type") == "assistant":
            _add_assistant(exchanges[-1], entry)
        elif entry.get("type") == "user" and _has_tool_result(entry):
            exchanges[-1].complete = False  # the answer goes on after a tool result
    return exchanges


def _read_lines(handle, start: int, end: int) -> list[tuple[int, bytes]]:
    """Whole lines that begin in ``[start, end)``, with their file offsets."""
    # Start one byte early: that shows whether ``start`` is the beginning of a line.
    position = max(0, start - 1)
    handle.seek(position)
    data = handle.read(end - position)
    lines: list[tuple[int, bytes]] = []
    index = 0
    if start > 0:
        newline = data.find(b"\n")
        if newline < 0:
            return []
        index = newline + 1
    while index < len(data):
        newline = data.find(b"\n", index)
        stop = len(data) if newline < 0 else newline
        if stop > index:
            lines.append((position + index, data[index:stop]))
        index = stop + 1
    return lines


def _cursor_offset(handle, before: str, size: int) -> int:
    """Validate a cursor: it must point at the start of an owner message line."""
    bad = ClaudeError("bad_request", 400, "unknown `before` cursor")
    match = _CURSOR.fullmatch(before)
    if match is None:
        raise bad
    offset = int(match.group(1))
    if offset >= size:
        raise bad
    if offset > 0:
        handle.seek(offset - 1)
        if handle.read(1) != b"\n":
            raise bad
    handle.seek(offset)
    entry = _parse(handle.readline())
    if entry is None or entry.get("isSidechain") or owner_text(entry) is None:
        raise bad
    return offset


def _collect(handle, end: int, wanted: int) -> tuple[list[Exchange], int]:
    """Exchanges that start before ``end``, reading backwards in growing windows.

    Returns them with the offset the reading started at (0 = start of file).
    """
    window = FIRST_WINDOW_BYTES
    while True:
        start = max(0, end - window)
        exchanges = build_exchanges(_read_lines(handle, start, end))
        enough = len(exchanges) >= wanted or (bool(exchanges) and window >= MAX_WINDOW_BYTES)
        if start == 0 or enough or window >= HARD_WINDOW_BYTES:
            return exchanges, start
        window = min(window * 4, HARD_WINDOW_BYTES)


def clamp_limit(limit: int) -> int:
    return max(1, min(limit, MAX_LIMIT))


def read_page(path: Path, limit: int = DEFAULT_LIMIT, before: str | None = None) -> dict:
    """One page of exchanges, oldest first, ending right before the ``before`` cursor.

    Without a cursor the page holds the latest exchanges. ``older`` is the
    cursor for the page before this one, or ``None`` when nothing earlier
    was found. An unreadable file gives an empty page.
    """
    limit = clamp_limit(limit)
    try:
        with path.open("rb") as handle:
            size = handle.seek(0, 2)
            end = size if before is None else _cursor_offset(handle, before, size)
            exchanges, start = _collect(handle, end, limit + 1)
    except OSError:
        exchanges, start = [], 0
    page = exchanges[-limit:]
    more = len(exchanges) > limit or start > 0
    return {
        "exchanges": [exchange.as_json() for exchange in page],
        "older": cursor_for(page[0]) if page and more else None,
        "total_exchanges_known": None,
    }
