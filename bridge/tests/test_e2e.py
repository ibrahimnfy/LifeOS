"""End-to-end layer, device store and pairing (docs/security.md, section 10)."""

import json
import struct

import pytest
from nacl import bindings

from lifeos_bridge import devices, e2e
from lifeos_bridge.__main__ import main as cli

NOW = 1_800_000_000.0


class Phone:
    """The phone's side, built from the same primitives."""

    def __init__(self, qr_text: str) -> None:
        assert qr_text.startswith(devices.QR_PREFIX)
        data = json.loads(devices._unb64(qr_text[len(devices.QR_PREFIX) :]))
        self.id, self.secret, self.relay = (
            bytes.fromhex(data["d"]),
            devices._unb64(data["s"]),
            data["r"],
        )
        self.counter = 0

    def send(self, kind, payload, now=NOW, request_id="r1"):
        self.counter += 1
        header = e2e.Header(self.id, e2e.PHONE_TO_BRIDGE, self.counter, kind)
        return e2e.seal(self.secret, header, int(now), request_id, payload)


class RecordingRelay:
    """A hostile relay that keeps everything it carries."""

    def __init__(self):
        self.seen = []

    def carry(self, blob):
        self.seen.append(bytes(blob))
        return blob


@pytest.fixture
def paired(isolated_config):
    device_id, qr = devices.begin_pairing("Test phone", {"kind": "memory"}, NOW)
    phone = Phone(qr)
    hello = phone.send(e2e.HELLO, {"name": "Test phone"})
    code = devices.receive_hello(hello, NOW + 5)
    assert code is not None and len(code) == 6 and code.isdigit()
    assert code == e2e.comparison_code(phone.secret, hello)  # the phone shows the same
    device = devices.confirm_pairing(device_id, NOW + 10)
    return phone, device


def drops(config):
    path = config / "relay-drops.json"
    return json.loads(path.read_text()) if path.exists() else {}


def test_key_derivation_matches_the_documented_kdf_construction():
    secret = bytes(range(32))
    expected = bindings.crypto_generichash_blake2b_salt_personal(
        b"",
        digest_size=32,
        key=secret,
        salt=struct.pack("<Q", 2) + bytes(8),
        person=b"lifeosv1" + bytes(8),
    )
    assert e2e.derive_key(secret, 2) == expected
    keys = {e2e.derive_key(secret, n) for n in (1, 2, 3, 4)}
    assert len(keys) == 4 and secret not in keys


def test_round_trip_and_storage_modes(paired, isolated_config):
    phone, device = paired
    receiver = devices.Receiver()
    message = receiver.accept(phone.send(e2e.REQUEST, {"prompt": "hello"}), NOW + 20)
    assert message.payload == {"prompt": "hello"} and message.request_id == "r1"
    folder = isolated_config / "devices"
    assert folder.stat().st_mode & 0o777 == 0o700
    assert all(f.stat().st_mode & 0o777 == 0o600 for f in folder.iterdir())
    assert not list(folder.glob("pending-*"))

    answer = devices.seal_for_phone(
        devices.load(device.id), e2e.CHUNK, "r1", {"text": "hi"}, NOW + 21
    )
    opened = e2e.open_sealed(phone.secret, answer)
    assert opened.header.direction == e2e.BRIDGE_TO_PHONE and opened.payload == {"text": "hi"}
    assert devices.load(device.id).sent == 1


def test_any_changed_byte_is_rejected(paired, isolated_config):
    phone, _ = paired
    blob = phone.send(e2e.REQUEST, {"prompt": "hello"})
    receiver = devices.Receiver()
    # every header byte (device id, direction, counter, kind), the nonce, the ciphertext, the tag
    positions = [*range(e2e.HEADER.size), e2e.HEADER.size + 3, e2e.HEADER.size + 30, len(blob) - 1]
    for position in positions:
        changed = bytearray(blob)
        changed[position] ^= 0x01
        assert receiver.accept(bytes(changed), NOW + 20) is None, position
    assert receiver.accept(blob[:-1], NOW + 20) is None
    assert receiver.accept(blob, NOW + 20) is not None  # the untouched message still works


def test_replay_is_rejected_also_after_a_restart(paired, isolated_config):
    phone, _ = paired
    blob = phone.send(e2e.REQUEST, {"prompt": "once"})
    assert devices.Receiver().accept(blob, NOW + 20) is not None
    assert devices.Receiver().accept(blob, NOW + 21) is None  # a new receiver = a restart
    older = e2e.seal(
        phone.secret, e2e.Header(phone.id, e2e.PHONE_TO_BRIDGE, 1, e2e.REQUEST), int(NOW), "r", {}
    )
    assert devices.Receiver().accept(older, NOW + 22) is None
    assert drops(isolated_config)["replay"] == 2


