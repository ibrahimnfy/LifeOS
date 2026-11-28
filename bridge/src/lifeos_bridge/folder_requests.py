"""Folders by asking (#179): find a folder by name and work there, or stop using one.

The owner asks in any chat ("find my thesis folder and let's work there", "stop
using the old-site folder"). The run cannot search or change anything itself; it
ends its answer with one line of its own, and the bridge does the rest:

- ``[[switch-find: <folder name or path>]]``: the bridge searches **folder names**
  under the home folder (:func:`search`). One clear match is added to the allowed
  folders when it is not one yet, with an audit record and a notification, and the
  answer's ``switch`` item starts a new chat there (``action: "new"``). Several
  matches give a pick card (``action: "choose_folder"``, at most five folders);
  the app sends the pick to ``POST /folders/open``, which applies the same rules.
  ``[[switch-find: <name> | <request>]]`` carries the owner's request to the new
  chat (``"forward"`` on the item, the card's included; #187, see ``conversations``).
- ``[[switch-remove: <folder label or path>]]``: the folder's line is removed from
  ``allowed_paths`` (nothing else changes, no file is touched), with an audit record.

**What the search reads:** names of folders only, never a file's content or name.
It never leaves the home folder, follows no symbolic link, skips hidden folders,
system and cache folders (:data:`SKIPPED_NAMES`), the private settings folder, the
LifeOS program folder, the chat root and the copies of skills, and stops at
:data:`MAX_DEPTH` levels, :data:`MAX_ENTRIES` entries or :data:`MAX_SECONDS`.

**The limits stay** (``paths.refusal_reason``): never the home folder itself,
anything outside it, a hidden folder, the program or the settings folder. A folder
inside a git repository stands for the repository's root (#139); when that root is
refused, the folder is refused with the root's reason. The owner's read-only course
folders (``course_folders``) never become allowed folders: a match there opens a new
chat in that course's folder of the Moodle sync, where the course folder is readable.

**No approval prompt** (owner's decision), so markers act only in the fresh answer
to a message the owner sent (``owner=True``); in any other answer, and in the
history, the lines are only removed. Whatever the bridge did, or why it did
nothing, is said in a short note added to the answer.
"""

from __future__ import annotations

import os
import re
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

from . import allowed, capable, chats, conversations, course_folders, folders
from .claude import ClaudeError
from .config import Settings, config_dir
from .paths import refusal_reason, repo_dir
from .resources import normalised  # names compared as the resource check compares them

FIND, REMOVE = "switch-find", "switch-remove"
MARKER = re.compile(r"^[ \t]*\[\[(switch-find|switch-remove):\s*(.*?)\s*\]\][ \t]*$", re.MULTILINE)
MAX_CHOICES = 5
MAX_REMOVALS = 3  # per answer
MAX_DEPTH = 6  # levels below the home folder
MAX_ENTRIES = 200_000
MAX_SECONDS = 3.0
MAX_NAME = 200
# Folders the search never enters (besides every hidden one): system, cache, package
# and build folders, where nobody keeps a folder to work in.
SKIPPED_NAMES = frozenset(
    {
        "snap", "node_modules", "__pycache__", "site-packages", "dist-packages", "venv",
        "Android", "AndroidStudioProjects-cache", "go", "build", "dist", "target", "vendor",
        "bower_components", "Trash", "lost+found",
    }
)  # fmt: skip


@dataclass
class Found:
    """What a search found: equal names first, then names that contain the wanted words."""

    equal: list[Path] = field(default_factory=list)
    partial: list[Path] = field(default_factory=list)
    complete: bool = True  # False: a bound stopped the search early


def _skipped(settings: Settings) -> set[Path]:
    places = [config_dir(), chats.root(settings), settings.skill_copies_dir]
    repo = repo_dir()
    if repo is not None:
        places.append(repo)
    found = set()
    for place in places:
        try:
            found.add(place.expanduser().resolve())
        except (OSError, RuntimeError):
            continue
    return found


