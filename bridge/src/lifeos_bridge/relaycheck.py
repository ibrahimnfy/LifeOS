"""Connection test for the relay (``scripts/relay-check``).

Checks, with ordinary HTTPS requests only, whether the Realtime Database
mailbox and the push service can be used from the network this computer is
on (docs/research/connectivity.md, section 7). Certificate verification is
never disabled and nothing works around a filter: a failed check is a
result. The test writes a few small random values under one scratch path
of the owner's database and deletes them again.

Nothing identifying is printed or stored: no project name, address or
certificate issuer.
"""

from __future__ import annotations

import argparse
import http.client
import itertools
import json
import secrets
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from . import google
from .config import config_dir

PUBLIC_ISSUERS = ("Google Trust Services",)
RESULT_FILE = "relay-check.json"


def _request(method: str, url: str, token: str | None = None, body: object = None,
             timeout: float = 20) -> tuple[int, bytes, float]:  # fmt: skip
    """One HTTPS request; returns status, body and seconds. Raises on network failure."""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers=headers)  # noqa: S310
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310
            return response.status, response.read(), time.monotonic() - started
    except urllib.error.HTTPError as error:
        return error.code, error.read(), time.monotonic() - started


def check_certificate(host: str) -> dict:
    """Does the system trust the certificate, and is it the provider's own or a re-signed one?"""
    context = ssl.create_default_context()
    with (
        socket.create_connection((host, 443), timeout=15) as raw,
        context.wrap_socket(raw, server_hostname=host) as tls,
    ):
        issuer = dict(item[0] for item in tls.getpeercert().get("issuer", ()))
        organisation = issuer.get("organizationName", "")
        return {
            "trusted_by_system": True,
            "issued_by_provider": any(name in organisation for name in PUBLIC_ISSUERS),
            "tls": tls.version(),
        }


class Stream:
    """A server-sent-events listener on one database path (plain HTTPS)."""

    def __init__(self, url: str, token: str) -> None:
        self.events: list[tuple[float, str, str]] = []
        self.error: str | None = None
        self.opened = threading.Event()
        self.closed_at: float | None = None
        self._stop = False
        self._thread = threading.Thread(target=self._run, args=(url, token), daemon=True)
        self._thread.start()

    def _run(self, url: str, token: str) -> None:
        try:
            for _ in range(4):  # the endpoint may answer with a redirect first
                parts = urllib.parse.urlsplit(url)
                connection = http.client.HTTPSConnection(parts.netloc, timeout=120)
                path = parts.path + ("?" + parts.query if parts.query else "")
                connection.request("GET", path, headers={
                    "Accept": "text/event-stream", "Authorization": f"Bearer {token}"})  # fmt: skip
                response = connection.getresponse()
                if response.status in (301, 302, 307, 308):
                    url = response.getheader("Location") or ""
                    connection.close()
                    continue
                break
            if response.status != 200:
                self.error = f"status {response.status}"
                self.opened.set()
                return
            self.opened.set()
            name = ""
            while not self._stop:
                line = response.readline()
                if not line:
                    break
                text = line.decode(errors="replace").rstrip("\r\n")
                if text.startswith("event:"):
                    name = text[6:].strip()
                elif text.startswith("data:"):
                    self.events.append((time.monotonic(), name, text[5:].strip()))
        except (OSError, http.client.HTTPException) as error:
            self.error = type(error).__name__
        finally:
            self.closed_at = time.monotonic()
            self.opened.set()

    def stop(self) -> None:
        self._stop = True


