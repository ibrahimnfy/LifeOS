"""Questions and approvals in the middle of a run (#105). Stand-in claude only."""

import asyncio
import dataclasses
import json
import threading
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, gate, interactions, relay, runcontrol, sdk_runner
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError
from lifeos_bridge.interactions import Interactions

QUESTION = {
    "questions": [
        {
            "question": "Which format?",
            "header": "Format",
            "multiSelect": False,
            "options": [
                {"label": "Short", "description": "A summary"},
                {"label": "Long", "description": "Everything"},
            ],
        }
    ]
}


@pytest.fixture
def sdk_settings(settings, monkeypatch):
    monkeypatch.setattr(sdk_runner, "GRACE", 0.3)
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    return dataclasses.replace(settings, runner="sdk", interaction_timeout=5)


def tool_prompt(tool, tool_input):
    return f"FAKE_TOOL {tool} {json.dumps(tool_input)}"


def decision(answer: str) -> dict:
    return json.loads(answer)


def audit_entries(isolated_config):
    folder = isolated_config / "audit"
    lines = []
    for path in sorted(folder.glob("*.jsonl")) if folder.exists() else []:
        lines += [json.loads(line) for line in path.read_text().splitlines()]
    return lines


async def streamed(pool, settings, prompt, session_id, on_interaction=None):
    """Run a streaming turn; ``on_interaction(event)`` may answer. Returns all events."""
    events = []
    async for event, payload in sdk_runner.stream(
        pool, settings, prompt, session_id=session_id, resume=False
    ):
        events.append((event, payload))
        if event == "interaction" and on_interaction is not None:
            await on_interaction(payload)
    return events


# ------------------------------------------------------------------- questions


def test_a_question_waits_for_the_owner_and_the_answer_reaches_the_assistant(sdk_settings):
    session_id = str(uuid.uuid4())

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)

        async def answer(event):
            assert event["kind"] == "question" and event["session_id"] == session_id
            assert event["questions"][0]["options"][0] == {
                "label": "Short",
                "description": "A summary",
            }
            assert pool.asking.pending(session_id)[0]["id"] == event["id"]
            pool.asking.answer(session_id, event["id"], {"answers": {"Which format?": "Short"}})

        events = await streamed(
            pool, sdk_settings, tool_prompt("AskUserQuestion", QUESTION), session_id, answer
        )
        assert pool.asking.pending(session_id) == []
        await pool.close_all()
        return events

    events = asyncio.run(scenario())
    assert [e for e, _ in events] == ["interaction", "done"]
    sent = decision(events[-1][1]["answer"])
    assert sent["behavior"] == "allow"
    assert sent["updatedInput"] == {**QUESTION, "answers": {"Which format?": "Short"}}


def test_free_text_and_decline(sdk_settings):
    async def scenario(body):
        pool = sdk_runner.SdkPool(sdk_settings)
        session_id = str(uuid.uuid4())

        async def answer(event):
            pool.asking.answer(session_id, event["id"], body)

        events = await streamed(
            pool, sdk_settings, tool_prompt("AskUserQuestion", QUESTION), session_id, answer
        )
        await pool.close_all()
        return decision(events[-1][1]["answer"])

    own = asyncio.run(scenario({"answers": {"Which format?": "a table"}, "response": "thanks"}))
    assert own["updatedInput"]["answers"] == {"Which format?": "a table"}
    assert own["updatedInput"]["response"] == "thanks"
    declined = asyncio.run(scenario({"decline": True}))
    assert declined == {"behavior": "deny", "message": sdk_runner.QUESTION_DECLINED}


def test_without_an_answering_client_a_question_is_refused_at_once(sdk_settings):
    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        started = time.monotonic()
        result = await sdk_runner.run(
            pool, sdk_settings, tool_prompt("AskUserQuestion", QUESTION),
            session_id=str(uuid.uuid4()), resume=False,
        )  # fmt: skip
        await pool.close_all()
        return result, time.monotonic() - started

    result, took = asyncio.run(scenario())
    assert decision(result.answer) == {
        "behavior": "deny",
        "message": sdk_runner.NO_CLIENT_QUESTION,
    }
    assert took < 3


def test_an_unanswered_question_times_out_as_no_and_pauses_the_run_limit(sdk_settings):
    quick = dataclasses.replace(sdk_settings, interaction_timeout=1, ask_timeout=1)

    async def scenario():
        pool = sdk_runner.SdkPool(quick)
        pool.asking.timeout = 1.5  # longer than the run limit: the limit must be paused
        events = await streamed(
            pool, quick, tool_prompt("AskUserQuestion", QUESTION), str(uuid.uuid4())
        )
        await pool.close_all()
        return events

    events = asyncio.run(scenario())
    assert events[-1][0] == "done"  # not "timeout": waiting for the owner does not count
    assert decision(events[-1][1]["answer"]) == {
        "behavior": "deny",
        "message": sdk_runner.QUESTION_TIMEOUT,
    }


