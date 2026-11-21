"""Blank chats: every new chat gets its own new, empty folder (#163).

A conversation started without a folder runs in ``<chat root>/<date>-<id>/``,
which the bridge creates. Nothing personal is given to it: no memory text in
its prompt, and Claude Code loads no memory file for it (``claude.Policy.clean``).
The memory folder is in reach, and one short fixed instruction
(:func:`context`) says where it is and that it is used only when the owner asks.

**The chat root** (``LIFEOS_CHAT_DIR``, default ``~/LifeOS-Chats``) must be a
folder inside the home folder that is not hidden, holds no instruction file
(``CLAUDE.md`` and the like), is not a git repository and is not inside one,
and neither contains nor is contained in the memory folder, an allowed
folder, the LifeOS program folder or the private settings folder. When that
is not true, blank chats are refused with ``503 chat_root_unusable`` and the
reason (:func:`root_refusal`). The root is never an allowed folder: a run
works in its own chat folder only and never reaches another chat's folder.

**A chat folder that holds no files is removed** once nothing runs in it.
"No files" means: at every depth it holds nothing but folders. Any other
entry (a regular file, a hidden file, a symbolic link, also one to a
folder) is a file, and the folder is kept. Only empty folders are ever
removed, one by one (``rmdir``), so nothing can be destroyed. The
conversation stays: Claude Code keeps it under the folder's path, and the
next message creates the same path again (:func:`ensure`).
"""

from __future__ import annotations

import contextlib
import dataclasses
import os
import re
import secrets
from collections.abc import Iterable
from datetime import date
from pathlib import Path

from . import capable, claude
from .claude import ClaudeError
from .config import Settings
from .paths import refusal_reason

# <date>-<six hex digits>, for example 2027-01-15-3fa9c1.
NAME = re.compile(r"\d{4}-\d{2}-\d{2}-[0-9a-f]{6}")
# Files that would give every chat instructions or settings of their own.
INSTRUCTION_FILES = ("CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", ".claude", ".mcp.json")
LABEL = "Chats"


def root(settings: Settings) -> Path:
    return settings.chat_root.expanduser()


def root_refusal(settings: Settings) -> str | None:
    """Why blank chats cannot use the chat root, or ``None`` when they can."""
    folder = root(settings)
    reason = _placement_refusal(settings)
    if reason is not None:
        return reason
    resolved = folder.resolve()
    reason = refusal_reason(folder)  # inside home, not hidden, not the program or settings folder
    if reason is not None:
        return f"the chat folder cannot be used: {reason}"
    found = [name for name in INSTRUCTION_FILES if os.path.lexists(resolved / name)]
    if found:
        return f"the chat folder holds {found[0]}; it must hold no instruction or settings file"
    if capable.repository_root(resolved) is not None or os.path.lexists(resolved / ".git"):
        return "the chat folder is a git repository or inside one"
    return None


def unusable(reason: str) -> ClaudeError:
    return ClaudeError("chat_root_unusable", 503, f"blank chats are switched off: {reason}")


def _placement_refusal(settings: Settings) -> str | None:
    """The checks that need no folder yet: where the chat root lies."""
    folder = root(settings)
    if not folder.is_absolute():
        return "the chat folder must be an absolute path"
    resolved, home = folder.resolve(), Path.home().resolve()
    if resolved == home or not resolved.is_relative_to(home):
        return "the chat folder must be a folder inside the home folder"
    for other in settings.allowed_roots:
        if resolved == other or resolved.is_relative_to(other) or other.is_relative_to(resolved):
            return "the chat folder must be outside the memory folder and every allowed folder"
    return None


def prepare_root(settings: Settings) -> Path:
    """The usable chat root, created when it is missing; raises ``chat_root_unusable``."""
    base = root(settings)
    created = False
    if not base.exists():
        reason = _placement_refusal(settings)  # created only where it may be
        if reason is not None:
            raise unusable(reason)
        try:
            base.mkdir(mode=0o700, parents=True, exist_ok=True)
        except OSError as exc:
            raise unusable("the chat folder could not be created") from exc
        created = True
    reason = root_refusal(settings)
    if reason is not None:
        if created:
            with contextlib.suppress(OSError):
                base.rmdir()  # the bridge's own empty folder, made a moment ago
        raise unusable(reason)
    return base


