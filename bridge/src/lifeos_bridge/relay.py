# ruff: noqa: E501
"""The relay path: paired phones reach the bridge through the mailbox.

For every paired phone the bridge listens for sealed messages, applies the
checks of docs/security.md, and answers requests by calling its own local
API, so a request from the phone does exactly what the same request does on
the computer. Answers go back sealed, in pieces small enough for the
mailbox. The bridge only ever makes outbound connections.
"""

from __future__ import annotations

import collections
import contextlib
import json
import re
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Generator, Iterator
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import audit, devices, e2e, files, google, mailbox, push
from .claude import ClaudeError
from .config import Settings

# What a phone may call. Everything else answers 404 without touching the local API.
ALLOWED_PREFIXES = (
    "/ask", "/sessions", "/reminders", "/notes", "/tasks", "/events",
    "/agenda", "/coach", "/context-map", "/allowed-paths", "/models", "/moodle", "/courses", "/whoami",
    "/folders", "/activity", "/app", "/memory", "/projects", "/command",
)  # fmt: skip
ALLOWED_METHODS = ("GET", "POST", "PATCH", "PUT", "DELETE")
PART_CHARS = 600_000  # a sealed message stays well below the mailbox and message limits
DELTA_SECONDS = 0.4
DELTA_CHARS = 2000
RESCAN_SECONDS = 5.0
SWEEP_SECONDS = 600.0
KEEP_SECONDS = 15 * 60  # nothing stays in the mailbox longer than this
MAX_WORKERS = 4
# A stream the phone stopped reading frees its worker: when this many of its chunks
# wait unread in the mailbox and the oldest is older than STREAM_STALL_SECONDS.
STREAM_WINDOW = 4
STREAM_STALL_SECONDS = 45.0
STREAM_CHECK_SECONDS = 5.0  # how often the mailbox is looked at while a stream runs
DETACH_PATH = "/relay/detach"  # the phone's own "I stopped reading this stream"
EARLY_MAX = 256  # detaches remembered for requests still waiting for a worker (#158)
EARLY_SECONDS = 600.0
FILE_WINDOW = 3  # at most this many file chunks wait in the mailbox
FILE_STALL_SECONDS = 120.0  # give up when the phone stops taking chunks
FILE_POLL_SECONDS = 0.3

# One local call: (status, kind, items). kind "json": one object; kind "sse": (event, data) pairs.
LocalCall = Callable[[str, str, dict, object], tuple[int, str, Iterator]]


def local_api(settings: Settings) -> LocalCall:
    """Calls the bridge's own HTTP API on the loopback interface."""

    def call(method: str, path: str, query: dict, body: object) -> tuple[int, str, Iterator]:
        url = f"http://{settings.host}:{settings.port}{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(  # noqa: S310
            url,
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {settings.token}",
                "Content-Type": "application/json",
            },
        )
        try:
            response = urllib.request.urlopen(request, timeout=settings.ask_timeout + 60)  # noqa: S310
        except urllib.error.HTTPError as error:
            response = error
        status = response.status if hasattr(response, "status") else response.code
        if "text/event-stream" in (response.headers.get("Content-Type") or ""):
            return status, "sse", _sse_events(response)
        try:
            return status, "json", iter([json.loads(response.read() or b"null")])
        finally:
            response.close()

    return call


