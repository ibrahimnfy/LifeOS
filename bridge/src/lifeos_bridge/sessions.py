"""Find Claude Code sessions stored on this computer.

Claude Code keeps one JSON-lines transcript per session under
``~/.claude/projects/<encoded folder>/<session id>.jsonl``. The line format
is internal and changes between versions, so this reader is deliberately
tolerant: it looks only at the beginning and end of a file, uses a handful
of fields, and skips whatever it does not understand.

Privacy: only project folders that belong to an allowed root are opened at
all. Titles are prompt text; they are returned to the authenticated client
and never logged.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from .claude import is_session_id

HEAD_BYTES = 256 * 1024
TAIL_BYTES = 256 * 1024
TITLE_LENGTH = 80
_TITLE_KEYS = ("customTitle", "aiTitle", "summary")


@dataclass(frozen=True)
class SessionInfo:
    id: str
    title: str
    folder: Path
    last_activity: datetime

    def as_json(self, *, in_use: bool = False) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "folder": str(self.folder),
            "last_activity": self.last_activity.isoformat().replace("+00:00", "Z"),
            "in_use": in_use,
        }


def encode_folder(folder: Path) -> str:
    return re.sub(r"[^A-Za-z0-9]", "-", str(folder))


def projects_dir(home: Path | None = None) -> Path:
    return (home or Path.home()) / ".claude" / "projects"


def _is_inside(folder: Path, roots: tuple[Path, ...]) -> bool:
    # Resolve first: ".." parts or a symlink must not make an outside folder look inside.
    folder = folder.resolve()
    return any(folder == root or folder.is_relative_to(root) for root in roots)


def _candidate_dirs(roots: tuple[Path, ...], home: Path | None) -> list[Path]:
    """Project folders whose name could belong to an allowed root.

    The name encoding is lossy, so this is only a pre-filter; the folder
    recorded inside each transcript is checked afterwards.
    """
    base = projects_dir(home)
    if not base.is_dir():
        return []
    prefixes = [encode_folder(root) for root in roots]
    return sorted(
        entry
        for entry in base.iterdir()
        if entry.is_dir()
        and any(entry.name == prefix or entry.name.startswith(prefix + "-") for prefix in prefixes)
    )


def _json_lines(chunk: bytes) -> list[dict]:
    lines = []
    for raw in chunk.splitlines():
        try:
            value = json.loads(raw)
        except ValueError:
            continue  # cut-off or foreign line
        if isinstance(value, dict):
            lines.append(value)
    return lines


def _prompt_text(line: dict) -> str | None:
    if line.get("type") != "user" or line.get("isSidechain") or line.get("isMeta"):
        return None
    message = line.get("message")
    content = message.get("content") if isinstance(message, dict) else None
    if isinstance(content, list):
        texts = [b.get("text") for b in content if isinstance(b, dict) and b.get("type") == "text"]
        content = next((t for t in texts if isinstance(t, str)), None)
    if not isinstance(content, str):
        return None
    text = " ".join(content.split())
    # Lines starting with "<" are tool or system envelopes, not what the owner typed.
    return text if text and not text.startswith("<") else None


def _shorten(text: str) -> str:
    text = " ".join(text.split())
    return text if len(text) <= TITLE_LENGTH else text[: TITLE_LENGTH - 1].rstrip() + "…"


def read_session(path: Path) -> SessionInfo | None:
    """Read one transcript; ``None`` when it is unreadable or has no folder."""
    if not is_session_id(path.stem):
        return None
    try:
        stat = path.stat()
        with path.open("rb") as handle:
            head = handle.read(HEAD_BYTES)
            tail = b""
            if stat.st_size > HEAD_BYTES:
                handle.seek(max(HEAD_BYTES, stat.st_size - TAIL_BYTES))
                tail = handle.read()
    except OSError:
        return None
    head_lines, tail_lines = _json_lines(head), _json_lines(tail)

    folder = next(
        (line["cwd"] for line in head_lines if isinstance(line.get("cwd"), str) and line["cwd"]),
        None,
    )
    if folder is None or not Path(folder).is_absolute():
        return None

    title = None
    for line in reversed(head_lines + tail_lines):  # the latest title wins
        title = next(
            (line[key] for key in _TITLE_KEYS if isinstance(line.get(key), str) and line[key]),
            None,
        )
        if title:
            break
    if not title:
        title = next((text for line in head_lines if (text := _prompt_text(line))), None)

    return SessionInfo(
        id=path.stem,
        title=_shorten(title) if title else "(untitled)",
        folder=Path(folder),
        last_activity=datetime.fromtimestamp(stat.st_mtime, tz=UTC),
    )


def list_sessions(roots: tuple[Path, ...], home: Path | None = None) -> list[SessionInfo]:
    """All sessions whose folder is inside an allowed root, newest first."""
    sessions = []
    for directory in _candidate_dirs(roots, home):
        try:
            files = list(directory.glob("*.jsonl"))
        except OSError:
            continue
        for path in files:
            info = read_session(path)
            if info is not None and _is_inside(info.folder, roots):
                sessions.append(info)
    return sorted(sessions, key=lambda s: s.last_activity, reverse=True)


def find_session(
    session_id: str, roots: tuple[Path, ...], home: Path | None = None
) -> SessionInfo | None:
    if not is_session_id(session_id):
        return None
    for directory in _candidate_dirs(roots, home):
        info = read_session(directory / f"{session_id}.jsonl")
        if info is not None and _is_inside(info.folder, roots):
            return info
    return None
