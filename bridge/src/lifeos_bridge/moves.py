"""Move a blank chat's files into a project or course folder, after the owner approved (#163).

A run in a chat folder cannot write into a project: it may only *propose*
the move, with a line of its own in its answer::

    [[move-files: <target folder> | <file> | <file>]]

The bridge takes the line out of the answer and turns it into an approval
card (``approvals``, kind ``move_files``) that names the files with their
sizes and the target. Only ``{"approve": true}`` moves them, and the bridge
does the moving itself:

- **Target**: an allowed folder other than the memory folder, named by its
  path or its folder name, or an existing folder inside one. Never a hidden
  folder.
- **Files**: regular files inside the chat's own folder, given relative to
  it. No symbolic links (the file itself or a folder on the way), nothing
  hidden (so no ``.claude``, ``.mcp.json`` or ``.git`` content can arrive in
  a project this way), at most :data:`MAX_FILES`.
- **Never overwriting**: a file keeps its relative path below the target; if
  something is already there, nothing is moved. Each file is created
  exclusively and written as a plain file (mode 644), then its source is
  removed.
- Everything is checked again when the owner answers, and the audit record
  is written before the first file moves.
"""

from __future__ import annotations

import contextlib
import os
import re
import stat
from pathlib import Path, PurePosixPath

from . import approvals, audit, chats
from .claude import ClaudeError
from .config import Settings

KIND = "move_files"
MARKER = re.compile(r"^[ \t]*\[\[move-files:\s*(.+?)\s*\]\][ \t]*$", re.MULTILINE)
MAX_FILES = 50
MAX_PROPOSALS = 3
MAX_DEPTH = 8
CHUNK = 1024 * 1024


INSTRUCTION_NAMES = frozenset({"claude.md", "claude.local.md", "agents.md"})


def refused(code: str, message: str) -> ClaudeError:
    return ClaudeError(code, 409, message)


def _clean(raw: str) -> str:
    return raw.strip().strip("`'\"").strip()


def target_for(settings: Settings, raw: str) -> tuple[Path, str]:
    """The folder files may be moved into, and its label. Raises when it is not one."""
    name = _clean(raw)
    memory = settings.ask_dir.resolve()
    roots = [root for root in settings.allowed_roots if root != memory and root.is_dir()]
    wanted = Path(name).expanduser()
    if not wanted.is_absolute():
        # A folder name, optionally with a folder inside it: "project" or "project/notes".
        first, _, rest = name.partition("/")
        matches = [root for root in roots if root.name.casefold() == first.casefold()]
        if len(matches) != 1:
            raise refused("target_not_allowed", "the target is not one of the allowed folders")
        wanted = matches[0] / rest if rest else matches[0]
    try:
        resolved = wanted.resolve(strict=True)
    except OSError as exc:
        raise refused("target_not_allowed", "the target folder does not exist") from exc
    root = next((r for r in roots if resolved == r or resolved.is_relative_to(r)), None)
    if root is None or not resolved.is_dir():
        raise refused("target_not_allowed", "the target is not one of the allowed folders")
    inner = resolved.relative_to(root)
    if any(part.startswith(".") for part in inner.parts):
        raise refused("target_not_allowed", "files are never moved into a hidden folder")
    return resolved, "/".join([root.name, *inner.parts])


def _relative(folder: Path, raw: str) -> PurePosixPath:
    name = _clean(raw)
    path = PurePosixPath(name)
    if path.is_absolute():
        try:
            path = PurePosixPath(Path(name).relative_to(folder))
        except ValueError as exc:
            raise refused("file_not_in_chat", "only files of this chat can be moved") from exc
    parts = path.parts
    if not parts or ".." in parts or len(parts) > MAX_DEPTH:
        raise refused("file_not_in_chat", "only files of this chat can be moved")
    if any(part.startswith(".") for part in parts):
        raise refused("file_hidden", "hidden files and folders are never moved")
    if parts[-1].lower() in INSTRUCTION_NAMES:
        # A file that Claude Code would read as instructions in the target folder.
        raise refused("file_instructions", "instruction files are never moved into a folder")
    return path


def _is_real_folder(path: Path) -> bool:
    try:
        return stat.S_ISDIR(os.lstat(path).st_mode)
    except OSError:
        return False


def check_file(folder: Path, target: Path, raw: str) -> dict:
    """One file of a proposal: ``{"path", "size"}``, or a ClaudeError saying why not."""
    relative = _relative(folder, raw)
    source = folder
    for part in relative.parts[:-1]:
        source = source / part
        if not _is_real_folder(source):  # a link on the way would lead out of the chat
            raise refused("file_not_in_chat", "only files of this chat can be moved")
    source = source / relative.parts[-1]
    try:
        info = os.lstat(source)
    except OSError as exc:
        raise refused("file_missing", f"{relative} is not a file of this chat") from exc
    if not stat.S_ISREG(info.st_mode):
        raise refused("file_not_regular", f"{relative} is not a regular file (links are not moved)")
    destination = target
    for part in relative.parts[:-1]:
        destination = destination / part
        if os.path.lexists(destination) and not _is_real_folder(destination):
            raise refused("file_exists", f"{relative} cannot be placed in the target")
    destination = destination / relative.parts[-1]
    if os.path.lexists(destination):
        raise refused("file_exists", f"{relative} already exists in the target; nothing is moved")
    return {"path": relative.as_posix(), "size": info.st_size}


