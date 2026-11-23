"""The LifeOS program folder as an allowed folder (#180).

The owner may list the program folder (the checkout this bridge runs from) in
``allowed_paths``, so that a chat on the phone can work on LifeOS itself.
Only the folder itself can be allowed, never a folder inside or around it
(``paths.refusal_reason``). Two kinds of protection come with it:

**What a run must never see or change** (every run that has the folder in
reach, read-only runs included; docs/permission-policy.md, section 9.12):

- hidden from commands and file tools, and never written: ``.tools/`` (the
  build tool caches and the Android user folder with its keys) and
  ``.claude/`` (other agents' git worktrees, Claude Code's own files);
- never written: ``bridge/.venv`` (the interpreter and packages the bridge,
  its gate and its tests run with), and the parts of ``.git`` that make git
  run programs or that belong to other worktrees (``hooks``, ``config``,
  ``config.worktree``, ``info``, ``worktrees``, ``modules``).

**Nothing the bridge runs by itself comes from files a run can change.** A
run may change the bridge's code in the program folder; that code runs only
after the owner approved an update (``updates.py``). Until then:

- every module of the bridge is imported at start (:func:`import_all`), so a
  later local import never loads a changed file;
- the gate program that every tool call of a capable run passes is a private
  copy taken at start, run with the standard library only
  (:func:`gate_base`); so is ``scripts/notify.sh`` (:func:`notify_script`);
- the automatic guardrail check, which runs the bridge's code in a process of
  its own, waits while the code differs from what the bridge started with
  (:func:`code_changed`).

What remains is written in the policy: a new start of the bridge (a crash, a
reboot, by hand) runs the code that is on disk.
"""

from __future__ import annotations

import contextlib
import hashlib
import importlib
import os
import pkgutil
import re
import shutil
import sys
from collections.abc import Iterable
from pathlib import Path

from . import paths
from .config import config_dir

# Neither readable nor writable for any run.
HIDDEN = (".tools", ".claude")
# Readable, never writable.
PROTECTED_FOLDERS = ("bridge/.venv", ".git/hooks", ".git/info", ".git/worktrees", ".git/modules")
PROTECTED_FILES = (".git/config", ".git/config.worktree")

PINNED_FOLDER = "pinned"
SHA = re.compile(r"[0-9a-f]{40}")

_pinned: dict[str, object] = {}


def program_dir(roots: Iterable[Path]) -> Path | None:
    """The program folder, when it is one of ``roots`` (the allowed folders)."""
    return paths.program_dir(roots)


# ---- rules for runs -------------------------------------------------------------------


def hidden_paths(program: Path) -> list[str]:
    return [str(program / name) for name in HIDDEN]


def unwritable_paths(program: Path) -> list[str]:
    names = (*HIDDEN, *PROTECTED_FOLDERS, *PROTECTED_FILES)
    return [str(program / name) for name in names]


def deny_rules(program: Path) -> list[str]:
    """Claude Code's own file tools: the second layer next to the sandbox."""
    root = "/" + str(program)  # permission-rule syntax: ``//`` is the filesystem root
    rules = [f"Read({root}/{name}/**)" for name in HIDDEN]
    rules += [f"Edit({root}/{name}/**)" for name in (*HIDDEN, *PROTECTED_FOLDERS)]
    rules += [f"Edit({root}/{name})" for name in PROTECTED_FILES]
    return rules


def read_only_settings(roots: Iterable[Path]) -> dict | None:
    """Settings for a run without the capable mode while the program folder is allowed.

    A read-only run has no shell and no write tool, but its file tools could read
    what is hidden from capable runs; these rules keep them out. ``None`` when the
    program folder is not allowed (nothing changes then).
    """
    program = program_dir(roots)
    return None if program is None else {"permissions": {"deny": deny_rules(program)}}


# ---- the commit the program folder is at --------------------------------------------------


def _git_dirs(repo: Path) -> tuple[Path, Path] | None:
    """The repository's own git folder and its common folder (they differ for a worktree)."""
    marker = repo / ".git"
    try:
        if marker.is_dir():
            return marker, marker
        text = marker.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        return None
    if not text.startswith("gitdir:"):
        return None
    own = Path(text.partition(":")[2].strip())
    own = own if own.is_absolute() else (repo / own)
    try:
        common = (own / (own / "commondir").read_text(encoding="utf-8").strip()).resolve()
    except OSError:
        common = own
    return own, common