def search(
    settings: Settings,
    name: str,
    home: Path | None = None,
    *,
    max_depth: int = MAX_DEPTH,
    max_entries: int = MAX_ENTRIES,
    seconds: float = MAX_SECONDS,
) -> Found:
    """Folders under ``home`` (the home folder) whose name is ``name``, by name only."""
    wanted = normalised(name)
    found = Found()
    if not wanted:
        return found
    base = (home or Path.home()).resolve()
    skipped = _skipped(settings)
    deadline = time.monotonic() + seconds
    queue: deque[tuple[Path, int]] = deque([(base, 0)])
    seen = 0
    while queue:
        folder, depth = queue.popleft()
        try:
            with os.scandir(folder) as entries:
                for entry in entries:
                    seen += 1
                    if seen > max_entries or time.monotonic() > deadline:
                        found.complete = False
                        return found
                    if entry.name.startswith(".") or entry.name in SKIPPED_NAMES:
                        continue
                    if not entry.is_dir(follow_symlinks=False):
                        continue  # files and links are never looked at
                    path = Path(entry.path)
                    if path in skipped:
                        continue
                    have = normalised(entry.name)
                    if have == wanted:
                        found.equal.append(path)
                    elif have and f" {wanted} " in f" {have} ":
                        found.partial.append(path)
                    if depth + 1 < max_depth:
                        queue.append((path, depth + 1))
        except OSError:
            continue
    return found


# ---- where a match leads --------------------------------------------------------------


@dataclass
class Target:
    """Where a found folder leads: the folder a new chat starts in, or why none can."""

    path: Path
    label: str
    kind: str = "project"  # project, memory, course (a course's folder of the sync)
    allowed: bool = False  # already a folder a conversation can start in
    reason: str | None = None


def _root_entry(settings: Settings, path: Path) -> dict | None:
    for entry in folders.catalogue(settings):
        if entry["kind"] not in ("chat", "coach") and Path(entry["path"]) == path:
            return entry
    return None


def _course_sync_target(settings: Settings, course: int | None) -> Target | None:
    """The course's folder of the Moodle sync, when a conversation can start there."""
    sync = course_folders.sync_folder(course) if course is not None else None
    roots = settings.allowed_roots
    if sync is None or not any(sync == root or sync.is_relative_to(root) for root in roots):
        return None
    return Target(sync, course_folders.course_listing(course) or sync.name, "course", allowed=True)


def _course_target(settings: Settings, course_dir: Path) -> Target:
    """A match in one of the owner's read-only course folders: that course's chats."""
    course = next(
        (
            number
            for number, paths in course_folders.listed(settings).items()
            if course_dir in paths
        ),
        None,
    )
    target = _course_sync_target(settings, course)
    if target is None:
        return Target(course_dir, course_dir.name, "course", reason=(
            "this is one of the owner's read-only course folders; it is read in the chats of "
            "its course, and that course has no folder of the course sync here yet"
        ))  # fmt: skip
    return target