class SseEvents:
    """The (event, data) pairs of a server-sent event response.

    ``close()`` may be called from another thread: it ends a read that is
    waiting (the stream of a phone that went away), so the worker is freed.
    """

    def __init__(self, response) -> None:
        self.response = response
        self.closed = threading.Event()

    def close(self) -> None:
        self.closed.set()
        # Shutting the socket down wakes a read that waits in another thread; a plain
        # close would only take effect at the next keep-alive.
        raw = getattr(getattr(self.response, "fp", None), "raw", None)
        sock = getattr(raw, "_sock", None)
        if sock is not None:
            with contextlib.suppress(OSError):
                sock.shutdown(socket.SHUT_RDWR)

    def __iter__(self) -> Iterator[tuple[str, object]]:
        event = ""
        try:
            for raw in self.response:
                if self.closed.is_set():
                    return
                line = raw.decode(errors="replace").rstrip("\r\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    try:
                        yield event, json.loads(line[5:].strip())
                    except ValueError:
                        continue
        except (OSError, ValueError, AttributeError):
            if not self.closed.is_set():
                raise
        finally:
            with contextlib.suppress(Exception):
                self.response.close()


def _sse_events(response) -> SseEvents:
    return SseEvents(response)


REFUSED_ROUTE = "(refused)"
UNKNOWN_ROUTE = "(unknown)"
# (compiled path pattern, template) of the bridge's routes, e.g. "/notes/{note_id}".
RouteTable = list[tuple[re.Pattern[str], str]]


def route_table(app) -> RouteTable:
    """The route templates of the running bridge, to name requests without their ids."""
    return [
        (route.path_regex, route.path)
        for route in getattr(app, "routes", [])
        if getattr(route, "path_regex", None) is not None
    ]


def route_template(routes: RouteTable, method: object, path: object) -> str:
    """The template a request matched: never the path itself, which may carry ids."""
    if path in ("/push/token", DETACH_PATH):  # answered by the relay itself
        return path
    is_file = isinstance(path, str) and path.startswith("/files/") and "/" not in path[7:]
    if not is_file and not allowed(method, path):
        return REFUSED_ROUTE
    for pattern, template in routes:
        if pattern.match(str(path)):
            return template
    return UNKNOWN_ROUTE


def allowed(method: object, path: object) -> bool:
    return (
        method in ALLOWED_METHODS
        and isinstance(path, str)
        and path.startswith("/")
        and ".." not in path
        and "?" not in path
        and any(path == prefix or path.startswith(prefix + "/") for prefix in ALLOWED_PREFIXES)
    )


class Stream:
    """One answer the relay is sending to a phone, watched for being abandoned."""

    def __init__(self, device_id: bytes, rid: str, path: str) -> None:
        self.device_id = device_id
        self.rid = rid
        self.path = path
        # Following a run (GET /sessions/{id}/live) is read-only: it may simply be closed.
        self.follower = path.startswith("/sessions/") and path.endswith("/live")
        self.events: object | None = None  # the local server-sent event reader
        self.answer: Generator | None = None  # the relay's messages made from it
        self.detached = threading.Event()
        self.stalled = False  # let go because nobody read it (not by a detach)
        self.written: list[str] = []
        self.checked = time.monotonic()

    def sent(self, key: str) -> None:
        self.written.append(key)


class Relay:
    def __init__(
        self,
        box,
        call_local: LocalCall,
        offers: files.Offers | None = None,
        routes: RouteTable | None = None,
    ) -> None:
        self.box = box
        self.call_local = call_local
        self.offers = offers
        self.routes = routes or []
        self._answering = threading.local()  # the status of the request this thread answers
        self.receiver = devices.Receiver()
        self.stop = threading.Event()
        self._listeners: dict[str, threading.Event] = {}
        self._send_locks: dict[bytes, threading.Lock] = {}
        self._streams: dict[tuple[bytes, str], Stream] = {}  # answers being sent, by request
        self._streams_lock = threading.Lock()
        self._waiting: set[tuple[bytes, str]] = set()  # requests no worker has picked up yet
        self._early: dict[tuple[bytes, str], float] = {}  # detached while waiting: until when
        self._pool = ThreadPoolExecutor(max_workers=MAX_WORKERS, thread_name_prefix="relay")
        self._thread: threading.Thread | None = None

    # -- sending ----------------------------------------------------------------
    def send(self, device_id: bytes, kind: int, request_id: str, payload: object) -> str:
        """Seal one message for the phone and put it into its mailbox. Returns the entry's key."""
        # Numbering and writing are one step per phone: messages reach the mailbox in
        # counter order, so the phone never sees a lower counter after a higher one.
        with self._send_locks.setdefault(device_id, threading.Lock()):
            blob = devices.seal_to(device_id, kind, request_id, payload)
            key = mailbox.new_key()
            self.box.put(f"lifeos/{device_id.hex()}/p/{key}", mailbox.encode(blob))
        if kind in (e2e.END, e2e.ERROR) and isinstance(payload, dict):
            self._answering.status = payload.get("status")
        return key

    # -- receiving --------------------------------------------------------------
    def on_entry(self, device_hex: str, key: str, value: object) -> None:
        """One entry of a phone's to-bridge mailbox: check it, take it out, act on it."""
        blob = mailbox.decode(value)
        message = self.receiver.accept(blob) if blob else None
        # If the delete fails, a second delivery is rejected as a repeat.
        with contextlib.suppress(mailbox.MailboxError):
            self.box.delete(f"lifeos/{device_hex}/b/{key}")
        if message is None or message.header.kind != e2e.REQUEST:
            return
        payload = message.payload if isinstance(message.payload, dict) else {}
        if payload.get("path") == DETACH_PATH:
            # At once, not behind the streams it frees (they may hold every worker).
            self._answer(message)
            return
        with self._streams_lock:
            self._waiting.add((message.header.device_id, message.request_id))
        self._pool.submit(self._answer, message)

    def _answer(self, message: e2e.Message) -> None:
        """Answer one request, then record it in the audit log (never its body or query)."""
        self._answering.status = None
        try:
            self._handle(message)
        finally:
            with self._streams_lock:  # also for the requests that are not streams
                self._waiting.discard((message.header.device_id, message.request_id))
            request = message.payload if isinstance(message.payload, dict) else {}
            method = request.get("m")
            # Best effort: the request has been answered already; a full disk must
            # not turn every answer into a failure.
            audit.try_record(
                "RelayRequest",
                {
                    "device": message.header.device_id.hex(),
                    "method": method if method in ALLOWED_METHODS else "(other)",
                    "route": route_template(self.routes, method, request.get("path")),
                    "status": self._answering.status,
                },
            )

    def _handle(self, message: e2e.Message) -> None:
        device_id, rid = message.header.device_id, message.request_id
        request = message.payload
        if isinstance(request, dict) and request.get("path") == "/push/token":
            body = request.get("body") if isinstance(request.get("body"), dict) else {}
            done = request.get("m") == "POST" and push.register(device_id, body.get("token"))
            with contextlib.suppress(Exception):
                self.send(
                    device_id,
                    e2e.END if done else e2e.ERROR,
                    rid,
                    {"status": 200, "body": {"registered": True}}
                    if done
                    else {"status": 400, "error": "bad_request"},
                )
            return
        if isinstance(request, dict) and request.get("path") == DETACH_PATH:
            body = request.get("body") if isinstance(request.get("body"), dict) else {}
            found = request.get("m") == "POST" and self.detach(device_id, str(body.get("r") or ""))
            with contextlib.suppress(Exception):
                self.send(device_id, e2e.END, rid, {"status": 200, "body": {"detached": found}})
            return
        path = str(request.get("path") if isinstance(request, dict) else "")
        stream = Stream(device_id, rid, path)
        # In one step: no longer waiting for a worker, and either detached already or
        # known as a stream (so a detach never falls between the two).
        with self._streams_lock:
            self._waiting.discard((device_id, rid))
            early = self._early.pop((device_id, rid), 0.0) > time.monotonic()
            if not early:
                self._streams[(device_id, rid)] = stream
        if early:
            self._start_detached(stream, message.payload)
            return
        if path.startswith("/files/"):
            try:
                with contextlib.suppress(Exception):
                    self.send_file(device_id, rid, request, stream)
            finally:
                with self._streams_lock:
                    self._streams.pop((device_id, rid), None)
            return
        answer = stream.answer = self.dispatch(message.payload, stream)
        try:
            for kind, payload in answer:
                stream.sent(self.send(device_id, kind, rid, payload))
                if kind == e2e.CHUNK and self._abandoned(stream):
                    break
        except devices.PairingError:
            return  # revoked meanwhile
        except Exception:  # never let one request take the relay down
            if not stream.detached.is_set():
                with contextlib.suppress(Exception):
                    self.send(device_id, e2e.ERROR, rid, {"status": 502, "error": "relay_failed"})
        finally:
            with self._streams_lock:
                self._streams.pop((device_id, rid), None)
            if stream.detached.is_set():
                self._let_go(stream)
            if stream.stalled:
                # Not asked for by the phone: one last word, so a phone that comes back
                # knows to follow the run again (GET /sessions/{id}/live).
                with contextlib.suppress(Exception):
                    self.send(
                        device_id, e2e.ERROR, rid, {"status": 504, "error": "stream_abandoned"}
                    )

    # -- streams the phone stopped reading ------------------------------------------
    def detach(self, device_id: bytes, rid: str) -> bool:
        """The phone stopped reading a stream (locked, another session): free its worker.

        A ``/live`` follower is read-only, so its connection is closed at once. A
        chat stream is never cut (that would stop the run): the worker hands it to
        a reader that lets the run finish. A file transfer stops before its next
        chunk. A request still waiting for a worker is remembered (#158).
        """
        key = (device_id, rid)
        with self._streams_lock:
            stream = self._streams.get(key)
            if stream is None:
                if key not in self._waiting:
                    return False  # unknown, or over already
                # Still waiting for a free worker (#158): remembered for when it starts.
                now = time.monotonic()
                self._early = {k: t for k, t in self._early.items() if t > now}
                while len(self._early) >= EARLY_MAX:
                    self._early.pop(next(iter(self._early)))
                self._early[key] = now + EARLY_SECONDS
                return True
        stream.detached.set()
        if stream.follower and stream.events is not None:
            close = getattr(stream.events, "close", None)
            if close is not None:
                close()  # wakes the worker that waits for the next event
        return True

    def _start_detached(self, stream: Stream, request: object) -> None:
        """A request the phone detached before a worker picked it up (#158).

        Following a run and fetching a file only read: they are not started. Anything
        else (a chat message) still runs, since the owner's message is never dropped,
        but in a thread of its own that reads its answer to the end and sends nothing,
        so it holds no worker.
        """
        if stream.follower or stream.path.startswith("/files/"):
            return

        def drain() -> None:
            with contextlib.suppress(Exception):
                collections.deque(self.dispatch(request), maxlen=0)

        threading.Thread(target=drain, daemon=True, name="relay-drain").start()

    def _abandoned(self, stream: Stream) -> bool:
        """Detached by the phone, or its chunks wait unread in the mailbox for too long."""
        if stream.detached.is_set():
            return True
        now = time.monotonic()
        if not stream.written or now - stream.checked < STREAM_CHECK_SECONDS:
            return False
        stream.checked = now
        try:
            waiting = set(self.box.keys(f"lifeos/{stream.device_id.hex()}/p"))
        except mailbox.MailboxError:
            return False
        stream.written = [key for key in stream.written if key in waiting]
        oldest = mailbox.key_time(stream.written[0]) if stream.written else None
        if (
            len(stream.written) >= STREAM_WINDOW
            and oldest is not None
            and time.time() - oldest > STREAM_STALL_SECONDS
        ):
            stream.stalled = True
            stream.detached.set()
            return True
        return False

    def _let_go(self, stream: Stream) -> None:
        """After a stream was abandoned: nothing of it stays unread, the run goes on."""
        side = f"lifeos/{stream.device_id.hex()}/p"
        for key in stream.written:
            with contextlib.suppress(mailbox.MailboxError):
                self.box.delete(f"{side}/{key}")
        if stream.events is None or stream.answer is None:
            return
        if stream.follower:
            close = getattr(stream.events, "close", None)
            if close is not None:
                close()
            stream.answer.close()
            return
        # A chat stream: the same reader goes on to its end (dropping it would close the
        # connection and stop the run), its messages are no longer sent.
        answer = stream.answer
        threading.Thread(
            target=lambda: collections.deque(answer, maxlen=0), daemon=True, name="relay-drain"
        ).start()

    def send_file(
        self, device_id: bytes, rid: str, request: dict, stream: Stream | None = None
    ) -> None:
        """Send an offered file in sealed chunks; only a few wait in the mailbox at a time.

        The phone takes a chunk out of the mailbox as soon as it has it, which
        is the acknowledgement. If it stops taking chunks, the transfer is
        abandoned and what is still in the mailbox is removed. ``q.from``
        continues an interrupted transfer at that chunk.
        """
        query = request.get("q") if isinstance(request.get("q"), dict) else {}
        offer_id = str(request.get("path"))[len("/files/") :]
        try:
            if self.offers is None or request.get("m") != "GET" or "/" in offer_id:
                raise ClaudeError("offer_not_found", 404, "")
            offer = self.offers.take(offer_id, str(query.get("session_id") or ""))
            first = int(query.get("from") or 0)
        except ClaudeError as error:
            self.send(device_id, e2e.ERROR, rid, {"status": error.status, "error": error.code})
            return
        except (TypeError, ValueError):
            self.send(device_id, e2e.ERROR, rid, {"status": 400, "error": "bad_request"})
            return
        side = f"lifeos/{device_id.hex()}/p"
        total = max(1, -(-offer.size // files.CHUNK_BYTES))
        written: list[str] = []

        def cancelled() -> bool:  # the phone detached this request (#158)
            return stream is not None and stream.detached.is_set()

        try:
            for index, block in enumerate(files.chunks(offer)):
                if index < first:
                    continue
                waited = 0.0
                if cancelled():
                    raise TimeoutError
                while len(set(self.box.keys(side)) & set(written)) >= FILE_WINDOW:
                    if waited >= FILE_STALL_SECONDS or self.stop.is_set() or cancelled():
                        raise TimeoutError
                    time.sleep(FILE_POLL_SECONDS)
                    waited += FILE_POLL_SECONDS
                payload = {"i": index, "n": total, "b64": mailbox.encode(block)}
                written.append(self.send(device_id, e2e.CHUNK, rid, payload))
            done = {"status": 200, "file": {"size": offer.size, "chunks": total,
                                            "sha256": files.digest(offer)}}  # fmt: skip
            self.send(device_id, e2e.END, rid, done)
        except (TimeoutError, OSError, mailbox.MailboxError, devices.PairingError):
            # Nothing of an abandoned transfer stays in the mailbox.
            for key in set(self.box.keys(side)) & set(written):
                with contextlib.suppress(mailbox.MailboxError):
                    self.box.delete(f"{side}/{key}")
            if not cancelled():  # a phone that cancelled needs no word about it
                with contextlib.suppress(Exception):
                    self.send(
                        device_id, e2e.ERROR, rid, {"status": 504, "error": "transfer_abandoned"}
                    )

    def dispatch(
        self, request: object, stream: Stream | None = None
    ) -> Iterator[tuple[int, object]]:
        """Turn one request from the phone into the messages of its answer."""
        if not isinstance(request, dict) or not allowed(request.get("m"), request.get("path")):
            yield e2e.ERROR, {"status": 404, "error": "not_found"}
            return
        query = request.get("q") if isinstance(request.get("q"), dict) else {}
        status, kind, items = self.call_local(
            request["m"], request["path"], query, request.get("body")
        )
        if kind == "sse":
            if stream is not None:
                stream.events = items
            yield from self._stream(status, items)
            return
        body = next(iter(items), None)
        text = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
        if len(text) <= PART_CHARS:
            yield e2e.END, {"status": status, "body": body}
            return
        parts = [text[i : i + PART_CHARS] for i in range(0, len(text), PART_CHARS)]
        for index, part in enumerate(parts):
            yield e2e.CHUNK, {"i": index, "part": part}
        yield e2e.END, {"status": status, "parts": len(parts)}

    @staticmethod
    def _stream(status: int, events: Iterator[tuple[str, object]]) -> Iterator[tuple[int, object]]:
        """Server-sent events as chunks; text deltas are gathered so one answer is tens of messages."""
        text, since = "", time.monotonic()
        for event, data in events:
            if event == "delta" and isinstance(data, dict):
                text += str(data.get("text") or "")
                if len(text) >= DELTA_CHARS or time.monotonic() - since >= DELTA_SECONDS:
                    yield e2e.CHUNK, {"event": "delta", "data": {"text": text}}
                    text, since = "", time.monotonic()
                continue
            if text:
                yield e2e.CHUNK, {"event": "delta", "data": {"text": text}}
                text, since = "", time.monotonic()
            yield e2e.CHUNK, {"event": event, "data": data}
        if text:
            yield e2e.CHUNK, {"event": "delta", "data": {"text": text}}
        yield e2e.END, {"status": status, "stream": True}

    # -- life cycle ---------------------------------------------------------------
    def rescan(self) -> None:
        """Listen for every paired phone; stop listening for revoked ones and clean up."""
        active = {d.id.hex() for d in devices.list_devices() if not d.revoked}
        for device_hex in active - set(self._listeners):
            stop = threading.Event()
            self._listeners[device_hex] = stop
            threading.Thread(
                target=self.box.listen,
                args=(
                    f"lifeos/{device_hex}/b",
                    lambda k, v, d=device_hex: self.on_entry(d, k, v),
                    stop,
                ),
                daemon=True,
                name=f"relay-listen-{device_hex[:8]}",
            ).start()
        for device_hex in set(self._listeners) - active:
            self._listeners.pop(device_hex).set()
            for path in (f"access/{device_hex}", f"lifeos/{device_hex}"):
                with contextlib.suppress(mailbox.MailboxError):
                    self.box.delete(path)

    def sweep(self, now: float | None = None) -> int:
        """Delete mailbox entries older than the retention bound. Returns how many."""
        now = time.time() if now is None else now
        removed = 0
        for device_hex in list(self._listeners):
            for side in ("b", "p"):
                try:
                    keys = self.box.keys(f"lifeos/{device_hex}/{side}")
                except mailbox.MailboxError:
                    continue
                for key in keys:
                    made = mailbox.key_time(key)
                    if made is None or now - made > KEEP_SECONDS:
                        try:
                            self.box.delete(f"lifeos/{device_hex}/{side}/{key}")
                            removed += 1
                        except mailbox.MailboxError:
                            pass
        return removed

    def _run(self) -> None:
        last_sweep = 0.0
        while not self.stop.is_set():
            try:
                self.rescan()
                if time.monotonic() - last_sweep > SWEEP_SECONDS:
                    self.sweep()
                    devices.sweep_pending()
                    last_sweep = time.monotonic()
            except Exception:  # the relay must survive anything  # noqa: S110
                pass
            self.stop.wait(RESCAN_SECONDS)
        for stop in self._listeners.values():
            stop.set()

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, daemon=True, name="relay")
        self._thread.start()

    def close(self) -> None:
        self.stop.set()
        self._pool.shutdown(wait=False, cancel_futures=True)


def configured() -> bool:
    folder: Path = google.firebase_dir()
    return (folder / "google-services.json").is_file() and (
        folder / "service-account.json"
    ).is_file()


def start_if_configured(
    settings: Settings, offers: files.Offers | None = None, routes: RouteTable | None = None
) -> Relay | None:
    """Start the relay when the owner's Firebase files exist; otherwise the bridge is local only."""
    if not configured():
        return None
    try:
        relay = Relay(mailbox.Mailbox(), local_api(settings), offers, routes)
    except google.FirebaseConfigError:
        return None
    relay.start()
    return relay


# --------------------------------------------------------------------------- pairing


def relay_info() -> dict:
    """What the phone needs to reach the mailbox. Not secret; part of the QR code."""
    project = google.read_project()
    return {"k": "rtdb", "u": project["database_url"], "a": project["api_key"]}


def pair(box, name: str, info: dict, confirm: Callable[[str], bool], show: Callable[[str], None],
         wait_seconds: float = devices.PAIRING_SECONDS, poll_seconds: float = 1.0) -> devices.Device | None:  # fmt: skip
    """Pair one phone: show the QR code, wait for its hello, compare codes, store the device."""
    device_id, qr_text = devices.begin_pairing(name, info)
    device_hex = device_id.hex()
    show(devices.qr_for_terminal(qr_text))
    show("Scan this code with the LifeOS app. It is valid for two minutes and for one phone.")
    deadline = time.time() + wait_seconds
    code = None
    try:
        while time.time() < deadline and code is None:
            blob = mailbox.decode(box.get(f"pair/{device_hex}/hello"))
            if blob:
                code = devices.receive_hello(blob)
                if code is None:
                    break
            else:
                time.sleep(poll_seconds)
        if code is None:
            show("No phone completed the pairing. Nothing was stored.")
            devices.cancel_pairing(device_id)
            return None
        if not confirm(f"{code[:3]} {code[3:]}"):
            show("Not confirmed. Nothing was stored.")
            devices.cancel_pairing(device_id)
            return None
        device = devices.confirm_pairing(device_id)
        if device.relay_uid:
            box.put(f"access/{device_hex}", device.relay_uid)
        blob = devices.seal_to(device_id, e2e.END, "pair", {"paired": True, "name": device.name})
        box.put(f"lifeos/{device_hex}/p/{mailbox.new_key()}", mailbox.encode(blob))
        show(f"Paired: {device.name}.")
        return device
    finally:
        with contextlib.suppress(mailbox.MailboxError):
            box.delete(f"pair/{device_hex}")
