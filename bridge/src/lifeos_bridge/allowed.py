"""Add and remove allowed folders (``~/.config/lifeos/allowed_paths``).

Every change is checked against the fixed limits in ``paths.py``, written to
a local change log, and announced with ``scripts/notify.sh``. The
notification never contains the path.
"""

from __future__ import annotations

import contextlib
import json
import os
import subprocess
from datetime import UTC, datetime
from pathlib import Path

from .claude import ClaudeError
from .config import read_allowed_paths
from .paths import refusal_reason, repo_dir

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


def _record(file: Path, action: str, path: Path) -> None:
    entry = {"ts": datetime.now(UTC).isoformat(timespec="seconds"), "action": action,
             "path": str(path)}  # fmt: skip
    log = file.with_name(CHANGE_LOG_NAME)
    descriptor = os.open(log, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, ensure_ascii=False) + "\n")


def notify(action: str, count: int, script: Path | None = None) -> None:
    """Tell the owner on the computer. Never fails the request; never names the path."""
    if script is None:
        repo = repo_dir()
        script = repo / "scripts" / "notify.sh" if repo else None
    if script is None or not script.is_file():
        return
    message = f"A folder was {action}; {count} allowed now. If this was not you, check the bridge."
    with contextlib.suppress(OSError, subprocess.SubprocessError):
        subprocess.run(  # noqa: S603
            [str(script), "LifeOS: allowed folders changed", message, ""],
            capture_output=True, timeout=10, check=False,
        )  # fmt: skip


def _resolved_entries(file: Path) -> list[Path]:
    return [p.expanduser().resolve() for p in read_allowed_paths(file)]


def add(file: Path, raw: str, notify_script: Path | None = None) -> bool:
    """Add a folder. Returns False when it was already listed."""
    reason = refusal_reason(Path(raw))
    if reason is not None:
        raise ClaudeError("path_not_allowed", 400, reason)
    resolved = Path(raw).expanduser().resolve()
    if resolved in _resolved_entries(file):
        return False
    # Record first: a change that cannot be logged is not made.
    file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    _record(file, "added", resolved)
    current = file.read_text(encoding="utf-8") if file.exists() else HEADER
    if current and not current.endswith("\n"):
        current += "\n"
    _write(file, current + f"{resolved}\n")
    notify("added", len(_resolved_entries(file)), notify_script)
    return True


def remove(file: Path, raw: str, notify_script: Path | None = None) -> None:
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
    _record(file, "removed", target)
    _write(file, "\n".join(kept) + "\n")
    notify("removed", len(_resolved_entries(file)), notify_script)
