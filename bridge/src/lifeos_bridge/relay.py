# ruff: noqa: E501
"""The relay path: paired phones reach the bridge through the mailbox.

For every paired phone the bridge listens for sealed messages, applies the
checks of docs/security.md, and answers requests by calling its own local
API, so a request from the phone does exactly what the same request does on
the computer. Answers go back sealed, in pieces small enough for the
mailbox. The bridge only ever makes outbound connections.
"""

from __future__ import annotations

import contextlib
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Iterator
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import devices, e2e, google, mailbox
from .config import Settings

# What a phone may call. Everything else answers 404 without touching the local API.
ALLOWED_PREFIXES = (
    "/ask", "/sessions", "/session-groups", "/reminders", "/notes", "/tasks", "/events",
    "/agenda", "/coach", "/context-map", "/allowed-paths", "/models", "/moodle", "/whoami",
)  # fmt: skip
ALLOWED_METHODS = ("GET", "POST", "PATCH", "PUT", "DELETE")
PART_CHARS = 600_000  # a sealed message stays well below the mailbox and message limits
DELTA_SECONDS = 0.4
DELTA_CHARS = 2000
RESCAN_SECONDS = 5.0
SWEEP_SECONDS = 600.0
KEEP_SECONDS = 15 * 60  # nothing stays in the mailbox longer than this
MAX_WORKERS = 4

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


def _sse_events(response) -> Iterator[tuple[str, object]]:
    event = ""
    with response:
        for raw in response:
            line = raw.decode(errors="replace").rstrip("\r\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                try:
                    yield event, json.loads(line[5:].strip())
                except ValueError:
                    continue


def allowed(method: object, path: object) -> bool:
    return (
        method in ALLOWED_METHODS
        and isinstance(path, str)
        and path.startswith("/")
        and ".." not in path
        and "?" not in path
        and any(path == prefix or path.startswith(prefix + "/") for prefix in ALLOWED_PREFIXES)
    )


class Relay:
    def __init__(self, box, call_local: LocalCall) -> None:
        self.box = box
        self.call_local = call_local
        self.receiver = devices.Receiver()
        self.stop = threading.Event()
        self._listeners: dict[str, threading.Event] = {}
        self._pool = ThreadPoolExecutor(max_workers=MAX_WORKERS, thread_name_prefix="relay")
        self._thread: threading.Thread | None = None

    # -- sending ----------------------------------------------------------------
    def send(self, device_id: bytes, kind: int, request_id: str, payload: object) -> None:
        blob = devices.seal_to(device_id, kind, request_id, payload)
        self.box.put(f"lifeos/{device_id.hex()}/p/{mailbox.new_key()}", mailbox.encode(blob))

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
        self._pool.submit(self._answer, message)

    def _answer(self, message: e2e.Message) -> None:
        device_id, rid = message.header.device_id, message.request_id
        try:
            for kind, payload in self.dispatch(message.payload):
                self.send(device_id, kind, rid, payload)
        except devices.PairingError:
            return  # revoked meanwhile
        except Exception:  # never let one request take the relay down
            with contextlib.suppress(Exception):
                self.send(device_id, e2e.ERROR, rid, {"status": 502, "error": "relay_failed"})

    def dispatch(self, request: object) -> Iterator[tuple[int, object]]:
        """Turn one request from the phone into the messages of its answer."""
        if not isinstance(request, dict) or not allowed(request.get("m"), request.get("path")):
            yield e2e.ERROR, {"status": 404, "error": "not_found"}
            return
        query = request.get("q") if isinstance(request.get("q"), dict) else {}
        status, kind, items = self.call_local(
            request["m"], request["path"], query, request.get("body")
        )
        if kind == "sse":
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


def start_if_configured(settings: Settings) -> Relay | None:
    """Start the relay when the owner's Firebase files exist; otherwise the bridge is local only."""
    if not configured():
        return None
    try:
        relay = Relay(mailbox.Mailbox(), local_api(settings))
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
