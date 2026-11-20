"""End-to-end layer between a paired phone and the bridge (docs/security.md).

Only documented constructions of libsodium are used, through PyNaCl:

- random bytes: ``randombytes``;
- key derivation: keyed BLAKE2b with salt and personalisation
  (``crypto_generichash_blake2b_salt_personal``), with exactly the inputs
  libsodium's ``crypto_kdf_derive_from_key`` uses, so the phone can call
  ``crypto_kdf`` directly (PyNaCl has no binding for it);
- every message: ``crypto_aead_xchacha20poly1305_ietf`` with a random
  24-byte nonce and the routing header as associated data;
- pairing comparison code: BLAKE2b (``crypto_generichash``).

There is no own cipher, mode or handshake here. Keys are static per pairing
(no forward secrecy; the owner's decision).

Wire format of one message::

    header (30 bytes, authenticated, readable by the relay)
        "LOS" | version (1) | device id (16) | direction (1) | counter (8, big endian) | kind (1)
    nonce (24 random bytes)
    ciphertext and tag

The plaintext is JSON: ``{"ts": <unix seconds>, "rid": <request id>, "p": <payload>}``.
"""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass

from nacl import bindings
from nacl.exceptions import CryptoError

MAGIC = b"LOS"
VERSION = 1
HEADER = struct.Struct(">3sB16sBQB")
NONCE_BYTES = bindings.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
KEY_BYTES = bindings.crypto_aead_xchacha20poly1305_ietf_KEYBYTES
SECRET_BYTES = 32
DEVICE_ID_BYTES = 16
MAX_MESSAGE_BYTES = 1_400_000
KDF_CONTEXT = b"lifeosv1"  # 8 bytes, as crypto_kdf requires

# Directions
PHONE_TO_BRIDGE, BRIDGE_TO_PHONE, PUSH = 1, 2, 3
# Sub-key numbers: one key per direction, one for push hints, one for the pairing code.
SUBKEY_CODE = 4

# Message kinds
HELLO, REQUEST, CANCEL, ACK = 1, 2, 3, 4
CHUNK, END, ERROR = 16, 17, 18
PUSH_HINT = 32
KINDS = frozenset({HELLO, REQUEST, CANCEL, ACK, CHUNK, END, ERROR, PUSH_HINT})
# Kinds that start work: only these must also be recent.
TIMED_KINDS = frozenset({HELLO, REQUEST})


class Rejected(Exception):
    """A message failed a check. ``reason`` is a short fixed word, never content."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class Header:
    device_id: bytes
    direction: int
    counter: int
    kind: int

    def pack(self) -> bytes:
        return HEADER.pack(MAGIC, VERSION, self.device_id, self.direction, self.counter, self.kind)


@dataclass(frozen=True)
class Message:
    header: Header
    timestamp: int
    request_id: str
    payload: object


def random_bytes(count: int) -> bytes:
    return bindings.randombytes(count)


def derive_key(secret: bytes, subkey: int) -> bytes:
    """Sub-key ``subkey`` of a device secret; identical to libsodium's ``crypto_kdf``."""
    if len(secret) != SECRET_BYTES:
        raise ValueError("device secret has the wrong length")
    return bindings.crypto_generichash_blake2b_salt_personal(
        b"",
        digest_size=KEY_BYTES,
        key=secret,
        salt=struct.pack("<Q", subkey) + bytes(8),
        person=KDF_CONTEXT + bytes(8),
    )


def parse_header(blob: bytes) -> Header:
    """The routing header of a message. Raises :class:`Rejected` for anything malformed."""
    if len(blob) > MAX_MESSAGE_BYTES:
        raise Rejected("too_large")
    if len(blob) < HEADER.size + NONCE_BYTES + bindings.crypto_aead_xchacha20poly1305_ietf_ABYTES:
        raise Rejected("malformed")
    magic, version, device_id, direction, counter, kind = HEADER.unpack_from(blob)
    if magic != MAGIC or version != VERSION:
        raise Rejected("version")
    if kind not in KINDS:
        raise Rejected("kind")
    return Header(device_id, direction, counter, kind)


def seal(secret: bytes, header: Header, timestamp: int, request_id: str, payload: object) -> bytes:
    """Encrypt and authenticate one message."""
    plaintext = json.dumps(
        {"ts": int(timestamp), "rid": request_id, "p": payload},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()
    packed = header.pack()
    nonce = random_bytes(NONCE_BYTES)
    ciphertext = bindings.crypto_aead_xchacha20poly1305_ietf_encrypt(
        plaintext, packed, nonce, derive_key(secret, header.direction)
    )
    blob = packed + nonce + ciphertext
    if len(blob) > MAX_MESSAGE_BYTES:
        raise ValueError("message too large; split it into chunks")
    return blob


def open_sealed(secret: bytes, blob: bytes) -> Message:
    """Decrypt one message. Success proves it was made by a holder of the device secret."""
    header = parse_header(blob)
    packed = blob[: HEADER.size]
    nonce = blob[HEADER.size : HEADER.size + NONCE_BYTES]
    try:
        plaintext = bindings.crypto_aead_xchacha20poly1305_ietf_decrypt(
            blob[HEADER.size + NONCE_BYTES :], packed, nonce, derive_key(secret, header.direction)
        )
    except CryptoError as exc:
        raise Rejected("authentication") from exc
    try:
        data = json.loads(plaintext)
        return Message(header, int(data["ts"]), str(data["rid"]), data["p"])
    except (ValueError, KeyError, TypeError) as exc:
        raise Rejected("malformed") from exc


def comparison_code(secret: bytes, hello_blob: bytes) -> str:
    """Six digits both screens show at pairing; depends on the secret and the hello message."""
    digest = bindings.crypto_generichash_blake2b_salt_personal(
        hello_blob, digest_size=32, key=derive_key(secret, SUBKEY_CODE)
    )
    return f"{int.from_bytes(digest[:8], 'big') % 1_000_000:06d}"
