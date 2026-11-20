"""Paired phones: the device store, pairing, and the checks on every incoming message.

Device secrets live in ``~/.config/lifeos/devices/`` (folder mode 700, files
mode 600) and nowhere else: never in the repository, the memory folder, a
log or a notification. Design: docs/security.md.
"""

from __future__ import annotations

import base64
import json
import threading
import time
from collections import deque
from dataclasses import dataclass
from pathlib import Path

from . import audit, e2e, notifications, store
from .config import config_dir

# One writer at a time for device records: receiving and sending both update counters.
LOCK = threading.RLock()
PAIRING_SECONDS = 120
FRESHNESS_SECONDS = 300
RATE_LIMIT = 240  # messages per minute and device
QR_PREFIX = "lifeos-pair:1:"
MAX_NAME = 40


class PairingError(Exception):
    """Pairing could not continue. The message is safe to show."""


def devices_dir() -> Path:
    return config_dir() / "devices"


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _path(device_id: bytes, pending: bool = False) -> Path:
    return devices_dir() / f"{'pending-' if pending else ''}{device_id.hex()}.json"


@dataclass
class Device:
    id: bytes
    name: str
    secret: bytes | None  # None once revoked
    paired_at: int
    last_seen: int = 0
    received: int = 0  # highest accepted counter, phone -> bridge
    sent: int = 0  # last used counter, bridge -> phone
    sent_push: int = 0
    revoked_at: int | None = None
    relay_uid: str | None = None  # the phone's account id at the relay (not a secret)
    push_token: str | None = None  # the phone's push address; cleared on revocation

    @property
    def revoked(self) -> bool:
        return self.secret is None

    def as_record(self) -> dict:
        return {
            "id": self.id.hex(),
            "name": self.name,
            "secret": _b64(self.secret) if self.secret else None,
            "paired_at": self.paired_at,
            "last_seen": self.last_seen,
            "received": self.received,
            "sent": self.sent,
            "sent_push": self.sent_push,
            "revoked_at": self.revoked_at,
            "relay_uid": self.relay_uid,
            "push_token": self.push_token,
        }

    def public(self) -> dict:
        """What listings show: never the secret."""
        record = self.as_record()
        del record["secret"]
        record["push"] = bool(record.pop("push_token"))
        record["revoked"] = self.revoked
        return record


def _from_record(record: dict) -> Device:
    secret = record.get("secret")
    return Device(
        id=bytes.fromhex(record["id"]),
        name=str(record.get("name") or ""),
        secret=_unb64(secret) if secret else None,
        paired_at=int(record.get("paired_at") or 0),
        last_seen=int(record.get("last_seen") or 0),
        received=int(record.get("received") or 0),
        sent=int(record.get("sent") or 0),
        sent_push=int(record.get("sent_push") or 0),
        revoked_at=record.get("revoked_at"),
        relay_uid=record.get("relay_uid") or None,
        push_token=record.get("push_token") or None,
    )


def save(device: Device) -> None:
    devices_dir().mkdir(mode=0o700, parents=True, exist_ok=True)
    store.write_json(_path(device.id), device.as_record())


def load(device_id: bytes) -> Device | None:
    record = store.read_json(_path(device_id), {})
    try:
        return _from_record(record) if record else None
    except (KeyError, ValueError, TypeError):
        return None


def list_devices() -> list[Device]:
    found = []
    for file in sorted(devices_dir().glob("*.json")):
        if file.name.startswith("pending-"):
            continue
        record = store.read_json(file, {})
        try:
            found.append(_from_record(record))
        except (KeyError, ValueError, TypeError):
            continue
    return sorted(found, key=lambda d: d.paired_at)


def revoke(
    device_id_hex: str, now: float | None = None, notify_script: Path | None = None
) -> Device:
    """Delete the device's secret. From now on its messages fail; no network is needed."""
    matches = [d for d in list_devices() if d.id.hex().startswith(device_id_hex.lower())]
    if len(matches) != 1 or len(device_id_hex) < 4:
        raise PairingError("no single device matches this id")
    device = matches[0]
    if not device.revoked:
        device.secret = None
        device.push_token = None
        device.revoked_at = int(now if now is not None else time.time())
        save(device)
        audit.try_record("DeviceRevoked", {"device": device.id.hex()})  # never blocks it
        notifications.send(
            "LifeOS: a phone was unpaired",
            "A paired phone was revoked on the computer.",
            "",
            notify_script,
        )
    return device


