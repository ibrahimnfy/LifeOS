# ruff: noqa: E501
"""The relay path with an in-memory mailbox and a stand-in local API."""

import http.server
import json
import shutil
import threading
import time
import urllib.request

import pytest

from lifeos_bridge import devices, e2e, mailbox, relay
from lifeos_bridge.__main__ import main as cli


class Phone:
    """The phone's side: scans the QR text, talks only through the mailbox."""

    def __init__(self, box, qr_text: str) -> None:
        data = json.loads(devices._unb64(qr_text[len(devices.QR_PREFIX) :]))
        self.box, self.relay = box, data["r"]
        self.id, self.secret = bytes.fromhex(data["d"]), devices._unb64(data["s"])
        self.counter, self.seen = 0, set()

    def seal(self, kind, payload, rid="r1"):
        self.counter += 1
        header = e2e.Header(self.id, e2e.PHONE_TO_BRIDGE, self.counter, kind)
        return e2e.seal(self.secret, header, int(time.time()), rid, payload)

    def hello(self, uid="uid123"):
        self.box.put(
            f"pair/{self.id.hex()}/hello", mailbox.encode(self.seal(e2e.HELLO, {"uid": uid}))
        )

    def request(self, method, path, body=None, query=None, rid="r1"):
        payload = {"m": method, "path": path, "q": query or {}, "body": body}
        blob = self.seal(e2e.REQUEST, payload, rid)
        self.box.put(f"lifeos/{self.id.hex()}/b/{mailbox.new_key()}", mailbox.encode(blob))

    def receive(self, until_end=True, timeout=5.0):
        """Take messages for the phone out of the mailbox (acknowledging by deleting)."""
        got, deadline = [], time.time() + timeout
        while time.time() < deadline:
            for key in self.box.keys(f"lifeos/{self.id.hex()}/p"):
                value = self.box.get(f"lifeos/{self.id.hex()}/p/{key}")
                self.box.delete(f"lifeos/{self.id.hex()}/p/{key}")
                message = e2e.open_sealed(self.secret, mailbox.decode(value))
                assert message.header.direction == e2e.BRIDGE_TO_PHONE
                got.append(message)
            if got and (not until_end or got[-1].header.kind in (e2e.END, e2e.ERROR)):
                return got
            time.sleep(0.02)
        return got


def fake_local(method, path, query, body):
    if path == "/whoami":
        return 200, "json", iter([{"status": "ok", "version": "test"}])
    if path == "/ask" and body.get("stream"):
        events = [("session", {"session_id": "s1"})]
        events += [("delta", {"text": f"word{i} "}) for i in range(50)]
        events += [("done", {"answer": "all words", "session_id": "s1"})]
        return 200, "sse", iter(events)
    if path == "/ask":
        return 200, "json", iter([{"answer": "echo: " + body["prompt"], "session_id": "s1"}])
    if path == "/sessions":
        return 200, "json", iter([{"sessions": ["x" * 1000] * 1500, "limit": query.get("limit")}])
    if path == "/tasks":
        raise RuntimeError("boom")
    return 404, "json", iter([{"error": "not_found"}])


@pytest.fixture
def paired(isolated_config):
    """A bridge relay with one paired phone, both on the same recording mailbox."""
    box = mailbox.MemoryMailbox()
    holder = {}

    def show(text):
        if "lifeos-pair:" in holder.get("qr", "") or not hasattr(show, "qr"):
            pass

    def run_pairing():
        original = devices.begin_pairing

        def begin(name, info, now=None):
            device_id, qr = original(name, info, now)
            holder["qr"] = qr
            return device_id, qr

        devices.begin_pairing = begin
        try:
            holder["device"] = relay.pair(
                box, "Test phone", {"k": "memory"}, lambda code: holder.setdefault("code", code) is not None,
                lambda text: None, wait_seconds=5, poll_seconds=0.01)  # fmt: skip
        finally:
            devices.begin_pairing = original

    thread = threading.Thread(target=run_pairing)
    thread.start()
    while "qr" not in holder:
        time.sleep(0.005)
    phone = Phone(box, holder["qr"])
    phone.hello()
    thread.join(10)
    service = relay.Relay(box, fake_local)
    service.start()
    yield service, phone, box, holder
    service.close()


