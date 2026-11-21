"""Skills and commands of a capable run: the owner's personal ones, and the folder's own.

- **Personal** (#162, owner's decision #149): the skills and commands of the
  owner's personal Claude Code folder are offered in every capable run, once
  the guardrail check passed with the personal-skill probes
  (``capable.personal_available``).
- **The session folder's own** (#91): only when the owner switched them on
  for the session (they can come from a cloned project), and only after the
  check with the skill probes (``capable.skills_available``).
- Read-only runs never see any skill.

How they reach Claude Code without loosening anything else: runs keep
``--restricted``, which ignores every settings file (user, project, local)
and so also every personal, project and plugin skill. The bridge copies the
two ``skills`` and ``commands`` folders into copies of its own and passes
each with ``--plugin-dir``: plugins that hold nothing but those files. No
hooks, MCP servers, agents or settings ever go into them. The settings JSON
of such a run also switches off shell lines in skills and Claude Code's
bundled skills.

**Where the copies are (#171).** In a folder of the bridge's own
(``Settings.skill_copies_dir``, by default ``~/.local/share/lifeos/skill-copies``),
outside the private settings folder, every allowed folder and the memory
folder. A run may read its own copies, so a skill's supporting files
(reference files, scripts) work: with the file tools (the copies are passed
with ``--add-dir``) and from sandboxed commands (``allowRead``). It can
never write there: the sandbox denies writes (``denyWrite``), the file tools
are denied by rule, and the files are read-only. So a run cannot change the
plugins it was started with, and nothing of the private settings folder
becomes readable. The bridge executes nothing of a copy; a script in it
runs only when the run starts it as an ordinary sandboxed command, through
the gate and the audit log (``capable.build_policy``).

**The copy is not byte for byte.** A skill's or command's frontmatter can
ask Claude Code for more than text: ``hooks`` (programs that Claude Code
starts outside the sandbox once the skill is invoked; seen running with the
real CLI), ``allowed-tools`` (tools approved without asking), a model, a
subagent. The bridge therefore writes the frontmatter of every Markdown file
in a copy itself (``sanitise``): only the name, the description, the
argument hints and the two "who may invoke it" flags are kept, each as a
plain quoted value. Everything else in the frontmatter is dropped, whatever
way it was spelled. The text below the frontmatter is unchanged.

In a run, the skills are named ``folder:<name>`` and ``personal:<name>``; a
message from the phone that starts with ``/`` must name one of them.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
import re
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import commands
from .claude import ClaudeError
from .config import Settings, config_dir, default_skill_copies_dir

PLUGIN = "folder"
PERSONAL = "personal"
LEGACY_DIR = "skill-snapshots"  # in the private settings folder, before #171
PERSONAL_SNAPSHOTS = "personal"  # sub-folder: one copy per content, shared by all sessions
PERSONAL_KEEP_SECONDS = 24 * 3600  # an unused older personal copy is removed after this
MAX_FILE_BYTES = 1024 * 1024
MAX_TOTAL_BYTES = 20 * 1024 * 1024
MAX_FILES = 500
# Part of every snapshot's name: copies made before the frontmatter was rewritten are
# never reused.
FORMAT = b"sanitised-2"
# Settings of a run with skills (merged into the capable settings JSON).
SETTINGS = {"disableSkillShellExecution": True, "disableBundledSkills": True}
DESCRIPTIONS = {
    PLUGIN: "The skills and commands of the session's folder",
    PERSONAL: "The owner's personal skills and commands",
}
_FIRST_WORD = re.compile(r"/([^\s]+)")
# Frontmatter the bridge writes into a copy; everything else is dropped.
_TEXT_KEYS = ("when_to_use", "argument-hint")
_FLAG_KEYS = ("disable-model-invocation", "user-invocable")
_ARGUMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_-]{0,63}")
_TRUE, _FALSE = ("true", "yes", "on"), ("false", "no", "off")


def _quoted(value: str) -> str:
    """A one-line value that every YAML reader takes as plain text."""
    return json.dumps(" ".join(value.replace("---", "- - -").split()), ensure_ascii=False)


def sanitise(data: bytes, name: str | None = None) -> bytes:
    """A skill or command file with a frontmatter written by the bridge.

    ``name``: the skill's folder name, written as its ``name`` (so the name a
    run offers is the one the listing shows). A file without frontmatter is
    returned unchanged.
    """
    text = data.decode("utf-8", errors="replace").lstrip("﻿ \t\r\n")
    if not text.startswith("---"):
        return data
    fields, body = commands.split_frontmatter(text)
    lines = ["---"]
    if name is not None:
        lines.append(f"name: {_quoted(name)}")
    # Always written, so the block the bridge wrote is never empty (a reader then
    # cannot take anything below it for the frontmatter). Claude Code's own fallback
    # is the first line of the text too.
    described = fields.get("description") or commands._first_text_line(body)
    lines.append(f"description: {_quoted(described[:1000])}")
    for key in _TEXT_KEYS:
        if fields.get(key):
            lines.append(f"{key}: {_quoted(fields[key])}")
    arguments = re.findall(r"[^\s,\[\]\"']+", fields.get("arguments", ""))
    if arguments and all(_ARGUMENT.fullmatch(word) for word in arguments):
        lines.append(f"arguments: {json.dumps(arguments)}")
    for key in _FLAG_KEYS:
        if key in fields:
            value = fields[key].strip().lower()
            if value in _TRUE or (key == "disable-model-invocation" and value not in _FALSE):
                lines.append(f"{key}: true")  # an unclear "not by the model" stays "not"
            elif value in _FALSE:
                lines.append(f"{key}: false")
    return ("\n".join([*lines, "---", body]) + "\n").encode()


def _files(base: Path) -> list[tuple[str, Path]]:
    """``(relative target, source)`` of every file of ``base`` that goes into a snapshot."""
    found: list[tuple[str, Path]] = []
    for kind, top in (("skills", base / "skills"), ("commands", base / "commands")):
        for current, dirs, names in os.walk(top, followlinks=False):
            dirs[:] = sorted(d for d in dirs if not d.startswith("."))  # no .trash, no .git
            for name in sorted(names):
                source = Path(current) / name
                if name.startswith(".") or source.is_symlink():
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


def _contents(base: Path) -> list[tuple[str, bytes, bool]]:
    """What a snapshot of ``base`` holds: the files within the limits, frontmatter rewritten.

    ``(relative path, content, whether the owner may execute the source)``.
    """
    total = 0
    contents: list[tuple[str, bytes, bool]] = []
    for relative, source in _files(base):
        try:
            data = source.read_bytes()
            executable = bool(source.stat().st_mode & 0o100)
        except OSError:
            continue
        if len(data) > MAX_FILE_BYTES:
            continue
        total += len(data)
        if total > MAX_TOTAL_BYTES:
            break
        if relative.lower().endswith(".md"):
            parts = relative.split("/")
            is_skill = parts[0] == "skills" and len(parts) == 3 and parts[2] == "SKILL.md"
            data = sanitise(data, parts[1] if is_skill else None)
            executable = False
        contents.append((relative, data, executable))
    return contents


def _digest(contents: list[tuple[str, bytes, bool]]) -> str:
    digest = hashlib.sha256(FORMAT)
    for relative, data, executable in contents:
        digest.update(relative.encode() + b"\0" + hashlib.sha256(data).digest())
        digest.update(b"x" if executable else b"-")
    return digest.hexdigest()[:16]


def _build(target: Path, plugin: str, contents: list[tuple[str, bytes, bool]]) -> None:
    """Write one copy: folders for the owner only, files read-only (a script keeps its
    execute bit, so the run can start it the way the skill says)."""
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    building = target.parent / f".{target.name}.{os.getpid()}"
    shutil.rmtree(building, ignore_errors=True)
    (building / ".claude-plugin").mkdir(parents=True, mode=0o700)
    manifest = {"name": plugin, "description": DESCRIPTIONS[plugin]}
    (building / ".claude-plugin" / "plugin.json").write_text(json.dumps(manifest))
    (building / ".claude-plugin" / "plugin.json").chmod(0o400)
    for relative, data, executable in contents:
        path = building / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(0o500 if executable else 0o400)
    for folder in (building, *(p for p in building.rglob("*") if p.is_dir())):
        folder.chmod(0o700)  # every folder, also the ones made on the way to a file
    try:
        os.replace(building, target)
    except OSError:
        shutil.rmtree(building, ignore_errors=True)
        if not target.is_dir():  # not made by another request at the same moment
            raise


def _folder_base(folder: Path, personal: Path | None) -> Path | None:
    """``<folder>/.claude``, unless that is the owner's personal folder."""
    try:
        base = folder.resolve() / ".claude"
        if base in (Path.home().resolve() / ".claude", personal and personal.resolve()):
            return None  # the personal folder is never a session's own
    except (OSError, RuntimeError):
        return None
    return base


