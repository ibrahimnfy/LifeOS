"""The owner's projects for the app's Projects screen (#152). No model run.

A project is a memory file ``projects/<name>.md`` and/or an allowed folder
with its context-map file ``map/<slug>.md``. They belong together when the
project file links ``[[map/<slug>]]`` or has the same name as the map file.
For each project: its status (front matter ``status:``), its state (the map's
``## State``), a short summary, its folder (to start a chat there), its open
tasks (``(project: <name>)`` on the task line), its sessions and its last
activity: the newest of the last session in its folder, the folder's last git
commit and the memory file's ``updated`` day.

The course folder of the Moodle sync is an allowed folder but not a project.
"""

from __future__ import annotations

import re
import subprocess
from datetime import UTC, date, datetime
from pathlib import Path

from . import contextmap, memory, moodle_sync, sessions
from .claude import ClaudeError
from .config import Settings

MAX_TEXT = 280
MAX_TASKS = 20
MAX_SESSIONS = 5
NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")


def _front(text: str, field: str) -> str | None:
    if not text.startswith("---"):
        return None
    head = text.split("\n---", 1)[0]
    match = re.search(rf"^{field}:\s*(.+?)\s*(?:#.*)?$", head, re.MULTILINE)
    return match.group(1).strip().strip('"') if match else None


def _summary(text: str) -> str | None:
    """The first paragraph after the front matter (the topic file's summary)."""
    body = text.split("\n---", 1)[1] if text.startswith("---") and "\n---" in text[3:] else text
    for block in re.split(r"\n\s*\n", body.strip()):
        block = block.strip()
        if block and not block.startswith(("#", "-", "<!--")):
            return " ".join(block.split())[:MAX_TEXT]
    return None


def _section(text: str, title: str) -> str | None:
    match = re.search(rf"^## {re.escape(title)}\n(.+?)(?:\n## |\Z)", text, re.MULTILINE | re.S)
    if not match:
        return None
    return " ".join(match.group(1).split())[:MAX_TEXT] or None


def _git_last(folder: Path) -> datetime | None:
    if not (folder / ".git").exists():
        return None
    try:
        done = subprocess.run(  # noqa: S603
            ["git", "-C", str(folder), "log", "-1", "--format=%cI"],  # noqa: S607
            capture_output=True, text=True, timeout=5, check=False,
        )  # fmt: skip
        return datetime.fromisoformat(done.stdout.strip()) if done.returncode == 0 else None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def _day(value: str | None) -> datetime | None:
    try:
        return datetime.combine(date.fromisoformat(value or ""), datetime.min.time(), UTC)
    except ValueError:
        return None


def _iso(moment: datetime | None) -> str | None:
    if moment is None:
        return None
    return moment.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def collect(settings: Settings) -> list[dict]:
    """Every project with its details; newest activity first."""
    memory_dir = settings.ask_dir
    course_root = moodle_sync.download_dir()
    folders = {
        str(folder): folder
        for folder in contextmap.roots_to_map(settings)
        if course_root is None or folder.resolve() != course_root.resolve()
    }
    maps = {slug: path for slug, path in contextmap.existing_locations(memory_dir).items()}
    by_folder = {path: slug for slug, path in maps.items()}
    entries: dict[str, dict] = {}
    for file in sorted((memory_dir / "projects").glob("*.md")):
        if not NAME.match(file.stem) or not file.resolve().is_relative_to(memory_dir.resolve()):
            continue
        text = memory.read_text(file)
        slug = next((s for s in re.findall(r"\[\[map/([a-z0-9-]+)\]\]", text) if s in maps), None)
        slug = slug or (file.stem if file.stem in maps else None)
        entries[f"p-{file.stem}"] = {
            "name": file.stem,
            "title": _front(text, "title") or file.stem,
            "status": _front(text, "status"),
            "summary": _summary(text),
            "updated": _front(text, "updated"),
            "memory_file": f"projects/{file.stem}.md",
            "slug": slug,
        }
    linked = {e["slug"] for e in entries.values() if e["slug"]}
    for path, folder in folders.items():
        slug = by_folder.get(path)
        if slug in linked:
            continue
        entries[f"f-{slug or folder.name}"] = {
            "name": slug or folder.name, "title": folder.name, "status": None, "summary": None,
            "updated": None, "memory_file": None, "slug": slug, "folder_only": str(folder),
        }  # fmt: skip
    tasks = memory.list_tasks(memory_dir)
    found_sessions = sessions.list_sessions(settings.allowed_roots)
    projects = []
    for project_id, entry in entries.items():
        slug = entry["slug"]
        folder_text = maps.get(slug) if slug else entry.get("folder_only")
        folder = folders.get(folder_text) if folder_text else None
        map_text = memory.read_text(memory_dir / "map" / f"{slug}.md") if slug else ""
        mine = [t for t in tasks if t.fields.get("project") == entry["name"]]
        here = [
            s for s in found_sessions
            if folder is not None and (s.folder == folder or s.folder.is_relative_to(folder))
        ]  # fmt: skip
        moments = [m for m in (
            here[0].last_activity if here else None,
            _git_last(folder) if folder is not None else None,
            _day(entry["updated"]),
        ) if m is not None]  # fmt: skip
        projects.append(
            {
                "id": project_id,
                "name": entry["name"],
                "title": entry["title"],
                "status": entry["status"],
                "summary": entry["summary"] or _section(map_text, "What it is"),
                "state": _section(map_text, "State"),
                "folder": str(folder) if folder is not None else None,
                "folder_exists": folder is not None and folder.is_dir(),
                "memory_file": entry["memory_file"],
                "map_file": f"map/{slug}.md" if slug else None,
                "last_activity": _iso(max(moments)) if moments else None,
                "open_task_count": len(mine),
                "open_tasks": [t.as_json() for t in mine[:MAX_TASKS]],
                "sessions": [
                    {"id": s.id, "title": s.title, "last_activity": _iso(s.last_activity)}
                    for s in here[:MAX_SESSIONS]
                ],
            }
        )
    projects.sort(key=lambda p: p["last_activity"] or "", reverse=True)
    return projects


def one(settings: Settings, project_id: str) -> dict:
    found = next((p for p in collect(settings) if p["id"] == project_id), None)
    if found is None:
        raise ClaudeError("project_not_found", 404, "no project has this id")
    return found
