"""The bridge's own records in the local audit log (``~/.config/lifeos/audit/``).

The gate program records every tool call of a capable run there; this
module adds what the bridge itself does on the owner's behalf: file offers
and downloads, course download addresses, every request that arrives from a
phone through the relay, pairing and revocation, allowed-folder changes and
Moodle sign-ins. ``docs/security.md`` lists what each record holds.

A record carries names, sizes, ids, route templates and status codes only:
never a file's content, a note's text, a request body, a download address
or a token. Records that are not about a Claude session have an empty
``session_id``, so the capable-mode checks that read the log per session
never see them.

This module depends on nothing but the standard library and the config
location, so command-line scripts can use it too.
"""

from __future__ import annotations

import contextlib
import json
import os
from datetime import UTC, datetime
from pathlib import Path

from .config import config_dir

DIR_NAME = "audit"


class AuditUnavailable(Exception):
    """The record could not be written. Whatever needed it must not happen."""


def directory() -> Path:
    return config_dir() / DIR_NAME


def write(entry: dict, folder: Path | None = None) -> None:
    """Append one JSON line to this month's file (mode 600, folder mode 700)."""
    folder = folder or directory()
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = folder / f"{entry['ts'][:7]}.jsonl"
    line = (json.dumps(entry, ensure_ascii=False) + "\n").encode()
    descriptor = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    try:
        os.write(descriptor, line)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def record(action: str, detail: dict, *, session_id: str = "") -> None:
    """Write one record of the bridge. Raises :class:`AuditUnavailable` when it cannot."""
    entry = {
        "ts": datetime.now(UTC).isoformat(timespec="milliseconds"),
        "session_id": session_id,
        "cwd": "",
        "tool": action,
        "detail": detail,
        "decision": "allowed",
    }
    try:
        write(entry)
    except OSError as exc:
        raise AuditUnavailable(action) from exc


def try_record(action: str, detail: dict, *, session_id: str = "") -> bool:
    """For actions that must happen anyway (a revocation, a relay answer): best effort."""
    with contextlib.suppress(AuditUnavailable):
        record(action, detail, session_id=session_id)
        return True
    return False