def target_of(settings: Settings, raw: Path) -> Target:
    """The target of a found or named folder, with every limit applied."""
    path = raw.expanduser()
    reason = refusal_reason(path)
    if reason is not None:
        return Target(path, path.name, reason=reason)
    path = path.resolve()
    course_dir = course_folders.containing(settings, path)
    if course_dir is not None:
        return _course_target(settings, course_dir)
    # The course sync's folder of a course (or a folder in it): that course's chats.
    synced = _course_sync_target(settings, course_folders.course_of(path))
    if synced is not None:
        return synced
    # A folder inside a git repository stands for the repository's root (#139).
    enclosing = capable.repository_root(path)
    if enclosing is not None and enclosing != path:
        path = enclosing
        reason = refusal_reason(path)
        if reason is not None:
            return Target(path, path.name, reason=(
                f"it lies inside the git repository at {path}, which cannot be allowed: {reason}"
            ))  # fmt: skip
    entry = _root_entry(settings, path)
    if entry is not None:
        return Target(path, entry["name"], entry["kind"], allowed=True)
    for place, what in ((chats.root(settings), "the chats folder"),
                        (settings.skill_copies_dir, "the folder of the skill copies")):  # fmt: skip
        place = place.expanduser().resolve()
        if path == place or path.is_relative_to(place) or place.is_relative_to(path):
            return Target(path, path.name, reason=f"{what} cannot be a working folder")
    reason = allowed.course_refusal(path)
    if reason is not None:
        return Target(path, path.name, reason=reason)
    # A folder around a course folder may be allowed: the course folder in it stays
    # read-only (every capable run denies writes there).
    return Target(path, path.name)


def _where(path: Path) -> str:
    """The folder around ``path``, short: ``~/Desktop/work``."""
    parent = path.parent
    home = Path.home().resolve()
    if parent == home:
        return "~"
    return "~/" + str(parent.relative_to(home)) if parent.is_relative_to(home) else str(parent)


def _folder_item(settings: Settings, target: Target) -> dict:
    """The ``folder`` of a ``switch`` item (``GET /folders`` style), with its colour."""
    known = folders.Folders(settings)
    entry = known.of(target.path)
    colour = entry["colour"] if entry is not None else 0
    return {"path": str(target.path), "label": target.label, "kind": target.kind, "colour": colour}


def course_folder(settings: Settings, course: int) -> dict | None:
    """The ``folder`` item of a course's own folder of the sync, when a chat can start there."""
    target = _course_sync_target(settings, course)
    return _folder_item(settings, target) if target is not None else None


def _add(settings: Settings, target: Target, by: str) -> bool:
    if settings.allowed_paths_file is None:
        raise ClaudeError("not_configured", 503, "no allowed-paths file is configured")
    return allowed.add(settings.allowed_paths_file, str(target.path), settings.notify_script, by=by)


@dataclass
class Outcome:
    """What one marker led to: a ``switch`` item, and a note for the answer."""

    switch: dict | None = None
    note: str = ""


def find(settings: Settings, value: str, home: Path | None = None) -> Outcome:
    """``[[switch-find: …]]``: one clear match opens there (added if needed); several are
    offered on a card; nothing usable is said in a note."""
    value = value.strip().strip("`'\"").strip()[:MAX_NAME]
    if not value:
        return Outcome()
    if value.startswith(("/", "~")):
        candidates, complete = [Path(value)], True
    else:
        # A folder a conversation can already start in, named by its label: no search.
        roots = [
            entry for entry in folders.catalogue(settings)
            if entry["kind"] not in ("chat", "coach")
            and normalised(entry["name"]) == normalised(value)
        ]  # fmt: skip
        if len(roots) == 1:
            candidates, complete = [Path(roots[0]["path"])], True
        else:
            found = search(settings, value, home)
            # Nearest to the home folder first, then by name.
            candidates = sorted(
                found.equal or found.partial, key=lambda p: (len(p.parts), str(p).casefold())
            )
            complete = found.complete
    targets: list[Target] = []
    for candidate in candidates:
        target = target_of(settings, candidate)
        if all(target.path != other.path for other in targets):
            targets.append(target)
    usable = [target for target in targets if target.reason is None]
    if not usable:
        if targets:
            first = targets[0]
            return Outcome(note=f"The folder “{first.label}” cannot be used: {first.reason}.")
        more = "" if complete else " (the search stopped early: name it by its path)"
        return Outcome(note=f"No folder named “{value}” was found in the home folder{more}.")
    if len(usable) == 1 and complete:
        target = usable[0]
        if not target.allowed:
            try:
                _add(settings, target, "chat")
            except ClaudeError as error:
                return Outcome(note=f"The folder “{target.label}” cannot be used: {error.message}.")
            target.kind = "project"
        # The run wrote its answer before the search: the note names what was found (#187).
        return Outcome(
            switch={"action": "new", "sessions": [], "folder": _folder_item(settings, target)},
            note=f"A new chat opens in “{target.label}”.",
        )
    choices = [
        {**_folder_item(settings, target), "allowed": target.allowed, "where": _where(target.path)}
        for target in usable[:MAX_CHOICES]
    ]
    return Outcome(
        switch={"action": "choose_folder", "sessions": [], "folder": None, "folders": choices}
    )


