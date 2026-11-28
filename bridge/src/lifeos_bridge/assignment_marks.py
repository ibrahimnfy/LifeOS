"""Course assignments the owner marks by hand (#196): done, or removed from the lists.

Work handed in on paper never shows as submitted on the site, so every
assignment (from the site or not) can be marked in the app:

- ``done``: handed in or finished; its task is completed and its deadline no
  longer appears among the coming ones.
- ``removed``: not to be shown at all; its task moves to the trash.

Marks live in the memory folder, ``courses/assignment-marks.md``, one line per
assignment, committed with the task change they caused. Clearing a mark (undo)
reverses that change: a task the mark completed is opened again, one it moved
to the trash comes back. A task the owner had finished or deleted before the
mark is never touched.
"""

from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path

from . import memory
from .claude import ClaudeError

FILE = "courses/assignment-marks.md"
HEADER = (
    "# Assignment marks\n\n"
    "Course assignments the owner marked by hand in the app: `done` (handed in, for "
    "example on paper) or `removed` (not shown). Written by the bridge.\n\n"
)
MARKS = ("done", "removed")
LINE = re.compile(r"^- moodle-(\d+)\s+(.*)$")


def read(brain: Path) -> dict[int, dict]:
    """The marks by assignment id: ``{"mark", "on", "task"?, "closed"?}``."""
    found: dict[int, dict] = {}
    for line in memory.read_text(brain / FILE).splitlines():
        match = LINE.match(line.strip())
        if not match:
            continue
        fields = {k: v.strip() for k, v in memory.FIELD.findall(match.group(2))}
        if fields.get("mark") in MARKS:
            found[int(match.group(1))] = fields
    return found


def safe_read(brain: Path | None) -> dict[int, dict]:
    """:func:`read` for the views: no marks when the memory folder cannot be read."""
    if brain is None:
        return {}
    try:
        return read(brain)
    except (OSError, ValueError):
        return {}


def _write(brain: Path, marks: dict[int, dict]) -> None:
    lines = [
        f"- moodle-{key} " + " ".join(f"({k}: {v})" for k, v in fields.items() if v)
        for key, fields in sorted(marks.items())
    ]
    memory.write_text(brain / FILE, HEADER + "\n".join(lines) + ("\n" if lines else ""))


def _task_of(brain: Path, assignment_id: int, task_id: str | None) -> memory.Task | None:
    if task_id:
        task = memory.find_task(brain, task_id)
        if task is not None:
            return task
    return memory.task_with_field(brain, "assignment", f"moodle-{assignment_id}")


def _undo_closure(brain: Path, fields: dict) -> None:
    """Reverse what a mark did to its task (nothing when the owner changed it since)."""
    task_id, closed = fields.get("task"), fields.get("closed")
    if not task_id or not closed:
        return
    try:
        if closed == "completed":
            memory.reopen_task(brain, task_id, commit_now=False)
        elif closed == "trashed":
            memory.restore_task(brain, task_id, commit_now=False)
    except ClaudeError:
        pass  # finished, reopened or deleted by hand meanwhile: left as the owner left it


def set_mark(
    brain: Path,
    assignment_id: int,
    mark: str | None,
    *,
    task_id: str | None = None,
    now: datetime | None = None,
) -> dict:
    """Mark one assignment (``None`` clears the mark). Returns ``{"mark", "previous", "task"}``.

    ``task_id`` is the task the sync made for it, when known.
    """
    if mark is not None and mark not in MARKS:
        raise memory.bad("`mark` must be done, removed or null")
    now = now or memory.now_local()
    with memory.LOCK:
        memory.check_repo(brain)
        marks = read(brain)
        before = marks.get(assignment_id)
        previous = before["mark"] if before else None
        if previous == mark:
            return {"mark": mark, "previous": previous, "task": (before or {}).get("task")}
        if before is not None:
            _undo_closure(brain, before)
            marks.pop(assignment_id)
        task_line = None
        if mark is not None:
            fields = {"mark": mark, "on": now.date().isoformat()}
            task = _task_of(brain, assignment_id, task_id)
            if task is not None and not task.done:
                open_ids = {t.id for t in memory.list_tasks(brain)}
                if task.id in open_ids:
                    if mark == "done":
                        memory.complete_task(brain, task.id, now, commit_now=False)
                        fields |= {"task": task.id, "closed": "completed"}
                    else:
                        memory.trash_task(brain, task.id, commit_now=False)
                        fields |= {"task": task.id, "closed": "trashed"}
            task_line = fields.get("task")
            marks[assignment_id] = fields
        _write(brain, marks)
        paths = [
            name
            for name in (FILE, "tasks.md", "tasks-done.md", memory.TRASHED_LINES)
            if (brain / name).exists()
        ]
        message = {
            "done": "Mark a course assignment as done",
            "removed": "Remove a course assignment from the lists",
            None: "Clear the mark of a course assignment",
        }[mark]
        memory.commit(brain, message, paths)
    return {"mark": mark, "previous": previous, "task": task_line}
