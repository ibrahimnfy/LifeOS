"""Chat through the relay (#147): keep-alives, phone-chosen session ids, closed
interactions, and sessions open elsewhere. The stand-in claude only; nothing real runs."""

import asyncio
import json
import uuid

from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import app as app_module
from lifeos_bridge import claude, e2e, relay
from lifeos_bridge.app import create_app


def events_of(text):
    """The (event, data) pairs of a server-sent event stream."""
    found, event = [], None
    for line in text.splitlines():
        if line.startswith("event: "):
            event = line[7:]
        elif line.startswith("data: "):
            found.append((event, json.loads(line[6:])))
    return found


def test_keepalives_fill_silences_without_losing_or_reordering_anything():
    async def slow():
        yield "delta", {"text": "a"}
        await asyncio.sleep(0.25)
        yield "done", {"answer": "a"}

    async def broken():
        yield "delta", {"text": "b"}
        raise claude.ClaudeError("claude_failed", 502, "x")

    async def collect(source):
        return [item async for item in app_module.with_keepalives(source, every=0.05)]

    got = asyncio.run(collect(slow()))
    assert got[0] == ("delta", {"text": "a"}) and got[-1] == ("done", {"answer": "a"})
    assert 2 <= got.count(("keepalive", {})) <= 6

    async def failing():
        try:
            await collect(broken())
        except claude.ClaudeError as error:
            return error.code
        return None

    assert asyncio.run(failing()) == "claude_failed"


def test_a_quiet_run_streams_keepalives_and_a_closed_interaction_at_its_end(
    settings, auth, monkeypatch
):
    monkeypatch.setattr(app_module, "KEEPALIVE_SECONDS", 0.05)

    async def quiet_run(settings, prompt, **kwargs):
        yield "interaction", {"id": "q1", "kind": "question"}
        await asyncio.sleep(0.3)  # a long tool call, or the owner deciding
        yield "error", {"error": "timeout", "message": "the run took too long"}

    monkeypatch.setattr(claude, "stream", quiet_run)
    client = TestClient(create_app(settings))
    response = client.post("/ask", headers=auth, json={"prompt": "x", "stream": True})
    names = [event for event, _ in events_of(response.text)]
    assert names.count("keepalive") >= 2
    closed = [data for event, data in events_of(response.text) if event == "interaction_closed"]
    assert closed == [{"id": "q1", "reason": "run_ended"}]
    assert names.index("interaction_closed") < names.index("error")


def test_the_relay_forwards_keepalives_as_small_sealed_events():
    events = iter([("delta", {"text": "x"}), ("keepalive", {}), ("done", {"answer": "x"})])
    messages = list(relay.Relay._stream(200, events))
    assert (e2e.CHUNK, {"event": "keepalive", "data": {}}) in messages
    assert messages.index((e2e.CHUNK, {"event": "delta", "data": {"text": "x"}})) < (
        messages.index((e2e.CHUNK, {"event": "keepalive", "data": {}}))
    )  # text before it is sent first


def test_a_phone_chosen_session_id_is_run_once(client, auth, calls, ask_dir):
    chosen = str(uuid.uuid4())
    body = {"prompt": "first question", "new_session_id": chosen}
    first = client.post("/ask", headers=auth, json=body)
    assert first.status_code == 200 and first.json()["session_id"] == chosen
    assert claude.session_exists(ask_dir, chosen) and len(calls()) == 1
    again = client.post("/ask", headers=auth, json=body)  # the answer was lost: sent again
    assert again.json() == {"repeat": True, "session_id": chosen, "running": False}
    assert len(calls()) == 1  # not run twice
    other = client.post("/ask", headers=auth, json={**body, "prompt": "something else"})
    assert other.status_code == 409 and other.json()["error"] == "session_id_conflict"
    follow = client.post(f"/sessions/{chosen}/messages", headers=auth, json={"prompt": "and?"})
    assert follow.status_code == 200 and len(calls()) == 2


def test_bad_or_used_phone_chosen_ids(client, auth, ask_dir, write_session):
    for bad in ["not-a-uuid", str(uuid.uuid4()).upper(), 5]:
        answer = client.post("/ask", headers=auth, json={"prompt": "x", "new_session_id": bad})
        assert answer.status_code == 400, bad
    both = {"prompt": "x", "new_session_id": str(uuid.uuid4()), "session_id": str(uuid.uuid4())}
    assert client.post("/ask", headers=auth, json=both).status_code == 400
    existing = write_session(ask_dir, [user_line(ask_dir, "from a terminal")])
    taken = client.post("/ask", headers=auth, json={"prompt": "x", "new_session_id": existing})
    assert taken.status_code == 409


def test_sessions_open_elsewhere_are_told_apart(settings, auth, ask_dir, write_session, tmp_path):
    here = write_session(ask_dir, [user_line(ask_dir, "one")])
    there = write_session(ask_dir, [user_line(ask_dir, "two")])
    agents = tmp_path / "home" / ".fake-claude-agents.json"
    agents.write_text(json.dumps([{"sessionId": there}]))
    listed = {
        s["id"]: s
        for s in TestClient(create_app(settings)).get("/sessions", headers=auth).json()["sessions"]
    }
    assert (listed[there]["open_elsewhere"], listed[there]["running_here"]) == (True, False)
    assert (listed[here]["open_elsewhere"], listed[here]["running_here"]) == (False, False)
    agents.write_text("FAIL")  # the check cannot be made: unknown, not "free"
    unknown = TestClient(create_app(settings)).get("/sessions", headers=auth).json()["sessions"]
    assert all(s["open_elsewhere"] is None for s in unknown)