def test_old_timestamps_and_reflected_messages_are_rejected(paired, isolated_config):
    phone, device = paired
    receiver = devices.Receiver()
    assert receiver.accept(phone.send(e2e.REQUEST, {}, now=NOW - 3600), NOW) is None
    assert receiver.accept(phone.send(e2e.REQUEST, {}, now=NOW + 3600), NOW) is None
    reflected = devices.seal_for_phone(devices.load(device.id), e2e.CHUNK, "r1", {"text": "x"}, NOW)
    assert receiver.accept(reflected, NOW) is None
    # a phone-to-bridge header on a bridge-to-phone ciphertext does not authenticate either
    forged = bytearray(reflected)
    forged[20] = e2e.PHONE_TO_BRIDGE
    assert receiver.accept(bytes(forged), NOW) is None
    found = drops(isolated_config)
    assert found["stale"] == 2 and found["direction"] == 1 and found["authentication"] == 1
    assert receiver.accept(phone.send(e2e.ACK, {}, now=NOW - 3600), NOW) is not None  # not timed


def test_revoked_device_is_rejected_immediately(paired, isolated_config, capsys):
    phone, device = paired
    receiver = devices.Receiver()
    assert receiver.accept(phone.send(e2e.REQUEST, {}), NOW) is not None
    assert cli(["devices"]) == 0
    assert "paired" in capsys.readouterr().out
    assert cli(["devices", "revoke", device.id.hex()[:8]]) == 0
    assert receiver.accept(phone.send(e2e.REQUEST, {}), NOW) is None
    record = json.loads((isolated_config / "devices" / f"{device.id.hex()}.json").read_text())
    assert record["secret"] is None and record["revoked_at"]
    with pytest.raises(devices.PairingError):
        devices.seal_for_phone(devices.load(device.id), e2e.CHUNK, "r", {})
    assert cli(["devices"]) == 0
    assert "revoked" in capsys.readouterr().out
    assert cli(["devices", "revoke", "ffff"]) == 1


def test_unknown_devices_cause_no_work_and_no_distinguishable_answer(paired, isolated_config):
    phone, _ = paired
    stranger_secret = e2e.random_bytes(32)
    header = e2e.Header(e2e.random_bytes(16), e2e.PHONE_TO_BRIDGE, 1, e2e.REQUEST)
    receiver = devices.Receiver()
    unknown = receiver.accept(e2e.seal(stranger_secret, header, int(NOW), "r", {}), NOW)
    wrong_key = receiver.accept(
        e2e.seal(
            stranger_secret,
            e2e.Header(phone.id, e2e.PHONE_TO_BRIDGE, 9, e2e.REQUEST),
            int(NOW),
            "r",
            {},
        ),
        NOW,
    )
    garbage = receiver.accept(b"not a message", NOW)
    assert unknown is None and wrong_key is None and garbage is None
    assert len(list((isolated_config / "devices").glob("*.json"))) == 1


def test_a_recording_relay_sees_no_plaintext(paired):
    phone, device = paired
    relay = RecordingRelay()
    secret_words = [
        "prompt-text-7391",
        "answer-text-5527",
        "Session title 8842",
        "/home/someone/folder",
    ]
    receiver = devices.Receiver()
    request = relay.carry(
        phone.send(e2e.REQUEST, {"prompt": secret_words[0], "cwd": secret_words[3]})
    )
    assert receiver.accept(request, NOW).payload["prompt"] == secret_words[0]
    relay.carry(
        devices.seal_for_phone(
            devices.load(device.id),
            e2e.CHUNK,
            "r1",
            {"text": secret_words[1], "title": secret_words[2]},
            NOW,
        )
    )
    relay.carry(
        devices.seal_for_phone(devices.load(device.id), e2e.PUSH_HINT, "", {"e": "answer"}, NOW)
    )
    recorded = b"".join(relay.seen)
    for word in secret_words:
        assert word.encode() not in recorded
    assert phone.secret not in recorded
    assert (
        e2e.parse_header(relay.seen[2]).direction == e2e.PUSH
    )  # separate key and counter for push


