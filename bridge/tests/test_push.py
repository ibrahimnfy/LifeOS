"""Push hints: sealed, without readable content. FCM is a stand-in; no network."""

import base64
import json
import time

import pytest

from lifeos_bridge import devices, e2e, google, notifications, push

NOW = 1_800_000_000.0
TOKEN = "fcm-registration-token-" + "x" * 40


@pytest.fixture
def phone(isolated_config):
    device_id, qr = devices.begin_pairing("Test phone", {}, NOW)
    data = json.loads(devices._unb64(qr[len(devices.QR_PREFIX) :]))
    secret = devices._unb64(data["s"])
    hello = e2e.seal(
        secret, e2e.Header(device_id, e2e.PHONE_TO_BRIDGE, 1, e2e.HELLO), int(NOW), "r", {}
    )
    devices.receive_hello(hello, NOW)
    devices.confirm_pairing(device_id, NOW)
    return device_id, secret


@pytest.fixture
def fcm(monkeypatch):
    """Records what would be sent to the push service; answers with a chosen status."""
    sent, status = [], {"code": 200}
    monkeypatch.setattr(google, "read_project", lambda: {"project_id": "example-project"})
    monkeypatch.setattr(google, "access_token", lambda scopes: "access-token")
    monkeypatch.setattr(
        push, "_post", lambda url, access, message: (sent.append((url, message)), status["code"])[1]
    )
    return sent, status


def test_registering_needs_a_paired_device_and_a_plausible_address(phone, isolated_config):
    device_id, _ = phone
    assert push.register(device_id, TOKEN) is True
    assert devices.load(device_id).push_token == TOKEN
    assert devices.load(device_id).public()["push"] is True
    assert "push_token" not in devices.load(device_id).public()
    for bad in ["short", None, 5, "x" * 5000, "ünicode" * 10]:
        assert push.register(device_id, bad) is False
    assert push.register(e2e.random_bytes(16), TOKEN) is False


def test_a_push_carries_only_a_sealed_hint(phone, fcm):
    device_id, secret = phone
    sent, _ = fcm
    push.register(device_id, TOKEN)
    assert push.send_hint("answer", "session-1234") == 1
    (url, body) = sent[0]
    assert url.endswith("/projects/example-project/messages:send")
    message = body["message"]
    assert set(message) == {"token", "data", "android"} and set(message["data"]) == {"h"}
    assert "notification" not in message  # the app builds the visible notification itself
    assert message["android"] == {"priority": "HIGH"}
    assert "session-1234" not in json.dumps(body) and "answer" not in json.dumps(body)
    hint = e2e.open_sealed(secret, base64.b64decode(message["data"]["h"]))
    assert hint.header.direction == e2e.PUSH and hint.header.kind == e2e.PUSH_HINT
    assert hint.payload == {"e": "answer", "c": "chat", "o": "session", "r": "session-1234"}
    assert len(json.dumps(body)) < 4096


def test_every_kind_has_a_channel_and_a_screen():
    assert set(push.CHANNELS.values()) == {"chat", "courses", "coach", "reminders"}
    assert set(push.CHANNELS) == set(push.OPENS) == push.EVENTS
    hint = push.hint_for("courses", "5", "course", {"files": 2, "grades": 0, "x": True})
    assert hint == {"e": "courses", "c": "courses", "o": "course", "r": "5", "n": {"files": 2}}


def test_one_sync_is_one_push_with_counts_and_no_readable_content(phone, fcm, monkeypatch):
    device_id, secret = phone
    sent, _ = fcm
    push.register(device_id, TOKEN)
    new = {"files": 2, "pages": 1, "assignments": 1, "announcements": 0, "deadlines": 1,
           "grades": 3}  # fmt: skip
    notifications.send("LifeOS: new in your courses", "2 new files, …", "", None,
                       event="courses", ref="5", opens="course", counts=new)  # fmt: skip
    (_, body) = sent[0]
    raw = json.dumps(body)
    assert "files" not in raw and "course" not in raw and "grades" not in raw
    hint = e2e.open_sealed(secret, base64.b64decode(body["message"]["data"]["h"]))
    assert hint.payload == {
        "e": "courses", "c": "courses", "o": "course", "r": "5",
        "n": {"files": 2, "pages": 1, "assignments": 1, "deadlines": 1, "grades": 3},
    }  # fmt: skip