def test_any_other_prompt_is_denied_as_dont_ask_would(sdk_settings):
    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        events = await streamed(
            pool, sdk_settings, tool_prompt("Read", {"file_path": "/etc/hostname"}),
            str(uuid.uuid4()),
        )  # fmt: skip
        await pool.close_all()
        return events

    events = asyncio.run(scenario())
    assert [e for e, _ in events] == ["done"]  # nothing was shown to the owner
    assert decision(events[-1][1]["answer"]) == {
        "behavior": "deny",
        "message": sdk_runner.DENIED_NOTE,
    }


def test_bash_is_never_approved_on_a_read_only_session(sdk_settings):
    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        events = await streamed(
            pool, sdk_settings, tool_prompt("Bash", {"command": "rm notes.md"}), str(uuid.uuid4())
        )
        await pool.close_all()
        return events

    events = asyncio.run(scenario())
    assert [e for e, _ in events] == ["done"]
    assert decision(events[-1][1]["answer"])["behavior"] == "deny"


def test_cancel_ends_a_waiting_question_as_no(sdk_settings):
    session_id = str(uuid.uuid4())

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)

        async def cancel(_event):
            await pool.live[session_id].stop(1.0)

        events = await streamed(
            pool, sdk_settings, tool_prompt("AskUserQuestion", QUESTION), session_id, cancel
        )
        assert pool.asking.pending(session_id) == []
        await pool.close_all()
        return events

    events = asyncio.run(scenario())
    assert events[0][0] == "interaction"
    last, payload = events[-1]
    # Stopped (the app reports "cancelled"), or the "no" arrived before the interrupt.
    assert last == "error" or decision(payload["answer"])["behavior"] == "deny"


# ------------------------------------------------------------------- approvals


async def capable_turn(settings, command, answer=None, *, answering=True):
    """A live session in capable mode (as #106 will start it) asked to run ``command``."""
    session_id = str(uuid.uuid4())
    asking = Interactions(settings.interaction_timeout)
    live = sdk_runner.LiveSession(
        settings,
        session_id,
        sdk_runner.Launch(settings.ask_dir, None, None, None),
        resume=False,
        asking=asking,
        capable=True,
    )
    await live.start()
    turn = await live.send(tool_prompt("Bash", {"command": command}), answering=answering)
    shown = []
    while True:
        item = await asyncio.wait_for(turn.queue.get(), 10)
        if isinstance(item, tuple) and item[0] == "interaction":
            shown.append(item[1])
            if answer is not None:
                asking.answer(session_id, item[1]["id"], answer)
        elif type(item).__name__ == "ResultMessage":
            break
    await live.close()
    return session_id, shown, decision(item.result)


def test_a_deletion_runs_only_after_the_owner_confirms(sdk_settings, isolated_config):
    session_id, shown, sent = asyncio.run(
        capable_turn(sdk_settings, "rm old.txt", {"approve": True})
    )
    [card] = shown
    assert card["kind"] == "approval" and card["command"] == "rm old.txt"
    assert card["category"] == "deletion" and card["folder"] == str(sdk_settings.ask_dir)
    assert sent == {"behavior": "allow", "updatedInput": {"command": "rm old.txt"}}
    steps = [(e["decision"], e.get("why")) for e in audit_entries(isolated_config)]
    assert steps == [("approval_requested", None), ("approved", "owner")]
    assert all(e["session_id"] == session_id for e in audit_entries(isolated_config))


def test_a_refused_deletion_is_reported_to_the_assistant(sdk_settings, isolated_config):
    _, _, sent = asyncio.run(capable_turn(sdk_settings, "rm old.txt", {"approve": False}))
    assert sent == {"behavior": "deny", "message": sdk_runner.APPROVAL_REFUSED}
    assert audit_entries(isolated_config)[-1]["decision"] == "refused"


def test_without_a_client_an_approval_is_refused_as_today(sdk_settings, isolated_config):
    _, shown, sent = asyncio.run(capable_turn(sdk_settings, "rm old.txt", answering=False))
    assert shown == []
    assert sent == {"behavior": "deny", "message": gate.APPROVAL_NOTE}
    assert audit_entries(isolated_config)[-1]["why"] == "no_client"


def test_an_unanswered_approval_times_out_as_no(sdk_settings, isolated_config):
    quick = dataclasses.replace(sdk_settings, interaction_timeout=1)
    _, shown, sent = asyncio.run(capable_turn(quick, "git push --force"))
    assert len(shown) == 1
    assert sent == {"behavior": "deny", "message": sdk_runner.APPROVAL_TIMEOUT}
    assert audit_entries(isolated_config)[-1]["why"] == "timeout"


@pytest.mark.parametrize("command", ["sudo -n true", "ls -la", "systemctl --user stop x"])
def test_only_approval_actions_can_be_asked_for(sdk_settings, isolated_config, command):
    _, shown, sent = asyncio.run(capable_turn(sdk_settings, command, {"approve": True}))
    assert shown == []
    assert sent == {"behavior": "deny", "message": sdk_runner.DENIED_NOTE}


def test_no_approval_without_an_audit_log(sdk_settings, isolated_config):
    (isolated_config / "audit").write_text("not a folder")
    _, shown, sent = asyncio.run(capable_turn(sdk_settings, "rm old.txt", {"approve": True}))
    assert shown == []
    assert sent["behavior"] == "deny"