def check(settings: Settings, folder: Path, target_raw: str, files: list[str]) -> dict:
    """A whole proposal, checked: the card's ``target``, ``target_label`` and ``files``."""
    if not chats.is_chat_folder(settings, folder) or not _is_real_folder(folder):
        raise refused("not_a_chat", "only the files of a blank chat can be moved this way")
    target, label = target_for(settings, target_raw)
    names = list(dict.fromkeys(_clean(name) for name in files if _clean(name)))
    if not names:
        raise refused("no_files", "no file was named")
    if len(names) > MAX_FILES:
        raise refused("too_many_files", f"at most {MAX_FILES} files can be moved at once")
    checked = [check_file(folder.resolve(), target, name) for name in names]
    if len({item["path"] for item in checked}) != len(checked):
        raise refused("file_exists", "a file was named twice")
    return {
        "target": str(target),
        "target_label": label,
        "files": checked,
        "total_size": sum(item["size"] for item in checked),
    }


def _record(action: str, entry: dict, **extra: object) -> None:
    """Names and sizes only, never a file's content. Not a run's tool call: no session id."""
    detail = {
        "session": entry.get("session_id"),
        "folder": entry.get("folder"),
        "target": entry.get("target"),
        "files": [item.get("path") for item in entry.get("files") or []],
        **extra,
    }
    audit.record(action, detail)


def from_answer(
    settings: Settings, answer: str, session_id: str, folder: Path
) -> tuple[str, list[dict]]:
    """Take the proposal lines out of an answer of a blank chat; one card per proposal."""
    cards: list[dict] = []
    for raw in MARKER.findall(answer):
        target_raw, *files = (part for part in raw.split("|"))
        if len(cards) >= MAX_PROPOSALS:
            cards.append(_refusal_card(target_raw, "too_many", "too many moves in one answer"))
            continue
        try:
            data = check(settings, folder, target_raw, files)
        except ClaudeError as error:
            cards.append(_refusal_card(target_raw, error.code, error.message))
            continue
        entry = approvals.add(KIND, session_id, str(folder), {**data, "state": "ready"})
        try:
            _record("move_files_proposed", entry)
        except audit.AuditUnavailable:
            approvals.take(session_id, entry["id"])
            cards.append(
                _refusal_card(target_raw, "audit_unavailable", "the audit log could not be written")
            )
            continue
        cards.append(approvals.public(entry))
    text = MARKER.sub("", answer).strip("\n") if cards else answer
    return text, cards


def _refusal_card(target_raw: str, code: str, message: str) -> dict:
    return {"kind": KIND, "state": "refused", "target": _clean(target_raw)[:200],
            "error": message, "error_code": code}  # fmt: skip


def _move(source: Path, destination: Path) -> None:
    """Copy into a file created exclusively (never onto anything), then remove the source."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    reading = os.open(source, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        if not stat.S_ISREG(os.fstat(reading).st_mode):
            raise OSError("not a regular file")
        writing = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644)
        try:
            while chunk := os.read(reading, CHUNK):
                view = memoryview(chunk)
                while view:
                    view = view[os.write(writing, view) :]
            os.fsync(writing)
        except OSError:
            os.close(writing)
            writing = -1
            with contextlib.suppress(OSError):
                os.unlink(destination)  # the bridge's own unfinished copy
            raise
        finally:
            if writing != -1:
                os.close(writing)
    finally:
        os.close(reading)
    os.unlink(source)


def answer(settings: Settings, entry: dict, approve: bool) -> dict:
    """The owner's answer (``approvals.settle``: the card is taken out only if this returns).

    A refused answer (a file changed or the target is taken meanwhile, the
    audit log cannot be written) raises and keeps the card, with nothing moved.
    """
    if not approve:
        with contextlib.suppress(audit.AuditUnavailable):
            _record("move_files_refused", entry, why="owner")
        return {"moved": False}
    folder = Path(entry["folder"])
    names = [str(item.get("path")) for item in entry.get("files") or []]
    data = check(settings, folder, str(entry.get("target")), names)  # everything again, now
    if data["target"] != entry.get("target") or data["files"] != entry.get("files"):
        raise refused("files_changed", "the files changed since the card was shown; ask again")
    try:
        _record("move_files_approved", entry)  # before anything is moved
    except audit.AuditUnavailable as exc:
        raise ClaudeError(
            "audit_unavailable", 503, "the audit log could not be written; nothing moved"
        ) from exc
    target, moved, failed = Path(data["target"]), [], []
    for name in names:
        try:
            _move(folder.resolve() / name, target / name)
            moved.append(name)
        except OSError:
            failed.append(name)
    with contextlib.suppress(audit.AuditUnavailable):
        _record("move_files_done", entry, moved=len(moved), failed=failed)
    return {"moved": not failed, "files": moved, "failed": failed, "target": data["target"],
            "target_label": data["target_label"]}  # fmt: skip


def expire(entry: dict) -> None:
    with contextlib.suppress(audit.AuditUnavailable):
        _record("move_files_expired", entry)