def test_pairing_through_the_mailbox(paired, isolated_config):
    _service, phone, box, holder = paired
    device = holder["device"]
    assert device is not None and device.relay_uid == "uid123"
    assert len(holder["code"].replace(" ", "")) == 6
    assert box.get(f"access/{phone.id.hex()}") == "uid123"
    assert box.get(f"pair/{phone.id.hex()}/hello") is None  # the pairing mailbox is emptied
    (first,) = phone.receive()
    assert first.payload == {"paired": True, "name": "Test phone"}
    assert phone.relay == {"k": "memory"}


def test_request_and_answer(paired):
    _service, phone, box, _ = paired
    phone.receive()
    phone.request("GET", "/whoami")
    (answer,) = phone.receive()
    assert answer.header.kind == e2e.END and answer.request_id == "r1"
    assert answer.payload == {"status": 200, "body": {"status": "ok", "version": "test"}}
    assert box.keys(f"lifeos/{phone.id.hex()}/b") == []  # taken out as soon as it was handled

    phone.request("POST", "/ask", {"prompt": "hello"}, rid="r2")
    (answer,) = phone.receive()
    assert answer.payload["body"]["answer"] == "echo: hello" and answer.request_id == "r2"


def test_streamed_answers_arrive_in_few_chunks(paired, monkeypatch):
    _service, phone, _box, _ = paired
    phone.receive()
    phone.request("POST", "/ask", {"prompt": "x", "stream": True})
    messages = phone.receive()
    kinds = [m.payload.get("event") for m in messages[:-1]]
    assert kinds[0] == "session" and kinds[-1] == "done"
    text = "".join(m.payload["data"]["text"] for m in messages if m.payload.get("event") == "delta")
    assert text == "".join(f"word{i} " for i in range(50))
    assert len([k for k in kinds if k == "delta"]) < 10  # gathered, not one message per word
    assert messages[-1].header.kind == e2e.END and messages[-1].payload == {
        "status": 200,
        "stream": True,
    }


def test_large_answers_are_split(paired):
    _service, phone, _box, _ = paired
    phone.receive()
    phone.request("GET", "/sessions", query={"limit": 5})
    messages = phone.receive()
    parts = [m.payload for m in messages[:-1]]
    assert len(parts) >= 3 and [p["i"] for p in parts] == list(range(len(parts)))
    body = json.loads("".join(p["part"] for p in parts))
    assert body["limit"] == 5 and len(body["sessions"]) == 1500
    assert messages[-1].payload == {"status": 200, "parts": len(parts)}


def test_paths_outside_the_list_and_failures(paired):
    _service, phone, _box, _ = paired
    phone.receive()
    for index, (method, path) in enumerate(
        [
            ("GET", "/health/../etc"),
            ("GET", "/unknown"),
            ("TRACE", "/ask"),
            ("GET", "/files/x"),
            ("GET", "/askx"),
        ]
    ):
        phone.request(method, path, rid=f"bad{index}")
        (answer,) = phone.receive()
        assert answer.header.kind == e2e.ERROR and answer.payload["status"] == 404, path
    phone.request("GET", "/tasks", rid="boom")
    (answer,) = phone.receive()
    assert answer.payload == {"status": 502, "error": "relay_failed"}


def test_forged_replayed_and_foreign_entries_cause_nothing(paired, isolated_config):
    _service, phone, box, _ = paired
    phone.receive()
    path = f"lifeos/{phone.id.hex()}/b"
    genuine = phone.seal(e2e.REQUEST, {"m": "GET", "path": "/whoami", "q": {}, "body": None})
    box.put(f"{path}/{mailbox.new_key()}", mailbox.encode(genuine))
    assert len(phone.receive()) == 1
    box.put(f"{path}/{mailbox.new_key()}", mailbox.encode(genuine))  # replay
    box.put(f"{path}/{mailbox.new_key()}", "not base64 !!")
    box.put(f"{path}/{mailbox.new_key()}", mailbox.encode(b"junk" * 30))
    stranger = e2e.seal(
        e2e.random_bytes(32), e2e.Header(phone.id, 1, 99, e2e.REQUEST), int(time.time()), "r", {}
    )
    box.put(f"{path}/{mailbox.new_key()}", mailbox.encode(stranger))
    time.sleep(0.3)
    assert phone.receive(timeout=0.3) == []
    assert box.keys(path) == []  # everything is removed from the mailbox, answered or not
    drops = json.loads((isolated_config / "relay-drops.json").read_text())
    assert drops["replay"] == 1 and drops["authentication"] == 1