def test_push_counters_rise_and_are_separate_from_message_counters(phone, fcm):
    device_id, _secret = phone
    sent, _ = fcm
    push.register(device_id, TOKEN)
    push.send_hint("coach")
    push.send_hint("courses")
    counters = [
        e2e.parse_header(base64.b64decode(m["message"]["data"]["h"])).counter for _, m in sent
    ]
    assert counters == [1, 2] and devices.load(device_id).sent == 0


def test_no_push_without_an_address_for_revoked_devices_or_unknown_events(phone, fcm):
    device_id, _ = phone
    sent, _ = fcm
    assert push.send_hint("answer") == 0  # no address yet
    push.register(device_id, TOKEN)
    assert push.send_hint("something-else") == 0
    devices.revoke(device_id.hex(), NOW)
    assert devices.load(device_id).push_token is None
    assert push.send_hint("answer") == 0 and sent == []


def test_an_address_the_service_no_longer_knows_is_forgotten(phone, fcm):
    device_id, _ = phone
    _, status = fcm
    push.register(device_id, TOKEN)
    status["code"] = 404
    assert push.send_hint("answer") == 0
    assert devices.load(device_id).push_token is None


def test_failures_never_reach_the_caller(phone, monkeypatch):
    device_id, _ = phone
    push.register(device_id, TOKEN)

    def broken():
        raise google.FirebaseConfigError("missing")

    monkeypatch.setattr(google, "read_project", broken)
    assert push.send_hint("answer") == 0


def test_notifications_with_an_event_wake_the_phone(phone, fcm):
    device_id, secret = phone
    sent, _ = fcm
    push.register(device_id, TOKEN)
    notifications.send(
        "LifeOS: session reminder", "fixed text", "", None, event="reminder", ref="abc"
    )
    notifications.send("LifeOS: other", "fixed text")  # no event: no push
    assert len(sent) == 1
    hint = e2e.open_sealed(secret, base64.b64decode(sent[0][1]["message"]["data"]["h"]))
    assert hint.payload == {"e": "reminder", "c": "reminders", "o": "reminder", "r": "abc"}


def test_an_event_is_one_push_the_script_does_not_push_again(tmp_path, monkeypatch):
    log = tmp_path / "script.log"
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf "%s\\n" "${{LIFEOS_NOTIFY_PUSH:-on}}" >> "{log}"\n')
    script.chmod(0o755)
    hints = []
    monkeypatch.setattr(push, "send_hint", lambda *a, **k: hints.append(a))
    monkeypatch.delenv("LIFEOS_NOTIFY_PUSH")
    notifications.send("LifeOS: new in your courses", "1 new file.", "", script, event="courses")
    notifications.send("LifeOS: allowed folders changed", "text", "", script)  # no event
    assert len(hints) == 1 and log.read_text().splitlines() == ["off", "on"]


def test_notice_endpoint_seals_title_and_link(client, auth, phone, fcm):
    device_id, secret = phone
    sent, _ = fcm
    push.register(device_id, TOKEN)
    assert client.post("/push/notify", json={}).status_code == 401
    body = {"title": "[HUMAN] #1 Example", "link": "https://example.org/1"}
    assert client.post("/push/notify", headers=auth, json=body).json() == {"phones_reached": 1}
    raw = json.dumps(sent[0][1])
    assert "Example" not in raw and "example.org/1" not in raw
    hint = e2e.open_sealed(secret, base64.b64decode(sent[0][1]["message"]["data"]["h"]))
    assert hint.payload == {"e": "notice", "c": "reminders", "o": "notice",
                            "t": "[HUMAN] #1 Example", "l": "https://example.org/1"}  # fmt: skip


def test_a_finished_run_wakes_the_phone_unless_switched_off(
    client, auth, phone, fcm, ask_dir, write_session
):
    from conftest import user_line

    device_id, _ = phone
    sent, _ = fcm
    push.register(device_id, TOKEN)
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    assert (
        client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "x"}).status_code
        == 200
    )
    deadline = time.time() + 5
    while not sent and time.time() < deadline:
        time.sleep(0.02)
    assert len(sent) == 1
    client.patch(f"/sessions/{session}", headers=auth, json={"notify_replies": False})
    client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "y"})
    time.sleep(0.3)
    assert len(sent) == 1
