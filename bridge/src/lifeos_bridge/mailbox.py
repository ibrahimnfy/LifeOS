"""The relay mailbox: a small tree in the owner's Realtime Database.

Phone and bridge leave sealed messages for each other here (docs/security.md).
The mailbox is untrusted: it only ever holds what ``e2e.seal`` produced.

Layout::

    /pair/<device id>/hello          the phone's first message during pairing
    /access/<device id>              the phone's relay account id (set by the bridge)
    /lifeos/<device id>/b/<key>      messages to the bridge
    /lifeos/<device id>/p/<key>      messages to the phone

Keys are ``<unix milliseconds>-<random>``, so they sort by time and old
entries can be swept. Values are base64 text. Each side deletes a message as
soon as it has taken it.

Everything is plain HTTPS through the standard library (system trust store,
verification always on): a streaming GET for new messages, with ordinary
polling as the fallback.
"""

from __future__ import annotations

import base64
import contextlib
import http.client
import json
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable

from . import google

TOKEN_LIFETIME = 45 * 60  # refresh well before the hour is over
POLL_SECONDS = 2.0
STREAM_FAILURES_BEFORE_POLLING = 3


class MailboxError(Exception):
    """The relay could not be used. Never contains addresses or content."""


def new_key(now: float | None = None) -> str:
    return f"{int((time.time() if now is None else now) * 1000):013d}-{secrets.token_hex(4)}"


def key_time(key: str) -> float | None:
    """When a key was made (seconds), or None if it is not one of ours."""
    head = key.split("-", 1)[0]
    return int(head) / 1000 if head.isdigit() and len(head) == 13 else None


def encode(blob: bytes) -> str:
    return base64.b64encode(blob).decode()


def decode(text: object) -> bytes | None:
    if not isinstance(text, str):
        return None
    try:
        return base64.b64decode(text, validate=True)
    except ValueError:
        return None


