"""Queue, send now, cancel, and model/effort per session. Stand-in claude only."""

import json
import threading
import time

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import claude, runcontrol
from lifeos_bridge.app import create_app
from lifeos_bridge.config import load_settings


@pytest.fixture
def running(settings, monkeypatch):
    """The app with a live event loop, so background sends really happen."""
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    with TestClient(create_app(settings)) as client:
        yield client
        # Let background sends finish before the event loop goes away.
        state = client.app.state
        wait_for(lambda: not state.drains and not state.slots.active)


@pytest.fixture
def session(ask_dir, write_session):
    return write_session(ask_dir, [user_line(ask_dir, "first")])


def post(client, auth, session, **body):
    return client.post(f"/sessions/{session}/messages", headers=auth, json=body)


def start_slow_run(client, auth, session):
    """Start a run that never answers by itself; returns the thread and its result holder."""
    holder = {}

    def go():
        holder["response"] = post(client, auth, session, prompt="FAKE_SLEEP")

    thread = threading.Thread(target=go)
    thread.start()
    wait_for(
        lambda: (
            session in client.app.state.slots.active
            and session in client.app.state.control._processes
        )
    )
    return thread, holder


def wait_for(condition, seconds=10):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if condition():
            return
        time.sleep(0.02)
    raise AssertionError("condition not reached")


def prompts(ask_dir):
    log = ask_dir / ".fake-claude-calls.jsonl"
    return [json.loads(line)["prompt"] for line in log.read_text().splitlines()]


def queue(client, auth, session):
    return client.get(f"/sessions/{session}/queue", headers=auth).json()


def test_routes_require_the_token(client):
    assert client.get("/models").status_code == 401
    assert client.post("/sessions/x/cancel").status_code == 401
    assert client.get("/sessions/x/queue").status_code == 401
    assert client.post("/sessions/x/queue/resume").status_code == 401


def test_messages_for_a_busy_session_wait_and_can_be_edited_or_removed(running, auth, session):
    thread, holder = start_slow_run(running, auth, session)
    first = post(running, auth, session, prompt="second message")
    assert first.status_code == 202 and first.json()["position"] == 1
    third = post(running, auth, session, prompt="third message").json()
    state = queue(running, auth, session)
    assert state["running"] is True and [e["prompt"] for e in state["queue"]] == [
        "second message",
        "third message",
    ]
    edited = running.patch(
        f"/sessions/{session}/queue/{first.json()['queue_id']}",
        headers=auth,
        json={"prompt": "changed"},
    )
    assert edited.json()["prompt"] == "changed"
    assert running.delete(
        f"/sessions/{session}/queue/{third['queue_id']}", headers=auth
    ).json() == {"removed": True}
    assert running.delete(f"/sessions/{session}/queue/nope", headers=auth).status_code == 404

    # Cancel: the run stops, the caller is told so, and the queue waits for the owner.
    cancelled = running.post(f"/sessions/{session}/cancel", headers=auth).json()
    assert cancelled["cancelled"] is True and "kept" in cancelled["note"]
    thread.join(10)
    assert holder["response"].status_code == 409
    assert holder["response"].json()["error"] == "cancelled"
    time.sleep(0.3)
    state = queue(running, auth, session)
    assert state["running"] is False and state["paused"] == "cancel"
    assert [e["prompt"] for e in state["queue"]] == ["changed"]

    # Resume: the waiting message is sent in the background, exactly once.
    assert running.post(f"/sessions/{session}/queue/resume", headers=auth).json()["queue"] == []
    wait_for(lambda: queue(running, auth, session)["last"] is not None)
    assert queue(running, auth, session)["last"]["state"] == "done"
    assert prompts(running.app.state.settings.ask_dir).count("changed") == 1


def test_queued_message_is_sent_when_the_run_ends_and_never_alongside_it(
    running, auth, session, ask_dir
):
    thread, _ = start_slow_run(running, auth, session)
    post(running, auth, session, prompt="after the run")
    time.sleep(0.5)
    assert "after the run" not in prompts(ask_dir)  # nothing started while the run is alive
    process = running.app.state.control._processes[session]
    process.terminate()  # the run ends by itself (not on request)
    thread.join(10)
    wait_for(lambda: "after the run" in prompts(ask_dir))
    wait_for(lambda: queue(running, auth, session)["last"] is not None)
    assert process.returncode is not None
    assert queue(running, auth, session)["queue"] == []


def test_send_now_interrupts_and_goes_first(running, auth, session, ask_dir):
    thread, holder = start_slow_run(running, auth, session)
    post(running, auth, session, prompt="waiting one")
    forced = post(running, auth, session, prompt="urgent", when="now")
    assert forced.status_code == 202 and forced.json()["interrupted_current_run"] is True
    thread.join(10)
    assert holder["response"].json()["error"] == "cancelled"
    wait_for(lambda: prompts(ask_dir)[-2:] == ["urgent", "waiting one"])


def test_cancel_with_nothing_running_and_bad_when(running, auth, session):
    assert running.post(f"/sessions/{session}/cancel", headers=auth).json()["cancelled"] is False
    assert post(running, auth, session, prompt="x", when="later").status_code == 400


