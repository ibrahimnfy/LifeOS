"""Control of running sessions: stop a run, and queue messages for a busy session.

One run per session is the rule everywhere. A message for a session that is
answering is stored in that session's queue and sent when the run has ended;
"send now" and cancel interrupt the run first. Interrupting never undoes
what a run already did to files.

Queued messages are prompt text: they are kept in the private config folder
(mode 600) so they survive a restart, and they are never logged. After a
restart they wait as drafts; nothing is sent automatically.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Protocol

from . import claude, store
from .claude import ClaudeError
from .config import config_dir

GRACE = 5.0  # seconds between interrupt, terminate and kill
MAX_QUEUED = 10
QUEUE_FILE = "queue.json"
STOP_NOTE = "The run was stopped. Changes it had already made to files are kept."


class Stoppable(Protocol):
    @property
    def running(self) -> bool: ...

    async def stop(self, grace: float) -> None: ...


def stopped_error() -> ClaudeError:
    return ClaudeError("cancelled", 409, STOP_NOTE)


class RunControl:
    def __init__(self) -> None:
        self._processes: dict[str, asyncio.subprocess.Process] = {}
        self._stopped: set[str] = set()
        self.outcomes: dict[str, dict] = {}
        loaded = store.read_json(self._path(), {})
        self.queues: dict[str, list[dict]] = {
            sid: [e for e in entries if isinstance(e, dict) and "id" in e and "prompt" in e]
            for sid, entries in loaded.items()
            if isinstance(entries, list)
        }
        self.queues = {sid: entries for sid, entries in self.queues.items() if entries}
        # Why a session's queue is on hold: "cancel" (ends with the owner's next message),
        # "error" or "restart" (end only when the owner resumes the queue).
        self.paused: dict[str, str] = dict.fromkeys(self.queues, "restart")

    @staticmethod
    def _path() -> Path:
        return config_dir() / QUEUE_FILE

    def _save(self) -> None:
        store.write_json(self._path(), {sid: q for sid, q in self.queues.items() if q})

    # -- running processes ----------------------------------------------------
    def tracker(self, session_id: str):
        """A callback for the runner: remembers what runs the session's turn.

        That is the ``claude -p`` process, or the live SDK session
        (``sdk_runner.LiveSession``), which stops a turn with the SDK's interrupt.
        """

        def remember(handle: asyncio.subprocess.Process | Stoppable) -> None:
            self._processes[session_id] = handle

        return remember

    def finished(self, session_id: str) -> None:
        self._processes.pop(session_id, None)

    async def stop(self, session_id: str) -> bool:
        """Interrupt the session's run; terminate and kill only if it does not stop."""
        handle = self._processes.get(session_id)
        if handle is None:
            return False
        if isinstance(handle, asyncio.subprocess.Process):
            if handle.returncode is not None:
                return False
            self._stopped.add(session_id)
            await claude._stop(handle, GRACE)  # interrupt, then terminate, then kill
            return True
        if not handle.running:
            return False
        self._stopped.add(session_id)
        await handle.stop(GRACE)  # the SDK's interrupt; the process is closed only if needed
        return True

    def take_stopped(self, session_id: str) -> bool:
        """True once after a run of this session was stopped on request."""
        if session_id in self._stopped:
            self._stopped.discard(session_id)
            return True
        return False

    def pause(self, session_id: str, reason: str) -> None:
        self.paused[session_id] = reason

    def unpause(self, session_id: str, only: str | None = None) -> None:
        if only is None or self.paused.get(session_id) == only:
            self.paused.pop(session_id, None)

    # -- the queue ------------------------------------------------------------
    def queue(self, session_id: str) -> list[dict]:
        return self.queues.get(session_id, [])

    def add(self, session_id: str, prompt: str, mode: str, cwd: Path, *, front: bool) -> dict:
        entries = self.queues.setdefault(session_id, [])
        if len(entries) >= MAX_QUEUED:
            raise ClaudeError("queue_full", 429, f"at most {MAX_QUEUED} messages can wait")
        entry = {
            "id": uuid.uuid4().hex[:12],
            "prompt": prompt,
            "mode": mode,
            "cwd": str(cwd),
            "queued_at": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        }
        entries.insert(0, entry) if front else entries.append(entry)
        self._save()
        return entry

    def _find(self, session_id: str, queue_id: str) -> dict:
        for entry in self.queue(session_id):
            if entry["id"] == queue_id:
                return entry
        raise ClaudeError("not_queued", 404, "this message is not waiting (it may have been sent)")

    def edit(self, session_id: str, queue_id: str, prompt: str) -> dict:
        entry = self._find(session_id, queue_id)
        entry["prompt"] = prompt
        self._save()
        return entry

    def remove(self, session_id: str, queue_id: str) -> None:
        self.queues[session_id].remove(self._find(session_id, queue_id))
        self._save()

    def pop_next(self, session_id: str) -> dict | None:
        entries = self.queue(session_id)
        if not entries or session_id in self.paused:
            return None
        entry = entries.pop(0)
        self._save()
        return entry

    @staticmethod
    def public(entry: dict) -> dict:
        return {key: entry[key] for key in ("id", "prompt", "mode", "queued_at")}
