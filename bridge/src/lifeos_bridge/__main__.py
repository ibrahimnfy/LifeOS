"""Command-line entry point: ``lifeos-bridge``.

Without arguments it starts the server. ``lifeos-bridge pair [name]`` pairs a
phone, ``lifeos-bridge devices`` lists the paired phones and
``lifeos-bridge devices revoke <id>`` unpairs one, and ``lifeos-bridge
release`` registers a new release build of the app for the phones.
"""

from __future__ import annotations

import sys
from datetime import datetime

import uvicorn

from . import devices
from .app import create_app
from .config import ConfigError, load_settings


def _when(seconds: int | None) -> str:
    return datetime.fromtimestamp(seconds).strftime("%Y-%m-%d %H:%M") if seconds else "never"


def devices_command(args: list[str]) -> int:
    if args[:1] == ["revoke"] and len(args) == 2:
        try:
            device = devices.revoke(args[1])
        except devices.PairingError as error:
            print(f"lifeos-bridge: {error}", file=sys.stderr)
            return 1
        print(f"revoked: {device.id.hex()[:8]}  {device.name}")
        return 0
    if args:
        print("usage: lifeos-bridge devices [revoke <id>]", file=sys.stderr)
        return 2
    found = devices.list_devices()
    if not found:
        print("no paired devices")
    for device in found:
        state = "revoked" if device.revoked else "paired"
        print(
            f"{device.id.hex()[:8]}  {state:8}  {device.name:20}  "
            f"paired {_when(device.paired_at)}  last seen {_when(device.last_seen)}"
        )
    return 0


def pair_command(args: list[str]) -> int:
    """Pair a phone: QR code in the terminal, comparison code, confirmation."""
    from . import google, mailbox, relay

    name = " ".join(args) or "phone"
    try:
        box, info = mailbox.Mailbox(), relay.relay_info()
    except google.FirebaseConfigError as error:
        print(f"lifeos-bridge: {error}", file=sys.stderr)
        return 2

    def confirm(code: str) -> bool:
        print(f"\nThe phone shows a six-digit code. This computer shows:  {code}")
        return input("Do both codes match? Type yes to pair: ").strip().lower() == "yes"

    try:
        device = relay.pair(box, name, info, confirm, print)
    except (mailbox.MailboxError, devices.PairingError) as error:
        print(f"lifeos-bridge: {error}", file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print("\nCancelled. Nothing was stored.", file=sys.stderr)
        return 1
    return 0 if device else 1


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] == ["devices"]:
        return devices_command(argv[1:])
    if argv[:1] == ["pair"]:
        return pair_command(argv[1:])
    if argv[:1] == ["release"]:
        from . import release

        return release.command(argv[1:])
    if argv:
        print(
            "usage: lifeos-bridge [pair [name] | devices [revoke <id>] | release [--build]]",
            file=sys.stderr,
        )
        return 2
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"lifeos-bridge: configuration error: {exc}", file=sys.stderr)
        return 2
    # The access log records method, path and status only. Prompts travel in
    # request bodies and are never logged.
    uvicorn.run(create_app(settings), host=settings.host, port=settings.port, log_level="info")
    return 0


if __name__ == "__main__":
    sys.exit(main())
