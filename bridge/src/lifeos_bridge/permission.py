"""The change permission of a chat, and who wrote a message (#190).

**The per-chat settings that exist.** A message runs in one of two modes, sent with
it (``mode``): ``read_only`` (the default: nothing is changed) or ``capable`` ("may
change files": files may be changed and commands run inside the guardrails; deleting,
force, pushing, installing and sending still ask on an approval card). There is no
third mode: a session of the computer that is continued from the phone uses the same
two. The app keeps the choice per chat and it is off for every chat it opens. Besides
it a chat has a title, a pin, reply notifications, a model, an effort and the switch
for its folder's own skills (``session_meta``); none of these is a permission.

**Who may switch a chat to ``capable`` without the owner's tap** (owner's decisions
of the twelfth round): a chat opened by asking takes over the mode of the chat it
came from, and the command bar picks the mode from the owner's request. Both go
through :func:`granted`, which is clamped in code: a course's chats stay read-only,
and ``capable`` is offered only where :func:`capable.require_available` allows it,
the check every capable run passes anyway when its message arrives.

**Relayed messages.** A message the app sends by itself, in the words of a model
(the request a switch carried to a new chat, #187; a message the command bar sends
into a chat), is not a message the owner sent: its answer applies no update, changes
no folder, carries no request further and hands no permission on. The app says so
(``relayed``); the bridge also remembers the requests its own switches carried
(:func:`remember`), so a first message with exactly that text is relayed whatever the
app says. A restart forgets them; ``continued_from`` still keeps an update away.
"""

from __future__ import annotations

import hashlib
import threading
import time
from pathlib import Path

from . import capable, chats, moodle_sync
from .config import Settings

MODES = ("read_only", "capable")
KEEP_SECONDS = 24 * 3600
MAX_KEPT = 500

_relayed: dict[str, float] = {}
_lock = threading.Lock()


def in_course_folder(folder: Path) -> bool:
    """True for the folder of the course sync and everything inside it."""
    root = moodle_sync.download_dir()
    if root is None:
        return False
    try:
        return folder.resolve().is_relative_to(root.expanduser().resolve())
    except OSError:
        return True  # cannot tell: treated as a course folder, so read-only


def granted(
    settings: Settings, folder: Path | None, wanted: object, kind: str | None = None
) -> str:
    """``capable`` or ``read_only`` for a chat in ``folder`` (``None``: a blank chat)."""
    if wanted != "capable" or kind in ("course", "coach"):
        return "read_only"
    if folder is None or kind == "chat":
        # A blank chat has no folder yet: any folder name of the chat root stands for it.
        return capable.grant(settings, chats.root(settings) / "2000-01-01-000000", "capable")
    return capable.grant(settings, folder, "capable", course=in_course_folder(folder))


def _key(text: str) -> str:
    return hashlib.sha256(" ".join(text.split()).encode()).hexdigest()


def remember(text: str) -> None:
    """A switch of the bridge carries ``text`` to a new chat: that message is relayed."""
    now = time.monotonic()
    with _lock:
        for old in [key for key, expires in _relayed.items() if expires < now]:
            del _relayed[old]
        while len(_relayed) >= MAX_KEPT:
            del _relayed[next(iter(_relayed))]
        _relayed[_key(text)] = now + KEEP_SECONDS


def was_relayed(prompt: str) -> bool:
    """True when ``prompt`` is a request one of the bridge's switches carried (not used up:
    the same text sent again is still not the owner's own)."""
    with _lock:
        expires = _relayed.get(_key(prompt))
    return expires is not None and expires >= time.monotonic()
