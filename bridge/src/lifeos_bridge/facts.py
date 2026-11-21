"""Fact lines the assistant may add to the memory: checked the same way everywhere.

A fact is one line under ``## Facts`` of an existing topic file (``profile.md``,
``projects/<name>.md``, ``courses/<name>.md``), tagged with its source and the
day: ``- [owner 2027-01-15] …`` when the owner said it, ``- [inferred
2027-01-15] …`` when the assistant concluded it (docs/memory-design.md 3.1,
the owner allowed inferred facts, always tagged).

Used by the coach's filing step, by the coach conversation's compaction and,
when it is built, by the consolidation proposal (only for the operations the
owner approved). Rules:

- only files that already exist inside the memory folder; no links leading out;
- one plain line of at most :data:`MAX_TEXT` characters, no id markers;
- appended only: nothing is replaced or removed, so an ``[owner …]`` fact is
  never overridden; a proposed fact whose text is already a fact of that file
  (owner or inferred, any day) is not added again.
"""

from __future__ import annotations

import re
from pathlib import Path

from . import memory

FILE = re.compile(r"profile\.md|(?:projects|courses)/[a-z0-9][a-z0-9-]*\.md")
SOURCES = ("owner", "inferred")
MAX_TEXT = 240
# A fact line: "- [owner 2027-01-15] text", also "[owner 2027-01-10, superseded 2027-01-20]".
FACT_LINE = re.compile(
    r"^- \[(owner|inferred|external:[^\] ,]+)(?: ([0-9-]+))?(, superseded [0-9-]+)?\] (.*)$"
)


def files(memory_dir: Path) -> list[str]:
    """The files a fact may go to: the profile and the existing project and course files."""
    found = ["profile.md"] if (memory_dir / "profile.md").is_file() else []
    for folder in ("projects", "courses"):
        for file in sorted((memory_dir / folder).glob("*.md")):
            if FILE.fullmatch(f"{folder}/{file.name}"):
                found.append(f"{folder}/{file.name}")
    return found


def _plain(value: object) -> str | None:
    if not isinstance(value, str) or not all(ch.isprintable() for ch in value):
        return None  # one plain line: a line break or a control character refuses it
    text = " ".join(value.split())
    if not text or len(text) > MAX_TEXT or "^t-" in text or "^e-" in text:
        return None
    return text


def _known(text: str) -> set[str]:
    """The texts of the fact lines a file has already (case and spacing ignored)."""
    return {
        " ".join(match.group(4).split()).casefold()
        for line in text.splitlines()
        if (match := FACT_LINE.match(line.strip()))
    }  # superseded ones too: a fact that stopped being true is not proposed again


def check(
    memory_dir: Path, proposals: object, limit: int, default_source: str = "inferred"
) -> list[tuple[str, str, str]]:
    """The proposed facts that may be written: ``(file, text, source)``, at most ``limit``.

    ``proposals`` is the model's list of ``{"file", "text", "source"?}``. A
    missing ``source`` is ``default_source``; anything that is not ``owner``
    counts as ``inferred``.
    """
    root = memory_dir.resolve()
    found: list[tuple[str, str, str]] = []
    seen: set[tuple[str, str]] = set()
    for item in proposals if isinstance(proposals, list) else []:
        if not isinstance(item, dict):
            continue
        file, text = item.get("file"), _plain(item.get("text"))
        source = "owner" if item.get("source", default_source) == "owner" else "inferred"
        if text is None or not isinstance(file, str) or not FILE.fullmatch(file):
            continue
        path = memory_dir / file
        if not path.is_file() or not path.resolve().is_relative_to(root):
            continue
        key = (file, " ".join(text.split()).casefold())
        if key in seen or key[1] in _known(memory.read_text(path)):
            continue  # already a fact there (an owner fact is never shadowed or replaced)
        seen.add(key)
        found.append((file, text, source))
        if len(found) == limit:
            break
    return found


def add_line(text: str, line: str) -> str:
    """``text`` with ``line`` as the last line under ``## Facts`` (heading added if missing)."""
    lines = text.splitlines()
    start = next((i for i, row in enumerate(lines) if row.strip() == "## Facts"), None)
    if start is None:
        return (text.rstrip("\n") + "\n\n" if text.strip() else "") + f"## Facts\n{line}\n"
    end = next(
        (i for i in range(start + 1, len(lines)) if re.match(r"#{1,2} ", lines[i])), len(lines)
    )
    while end > start + 1 and not lines[end - 1].strip():
        end -= 1
    lines.insert(end, line)
    return "\n".join(lines) + "\n"


def write(memory_dir: Path, checked: list[tuple[str, str, str]], day: str) -> list[str]:
    """Append the checked facts, tagged. The caller holds ``memory.LOCK`` and commits."""
    written = []
    for file, text, source in checked:
        path = memory_dir / file
        memory.write_text(path, add_line(memory.read_text(path), f"- [{source} {day}] {text}"))
        written.append(file)
    return list(dict.fromkeys(written))