def test_gate_asks_the_host_only_when_told_to(tmp_path):
    payload = {"tool_name": "Bash", "tool_input": {"command": "rm a.txt"}, "session_id": "s"}
    status, text = gate.decide(payload, tmp_path, ask_host=True)
    assert status == gate.ALLOW
    out = json.loads(text)["hookSpecificOutput"]
    assert out["permissionDecision"] == "ask" and out["hookEventName"] == "PreToolUse"
    assert gate.decide(payload, tmp_path)[0] == gate.BLOCK  # the claude -p path: unchanged
    never = {"tool_name": "Bash", "tool_input": {"command": "sudo true"}, "session_id": "s"}
    assert gate.decide(never, tmp_path, ask_host=True)[0] == gate.BLOCK
    decisions = [json.loads(line)["decision"] for line in next(tmp_path.glob("*.jsonl")).open()]
    assert decisions == ["asked", "refused", "refused"]


def test_capable_policy_with_an_answering_host(settings):
    plain = json.loads(capable.build_policy(settings, settings.ask_dir).settings_json)
    asked = json.loads(
        capable.build_policy(settings, settings.ask_dir, ask_host=True).settings_json
    )
    command = asked["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
    assert command.endswith("--ask-host")
    assert "--ask-host" not in plain["hooks"]["PreToolUse"][0]["hooks"][0]["command"]


# ------------------------------------------------------------------- answers and API


def interaction(kind, data):
    loop = asyncio.new_event_loop()
    try:
        asking = Interactions(5)
        asking_open = loop.run_until_complete(_open(asking, kind, data))
        return asking, asking_open
    finally:
        loop.close()


async def _open(asking, kind, data):
    return asking.open("s", kind, data)


@pytest.mark.parametrize(
    "body",
    [{}, {"answers": {}}, {"answers": {"Other question?": "x"}}, {"answers": {"Which format?": ""}},
     {"response": 3}, {"answers": {"Which format?": "x" * 5000}}],
)  # fmt: skip
def test_bad_answers_are_refused(body):
    asking, pending = interaction("question", interactions.question_data(QUESTION))
    with pytest.raises(ClaudeError) as caught:
        asking.answer("s", pending.id, body)
    assert caught.value.status == 400


def test_approval_answers_must_be_explicit():
    asking, pending = interaction("approval", {"command": "rm x"})
    with pytest.raises(ClaudeError):
        asking.answer("s", pending.id, {"approve": "yes"})
    with pytest.raises(ClaudeError) as caught:
        asking.answer("other-session", pending.id, {"approve": True})
    assert caught.value.status == 404


def test_malformed_questions_are_not_shown():
    assert interactions.question_data({"questions": "x"}) is None
    assert interactions.question_data({"questions": []}) is None
    assert interactions.question_data({"questions": [{"header": "no text"}]}) is None


def test_relay_passes_interaction_events_on():
    events = [
        ("delta", {"text": "a"}),
        ("interaction", {"id": "i1", "kind": "question"}),
        ("done", {"answer": "a"}),
    ]
    chunks = [p for _, p in relay.Relay._stream(200, iter(events)) if "event" in p]
    assert [c["event"] for c in chunks] == ["delta", "interaction", "done"]
    assert chunks[1]["data"] == {"id": "i1", "kind": "question"}


def test_api_question_round_trip(sdk_settings, auth):
    with TestClient(create_app(sdk_settings)) as client:
        holder = {}
        prompt = tool_prompt("AskUserQuestion", QUESTION)

        def ask():
            holder["response"] = client.post(
                "/ask", headers=auth, json={"prompt": prompt, "stream": True}
            )

        thread = threading.Thread(target=ask)
        thread.start()
        pool = client.app.state.sdk_pool
        deadline = time.time() + 10
        while not pool.asking.items:
            assert time.time() < deadline
            time.sleep(0.02)
        session_id = next(iter(pool.asking.items.values())).session_id
        listed = client.get(f"/sessions/{session_id}/interactions", headers=auth).json()
        [pending] = listed["pending"]
        assert pending["kind"] == "question" and "expires_at" in pending
        url = f"/sessions/{session_id}/interactions/{pending['id']}"
        assert client.post(url, headers=auth, json={"answers": {}}).status_code == 400
        ok = client.post(url, headers=auth, json={"answers": {"Which format?": "Long"}})
        assert ok.json() == {"answered": True, "id": pending["id"]}
        assert client.post(url, headers=auth, json={"decline": True}).status_code == 404
        thread.join(10)
        text = holder["response"].text
        assert "event: interaction" in text
        done = json.loads(text.strip().splitlines()[-1][6:])
        assert decision(done["answer"])["updatedInput"]["answers"] == {"Which format?": "Long"}


def test_api_without_the_sdk_runner_has_no_interactions(client, auth):
    assert client.get("/sessions/x/interactions", headers=auth).json() == {"pending": []}
    assert client.post("/sessions/x/interactions/y", headers=auth, json={}).status_code == 404