def head_commit(repo: Path | None) -> str | None:
    """The commit ``HEAD`` names, read from git's files; no git program is run.

    Running git in the program folder before the owner approved would let a
    run's changes there run a program outside the sandbox.
    """
    if repo is None:
        return None
    dirs = _git_dirs(repo)
    if dirs is None:
        return None
    own, common = dirs
    try:
        head = (own / "HEAD").read_text(encoding="utf-8").strip()
    except OSError:
        return None
    if SHA.fullmatch(head):
        return head
    if not head.startswith("ref: "):
        return None
    ref = head[5:].strip()
    if ".." in ref.split("/"):
        return None
    for folder in (own, common):
        with contextlib.suppress(OSError):
            value = (folder / ref).read_text(encoding="utf-8").strip()
            if SHA.fullmatch(value):
                return value
    with contextlib.suppress(OSError):
        for line in (common / "packed-refs").read_text(encoding="utf-8").splitlines():
            value, _, name = line.partition(" ")
            if name.strip() == ref and SHA.fullmatch(value):
                return value
    return None


# ---- what the bridge runs by itself: pinned at start -------------------------------------


def package_dir() -> Path:
    return Path(__file__).resolve().parent


def code_fingerprint() -> str:
    """SHA-256 over the bridge's own source files (names and contents)."""
    digest = hashlib.sha256()
    root = package_dir()
    for path in sorted(root.rglob("*.py")):
        with contextlib.suppress(OSError):
            content = path.read_bytes()
            digest.update(str(path.relative_to(root)).encode() + b"\0")
            digest.update(hashlib.sha256(content).digest())
    return digest.hexdigest()


def import_all() -> list[str]:
    """Import every module of the bridge now; returns those that could not be imported."""
    import lifeos_bridge

    failed = []
    for info in pkgutil.iter_modules(lifeos_bridge.__path__):
        if info.name == "__main__":
            continue
        try:
            importlib.import_module(f"lifeos_bridge.{info.name}")
        except Exception:  # a module that cannot load would fail later as well
            failed.append(info.name)
    return failed


def _copy(source: Path, target: Path, mode: int) -> None:
    temporary = target.with_name(target.name + ".tmp")
    with contextlib.suppress(OSError):
        temporary.unlink()
    shutil.copyfile(source, temporary)
    os.chmod(temporary, mode)
    temporary.replace(target)


def pin() -> None:
    """At bridge start: the code the bridge runs by itself, as it is now.

    Copies of the gate program and of ``scripts/notify.sh`` go into the private
    settings folder, out of every run's reach; the fingerprint of the source
    files is kept to tell later whether they changed.
    """
    import_all()
    _pinned.clear()
    _pinned["fingerprint"] = code_fingerprint()
    _pinned["commit"] = head_commit(paths.repo_dir())
    folder = config_dir() / PINNED_FOLDER
    try:
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(folder, 0o700)
        gate = folder / "gate.py"
        _copy(package_dir() / "gate.py", gate, 0o600)
        _pinned["gate"] = gate
        repo = paths.repo_dir()
        script = repo / "scripts" / "notify.sh" if repo is not None else None
        if script is not None and script.is_file():
            pinned_script = folder / "notify.sh"
            _copy(script, pinned_script, 0o700)
            _pinned["notify"] = pinned_script
    except OSError:
        pass  # without a copy the gate and the notifications run from the program folder


def gate_base() -> list[str] | None:
    """The command of the pinned gate (standard library only), or ``None`` before :func:`pin`."""
    gate = _pinned.get("gate")
    if not isinstance(gate, Path) or not gate.is_file():
        return None
    # -I: no environment variables, no user site, no script folder; -S: no site
    # packages and no ``.pth`` files at all (the gate needs the standard library only).
    return [os.path.realpath(sys.executable), "-I", "-S", str(gate)]


def notify_script() -> Path | None:
    script = _pinned.get("notify")
    return script if isinstance(script, Path) and script.is_file() else None


def running_commit() -> str | None:
    """The commit the program folder was at when this bridge started."""
    commit = _pinned.get("commit")
    return commit if isinstance(commit, str) else None


def code_changed() -> bool:
    """True when the bridge's source files differ from those it started with."""
    start = _pinned.get("fingerprint")
    return isinstance(start, str) and start != code_fingerprint()
