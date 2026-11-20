"""Tell the owner something through ``scripts/notify.sh``. Never fails the caller."""

from __future__ import annotations

import contextlib
import subprocess
from pathlib import Path

from .paths import repo_dir


def send(title: str, message: str, link: str = "", script: Path | None = None) -> None:
    """Texts passed here must not contain prompts, notes, titles of sessions or paths."""
    if script is None:
        repo = repo_dir()
        script = repo / "scripts" / "notify.sh" if repo else None
    if script is None or not script.is_file():
        return
    with contextlib.suppress(OSError, subprocess.SubprocessError):
        subprocess.run(  # noqa: S603
            [str(script), title, message, link], capture_output=True, timeout=10, check=False
        )