def test_the_mailbox_never_holds_plaintext(paired):
    _service, phone, box, _ = paired
    phone.receive()
    phone.request("POST", "/ask", {"prompt": "secret-prompt-4471"})
    phone.receive()
    everything = json.dumps(box.recorded)
    for word in ("secret-prompt-4471", "echo:", "Test phone", "/ask", devices._b64(phone.secret)):
        assert word not in everything


def test_revoking_stops_listening_and_cleans_the_mailbox(paired, capsys):
    service, phone, box, _ = paired
    phone.receive()
    assert cli(["devices", "revoke", phone.id.hex()[:8]]) == 0
    service.rescan()
    assert box.get(f"access/{phone.id.hex()}") is None
    phone.request("GET", "/whoami")
    time.sleep(0.2)
    assert phone.receive(timeout=0.3) == []


def test_sweep_removes_old_entries_only(paired):
    service, phone, box, _ = paired
    phone.receive()
    service.rescan()
    service.stop.set()  # the service sweeps by itself too; here only the explicit call counts
    service._thread.join(5)
    side = f"lifeos/{phone.id.hex()}/p"
    box.put(f"{side}/{mailbox.new_key(time.time() - 3600)}", "old")
    box.put(f"{side}/{mailbox.new_key()}", "new")
    box.put(f"{side}/strange-key", "x")
    assert service.sweep() == 2
    assert len(box.keys(side)) == 1


def test_pairing_without_a_phone_or_without_confirmation_stores_nothing(isolated_config):
    box = mailbox.MemoryMailbox()
    assert (
        relay.pair(
            box, "x", {}, lambda code: True, lambda text: None, wait_seconds=0.1, poll_seconds=0.01
        )
        is None
    )
    assert devices.list_devices() == [] and not list(
        (isolated_config / "devices").glob("pending-*")
    )


def test_relay_is_not_started_without_the_firebase_files(settings, isolated_config):
    assert relay.configured() is False
    assert relay.start_if_configured(settings) is None


def test_pair_command_needs_the_firebase_files(isolated_config, capsys):
    assert cli(["pair", "My", "phone"]) == 2
    assert "settings file" in capsys.readouterr().err


def test_keys_and_key_times():
    key = mailbox.new_key(1_800_000_000.5)
    assert mailbox.key_time(key) == 1_800_000_000.5 and mailbox.key_time("strange-key") is None
    assert mailbox.decode(mailbox.encode(b"\x00\x01")) == b"\x00\x01" and mailbox.decode(5) is None


# ---------------------------------------------------------------- file transfer


@pytest.fixture
def with_file(paired, settings, ask_dir, monkeypatch):
    from lifeos_bridge import files

    monkeypatch.setattr(files, "CHUNK_BYTES", 1000)
    monkeypatch.setattr(relay, "FILE_POLL_SECONDS", 0.01)
    service, phone, box, _ = paired
    content = bytes(range(256)) * 20  # 5120 bytes: six chunks
    (ask_dir / "Slides 2027.pdf").write_bytes(content)
    offers = files.Offers(settings)
    service.offers = offers
    _, cards, _ = offers.from_answer("[[file: Slides 2027.pdf]]", "session-1", ask_dir)
    phone.receive()
    return service, phone, box, cards[0]["offer"], content