# --------------------------------------------------------------------------- drops


def count_drop(reason: str) -> None:
    """Dropped messages are only counted, by reason; nothing about them is kept."""
    path = config_dir() / "relay-drops.json"
    counts = store.read_json(path, {})
    counts[reason] = int(counts.get(reason, 0)) + 1
    store.write_json(path, counts)


# --------------------------------------------------------------------------- receiving


class Receiver:
    """Applies the checks of docs/security.md, section 3.3, in order."""

    def __init__(self) -> None:
        self._recent: dict[bytes, deque[float]] = {}

    def _rate_ok(self, device_id: bytes, now: float) -> bool:
        window = self._recent.setdefault(device_id, deque())
        while window and now - window[0] > 60:
            window.popleft()
        if len(window) >= RATE_LIMIT:
            return False
        window.append(now)
        return True

    def accept(self, blob: bytes, now: float | None = None) -> e2e.Message | None:
        """Return the message if it passes every check; otherwise count the drop, return None.

        The caller learns nothing about *why* a message was dropped, and
        neither does the sender.
        """
        now = time.time() if now is None else now
        with LOCK:
            try:
                message, device = self._check(blob, now)
            except e2e.Rejected as rejected:
                count_drop(rejected.reason)
                return None
            # Stored before the message is acted on: a repeat is rejected even after a restart.
            device.received = message.header.counter
            device.last_seen = int(now)
            save(device)
        return message

    def _check(self, blob: bytes, now: float) -> tuple[e2e.Message, Device]:
        header = e2e.parse_header(blob)  # 1. version, shape, size
        device = load(header.device_id)
        if device is None:
            raise e2e.Rejected("unknown_device")
        if device.revoked or device.secret is None:
            raise e2e.Rejected("revoked")
        if header.direction != e2e.PHONE_TO_BRIDGE:
            raise e2e.Rejected("direction")
        message = e2e.open_sealed(device.secret, blob)  # 2. authentication
        if header.counter <= device.received:  # 3. not a repeat
            raise e2e.Rejected("replay")
        if header.kind in e2e.TIMED_KINDS and abs(now - message.timestamp) > FRESHNESS_SECONDS:
            raise e2e.Rejected("stale")  # 4. recent
        if not self._rate_ok(device.id, now):  # 5. rate
            raise e2e.Rejected("rate")
        return message, device


def seal_for_phone(
    device: Device, kind: int, request_id: str, payload: object, now: float | None = None
) -> bytes:
    """Seal a message to the phone; the counter is stored before the message exists."""
    if device.revoked or device.secret is None:
        raise PairingError("the device is revoked")
    push = kind == e2e.PUSH_HINT
    if push:
        device.sent_push += 1
    else:
        device.sent += 1
    save(device)
    header = e2e.Header(
        device.id,
        e2e.PUSH if push else e2e.BRIDGE_TO_PHONE,
        device.sent_push if push else device.sent,
        kind,
    )
    return e2e.seal(
        device.secret, header, int(time.time() if now is None else now), request_id, payload
    )


def seal_to(
    device_id: bytes, kind: int, request_id: str, payload: object, now: float | None = None
) -> bytes:
    """Load the device, seal a message for it and store the new counter, as one step."""
    with LOCK:
        device = load(device_id)
        if device is None:
            raise PairingError("unknown device")
        return seal_for_phone(device, kind, request_id, payload, now)


# --------------------------------------------------------------------------- pairing


@dataclass
class Pending:
    id: bytes
    name: str
    secret: bytes
    expires_at: float
    hello: str | None = None  # base64 of the first hello message, once received
    relay_uid: str | None = None

    def record(self) -> dict:
        return {
            "id": self.id.hex(),
            "name": self.name,
            "secret": _b64(self.secret),
            "expires_at": self.expires_at,
            "hello": self.hello,
            "relay_uid": self.relay_uid,
        }


def _load_pending(device_id: bytes) -> Pending | None:
    record = store.read_json(_path(device_id, pending=True), {})
    try:
        return Pending(
            bytes.fromhex(record["id"]),
            str(record["name"]),
            _unb64(record["secret"]),
            float(record["expires_at"]),
            record.get("hello"),
            record.get("relay_uid") or None,
        )
    except (KeyError, ValueError, TypeError):
        return None


def _destroy_pending(device_id: bytes) -> None:
    path = _path(device_id, pending=True)
    if path.exists():
        # Overwrite before unlinking, so the secret does not linger in the file's blocks.
        store.write_json(path, {})
        path.unlink()


