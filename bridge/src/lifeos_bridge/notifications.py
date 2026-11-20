"""Tell the owner something through ``scripts/notify.sh``. Never fails the caller.

Every notification of the bridge goes through :func:`send`. The script is
``scripts/notify.sh`` of this repository unless ``LIFEOS_NOTIFY_SCRIPT``
names another one; the value ``off`` switches notifications off. The test
suite sets ``off`` for every test, so tests can never notify the owner.
"""

from __future__ import annotations

import contextlib
import os
import subprocess
from pathlib import Path

from .paths import repo_dir

SCRIPT_VAR = "LIFEOS_NOTIFY_SCRIPT"


def default_script() -> Path | None:
    configured = os.environ.get(SCRIPT_VAR, "").strip()
    if configured.lower() == "off":
        return None
    if configured:
        return Path(configured)
    repo = repo_dir()
    return repo / "scripts" / "notify.sh" if repo else None


def send(
    title: str,
    message: str,
    link: str = "",
    script: Path | None = None,
    *,
    event: str | None = None,
    ref: str = "",
    opens: str | None = None,
    counts: dict | None = None,
) -> None:
    """Texts passed here must not contain prompts, notes, titles of sessions or paths.

    With ``event`` the paired phones are woken too, by a sealed hint without
    readable content (``push.send_hint``); ``ref`` names the item it concerns,
    ``opens`` the screen a tap opens, ``counts`` what changed (sealed only).
    """
    if event:
        from . import push  # local import: push needs the device store

        with contextlib.suppress(Exception):
            push.send_hint(event, ref, opens=opens, counts=counts)
    script = script or default_script()
    if script is None or not script.is_file():
        return
    with contextlib.suppress(OSError, subprocess.SubprocessError):
        subprocess.run(  # noqa: S603
            [str(script), title, message, link], capture_output=True, timeout=10, check=False
        )
