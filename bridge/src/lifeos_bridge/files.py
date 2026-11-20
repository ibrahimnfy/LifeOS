"""Files the assistant offers to the owner (docs/chat-design.md, section 5).

The assistant can only *offer* a file, by writing a line ``[[file: path]]``
in its answer. The bridge checks the path and turns it into a card with a
random offer id. Nothing is read or sent until the owner asks for that
offer. Only regular files inside the allowed folders or the memory folder
are ever offered; hidden files and folders are not.
"""

from __future__ import annotations

import hashlib
import mimetypes
import re
import secrets
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

from . import audit
from .claude import ClaudeError
from .config import Settings

MARKER = re.compile(r"^[ \t]*\[\[file:\s*(.+?)\s*\]\][ \t]*$", re.MULTILINE)
OFFER_SECONDS = 15 * 60
MAX_OFFERS_PER_ANSWER = 10
CHUNK_BYTES = 700_000  # one sealed relay message carries one chunk
INSTRUCTIONS = (
    "To give the owner a file, write a line of its own in the form [[file: path]] (a path "
    "inside the current folder, or an absolute path). The app then shows a download card; "
    "offer only files the owner asked for, and do not paste a file's content instead."
)


@dataclass(frozen=True)
class Offer:
    id: str
    session_id: str
    path: Path
    name: str
    size: int
    type: str
    expires_at: float

    def card(self) -> dict:
        return {"offer": self.id, "name": self.name, "type": self.type, "size": self.size}


def refusal(settings: Settings, path: Path) -> str | None:
    """Why this file may not be sent, or None. ``path`` must already be resolved."""
    roots = settings.allowed_roots
    root = next((r for r in roots if path == r or path.is_relative_to(r)), None)
    if root is None:
        return "outside the allowed folders"
    if any(part.startswith(".") for part in path.relative_to(root).parts):
        return "hidden files and folders are not sent"
    if not path.is_file():
        return "not a regular file"
    limit = settings.max_file_mb * 1024 * 1024
    if path.stat().st_size > limit:
        return f"larger than the limit of {settings.max_file_mb} MB"
    return None


def _audit(action: str, session_id: str, detail: dict) -> None:
    """Offers and downloads are recorded with name and size, never content or path."""
    try:
        audit.record(action, detail, session_id=session_id)
    except audit.AuditUnavailable as exc:
        raise ClaudeError(
            "audit_unavailable", 503, "the audit log cannot be written; no file is offered"
        ) from exc


class Offers:
    """The offers of the running bridge. They live in memory and expire."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._offers: dict[str, Offer] = {}

    def _sweep(self, now: float) -> None:
        for offer_id in [k for k, offer in self._offers.items() if offer.expires_at < now]:
            del self._offers[offer_id]

    def from_answer(self, answer: str, session_id: str, cwd: Path) -> tuple[str, list[dict], list]:
        """Replace offer lines in an answer. Returns the text, the cards, and the refusals."""
        now = time.time()
        self._sweep(now)
        cards: list[dict] = []
        refused: list[dict] = []

        def handle(match: re.Match[str]) -> str:
            raw = match.group(1).strip().strip("`'\"")
            name = Path(raw).name[:200] or "file"
            if len(cards) + len(refused) >= MAX_OFFERS_PER_ANSWER:
                refused.append({"name": name, "reason": "too many files in one answer"})
                return ""
            try:
                path = (cwd / Path(raw).expanduser()).resolve()
                reason = refusal(self.settings, path)
            except (OSError, ValueError, RuntimeError):
                reason = "not a usable path"
            if reason is not None:
                refused.append({"name": name, "reason": reason})
                return ""
            offer = Offer(
                secrets.token_urlsafe(18),
                session_id,
                path,
                path.name,
                path.stat().st_size,
                mimetypes.guess_type(path.name)[0] or "application/octet-stream",
                now + OFFER_SECONDS,
            )
            _audit("FileOffer", session_id, {"name": offer.name, "size": offer.size})
            self._offers[offer.id] = offer
            cards.append(offer.card())
            return ""

        text = MARKER.sub(handle, answer)
        if cards or refused:
            text = re.sub(r"\n{3,}", "\n\n", text).strip()
        return text, cards, refused

    def for_path(self, path: Path, session_id: str, extra: dict | None = None) -> Offer:
        """Offer one file the bridge itself chose (a synced course file), after the same checks.

        ``session_id`` is the name the download must be asked with, as for
        any offer; ``extra`` goes into the audit record (a course id).
        Raises ``file_unavailable`` when the file may not be sent.
        """
        now = time.time()
        self._sweep(now)
        try:
            path = path.resolve()
            reason = refusal(self.settings, path)
            size = path.stat().st_size if reason is None else 0
        except (OSError, ValueError, RuntimeError):
            reason = "not a usable path"
        if reason is not None:
            raise ClaudeError("file_unavailable", 409, f"the file cannot be sent: {reason}")
        offer = Offer(
            secrets.token_urlsafe(18),
            session_id,
            path,
            path.name,
            size,
            mimetypes.guess_type(path.name)[0] or "application/octet-stream",
            now + OFFER_SECONDS,
        )
        _audit("FileOffer", session_id, {**(extra or {}), "name": offer.name, "size": offer.size})
        self._offers[offer.id] = offer
        return offer

    def take(self, offer_id: str, session_id: str) -> Offer:
        """The offer, checked again: it must be unexpired, of this session, and still sendable."""
        self._sweep(time.time())
        offer = self._offers.get(offer_id)
        if offer is None or offer.session_id != session_id:
            raise ClaudeError(
                "offer_not_found", 404, "this file offer does not exist or has expired"
            )
        try:
            current = offer.path.resolve()
            reason = (
                "the file changed" if current != offer.path else refusal(self.settings, current)
            )
            if reason is None and current.stat().st_size != offer.size:
                reason = "the file changed"
        except OSError:
            reason = "the file is gone"
        if reason is not None:
            raise ClaudeError("file_unavailable", 409, f"the file cannot be sent: {reason}")
        _audit("FileDownload", session_id, {"name": offer.name, "size": offer.size})
        return offer


def chunks(offer: Offer, size: int | None = None) -> Iterator[bytes]:
    """The file in pieces, for the local download and for the relay transfer."""
    with offer.path.open("rb") as handle:
        while block := handle.read(size or CHUNK_BYTES):
            yield block


def digest(offer: Offer) -> str:
    """SHA-256 of the whole file, sent with the last chunk so the phone can check it."""
    sha = hashlib.sha256()
    for block in chunks(offer, 1024 * 1024):
        sha.update(block)
    return sha.hexdigest()
