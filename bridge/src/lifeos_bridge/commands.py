"""List the skills and custom commands of a session's folder.

Only the documented project locations are read, and only inside the
session's own folder:

- ``<folder>/.claude/skills/<name>/SKILL.md``
- ``<folder>/.claude/commands/**/*.md``

Nothing from the home folder and no plugins. Every file is resolved first
and skipped unless it really lies inside ``<folder>/.claude``, so a symbolic
link cannot lead the reader elsewhere. Nothing is executed and no model is
involved; of each file only the name, a one-line description, the argument
hint and a few fixed warning words are returned, never the content.

The listing is information for the app's composer. Whether a session's
capable runs may use them is decided in ``skills`` and the app
(``GET /sessions/{id}/commands``, #91).
"""

from __future__ import annotations

import os
import re
from pathlib import Path

MAX_ENTRIES = 200
MAX_FILE_BYTES = 64 * 1024
MAX_SCAN = 2000
MAX_DESCRIPTION = 200

_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
_KEY_LINE = re.compile(r"([A-Za-z][A-Za-z0-9_-]*)\s*:(.*)")
_BLOCK_MARKS = frozenset({"", ">", "|", ">-", "|-", ">+", "|+"})
# Pre-run shell in a skill or command: !`command` anywhere, or a ```! block.
_SHELL = re.compile(r"!`|^\s*```!", re.MULTILINE)


def _inside(path: Path, base: Path) -> Path | None:
    """``path`` resolved, when it is a regular file inside ``base``; else ``None``."""
    try:
        resolved = path.resolve()
        if resolved.is_relative_to(base) and resolved.is_file():
            return resolved
    except (OSError, RuntimeError):
        pass
    return None


def _read(path: Path) -> str | None:
    try:
        with path.open("rb") as handle:
            data = handle.read(MAX_FILE_BYTES)
    except OSError:
        return None
    return data.decode("utf-8", errors="replace").removeprefix("﻿")


def _unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    return value


def split_frontmatter(text: str) -> tuple[dict[str, str], str]:
    """Split a Markdown file into its frontmatter keys and its body.

    A tiny tolerant parser, not YAML: ``key: value`` lines between the first
    two ``---`` lines; indented lines continue the value above them. Keys are
    lower-cased. Without a closing ``---`` everything read counts as
    frontmatter, so a malformed file still yields what could be understood.
    """
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, text
    fields: dict[str, str] = {}
    key = None
    for index, line in enumerate(lines[1:], start=1):
        if line.strip() == "---":
            return fields, "\n".join(lines[index + 1 :])
        match = _KEY_LINE.fullmatch(line.rstrip())
        if match and not line[0].isspace():
            key = match.group(1).lower()
            value = match.group(2).strip()
            fields[key] = "" if value in _BLOCK_MARKS else _unquote(value)
        elif key is not None and line[:1].isspace() and line.strip():
            fields[key] = f"{fields[key]} {line.strip()}".strip()
    return fields, ""


def _one_line(text: str, limit: int = MAX_DESCRIPTION) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _first_text_line(body: str) -> str:
    for line in body.splitlines():
        line = line.strip().lstrip("#").strip()
        if line:
            return line
    return ""


def _has_scripts(skill_dir: Path) -> bool:
    """True when the skill folder holds anything that is not Markdown (names only)."""
    seen = 0
    for _root, _dirs, files in os.walk(skill_dir, followlinks=False):
        for name in files:
            seen += 1
            if not name.lower().endswith(".md"):
                return True
            if seen >= MAX_SCAN:
                return False
    return False


def describe(name: str, kind: str, text: str | None, *, has_scripts: bool = False) -> dict:
    """The listing entry for one skill or command file."""
    text = text or ""
    fields, body = split_frontmatter(text)
    warnings = []
    if _SHELL.search(text):  # the whole text: an unclosed frontmatter has no body
        warnings.append("shell_lines")
    if "allowed-tools" in fields:
        warnings.append("requests_tools")
    if has_scripts:
        warnings.append("has_scripts")
    hint = _one_line(fields.get("argument-hint", ""))
    return {
        "name": name,
        "kind": kind,
        "description": _one_line(fields.get("description") or _first_text_line(body)),
        "argument_hint": hint or None,
        "warnings": warnings,
    }


def _skill_candidates(base: Path) -> list[tuple[str, str, Path]]:
    try:
        names = sorted(entry.name for entry in os.scandir(base / "skills"))[:MAX_SCAN]
    except OSError:
        return []
    return [(name, "skill", base / "skills" / name / "SKILL.md") for name in names]


def _command_candidates(base: Path) -> list[tuple[str, str, Path]]:
    root = base / "commands"
    found: list[tuple[str, str, Path]] = []
    seen = 0
    for folder, dirs, files in os.walk(root, followlinks=False):
        dirs.sort()
        for filename in sorted(files):
            seen += 1
            if seen > MAX_SCAN:
                return found
            if filename.endswith(".md"):
                path = Path(folder) / filename
                parts = path.relative_to(root).with_suffix("").parts
                found.append((":".join(parts), "command", path))
    return found


def list_commands(folder: Path) -> list[dict]:
    """Skills and commands found in ``folder/.claude``, sorted by name."""
    try:
        base = folder.resolve() / ".claude"
        if base == Path.home().resolve() / ".claude":
            return []  # personal skills and commands are never listed
    except (OSError, RuntimeError):
        return []
    candidates = [
        candidate
        for candidate in _skill_candidates(base) + _command_candidates(base)
        if _NAME.fullmatch(candidate[0])
    ]
    candidates.sort(key=lambda candidate: candidate[:2])
    entries: list[dict] = []
    for name, kind, path in candidates:
        if len(entries) >= MAX_ENTRIES:
            break
        resolved = _inside(path, base)
        if resolved is None:
            continue
        scripts = kind == "skill" and _has_scripts(path.parent)
        entries.append(describe(name, kind, _read(resolved), has_scripts=scripts))
    return entries
