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
    sealed_title: str = "",
) -> None:
    """Texts passed here must not contain prompts, notes, titles of sessions or paths.

    With ``event`` the paired phones are woken too, by a sealed hint without
    readable content (``push.send_hint``); ``ref`` names the item it concerns,
    ``opens`` the screen a tap opens, ``counts`` what changed (sealed only), and
    ``sealed_title`` a notice's title, shown only inside the unlocked app.
    """
    if event:
        from . import push  # local import: push needs the device store

        extra = {"t": sealed_title} if sealed_title else {}
        with contextlib.suppress(Exception):
            push.send_hint(event, ref, opens=opens, counts=counts, **extra)
    script = script or default_script()
    if script is None or not script.is_file():
        return
    # The phones were woken above (or need no push): the script must not wake them a
    # second time through /push/notify.
    environment = {**os.environ, "LIFEOS_NOTIFY_PUSH": "off"} if event else None
    with contextlib.suppress(OSError, subprocess.SubprocessError):
        subprocess.run(  # noqa: S603
            [str(script), title, message, link],
            capture_output=True, timeout=10, check=False, env=environment,
        )  # fmt: skip