class Mailbox:
    """What the relay code needs from a mailbox; this one talks to the real database."""

    def __init__(self) -> None:
        self._base = google.read_project()["database_url"]
        self._token: str | None = None
        self._token_at = 0.0
        self._lock = threading.Lock()

    # -- access token ---------------------------------------------------------
    def token(self) -> str:
        with self._lock:
            if self._token is None or time.time() - self._token_at > TOKEN_LIFETIME:
                self._token = google.access_token(google.DATABASE_SCOPES)
                self._token_at = time.time()
            return self._token

    def _url(self, path: str, query: dict | None = None) -> str:
        url = f"{self._base}/{path.strip('/')}.json"
        return url + ("?" + urllib.parse.urlencode(query) if query else "")

    def _request(self, method: str, path: str, value: object = None, query: dict | None = None):
        data = None if value is None else json.dumps(value).encode()
        request = urllib.request.Request(  # noqa: S310
            self._url(path, query),
            data=data,
            method=method,
            headers={"Authorization": f"Bearer {self.token()}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310
                return json.loads(response.read() or b"null")
        except urllib.error.HTTPError as error:
            raise MailboxError(f"the relay answered with status {error.code}") from None
        except (urllib.error.URLError, OSError, ValueError) as error:
            raise MailboxError(f"the relay could not be reached ({type(error).__name__})") from None

    # -- plain operations -----------------------------------------------------
    def put(self, path: str, value: object) -> None:
        self._request("PUT", path, value)

    def get(self, path: str) -> object:
        return self._request("GET", path)

    def delete(self, path: str) -> None:
        self._request("DELETE", path)

    def keys(self, path: str) -> list[str]:
        """The names of the entries under ``path``, without downloading their content."""
        children = self._request("GET", path, query={"shallow": "true"})
        return sorted(children) if isinstance(children, dict) else []

    # -- listening ------------------------------------------------------------
    def listen(self, path: str, on_entry: Callable[[str, object], None], stop: threading.Event):
        """Call ``on_entry(key, value)`` for every child of ``path``, until ``stop`` is set.

        Streams when possible; after repeated stream failures it polls. The
        caller deletes entries it has handled, so nothing is delivered for ever.
        """
        failures = 0
        while not stop.is_set():
            if failures < STREAM_FAILURES_BEFORE_POLLING:
                started = time.monotonic()
                with contextlib.suppress(MailboxError, OSError, http.client.HTTPException):
                    self._stream(path, on_entry, stop)
                # A stream that lived for a while was fine; one that died at once counts.
                failures = 0 if time.monotonic() - started > 60 else failures + 1
                stop.wait(min(2**failures, 30))
            else:
                with contextlib.suppress(MailboxError):
                    self._poll_once(path, on_entry)
                stop.wait(POLL_SECONDS)
                # Try streaming again from time to time.
                if int(time.monotonic()) % 300 < POLL_SECONDS:
                    failures = 0

    def _poll_once(self, path: str, on_entry: Callable[[str, object], None]) -> None:
        children = self.get(path)
        if isinstance(children, dict):
            for key in sorted(children):
                on_entry(key, children[key])

    def _stream(self, path: str, on_entry: Callable[[str, object], None], stop: threading.Event):
        url = self._url(path)
        opened_at = time.time()
        for _ in range(4):  # the endpoint may redirect first
            parts = urllib.parse.urlsplit(url)
            connection = http.client.HTTPSConnection(parts.netloc, timeout=90)
            connection.request(
                "GET",
                parts.path + ("?" + parts.query if parts.query else ""),
                headers={"Accept": "text/event-stream", "Authorization": f"Bearer {self.token()}"},
            )
            response = connection.getresponse()
            if response.status in (301, 302, 307, 308):
                url = response.getheader("Location") or ""
                connection.close()
                continue
            break
        try:
            if response.status != 200:
                raise MailboxError(f"the relay answered with status {response.status}")
            event = ""
            # Reconnect before the access token runs out; keep-alives arrive every 30 s,
            # so a silent connection ends with the socket timeout.
            while not stop.is_set() and time.time() - opened_at < TOKEN_LIFETIME:
                line = response.readline()
                if not line:
                    return
                text = line.decode(errors="replace").rstrip("\r\n")
                if text.startswith("event:"):
                    event = text[6:].strip()
                elif text.startswith("data:"):
                    if event in ("cancel", "auth_revoked"):
                        return
                    if event in ("put", "patch"):
                        self._deliver(text[5:].strip(), on_entry)
        finally:
            connection.close()

    @staticmethod
    def _deliver(data: str, on_entry: Callable[[str, object], None]) -> None:
        try:
            change = json.loads(data)
        except ValueError:
            return
        if not isinstance(change, dict):
            return
        path, value = str(change.get("path") or "/").strip("/"), change.get("data")
        if value is None:
            return  # a deletion
        if not path:  # the first event carries everything that is there
            if isinstance(value, dict):
                for key in sorted(value):
                    on_entry(key, value[key])
        elif "/" not in path:
            on_entry(path, value)


class MemoryMailbox:
    """A mailbox in memory, for tests. It records everything that was ever written."""

    def __init__(self) -> None:
        self.tree: dict[str, object] = {}
        self.recorded: list[tuple[str, object]] = []
        self._lock = threading.Lock()

    def put(self, path: str, value: object) -> None:
        with self._lock:
            self.tree[path.strip("/")] = value
            self.recorded.append((path.strip("/"), value))

    def get(self, path: str) -> object:
        prefix = path.strip("/")
        with self._lock:
            if prefix in self.tree:
                return self.tree[prefix]
            children = {
                key[len(prefix) + 1 :]: value
                for key, value in self.tree.items()
                if key.startswith(prefix + "/") and "/" not in key[len(prefix) + 1 :]
            }
            return children or None

    def keys(self, path: str) -> list[str]:
        children = self.get(path)
        return sorted(children) if isinstance(children, dict) else []

    def delete(self, path: str) -> None:
        prefix = path.strip("/")
        with self._lock:
            for key in [k for k in self.tree if k == prefix or k.startswith(prefix + "/")]:
                del self.tree[key]

    def listen(self, path: str, on_entry: Callable[[str, object], None], stop: threading.Event):
        while not stop.is_set():
            children = self.get(path)
            if isinstance(children, dict):
                for key in sorted(children):
                    on_entry(key, children[key])
            stop.wait(0.02)