def remove(settings: Settings, value: str) -> str:
    """``[[switch-remove: …]]``: delete only that folder's line. Returns the note."""
    value = value.strip().strip("`'\"").strip()[:MAX_NAME]
    file = settings.allowed_paths_file
    if not value or file is None:
        return ""
    entries = [Path(item["path"]) for item in allowed.listing(file)]
    if value.startswith(("/", "~")):
        wanted = Path(value).expanduser()
        matches = [
            p for p in entries if p == wanted or p.expanduser().resolve() == wanted.resolve()
        ]
    else:
        matches = [p for p in entries if normalised(p.name) == normalised(value)]
    if not matches:
        return (
            f"“{value}” is not one of the allowed folders that can be removed; nothing was changed."
        )
    if len(matches) > 1:
        return (
            f"Several allowed folders are named “{value}”; nothing was removed. "
            "Name it by its path."
        )
    try:
        allowed.remove(file, str(matches[0]), settings.notify_script, by="chat")
    except ClaudeError as error:
        return f"The folder “{matches[0].name}” was not removed: {error.message}."
    return (
        f"The folder “{matches[0].name}” is no longer an allowed folder; "
        "none of its files were touched."
    )


def from_answer(
    settings: Settings, answer: str, *, owner: bool, home: Path | None = None
) -> tuple[str, dict | None]:
    """Take the folder lines out of a fresh answer and act on them (only for the owner).

    Returns the text, with a note of what was done, and the ``switch`` item of the
    first find line that led somewhere.
    """
    markers = MARKER.findall(answer)
    if not markers:
        return answer, None
    text = MARKER.sub("", answer).strip("\n")
    if not owner:
        return text, None
    switch, notes, finds, removals = None, [], 0, 0
    for kind, value in markers:
        if kind == REMOVE and removals < MAX_REMOVALS:
            removals += 1
            notes.append(remove(settings, value))
        elif kind == FIND and switch is None and finds == 0:
            finds += 1
            # The owner's request may follow a "|" (#187): it goes with the switch, also
            # with a pick card, so the new chat starts with it.
            name, raw = conversations.split_forward(value)
            outcome = find(settings, name, home)
            switch = outcome.switch
            notes.append(outcome.note)
            if switch is not None:
                forward, note = conversations.forward_of(raw)
                if forward is not None:
                    switch["forward"] = forward
                notes.append(note)
    notes = [note for note in notes if note]
    if notes:
        text = (text + "\n\n" if text else "") + " ".join(notes)
    return text, switch


def open_folder(settings: Settings, raw: object) -> dict:
    """``POST /folders/open``: the folder the owner picked on a card, by the same rules."""
    if not isinstance(raw, str) or not raw.strip() or "\n" in raw or len(raw) > 4096:
        raise ClaudeError("bad_request", 400, "`path` must be a folder path")
    path = Path(raw.strip()).expanduser()
    if not path.is_absolute():
        raise ClaudeError("bad_request", 400, "`path` must be absolute")
    target = target_of(settings, path)
    if target.reason is not None:
        raise ClaudeError("path_not_allowed", 403, target.reason)
    added = False
    if not target.allowed:
        added = _add(settings, target, "pick")
        target.kind = "project"
    return {"added": added, "folder": _folder_item(settings, target)}