def new_folder(settings: Settings, today: date) -> Path:
    """Create the folder of a new blank chat; raises ``chat_root_unusable`` when it cannot."""
    base = prepare_root(settings)
    for _ in range(8):
        folder = base / f"{today.isoformat()}-{secrets.token_hex(3)}"
        try:
            folder.mkdir(mode=0o700)
        except FileExistsError:
            continue
        except OSError as exc:
            raise unusable("a folder for the chat could not be created") from exc
        return folder
    raise unusable("a folder for the chat could not be created")


def is_chat_folder(settings: Settings, folder: Path) -> bool:
    """True for ``<chat root>/<date>-<id>``, whether or not it exists right now."""
    if not NAME.fullmatch(folder.name):
        return False
    try:
        return folder.parent.resolve() == root(settings).resolve()
    except OSError:
        return False


def in_chat_root(settings: Settings, folder: Path) -> bool:
    """True for a chat folder and anything inside one (a session started in a sub-folder)."""
    try:
        base, resolved = root(settings).resolve(), folder.resolve()
    except OSError:
        return False
    return resolved != base and resolved.is_relative_to(base)


def ensure(settings: Settings, folder: Path) -> None:
    """Create a chat's folder again when it was removed for holding no files.

    Claude Code binds a conversation to the path of its working directory, so
    the same path is all a later message needs. Only ever a chat folder under
    a usable root.
    """
    if folder.is_dir() or not is_chat_folder(settings, folder):
        return
    reason = root_refusal(settings)
    if reason is not None:
        raise unusable(reason)
    try:
        folder.mkdir(mode=0o700, exist_ok=True)
    except OSError as exc:
        raise unusable("the chat's folder could not be created again") from exc


def holds_files(folder: Path) -> bool:
    """True when anything but folders is inside, at any depth (hidden files and links count)."""
    try:
        with os.scandir(folder) as entries:
            for entry in entries:
                if not entry.is_dir(follow_symlinks=False) or holds_files(Path(entry.path)):
                    return True
    except OSError:
        return True  # unreadable: never treated as empty
    return False


def _remove_tree_of_folders(folder: Path) -> None:
    with os.scandir(folder) as entries:
        children = [Path(entry.path) for entry in entries]
    for child in children:
        _remove_tree_of_folders(child)
    os.rmdir(folder)  # fails, and keeps everything, when a file appeared meanwhile


def remove_if_empty(settings: Settings, folder: Path) -> bool:
    """Remove a chat folder that holds no files. Returns whether it was removed."""
    if not is_chat_folder(settings, folder) or folder.is_symlink() or not folder.is_dir():
        return False
    if holds_files(folder):
        return False
    try:
        _remove_tree_of_folders(folder)
    except OSError:
        return False
    return True


def sweep(settings: Settings, keep: Iterable[Path] = ()) -> list[Path]:
    """Remove every chat folder without files, except those in ``keep`` (a run is using them)."""
    held = {path.resolve() for path in keep}
    removed = []
    try:
        candidates = sorted(root(settings).iterdir())
    except OSError:
        return removed
    for folder in candidates:
        free = NAME.fullmatch(folder.name) and folder.resolve() not in held
        if free and remove_if_empty(settings, folder):
            removed.append(folder)
    return removed


def read_only_policy(settings: Settings) -> claude.Policy:
    """A read-only run of a blank chat: the memory folder is in reach of the file tools
    (read on request; there is no write tool), and Claude Code loads no memory file."""
    return dataclasses.replace(claude.READ_ONLY, add_dirs=(settings.ask_dir.resolve(),), clean=True)


def context(settings: Settings) -> str:
    """The one fixed instruction of a blank chat (part of its system prompt: keep it short).

    It names the memory folder and the move-files line; it never carries
    anything from the memory. Claude Code records the system prompt at a
    conversation's first message, so this text must stay true for its life.
    """
    return (
        "This chat starts without personal context. The owner's memory is the folder "
        f"{settings.ask_dir.resolve()} (its CLAUDE.md explains it): read it only when the owner "
        "asks you to use their memory, and write to it only when the owner asks you to "
        "remember or note something. To move files of this chat into one of the owner's "
        "project or course folders, never copy them yourself: write a line of its own in the "
        "form [[move-files: <target folder> | <file> | <file>]]; the owner approves it and "
        "the bridge moves them."
    )
