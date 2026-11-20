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

EVENTS = frozenset(
    {"answer", "reminder", "coach", "courses", "signin", "notice", "approval", "question"}
)
MAX_TOKEN = 4096
MAX_REF = 300
TIMEOUT = 15


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


def message_for(device: devices.Device, event: str, extra: dict) -> dict:
    """The FCM message for one device: nothing readable, only the sealed hint."""
    blob = devices.seal_to(device.id, e2e.PUSH_HINT, "", {"e": event, **extra})
    return {
        "message": {
            "token": device.push_token,
            "data": {"h": base64.b64encode(blob).decode()},
            "android": {"priority": "HIGH"},
        }
    }


def send_hint(event: str, ref: str = "", **extra: str) -> int:
    """Wake every paired phone that has a push address. Returns how many were reached.

    Never raises and never blocks for long: a push that cannot be sent is not
    retried; the phone sees everything when it next connects.
    """
    if event not in EVENTS:
        return 0
    targets = [d for d in devices.list_devices() if not d.revoked and d.push_token]
    if not targets:
        return 0
    payload = {key: str(value)[:MAX_REF] for key, value in {"r": ref, **extra}.items() if value}
    reached = 0
    with contextlib.suppress(Exception):
        project = google.read_project()["project_id"]
        access = google.access_token(google.MESSAGING_SCOPES)
        url = f"https://fcm.googleapis.com/v1/projects/{project}/messages:send"
        for device in targets:
            with contextlib.suppress(Exception):
                status = _post(url, access, message_for(device, event, payload))
                if status == 200:
                    reached += 1
                elif status == 404:  # the address is no longer registered
                    _forget(device.id)
    return reached
