# ruff: noqa: RUF001
"""Regenerate the app's test vectors: ``.venv/bin/python tests/make_e2e_vectors.py`` (in bridge/).

Writes ``app/app/src/test/resources/e2e-vectors.json`` from the bridge's own
``e2e`` and ``devices`` code, so the Android app's unit tests prove that both
sides derive the same keys, read each other's messages and show the same
comparison code. Every input is synthetic and fixed; the nonces are fixed too
(only here), so the file is the same on every run.
"""

from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path

from lifeos_bridge import devices, e2e

SECRET = bytes(range(32))
DEVICE_ID = bytes(range(0xA0, 0xB0))
TIMESTAMP = 1_800_000_000
RELAY = {"k": "rtdb", "u": "https://relay.example.invalid", "a": "synthetic-api-key"}
TARGET = Path(__file__).resolve().parents[2] / "app/app/src/test/resources/e2e-vectors.json"

MESSAGES = [
    (
        "hello",
        e2e.PHONE_TO_BRIDGE,
        1,
        e2e.HELLO,
        "hello",
        {"uid": "syntheticUid123", "name": "Phone"},
    ),
    (
        "request",
        e2e.PHONE_TO_BRIDGE,
        2,
        e2e.REQUEST,
        "r-0001",
        {"m": "GET", "path": "/whoami", "q": {}, "body": None},
    ),
    (
        "request_non_ascii",
        e2e.PHONE_TO_BRIDGE,
        3,
        e2e.REQUEST,
        "r-0002",
        {"m": "POST", "path": "/notes", "q": {}, "body": {"text": "Çay, şeker, ığdır – 東京 🙂"}},
    ),
    (
        "pair_end",
        e2e.BRIDGE_TO_PHONE,
        1,
        e2e.END,
        "pair",
        {"paired": True, "name": "Örnek telefon"},
    ),
    ("chunk", e2e.BRIDGE_TO_PHONE, 2, e2e.CHUNK, "r-0001", {"i": 0, "part": '{"a":"ğ'}),
    ("end", e2e.BRIDGE_TO_PHONE, 3, e2e.END, "r-0001", {"status": 200, "parts": 1}),
    (
        "error",
        e2e.BRIDGE_TO_PHONE,
        2**40 + 5,
        e2e.ERROR,
        "r-0002",
        {"status": 404, "error": "not_found"},
    ),
    ("push_hint", e2e.PUSH, 7, e2e.PUSH_HINT, "", {"kind": "answer", "session": "s1"}),
]


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def main() -> None:
    sealed = []
    original = e2e.random_bytes
    try:
        for index, (name, direction, counter, kind, rid, payload) in enumerate(MESSAGES):
            nonce = hashlib.sha256(f"nonce-{index}".encode()).digest()[: e2e.NONCE_BYTES]
            e2e.random_bytes = lambda count, nonce=nonce: nonce  # fixed nonce, for vectors only
            header = e2e.Header(DEVICE_ID, direction, counter, kind)
            blob = e2e.seal(SECRET, header, TIMESTAMP + index, rid, payload)
            assert e2e.open_sealed(SECRET, blob).payload == payload
            sealed.append(
                {
                    "name": name,
                    "direction": direction,
                    "counter": counter,
                    "kind": kind,
                    "ts": TIMESTAMP + index,
                    "rid": rid,
                    "payload": payload,
                    "nonce": nonce.hex(),
                    "header": header.pack().hex(),
                    "blob": b64(blob),
                }
            )
    finally:
        e2e.random_bytes = original
    hello = base64.b64decode(sealed[0]["blob"])
    qr_payload = {"d": DEVICE_ID.hex(), "s": devices._b64(SECRET), "r": RELAY}
    vectors = {
        "note": "Synthetic test vectors made by bridge/tests/make_e2e_vectors.py. No real keys.",
        "secret": SECRET.hex(),
        "device_id": DEVICE_ID.hex(),
        "subkeys": {str(n): e2e.derive_key(SECRET, n).hex() for n in (1, 2, 3, 4)},
        "sealed": sealed,
        "comparison_code": e2e.comparison_code(SECRET, hello),
        "pairing_text": devices.QR_PREFIX
        + devices._b64(json.dumps(qr_payload, separators=(",", ":")).encode()),
        "relay": RELAY,
    }
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(json.dumps(vectors, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {len(sealed)} sealed messages to {TARGET.relative_to(TARGET.parents[5])}")


if __name__ == "__main__":
    main()
