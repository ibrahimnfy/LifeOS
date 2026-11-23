"""Push notifications to paired phones through Firebase Cloud Messaging.

A push never carries readable content (docs/security.md, section 5.1). It is
a small hint, sealed with the device's own push key, saying what kind of
event happened and which item it concerns; the app wakes up, opens the hint,
fetches the content through the relay and builds the visible notification
itself. FCM "data" messages with high priority are used, so delivery works
while the app is closed.

The bridge calls the FCM HTTPS API itself, outbound, with a short-lived
access token of the owner's service account. The phone's push address is
stored with its device record and removed when the service says it is gone.
"""

from __future__ import annotations

import base64
import contextlib
import json
import urllib.error
import urllib.request

from . import devices, e2e, google

# Event kind → the Android notification channel it belongs to. The app shows a
# fixed, content-free text per kind on the lock screen (docs/security.md 5.1).
CHANNELS = {
    "answer": "chat",
    "question": "chat",
    "approval": "chat",
    "courses": "courses",
    "signin": "courses",
    "coach": "coach",
    "reminder": "reminders",
    "notice": "reminders",
    "update": "reminders",  # a new app release is ready
    # A question or approval was answered, expired or its run ended: the phone removes
    # the notification it showed for it (never shown itself).
    "cleared": "chat",
}
EVENTS = frozenset(CHANNELS)
# Event kind → the screen a tap opens; with ``r`` it names the item.
OPENS = {
    "answer": "session",
    "question": "session",
    "approval": "session",
    "courses": "courses",
    "signin": "moodle_signin",
    "coach": "coach",
    "reminder": "reminder",
    "notice": "notice",
    "update": "app_update",
    "cleared": "session",
}
MAX_TOKEN = 4096
MAX_REF = 300
MAX_COUNT = 9999
TIMEOUT = 15


# True in a process that must never wake the owner's phone: the guardrail check, whose
# approval probes open real approval requests (each pushed "a session waits for your
# approval" to the phone before this switch existed).
_silenced = False


def silence() -> None:
    """No push leaves this process from now on."""
    global _silenced
    _silenced = True


def hint_for(event: str, ref: str = "", opens: str | None = None,
             counts: dict | None = None, **extra: str) -> dict:  # fmt: skip
    """The sealed part of a push: kind, channel, screen to open, item, counts.

    ``counts`` (whole numbers per kind, e.g. new course files) travel only
    inside the sealed hint; the lock-screen text never shows them.
    """
    hint: dict = {"e": event, "c": CHANNELS[event], "o": opens or OPENS[event]}
    values = {"r": ref, **extra}
    hint |= {key: str(value)[:MAX_REF] for key, value in values.items() if value}
    if counts:
        hint["n"] = {
            str(kind)[:20]: min(int(number), MAX_COUNT)
            for kind, number in counts.items()
            if isinstance(number, int) and not isinstance(number, bool) and number > 0
        }
    return hint


def register(device_id: bytes, token: object) -> bool:
    """Remember the phone's push address. It arrives inside the sealed channel."""
    if not isinstance(token, str) or not 20 <= len(token) <= MAX_TOKEN or not token.isascii():
        return False
    with devices.LOCK:
        device = devices.load(device_id)
        if device is None or device.revoked:
            return False
        device.push_token = token
        devices.save(device)
    return True


def _forget(device_id: bytes) -> None:
    with devices.LOCK:
        device = devices.load(device_id)
        if device is not None:
            device.push_token = None
            devices.save(device)


def _post(url: str, access_token: str, message: dict) -> int:
    request = urllib.request.Request(  # noqa: S310
        url,
        data=json.dumps(message).encode(),
        headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:  # noqa: S310
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def message_for(device: devices.Device, hint: dict) -> dict:
    """The FCM message for one device: nothing readable, only the sealed hint."""
    blob = devices.seal_to(device.id, e2e.PUSH_HINT, "", hint)
    return {
        "message": {
            "token": device.push_token,
            "data": {"h": base64.b64encode(blob).decode()},
            "android": {"priority": "HIGH"},
        }
    }


def send_hint(
    event: str, ref: str = "", *, opens: str | None = None, counts: dict | None = None,
    **extra: str,
) -> int:  # fmt: skip
    """Wake every paired phone that has a push address. Returns how many were reached.

    Never raises and never blocks for long: a push that cannot be sent is not
    retried; the phone sees everything when it next connects.
    """
    if event not in EVENTS or _silenced:
        return 0
    targets = [d for d in devices.list_devices() if not d.revoked and d.push_token]
    if not targets:
        return 0
    payload = hint_for(event, ref, opens, counts, **extra)
    reached = 0
    with contextlib.suppress(Exception):
        project = google.read_project()["project_id"]
        access = google.access_token(google.MESSAGING_SCOPES)
        url = f"https://fcm.googleapis.com/v1/projects/{project}/messages:send"
        for device in targets:
            with contextlib.suppress(Exception):
                status = _post(url, access, message_for(device, payload))
                if status == 200:
                    reached += 1
                elif status == 404:  # the address is no longer registered
                    _forget(device.id)
    return reached
