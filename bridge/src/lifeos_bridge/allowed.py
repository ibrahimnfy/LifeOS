"""Add and remove allowed folders (``~/.config/lifeos/allowed_paths``).

Every change is checked against the fixed limits in ``paths.py``, written to
a local change log, and announced with ``scripts/notify.sh``. The
notification never contains the path.
"""

from __future__ import annotations

import json
import os
from datetime import UTC, datetime
from pathlib import Path

from . import audit, notifications
from .claude import ClaudeError
from .config import read_allowed_paths
from .paths import refusal_reason

CHANGE_LOG_NAME = "allowed_paths.log"
HEADER = (
    "# LifeOS: folders Claude may work in, besides the memory folder.\n"
    "# One absolute path per line. Lines starting with # are comments.\n"
)


def listing(file: Path) -> list[dict]:
    """Configured paths with whether each is usable, and why not."""
    items = []
    for path in read_allowed_paths(file):
        reason = refusal_reason(path)
        items.append({"path": str(path), "active": reason is None, "reason": reason})
    return items


def _write(file: Path, text: str) -> None:
    file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = file.with_name(file.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(file)


def _record(file: Path, action: str, path: Path, by: str = "") -> None:
    """The change log next to the list, and the audit log. Raises when either fails."""
    detail = {"path": str(path), **({"by": by} if by else {})}
    try:
        audit.record(f"AllowedPath{action.capitalize()}", detail)
    except audit.AuditUnavailable as exc:
        raise ClaudeError(
            "audit_unavailable", 503, "the audit log cannot be written; nothing was changed"
        ) from exc
    entry = {"ts": datetime.now(UTC).isoformat(timespec="seconds"), "action": action,
             "path": str(path)}  # fmt: skip
    log = file.with_name(CHANGE_LOG_NAME)
    descriptor = os.open(log, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, ensure_ascii=False) + "\n")


def notify(action: str, count: int, script: Path | None = None, name: str = "") -> None:
    """Tell the owner on the computer and the paired phones. Never fails the request.

    The computer's text is fixed and never names the folder. The phones get a
    sealed notice whose title names the folder (only its name, never the path);
    the app shows it after it was opened and unlocked, on the Chat tab (#179).
    One push only: with ``event`` the script does not wake the phones a second time.
    """
    message = f"A folder was {action}; {count} allowed now. If this was not you, check the bridge."
    title = f"Folder {action}: {name}" if name else f"A folder was {action}"
    notifications.send(
        "LifeOS: allowed folders changed", message, "", script,
        event="notice", opens="session", sealed_title=title,
    )  # fmt: skip


def course_refusal(path: Path) -> str | None:
    """Why ``path`` may not be allowed because of the owner's course folders (#179)."""
    from . import course_folders  # local: it reaches the course sync's records

    try:
        inside = course_folders.containing(None, path)
    except OSError:
        return None
    if inside is None:
        return None
    return (
        "this is one of the owner's read-only course folders (or inside one); it is read "
        "in that course's chats and never becomes a working folder"
    )


def _resolved_entries(file: Path) -> list[Path]:
    return [p.expanduser().resolve() for p in read_allowed_paths(file)]


def add(file: Path, raw: str, notify_script: Path | None = None, *, by: str = "") -> bool:
    """Add a folder (one line, written atomically). Returns False when it was already listed.

    ``by``: who asked, for the audit record (``chat``: a run's marker, #179).
    """
    reason = refusal_reason(Path(raw)) or course_refusal(Path(raw))
    if reason is not None:
        raise ClaudeError("path_not_allowed", 400, reason)
    resolved = Path(raw).expanduser().resolve()
    if resolved in _resolved_entries(file):
        return False
    # Record first: a change that cannot be logged is not made.
    file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    _record(file, "added", resolved, by)
    current = file.read_text(encoding="utf-8") if file.exists() else HEADER
    if current and not current.endswith("\n"):
        current += "\n"
    _write(file, current + f"{resolved}\n")
    notify("added", len(_resolved_entries(file)), notify_script, resolved.name)
    return True


def remove(file: Path, raw: str, notify_script: Path | None = None, *, by: str = "") -> None:
    """Remove one folder's line from the list; nothing else changes, no file is touched."""
    target = Path(raw).expanduser()
    target = target.resolve() if target.is_absolute() else target
    lines = file.read_text(encoding="utf-8").splitlines() if file.exists() else []
    kept, removed = [], False
    for line in lines:
        stripped = line.strip()
        is_entry = bool(stripped) and not stripped.startswith("#")
        if is_entry and Path(stripped).expanduser().resolve() == target:
            removed = True
            continue
        kept.append(line)
    if not removed:
        raise ClaudeError("path_not_listed", 404, "this folder is not in the list")
    _record(file, "removed", target, by)
    _write(file, "\n".join(kept) + "\n")
    notify("removed", len(_resolved_entries(file)), notify_script, target.name)