def personal_names(base: Path) -> set[str]:
    return {entry["name"] for entry in commands.list_personal(base)}


def check_prompt(prompt: str, offered: dict[str, set[str]]) -> str:
    """A message starting with ``/`` must name an offered skill or command; namespaced here.

    ``offered``: plugin name → names, the plugin that wins a shared name first.
    """
    stripped = prompt.lstrip()
    match = _FIRST_WORD.match(stripped)
    if match is None:
        return prompt
    word = match.group(1)
    for plugin, names in offered.items():
        if word.startswith(plugin + ":") and word.removeprefix(plugin + ":") in names:
            return "/" + word + stripped[match.end() :]
    for plugin, names in offered.items():
        if word in names:
            return f"/{plugin}:{word}" + stripped[match.end() :]
    raise ClaudeError(
        "command_not_allowed",
        400,
        "a message that starts with / must name one of the offered skills or commands",
    )


def copies_root(root: Path | None = None) -> Path:
    """The folder of the copies. Copies of an older bridge, which lay in the private
    settings folder, are removed: they are never used again."""
    shutil.rmtree(config_dir() / LEGACY_DIR, ignore_errors=True)
    return default_skill_copies_dir() if root is None else root


def snapshot(
    folder: Path, session_id: str, personal: Path | None = None, root: Path | None = None
) -> Path:
    """The plugin copy of the folder's skills for one session (reused while unchanged)."""
    base = _folder_base(folder, personal)
    contents = _contents(base) if base is not None else []
    root = copies_root(root)
    target = root / f"{session_id}-{_digest(contents)}"
    if target.is_dir():
        return target
    _build(target, PLUGIN, contents)
    for old in root.glob(f"{session_id}-*"):
        if old != target:
            shutil.rmtree(old, ignore_errors=True)  # an older copy of this session
    return target