def run_checks(idle_seconds: int) -> dict:
    results: dict[str, dict] = {}

    def record(name: str, action) -> dict | None:
        try:
            results[name] = {"ok": True, **(action() or {})}
        except Exception as error:  # every failure is a result
            results[name] = {"ok": False, "error": type(error).__name__}
            return None
        return results[name]

    project = google.read_project()
    base = project["database_url"]
    host = urllib.parse.urlsplit(base).netloc
    scratch = f"{base}/lifeos-relay-check/{secrets.token_hex(8)}"

    record("certificate_database", lambda: check_certificate(host))
    record("certificate_push", lambda: check_certificate("fcm.googleapis.com"))

    tokens: dict[str, str] = {}

    def get_tokens() -> dict:
        started = time.monotonic()
        tokens["db"] = google.access_token(google.DATABASE_SCOPES)
        tokens["fcm"] = google.access_token(google.MESSAGING_SCOPES)
        return {"seconds": round(time.monotonic() - started, 2)}

    if record("access_tokens", get_tokens) is None:
        return results
    token = tokens["db"]

    def expect(status: int, wanted: tuple[int, ...]) -> None:
        if status not in wanted:
            raise RuntimeError(f"status {status}")

    def round_trip() -> dict:
        status, _, write = _request("PUT", f"{scratch}/probe.json", token, {"v": "x" * 64})
        expect(status, (200,))
        status, body, read = _request("GET", f"{scratch}/probe.json", token)
        expect(status, (200,))
        if json.loads(body) != {"v": "x" * 64}:
            raise RuntimeError("value mismatch")
        return {"write_ms": round(write * 1000), "read_ms": round(read * 1000)}

    record("database_write_read", round_trip)

    def big_value() -> dict:
        blob = secrets.token_urlsafe(750_000)  # about 1 MB of text
        status, _, seconds = _request("PUT", f"{scratch}/big.json", token, blob, timeout=60)
        expect(status, (200,))
        _request("DELETE", f"{scratch}/big.json", token)
        return {"bytes": len(blob), "write_ms": round(seconds * 1000)}

    record("database_one_megabyte_value", big_value)

    def streaming() -> dict:
        stream = Stream(f"{scratch}/stream.json", token)
        if not stream.opened.wait(30) or stream.error:
            raise RuntimeError(stream.error or "the stream did not open")
        time.sleep(1.0)
        sent = []
        for index in range(5):
            sent.append(time.monotonic())
            _request("PUT", f"{scratch}/stream/m{index}.json", token, {"n": index})
            time.sleep(1.0)
        time.sleep(1.5)
        arrivals = [at for at, name, data in stream.events if name == "put" and '"n"' in data]
        delays = [round((got - at) * 1000) for got, at in zip(arrivals, sent, strict=False)]
        gaps = [b - a for a, b in itertools.pairwise(arrivals)]
        outcome = {
            "events_received": len(arrivals),
            "delay_ms": delays,
            "event_by_event": len(arrivals) == 5 and all(gap > 0.4 for gap in gaps),
        }
        idle_start = time.monotonic()
        while time.monotonic() - idle_start < idle_seconds and stream.closed_at is None:
            time.sleep(1)
        outcome["idle_seconds_watched"] = round(time.monotonic() - idle_start)
        outcome["survived_idle"] = stream.closed_at is None
        outcome["keep_alives"] = sum(1 for at, name, _ in stream.events
                                     if name == "keep-alive" and at >= idle_start)  # fmt: skip
        if stream.closed_at is None:  # a message after the idle period must still arrive
            before = len(stream.events)
            _request("PUT", f"{scratch}/stream/after.json", token, {"n": 99})
            time.sleep(2)
            outcome["delivers_after_idle"] = len(stream.events) > before
        stream.stop()
        if outcome["events_received"] == 0:
            raise RuntimeError("no events arrived")
        return outcome

    record("streaming", streaming)

    def polling() -> dict:
        parts = urllib.parse.urlsplit(f"{scratch}/empty.json")
        connection = http.client.HTTPSConnection(parts.netloc, timeout=20)
        times, sizes = [], []
        for _ in range(10):
            started = time.monotonic()
            connection.request("GET", parts.path, headers={"Authorization": f"Bearer {token}"})
            response = connection.getresponse()
            body = response.read()
            times.append(time.monotonic() - started)
            sizes.append(len(body) + sum(len(k) + len(v) + 4 for k, v in response.getheaders()))
            expect(response.status, (200,))
        connection.close()
        per_poll = round(sum(sizes) / len(sizes))
        return {
            "ms_per_poll": round(1000 * sum(times[1:]) / len(times[1:])),
            "response_bytes_per_poll": per_poll,
            "megabytes_per_day_at_2s": round(per_poll * 43200 / 1e6, 1),
            "megabytes_per_day_at_1s": round(per_poll * 86400 / 1e6, 1),
        }

    record("polling", polling)

    def reachable(url: str) -> dict:
        status, _, seconds = _request("POST", url, None, {})
        if status >= 500:
            raise RuntimeError(f"status {status}")
        return {"answered_with_status": status, "ms": round(seconds * 1000)}

    record("sign_in_endpoint", lambda: reachable(
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken"))  # fmt: skip
    record(
        "token_refresh_endpoint", lambda: reachable("https://securetoken.googleapis.com/v1/token")
    )

    def push_dry_run() -> dict:
        url = f"https://fcm.googleapis.com/v1/projects/{project['project_id']}/messages:send"
        message = {
            "validate_only": True,
            "message": {"token": "not-a-real-device", "data": {"k": "w"}},
        }
        status, _, seconds = _request("POST", url, tokens["fcm"], message)
        # 400 = the service understood us and rejected the made-up device: reachable and authorised.
        expect(status, (200, 400, 404))
        return {"answered_with_status": status, "ms": round(seconds * 1000)}

    record("push_service_dry_run", push_dry_run)
    record("cleanup", lambda: expect(_request("DELETE", f"{scratch}.json", token)[0], (200,)))
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="relay-check", description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--idle", type=int, default=75, help="seconds to watch an idle stream (default 75)"
    )
    args = parser.parse_args(argv)
    try:
        results = run_checks(max(0, min(args.idle, 3600)))
    except google.FirebaseConfigError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    for name, outcome in results.items():
        detail = ", ".join(f"{k}={v}" for k, v in outcome.items() if k != "ok")
        print(f"  {'ok  ' if outcome['ok'] else 'FAIL'}  {name}  {detail}")
    target: Path = config_dir() / RESULT_FILE
    target.write_text(
        json.dumps({"at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "results": results}, indent=1)
    )
    target.chmod(0o600)
    print(f"\nSaved: {target}")
    return 0 if all(outcome["ok"] for outcome in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
