"""A capable session may use the skills and commands of its own folder (#91).

Only when the owner switched it on for the session, only in the capable
mode, and only after the guardrail check passed with the skill probes
(``capable.skills_available``). Read-only runs never see any skill.

How the folder's skills reach Claude Code without loosening anything else:
runs keep ``--restricted``, which ignores every settings file (user,
project, local) and so also every personal, project and plugin skill. The
bridge copies the folder's own ``.claude/skills`` and ``.claude/commands``
into a snapshot in the private settings folder and passes it with
``--plugin-dir``: a plugin that holds nothing but those files. No hooks, MCP
servers, agents or settings ever go into it. The snapshot sits where
sandboxed commands can neither read nor write, so a run cannot change the
plugin it was started with. The settings JSON of such a run also switches
off shell lines in skills and Claude Code's bundled skills.

In the session, the folder's skills are named ``folder:<name>``; a message
from the phone that starts with ``/`` must name one of them.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
from pathlib import Path

from . import commands
from .claude import ClaudeError
from .config import config_dir

PLUGIN = "folder"
SNAPSHOT_DIR = "skill-snapshots"
MAX_FILE_BYTES = 1024 * 1024
MAX_TOTAL_BYTES = 20 * 1024 * 1024
MAX_FILES = 500
# Settings of a run with skills (merged into the capable settings JSON).
SETTINGS = {"disableSkillShellExecution": True, "disableBundledSkills": True}
_FIRST_WORD = re.compile(r"/([^\s]+)")


def _files(folder: Path) -> list[tuple[str, Path]]:
    """``(relative target, source)`` of every file that goes into the snapshot."""
    try:
        base = folder.resolve() / ".claude"
        if base == Path.home().resolve() / ".claude":
            return []  # the personal folder is never a session's own
    except (OSError, RuntimeError):
        return []
    found: list[tuple[str, Path]] = []
    for kind, top in (("skills", base / "skills"), ("commands", base / "commands")):
        for current, dirs, names in os.walk(top, followlinks=False):
            dirs.sort()
            for name in sorted(names):
                source = Path(current) / name
                if source.is_symlink():
                    continue  # a link could lead outside the folder
                resolved = commands._inside(source, base)
                if resolved is None:
                    continue
                relative = source.relative_to(top).as_posix()
                if kind == "commands" and not name.endswith(".md"):
                    continue
                found.append((f"{kind}/{relative}", resolved))
                if len(found) >= MAX_FILES:
                    return found
    return found


def available_names(folder: Path) -> set[str]:
    """The names a message may start with (``/name`` or ``/folder:name``)."""
    return {entry["name"] for entry in commands.list_commands(folder)}


def check_prompt(prompt: str, folder: Path) -> str:
    """A message starting with ``/`` must name one of the folder's skills; namespaced here."""
    stripped = prompt.lstrip()
    match = _FIRST_WORD.match(stripped)
    if match is None:
        return prompt
    word = match.group(1)
    name = word.removeprefix(PLUGIN + ":")
    if name not in available_names(folder):
        raise ClaudeError(
            "command_not_allowed",
            400,
            "a message that starts with / must name one of this folder's skills or commands",
        )
    return f"/{PLUGIN}:{name}" + stripped[match.end() :]


def snapshot(folder: Path, session_id: str) -> Path:
    """The plugin copy of the folder's skills for one session (reused while unchanged)."""
    files = _files(folder)
    digest = hashlib.sha256()
    total = 0
    contents: list[tuple[str, bytes]] = []
    for relative, source in files:
        try:
            data = source.read_bytes()
        except OSError:
            continue
        if len(data) > MAX_FILE_BYTES:
            continue
        total += len(data)
        if total > MAX_TOTAL_BYTES:
            break
        contents.append((relative, data))
        digest.update(relative.encode() + b"\0" + hashlib.sha256(data).digest())
    root = config_dir() / SNAPSHOT_DIR
    target = root / f"{session_id}-{digest.hexdigest()[:16]}"
    if target.is_dir():
        return target
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    building = root / f".{target.name}.{os.getpid()}"
    shutil.rmtree(building, ignore_errors=True)
    (building / ".claude-plugin").mkdir(parents=True, mode=0o700)
    manifest = {"name": PLUGIN, "description": "The skills and commands of the session's folder"}
    (building / ".claude-plugin" / "plugin.json").write_text(json.dumps(manifest))
    for relative, data in contents:
        path = building / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    os.replace(building, target)
    for old in root.glob(f"{session_id}-*"):
        if old != target:
            shutil.rmtree(old, ignore_errors=True)  # an older copy of this session
    return target