def personal_snapshot(base: Path, root: Path | None = None) -> Path | None:
    """The plugin copy of the owner's personal skills; ``None`` when there is none.

    One copy per content, shared by all sessions. Older copies stay for a day
    (a conversation that is still open may have been started with one).
    """
    try:
        contents = _contents(base.resolve())
    except (OSError, RuntimeError):
        return None
    if not contents:
        return None
    root = copies_root(root) / PERSONAL_SNAPSHOTS
    target = root / _digest(contents)
    if not target.is_dir():
        _build(target, PERSONAL, contents)
    now = time.time()
    with contextlib.suppress(OSError):
        os.utime(target, (now, now))  # in use
        for old in root.iterdir():
            if old != target and now - old.stat().st_mtime > PERSONAL_KEEP_SECONDS:
                shutil.rmtree(old, ignore_errors=True)
    return target


@dataclass(frozen=True)
class Offer:
    """The skills and commands one capable run is started with."""

    folder_dir: Path | None = None
    personal_dir: Path | None = None
    # Plugin name → names a message may start with; the folder's own win a shared name.
    names: dict[str, set[str]] = field(default_factory=dict)

    @property
    def any(self) -> bool:
        return self.folder_dir is not None or self.personal_dir is not None


def for_run(
    settings: Settings, folder: Path, session_id: str, *, folder_skills: bool, personal: bool
) -> Offer:
    """What a capable run in ``folder`` is offered. Used by the bridge and by the guardrail check.

    ``folder_skills``: the session's switch. ``personal``: whether the check
    with the personal-skill probes has passed. Copies are made here.
    """
    folder_dir = personal_dir = None
    names: dict[str, set[str]] = {}
    if folder_skills:
        folder_dir = snapshot(
            folder, session_id, settings.personal_claude_dir, settings.skill_copies_dir
        )
        names[PLUGIN] = {
            entry["name"] for entry in commands.list_commands(folder, settings.personal_claude_dir)
        }
    if personal:
        personal_dir = personal_snapshot(settings.personal_claude_dir, settings.skill_copies_dir)
        if personal_dir is not None:
            names[PERSONAL] = personal_names(settings.personal_claude_dir)
    return Offer(folder_dir, personal_dir, names)