def test_two_identical_messages_never_look_the_same(paired):
    _, device = paired
    first = devices.seal_for_phone(devices.load(device.id), e2e.CHUNK, "r", {"text": "same"}, NOW)
    second = devices.seal_for_phone(devices.load(device.id), e2e.CHUNK, "r", {"text": "same"}, NOW)
    assert first[e2e.HEADER.size :] != second[e2e.HEADER.size :]
    assert e2e.parse_header(second).counter == e2e.parse_header(first).counter + 1


def test_pairing_expires_after_two_minutes(isolated_config):
    device_id, qr = devices.begin_pairing("Late", {}, NOW)
    phone = Phone(qr)
    assert devices.receive_hello(phone.send(e2e.HELLO, {}, now=NOW + 121), NOW + 121) is None
    assert not list((isolated_config / "devices").glob("pending-*"))
    with pytest.raises(devices.PairingError):
        devices.confirm_pairing(device_id, NOW + 122)
    assert devices.list_devices() == []


def test_pairing_works_once_and_a_second_phone_destroys_it(isolated_config):
    device_id, qr = devices.begin_pairing("Mine", {}, NOW)
    owner, thief = Phone(qr), Phone(qr)  # somebody photographed the code
    assert devices.receive_hello(owner.send(e2e.HELLO, {}), NOW + 1) is not None
    assert devices.receive_hello(thief.send(e2e.HELLO, {"other": 1}), NOW + 2) is None
    with pytest.raises(devices.PairingError):
        devices.confirm_pairing(device_id, NOW + 3)
    assert devices.list_devices() == []

    device_id, qr = devices.begin_pairing("Mine", {}, NOW)
    phone = Phone(qr)
    hello = phone.send(e2e.HELLO, {})
    devices.receive_hello(hello, NOW + 1)
    devices.confirm_pairing(device_id, NOW + 2)
    assert devices.receive_hello(hello, NOW + 3) is None  # the pairing is used up
    assert devices.Receiver().accept(hello, NOW + 3) is None  # and the hello cannot be replayed
    with pytest.raises(devices.PairingError):
        devices.confirm_pairing(device_id, NOW + 4)


def test_unconfirmed_pairings_are_swept_and_cancel_destroys(isolated_config):
    first, _ = devices.begin_pairing("a", {}, NOW)
    devices.begin_pairing("b", {}, NOW)
    devices.cancel_pairing(first)
    assert devices.sweep_pending(NOW + 60) == 0
    assert devices.sweep_pending(NOW + 121) == 1
    assert not list((isolated_config / "devices").iterdir())


def test_rate_limit_per_device(paired, isolated_config, monkeypatch):
    phone, _ = paired
    monkeypatch.setattr(devices, "RATE_LIMIT", 3)
    receiver = devices.Receiver()
    results = [receiver.accept(phone.send(e2e.ACK, {}), NOW + i) for i in range(5)]
    assert [r is not None for r in results] == [True, True, True, False, False]
    assert receiver.accept(phone.send(e2e.ACK, {}), NOW + 120) is not None


def test_secrets_never_appear_in_listings_notifications_or_drop_counts(
    paired, isolated_config, tmp_path, capsys
):
    phone, device = paired
    log = tmp_path / "notify.log"
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf "%s|%s|%s\\n" "$1" "$2" "$3" >> "{log}"\n')
    script.chmod(0o755)
    devices.Receiver().accept(b"x" * 200, NOW)
    cli(["devices"])
    devices.revoke(device.id.hex(), NOW, script)
    secret_forms = [phone.secret.hex(), devices._b64(phone.secret)]
    public = json.dumps(device.public()) + capsys.readouterr().out + log.read_text()
    public += (isolated_config / "relay-drops.json").read_text()
    for form in secret_forms:
        assert form not in public
    assert device.id.hex() not in log.read_text()


def test_qr_code_renders_for_a_terminal(isolated_config):
    _, qr = devices.begin_pairing("Mine", {"kind": "memory"}, NOW)
    art = devices.qr_for_terminal(qr)
    assert len(art.splitlines()) > 10


def test_oversized_and_malformed_messages(paired):
    phone, _ = paired
    with pytest.raises(ValueError, match="too large"):
        phone.send(e2e.CHUNK, {"text": "x" * e2e.MAX_MESSAGE_BYTES})
    with pytest.raises(e2e.Rejected):
        e2e.parse_header(b"\x00" * (e2e.MAX_MESSAGE_BYTES + 1))
    with pytest.raises(e2e.Rejected):
        e2e.parse_header(b"XXX" + bytes(100))
