"""Limits on which folders may ever be allowed for Claude to work in.

These limits are fixed in code. They apply when ``allowed_paths`` is loaded,
when a path is added through the bridge, and before every capable run, so a
hand-edited file cannot widen them.
"""

from __future__ import annotations

import os
from pathlib import Path


def repo_dir() -> Path | None:
    """The LifeOS checkout this bridge runs from, when it runs from one."""
    candidate = Path(__file__).resolve().parents[3]
    return candidate if (candidate / "bridge" / "pyproject.toml").is_file() else None


def config_dir_path() -> Path:
    override = os.environ.get("LIFEOS_CONFIG_DIR")
    return (Path(override) if override else Path.home() / ".config" / "lifeos").resolve()


def _overlaps(a: Path, b: Path) -> bool:
    return a == b or a.is_relative_to(b) or b.is_relative_to(a)


def refusal_reason(path: Path) -> str | None:
    """Why ``path`` can never be an allowed folder, or ``None`` when it can."""
    path = path.expanduser()
    if not path.is_absolute():
        return "the path must be absolute"
    try:
        resolved = path.resolve(strict=True)  # follows symlinks
    except OSError:
        return "the folder does not exist"
    if not resolved.is_dir():
        return "the path is not a folder"
    home = Path.home().resolve()
    if resolved == home:
        return "the home folder itself cannot be allowed"
    if not resolved.is_relative_to(home):
        return "only folders inside the home folder can be allowed"
    if any(part.startswith(".") for part in resolved.relative_to(home).parts):
        return "hidden folders (settings, keys, credentials) cannot be allowed"
    repo = repo_dir()
    if repo is not None and _overlaps(resolved, repo):
        return "the LifeOS program folder cannot be allowed"
    if _overlaps(resolved, config_dir_path()):
        return "the private settings folder cannot be allowed"
    return None