def test_file_arrives_in_sealed_chunks_and_matches_its_hash(with_file):
    import hashlib

    _service, phone, box, offer, content = with_file
    phone.request("GET", f"/files/{offer}", query={"session_id": "session-1"})
    messages = phone.receive(timeout=10)
    chunks = [m.payload for m in messages[:-1]]
    assert [c["i"] for c in chunks] == list(range(6)) and all(c["n"] == 6 for c in chunks)
    data = b"".join(mailbox.decode(c["b64"]) for c in chunks)
    assert data == content
    assert messages[-1].payload == {
        "status": 200,
        "file": {"size": 5120, "chunks": 6, "sha256": hashlib.sha256(content).hexdigest()},
    }
    assert box.keys(f"lifeos/{phone.id.hex()}/p") == []
    recorded = json.dumps(box.recorded)
    assert "Slides" not in recorded and mailbox.encode(content[:300]) not in recorded


def test_only_a_few_chunks_wait_and_a_stalled_transfer_is_cleaned_up(with_file, monkeypatch):
    _service, phone, box, offer, _ = with_file
    monkeypatch.setattr(relay, "FILE_STALL_SECONDS", 0.3)
    phone.request("GET", f"/files/{offer}", query={"session_id": "session-1"})
    side = f"lifeos/{phone.id.hex()}/p"
    time.sleep(0.15)  # the phone takes nothing
    assert len(box.keys(side)) == relay.FILE_WINDOW
    time.sleep(1.0)
    (left,) = phone.receive(timeout=2)  # only the notice that the transfer was abandoned
    assert left.header.kind == e2e.ERROR and left.payload["error"] == "transfer_abandoned"
    assert box.keys(side) == []


def test_a_transfer_can_continue_at_a_chunk(with_file):
    _service, phone, _box, offer, content = with_file
    phone.request("GET", f"/files/{offer}", query={"session_id": "session-1", "from": 4})
    messages = phone.receive(timeout=10)
    assert [m.payload["i"] for m in messages[:-1]] == [4, 5]
    assert b"".join(mailbox.decode(m.payload["b64"]) for m in messages[:-1]) == content[4000:]


def test_foreign_expired_or_unknown_offers_send_nothing(with_file):
    _service, phone, _box, offer, _ = with_file
    for index, (path, query) in enumerate(
        [(f"/files/{offer}", {"session_id": "another"}), ("/files/unknown", {"session_id": "session-1"}),
         (f"/files/{offer}/x", {"session_id": "session-1"}), (f"/files/{offer}", {"session_id": "session-1", "from": "x"})]
    ):  # fmt: skip
        phone.request("GET", path, query=query, rid=f"f{index}")
        (answer,) = phone.receive()
        assert answer.header.kind == e2e.ERROR and answer.payload["status"] in (400, 404)


