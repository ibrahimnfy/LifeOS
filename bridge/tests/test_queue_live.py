# ruff: noqa: F811  (fixtures imported from the run-control tests are used as parameters)
"""Queue and file-offer gaps of the chat screen (#154): send now, background resume,
following a run after the fact, live file offers, one session read."""

import asyncio
import json
import threading
import time

import pytest

from conftest import user_line
from lifeos_bridge import claude, live, relay
from test_runcontrol import (  # noqa: F401  (fixtures)
    post,
    prompts,
    queue,
    running,
    session,
    start_slow_run,
    wait_for,
)


def events_of(text):
    found, event = [], None
    for line in text.splitlines():
        if line.startswith("event: "):
            event = line[7:]
        elif line.startswith("data: "):
            found.append((event, json.loads(line[6:])))
    return found


def test_the_hub_replays_then_follows_and_ends_only_its_own_run():
    hub = live.Live()

    async def scenario():
        token = hub.begin("s", "q1")
        hub.publish("s", "delta", {"text": "Hel"})
        hub.publish("s", "delta", {"text": "lo"})
        hub.publish("s", "keepalive", {})
        hub.publish("s", "interaction", {"id": "i1"})
        seen = []

        async def follower():
            async for item in hub.follow("s"):
                seen.append(item)

        task = asyncio.create_task(follower())
        await asyncio.sleep(0)
        hub.publish("s", "delta", {"text": "!"})
        hub.end("s", object())  # another run's token: nothing ends
        hub.publish("s", "done", {"answer": "Hello!"})
        hub.end("s", token)
        await asyncio.wait_for(task, 1)
        return seen

    seen = asyncio.run(scenario())
    assert seen == [("delta", {"text": "Hello"}), ("interaction", {"id": "i1"}),
                    ("delta", {"text": "!"}), ("done", {"answer": "Hello!"})]  # fmt: skip
    assert hub.running("s") is None


def test_send_now_promotes_a_waiting_message_and_interrupts(running, auth, session, ask_dir):
    thread, holder = start_slow_run(running, auth, session)
    first = post(running, auth, session, prompt="later one").json()
    second = post(running, auth, session, prompt="urgent one").json()
    assert running.post(f"/sessions/{session}/queue/nope/send-now", headers=auth).status_code == 404
    now = running.post(f"/sessions/{session}/queue/{second['queue_id']}/send-now", headers=auth)
    body = now.json()
    assert body["promoted"] is True and body["interrupted_current_run"] is True
    assert [e["id"] for e in body["queue"]] == [second["queue_id"], first["queue_id"]]
    thread.join(10)
    assert holder["response"].json()["error"] == "cancelled"
    wait_for(lambda: prompts(ask_dir).count("later one") == 1)
    sent = [p for p in prompts(ask_dir) if p in ("urgent one", "later one")]
    assert sent == ["urgent one", "later one"]  # the promoted one went first


def test_send_now_on_an_idle_session_sends_it_at_once(running, auth, session, ask_dir):
    thread, _holder = start_slow_run(running, auth, session)
    waiting = post(running, auth, session, prompt="held back").json()
    running.post(f"/sessions/{session}/cancel", headers=auth)  # the queue is now on hold
    thread.join(10)
    assert queue(running, auth, session)["paused"] == "cancel"
    now = running.post(f"/sessions/{session}/queue/{waiting['queue_id']}/send-now", headers=auth)
    assert now.json()["interrupted_current_run"] is False
    wait_for(lambda: "held back" in prompts(ask_dir))


@pytest.fixture
def scripted(monkeypatch):
    """A stand-in for streamed runs: some text, a pause, more text, the answer."""

    async def stream(settings, prompt, **kwargs):
        yield "delta", {"text": f"{prompt}: part one. "}
        await asyncio.sleep(0.6)
        yield "delta", {"text": "Part two."}
        answer = f"{prompt}: part one. Part two."
        yield "done", {"answer": answer, "session_id": kwargs["session_id"], "duration_ms": 1,
                       "num_turns": 1, "denied_tools": []}  # fmt: skip

    monkeypatch.setattr(claude, "stream", stream)


def test_a_queued_answer_can_be_followed_live(running, auth, session, scripted):
    state = running.app.state
    results = {}
    first = threading.Thread(
        target=lambda: results.setdefault(
            "first", post(running, auth, session, prompt="one", stream=True)
        )
    )
    first.start()
    wait_for(lambda: state.live.running(session) is not None)
    queued = post(running, auth, session, prompt="two").json()  # waits for the first
    assert queued["queued"] is True
    wait_for(lambda: (state.live.running(session) or {}).get("queue_id") == queued["queue_id"])
    time.sleep(0.2)  # the queued run has said its first part already
    followed = running.get(f"/sessions/{session}/live", headers=auth)
    first.join(10)
    events = events_of(followed.text)
    assert events[0] == ("live", {"session_id": session, "running": True, "followable": True,
                                  "queue_id": queued["queue_id"]})  # fmt: skip
    texts = [d["text"] for e, d in events if e == "delta"]
    assert texts == ["two: part one. ", "Part two."]  # what was said before, then live
    assert events[-1][0] == "done" and events[-1][1]["answer"] == "two: part one. Part two."
    idle = events_of(running.get(f"/sessions/{session}/live", headers=auth).text)
    assert idle == [("live", {"session_id": session, "running": False, "followable": False,
                              "queue_id": None})]  # fmt: skip
    assert running.get("/sessions/unknown/live", headers=auth).status_code == 404
    assert relay.allowed("GET", f"/sessions/{session}/live")


def test_live_file_offers_and_one_session(client, auth, ask_dir, write_session, tmp_path):
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    (ask_dir / "note.txt").write_text("hello")
    (tmp_path / "home" / ".fake-claude-answer").write_text("Here it is.\n[[file: note.txt]]\n")
    answer = client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "file?"})
    (card,) = answer.json()["files"]
    files = client.get(f"/sessions/{session}/files", headers=auth).json()["files"]
    assert len(files) == 1 and files[0]["offer"] == card["offer"]
    assert set(files[0]) == {"offer", "name", "type", "size", "expires_at"}
    assert files[0]["expires_at"].endswith("Z")
    assert client.get("/sessions/other/files", headers=auth).json() == {"files": []}
    one = client.get(f"/sessions/{session}", headers=auth).json()
    assert one["id"] == session and one["running_here"] is False
    for name in ("title", "folder", "model", "effort", "skills", "notify_replies", "pinned",
                 "group", "open_elsewhere", "followable", "queue", "queue_paused"):  # fmt: skip
        assert name in one, name
    assert client.get("/sessions/00000000-0000-4000-8000-000000000000",
                      headers=auth).status_code == 404  # fmt: skip