def clean_name(name: str) -> str:
    name = " ".join(str(name).split())[:MAX_NAME]
    return name or "phone"


def begin_pairing(name: str, relay: dict, now: float | None = None) -> tuple[bytes, str]:
    """Create a device id and secret. Returns the id and the text for the QR code.

    The QR text is the key: it is shown on the owner's screen only, is valid
    for two minutes and for one use, and is never logged.
    """
    now = time.time() if now is None else now
    pending = Pending(
        e2e.random_bytes(e2e.DEVICE_ID_BYTES),
        clean_name(name),
        e2e.random_bytes(e2e.SECRET_BYTES),
        now + PAIRING_SECONDS,
    )
    devices_dir().mkdir(mode=0o700, parents=True, exist_ok=True)
    store.write_json(_path(pending.id, pending=True), pending.record())
    payload = {"d": pending.id.hex(), "s": _b64(pending.secret), "r": relay}
    return pending.id, QR_PREFIX + _b64(json.dumps(payload, separators=(",", ":")).encode())


def receive_hello(blob: bytes, now: float | None = None) -> str | None:
    """Handle the phone's first message. Returns the six-digit code to compare, or None.

    A second, different hello for the same pairing means two devices hold the
    QR code: the pairing is destroyed.
    """
    now = time.time() if now is None else now
    try:
        header = e2e.parse_header(blob)
    except e2e.Rejected as rejected:
        count_drop(rejected.reason)
        return None
    pending = _load_pending(header.device_id)
    if pending is None:
        count_drop("unknown_device")
        return None
    if now > pending.expires_at:
        _destroy_pending(pending.id)
        count_drop("pairing_expired")
        return None
    try:
        message = e2e.open_sealed(pending.secret, blob)
        if header.direction != e2e.PHONE_TO_BRIDGE or header.kind != e2e.HELLO:
            raise e2e.Rejected("direction")
        if header.counter != 1:
            raise e2e.Rejected("replay")
        if abs(now - message.timestamp) > FRESHNESS_SECONDS:
            raise e2e.Rejected("stale")
    except e2e.Rejected as rejected:
        count_drop(rejected.reason)
        return None
    encoded = _b64(blob)
    if pending.hello is not None and pending.hello != encoded:
        _destroy_pending(pending.id)
        count_drop("pairing_conflict")
        return None
    pending.hello = encoded
    # The phone tells the bridge its relay account id inside the sealed hello.
    uid = message.payload.get("uid") if isinstance(message.payload, dict) else None
    if isinstance(uid, str) and 0 < len(uid) <= 128 and uid.isascii() and uid.isalnum():
        pending.relay_uid = uid
    store.write_json(_path(pending.id, pending=True), pending.record())
    return e2e.comparison_code(pending.secret, blob)


def confirm_pairing(
    device_id: bytes, now: float | None = None, notify_script: Path | None = None
) -> Device:
    """The owner confirmed that both screens show the same code: store the device."""
    now = time.time() if now is None else now
    pending = _load_pending(device_id)
    if pending is None or pending.hello is None:
        raise PairingError("there is no pairing waiting for confirmation")
    if now > pending.expires_at:
        _destroy_pending(device_id)
        raise PairingError("the pairing has expired; start again")
    device = Device(pending.id, pending.name, pending.secret, int(now), int(now), received=1)
    device.relay_uid = pending.relay_uid
    save(device)
    _destroy_pending(device_id)
    audit.try_record("DevicePaired", {"device": device.id.hex()})
    notifications.send(
        "LifeOS: a phone was paired", "A new phone was paired on the computer.", "", notify_script
    )
    return device


def cancel_pairing(device_id: bytes) -> None:
    """Declined or timed out: the secret is destroyed."""
    _destroy_pending(device_id)


def sweep_pending(now: float | None = None) -> int:
    """Destroy expired pairings. Returns how many were removed."""
    now = time.time() if now is None else now
    removed = 0
    for file in devices_dir().glob("pending-*.json"):
        record = store.read_json(file, {})
        if float(record.get("expires_at") or 0) < now:
            _destroy_pending(bytes.fromhex(file.stem.removeprefix("pending-")))
            removed += 1
    return removed


def qr_for_terminal(text: str) -> str:
    """The QR code as text for a terminal. Never write the result to a log."""
    import io

    import segno

    out = io.StringIO()
    segno.make(text, error="m").terminal(out=out, compact=True, border=2)
    return out.getvalue()