def test_answers_reach_the_mailbox_in_counter_order(paired):
    """Several workers answer at once; keys (time order) and counters must agree."""
    service, phone, box, _ = paired
    phone.receive()
    threads = [
        threading.Thread(target=service.send, args=(phone.id, e2e.CHUNK, f"r{i}", {"n": i}))
        for i in range(40)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    side = f"lifeos/{phone.id.hex()}/p"
    counters = [
        e2e.parse_header(mailbox.decode(box.get(f"{side}/{key}"))).counter
        for key in sorted(
            box.keys(side),
            key=lambda k: (
                k.split("-")[0],
                box.recorded.index((f"{side}/{k}", box.get(f"{side}/{k}"))),
            ),
        )
    ]
    assert counters == sorted(counters) and len(counters) == 40


def test_the_phone_registers_its_push_address_through_the_sealed_channel(paired):
    _, phone, box, _ = paired
    phone.receive()
    address = "fcm-registration-token-" + "y" * 40
    phone.request("POST", "/push/token", {"token": address})
    (answer,) = phone.receive()
    assert answer.payload == {"status": 200, "body": {"registered": True}}
    assert devices.load(phone.id).push_token == address
    assert address not in json.dumps(box.recorded)
    phone.request("POST", "/push/token", {"token": "short"}, rid="r2")
    (refused,) = phone.receive()
    assert refused.header.kind == e2e.ERROR


# ---- the audit log -----------------------------------------------------------------


def audit_records(config):
    return [
        json.loads(line)
        for path in sorted((config / "audit").glob("*.jsonl"))
        for line in path.read_text().splitlines()
    ]


def wait_for(config, tool, count):
    for _ in range(200):
        found = [r for r in audit_records(config) if r["tool"] == tool]
        if len(found) >= count:
            return found
        time.sleep(0.01)
    raise AssertionError(f"{count} {tool} records were not written")


def test_every_phone_request_is_recorded_without_bodies_or_ids(paired, isolated_config, settings):
    from lifeos_bridge.app import create_app

    service, phone, _box, _ = paired
    service.routes = relay.route_table(create_app(settings))
    phone.receive()
    asks = [
        ("GET", "/whoami", None, None),
        ("POST", "/ask", {"prompt": "a private prompt"}, None),
        ("GET", "/notes/i-0123456789ab", None, {"query": "private words"}),
        ("PATCH", "/tasks/t-20270115-01", {"title": "private title"}, None),
        ("GET", "/unknown/thing", None, None),
        ("POST", "/push/token", {"token": "fcm-" + "z" * 40}, None),
        ("GET", "/files/offer-id-secret", None, {"session_id": "s"}),
    ]
    for index, (method, path, body, query) in enumerate(asks):
        phone.request(method, path, body, query, rid=f"r{index}")
        phone.receive()
        wait_for(isolated_config, "RelayRequest", index + 1)  # written after the answer
    records = wait_for(isolated_config, "RelayRequest", len(asks))
    assert [
        (r["detail"]["method"], r["detail"]["route"], r["detail"]["status"]) for r in records
    ] == [
        ("GET", "/whoami", 200),
        ("POST", "/ask", 200),
        ("GET", "/notes/{note_id}", 404),
        ("PATCH", "/tasks/{task_id}", 404),
        ("GET", "(refused)", 404),
        ("POST", "/push/token", 200),
        ("GET", "/files/{offer_id}", 404),
    ]
    assert all(r["detail"]["device"] == phone.id.hex() and r["session_id"] == "" for r in records)
    assert all(set(r["detail"]) == {"device", "method", "route", "status"} for r in records)
    log = "".join(p.read_text() for p in (isolated_config / "audit").glob("*.jsonl"))
    for secret in ("private", "0123456789ab", "20270115", "unknown/thing", "zzzz", "offer-id"):
        assert secret not in log, secret


def test_an_unwritable_audit_log_does_not_stop_the_answers(paired, isolated_config):
    _service, phone, _box, _ = paired
    phone.receive()
    shutil.rmtree(isolated_config / "audit")
    (isolated_config / "audit").write_text("not a folder")
    phone.request("GET", "/whoami")
    (answer,) = phone.receive()
    assert answer.payload["status"] == 200


# ---- #157: streams the phone abandons free their worker ------------------------------


class EndlessEvents:
    """A long run's server-sent events: a keep-alive now and then until it ends or closes."""

    def __init__(self, every=0.05):
        self.every, self.closed, self.finished = every, threading.Event(), threading.Event()
        self.run_over = threading.Event()

    def close(self):
        self.closed.set()

    def __iter__(self):
        # Like the real reader: dropping the reader (closing it) closes the connection,
        # which would stop the run behind it.
        try:
            yield "session", {"session_id": "s1"}
            while not self.closed.is_set():
                if self.run_over.is_set():
                    yield "done", {"answer": "finished"}
                    self.finished.set()
                    return
                time.sleep(self.every)
                yield "keepalive", {}
        finally:
            if not self.finished.is_set():
                self.closed.set()


def until(condition, seconds=5.0):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if condition():
            return
        time.sleep(0.01)
    raise AssertionError("condition not reached")


def answer_of(phone, rid, timeout=5.0):
    """Take only this request's messages out of the mailbox (the others stay unread)."""
    got, deadline = [], time.time() + timeout
    side = f"lifeos/{phone.id.hex()}/p"
    while time.time() < deadline:
        for key in phone.box.keys(side):
            value = phone.box.get(f"{side}/{key}")
            if value is None:
                continue
            message = e2e.open_sealed(phone.secret, mailbox.decode(value))
            if message.request_id == rid:
                phone.box.delete(f"{side}/{key}")
                got.append(message)
        if got and got[-1].header.kind in (e2e.END, e2e.ERROR):
            return got
        time.sleep(0.02)
    return got


@pytest.fixture
def long_runs(paired):
    service, phone, _box, _ = paired
    phone.receive()
    streams = []

    def local(method, path, query, body):
        if path == "/whoami":
            return 200, "json", iter([{"status": "ok"}])
        events = EndlessEvents()
        streams.append((path, events))
        return 200, "sse", events

    service.call_local = local
    return service, phone, streams


def test_detached_streams_free_every_worker_and_the_run_goes_on(long_runs):
    _service, phone, streams = long_runs
    for index in range(relay.MAX_WORKERS):  # lock, unlock, lock …: every worker holds a stream
        path = "/sessions/s1/live" if index % 2 else "/sessions/s1/messages"
        phone.request("GET" if index % 2 else "POST", path, rid=f"s{index}")
        until(lambda index=index: len(streams) == index + 1)  # one after the other
    phone.request("GET", "/whoami", rid="who")
    assert answer_of(phone, "who", timeout=0.5) == []  # every worker is busy
    for index in range(relay.MAX_WORKERS):
        phone.request("POST", relay.DETACH_PATH, {"r": f"s{index}"}, rid=f"d{index}")
        (done,) = answer_of(phone, f"d{index}")
        assert done.payload == {"status": 200, "body": {"detached": True}}
    (who,) = answer_of(phone, "who")
    assert who.payload["body"] == {"status": "ok"}  # a worker was free again
    followers = [e for path, e in streams if path.endswith("/live")]
    chats = [e for path, e in streams if not path.endswith("/live")]
    assert all(e.closed.is_set() for e in followers)  # a follower is simply closed
    assert not any(e.closed.is_set() for e in chats)  # a chat stream is never cut …
    for events in chats:
        events.run_over.set()
    assert all(e.finished.wait(5) for e in chats)  # … its run goes on to its end
    phone.request("POST", relay.DETACH_PATH, {"r": "nothing"}, rid="d-none")
    assert answer_of(phone, "d-none")[0].payload["body"] == {"detached": False}


def test_a_stream_nobody_reads_frees_its_worker_by_itself(long_runs, monkeypatch):
    _service, phone, streams = long_runs
    monkeypatch.setattr(relay, "STREAM_WINDOW", 3)
    monkeypatch.setattr(relay, "STREAM_STALL_SECONDS", 0.3)
    monkeypatch.setattr(relay, "STREAM_CHECK_SECONDS", 0.05)
    for index in range(relay.MAX_WORKERS + 2):  # more streams than workers, none read
        phone.request("POST", "/sessions/s1/messages", rid=f"s{index}")
        time.sleep(0.01)  # one after the other, as a phone sends them
    until(lambda: len(streams) == relay.MAX_WORKERS + 2, seconds=10)  # all got a worker
    phone.request("GET", "/whoami", rid="who")
    (who,) = answer_of(phone, "who", timeout=10)
    assert who.payload["status"] == 200  # the stalled streams let go of their workers
    side = f"lifeos/{phone.id.hex()}/p"

    def left():
        # Read while other streams are still being cleaned up: an entry may go meanwhile.
        values = [phone.box.get(f"{side}/{key}") for key in phone.box.keys(side)]
        return [e2e.open_sealed(phone.secret, mailbox.decode(v)) for v in values if v is not None]

    count = relay.MAX_WORKERS + 2
    until(lambda: len(left()) == count and all(m.header.kind == e2e.ERROR for m in left()))
    # Of each abandoned stream only one last word stays: follow the run again.
    assert sorted(m.request_id for m in left()) == [f"s{i}" for i in range(count)]
    assert all(m.payload == {"status": 504, "error": "stream_abandoned"} for m in left())
    assert not any(e.closed.is_set() for _path, e in streams)  # the runs were not cut
    for _path, events in streams:
        events.run_over.set()


def test_a_detach_before_a_worker_is_free_is_remembered(long_runs):
    """#158: every worker busy; the phone leaves two requests that still wait for one."""
    service, phone, streams = long_runs
    for index in range(relay.MAX_WORKERS):
        phone.request("POST", "/sessions/s1/messages", rid=f"s{index}")
        until(lambda index=index: len(streams) == index + 1)
    phone.request("GET", "/sessions/s9/live", rid="follow")  # waits for a worker
    phone.request("POST", "/sessions/s9/messages", rid="chat")  # waits too
    until(lambda: len(service._waiting) == 2)
    for rid in ("follow", "chat"):
        phone.request("POST", relay.DETACH_PATH, {"r": rid}, rid=f"d-{rid}")
        assert answer_of(phone, f"d-{rid}")[0].payload["body"] == {"detached": True}
    phone.request("POST", relay.DETACH_PATH, {"r": "s0"}, rid="d-s0")  # one worker frees
    answer_of(phone, "d-s0")
    until(lambda: len(streams) == relay.MAX_WORKERS + 1)
    paths = [path for path, _e in streams]
    assert "/sessions/s9/live" not in paths  # a follower that nobody reads: not started
    chat = dict(streams)["/sessions/s9/messages"]  # the owner's message still runs …
    phone.request("GET", "/whoami", rid="who")
    (who,) = answer_of(phone, "who")  # … without holding the only free worker
    assert who.payload["status"] == 200
    assert not chat.closed.is_set()
    chat.run_over.set()
    assert chat.finished.wait(5)  # read to its end
    assert answer_of(phone, "follow", timeout=0.3) == []  # nothing sent for either
    assert answer_of(phone, "chat", timeout=0.3) == []
    assert service._early == {} and service._waiting == set()
    for _path, events in streams:
        events.run_over.set()


def test_early_detaches_are_bounded(long_runs, monkeypatch):
    service, phone, _streams = long_runs
    monkeypatch.setattr(relay, "EARLY_MAX", 2)
    for rid in ("a", "b", "c"):
        service._waiting.add((phone.id, rid))
        assert service.detach(phone.id, rid) is True
    assert list(service._early) == [(phone.id, "b"), (phone.id, "c")]
    assert service.detach(phone.id, "never-sent") is False


def test_a_cancelled_file_transfer_stops(with_file):
    _service, phone, box, offer, _ = with_file
    phone.request("GET", f"/files/{offer}", query={"session_id": "session-1"}, rid="file")
    side = f"lifeos/{phone.id.hex()}/p"
    until(lambda: len(box.keys(side)) == relay.FILE_WINDOW)  # the phone takes nothing
    phone.request("POST", relay.DETACH_PATH, {"r": "file"}, rid="cancel")
    deadline = time.time() + 5
    while time.time() < deadline and box.keys(side):
        time.sleep(0.02)
        answer_of(phone, "cancel", timeout=0.05)
    assert box.keys(side) == []  # chunks removed, and no "abandoned" notice
    phone.request("GET", "/whoami", rid="who")
    assert answer_of(phone, "who")[0].payload["status"] == 200


def test_closing_a_follower_wakes_its_waiting_read():
    """On a real connection: the worker that waits for the next event is freed at once."""
    release = threading.Event()

    class Endless(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            self.wfile.write(b'event: live\ndata: {"running": true}\n\n')
            self.wfile.flush()
            release.wait(10)  # a run with nothing to say for a long time

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Endless)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        url = f"http://127.0.0.1:{server.server_address[1]}/"
        events = relay.SseEvents(urllib.request.urlopen(url, timeout=30))
        got, done = [], threading.Event()

        def read():
            got.extend(events)
            done.set()

        threading.Thread(target=read, daemon=True).start()
        until(lambda: got == [("live", {"running": True})])
        time.sleep(0.1)  # the reader now waits for the next line
        started = time.monotonic()
        events.close()
        assert done.wait(2)
        assert time.monotonic() - started < 2
    finally:
        release.set()
        server.shutdown()
        server.server_close()


def test_pairing_and_revocation_are_recorded(paired, isolated_config):
    _service, phone, _box, _ = paired
    assert [r["detail"] for r in wait_for(isolated_config, "DevicePaired", 1)] == [
        {"device": phone.id.hex()}
    ]
    assert cli(["devices", "revoke", phone.id.hex()[:8]]) == 0
    assert [r["detail"] for r in wait_for(isolated_config, "DeviceRevoked", 1)] == [
        {"device": phone.id.hex()}
    ]