def test_queue_limit(running, auth, session, monkeypatch):
    monkeypatch.setattr(runcontrol, "MAX_QUEUED", 2)
    thread, _ = start_slow_run(running, auth, session)
    assert post(running, auth, session, prompt="a").status_code == 202
    assert post(running, auth, session, prompt="b").status_code == 202
    assert post(running, auth, session, prompt="c").status_code == 429
    running.post(f"/sessions/{session}/cancel", headers=auth)
    thread.join(10)


def test_queued_messages_survive_a_restart_as_drafts(
    settings, auth, session, isolated_config, ask_dir
):
    app = create_app(settings)
    app.state.slots.active.add(session)
    client = TestClient(app)
    post(client, auth, session, prompt="draft")
    stored = isolated_config / "queue.json"
    assert stored.stat().st_mode & 0o777 == 0o600

    with TestClient(create_app(settings)) as restarted:  # a new bridge
        state = queue(restarted, auth, session)
        assert state["paused"] == "restart" and [e["prompt"] for e in state["queue"]] == ["draft"]
        assert post(restarted, auth, session, prompt="direct").status_code == 200
        time.sleep(0.3)
        assert "draft" not in prompts(ask_dir)  # never sent automatically
        restarted.post(f"/sessions/{session}/queue/resume", headers=auth)
        wait_for(lambda: "draft" in prompts(ask_dir))
        wait_for(lambda: not restarted.app.state.drains and not restarted.app.state.slots.active)


def test_a_failed_queued_message_holds_the_rest(running, auth, session, ask_dir):
    thread, _ = start_slow_run(running, auth, session)
    post(running, auth, session, prompt="FAKE_CRASH")
    post(running, auth, session, prompt="must wait")
    running.app.state.control._processes[session].terminate()
    thread.join(10)
    wait_for(lambda: queue(running, auth, session)["last"] is not None)
    state = queue(running, auth, session)
    assert state["last"]["state"] == "error" and state["paused"] == "error"
    assert [e["prompt"] for e in state["queue"]] == ["must wait"]


# ---------------------------------------------------------------- model and effort


def last_args(ask_dir):
    return json.loads((ask_dir / ".fake-claude-calls.jsonl").read_text().splitlines()[-1])["args"]


def option(args, name):
    return args[args.index(name) + 1] if name in args else None


def test_models_endpoint_reads_effort_levels_from_the_cli(settings, auth, client):
    claude._efforts_cache.clear()
    body = client.get("/models", headers=auth).json()
    assert body["models"] == ["opus", "sonnet", "haiku", "fable"]
    assert body["efforts"] == ["low", "medium", "high", "xhigh", "max"]


def test_effort_levels_fall_back_when_the_cli_cannot_be_asked(settings):
    import dataclasses

    claude._efforts_cache.clear()
    broken = dataclasses.replace(settings, claude_bin="/nonexistent/claude")
    assert claude.effort_levels(broken) == claude.FALLBACK_EFFORTS


def test_defaults_come_from_the_settings(isolated_config, monkeypatch, tmp_path):
    (isolated_config / "env").write_text("LIFEOS_BRIDGE_TOKEN=" + "t" * 40 + "\n")
    (isolated_config / "env").chmod(0o600)
    loaded = load_settings()
    assert (loaded.default_model, loaded.default_effort) == ("opus", "high")
    command = claude.build_command(loaded, session_id="s", resume=False, stream=False)
    assert option(command, "--model") == "opus" and option(command, "--effort") == "high"
    monkeypatch.setenv("LIFEOS_DEFAULT_MODEL", "sonnet")
    monkeypatch.setenv("LIFEOS_MODELS", "sonnet, haiku")
    assert load_settings().models == ("sonnet", "haiku")
    assert load_settings().default_model == "sonnet"


def test_a_session_keeps_its_model_and_effort(client, auth, session, ask_dir):
    claude._efforts_cache.clear()
    first = post(client, auth, session, prompt="hello", model="haiku", effort="low")
    assert first.status_code == 200
    assert (option(last_args(ask_dir), "--model"), option(last_args(ask_dir), "--effort")) == (
        "haiku",
        "low",
    )
    post(client, auth, session, prompt="again")  # no choice given: the session's own is used
    assert (option(last_args(ask_dir), "--model"), option(last_args(ask_dir), "--effort")) == (
        "haiku",
        "low",
    )
    changed = client.patch(
        f"/sessions/{session}", headers=auth, json={"model": "sonnet", "effort": None}
    )
    assert changed.json()["model"] == "sonnet" and changed.json()["effort"] is None
    post(client, auth, session, prompt="third")
    assert option(last_args(ask_dir), "--model") == "sonnet"
    assert "--effort" not in last_args(ask_dir)  # test settings have no default effort


def test_unknown_models_and_efforts_are_refused_before_a_run(client, auth, session, ask_dir):
    for body in [{"model": "gpt"}, {"effort": "extreme"}, {"model": 5}]:
        assert post(client, auth, session, prompt="x", **body).status_code == 400
        assert client.patch(f"/sessions/{session}", headers=auth, json=body).status_code == 400
    assert not (ask_dir / ".fake-claude-calls.jsonl").exists()
    assert post(client, auth, session, prompt="x", model="claude-opus-5-5").status_code == 200


def test_new_ask_conversation_accepts_a_model(client, auth, ask_dir):
    response = client.post("/ask", headers=auth, json={"prompt": "hello", "model": "haiku"})
    assert response.status_code == 200
    assert option(last_args(ask_dir), "--model") == "haiku"
