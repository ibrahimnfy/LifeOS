"""Review fixes for #105-#107: opt-in clients, keep-alives, sandbox, audit counting, versions."""

import asyncio
import dataclasses
import json
import uuid

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, gate, runcontrol, sdk_runner
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError
from lifeos_bridge.interactions import Interactions
from test_interactions import QUESTION, decision, tool_prompt


@pytest.fixture
def sdk_settings(settings, monkeypatch):
    monkeypatch.setattr(sdk_runner, "GRACE", 0.3)
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    return dataclasses.replace(settings, runner="sdk", interaction_timeout=5)


def test_a_streaming_client_must_opt_in_to_be_asked(sdk_settings, auth):
    prompt = tool_prompt("AskUserQuestion", QUESTION)
    with TestClient(create_app(sdk_settings)) as client:
        response = client.post("/ask", headers=auth, json={"prompt": prompt, "stream": True})
    assert "event: interaction" not in response.text  # today's client: refused at once
    done = json.loads(response.text.strip().splitlines()[-1][6:])
    assert decision(done["answer"])["message"] == sdk_runner.NO_CLIENT_QUESTION


def test_a_waiting_stream_sends_keep_alives(sdk_settings, auth, monkeypatch):
    monkeypatch.setattr(sdk_runner, "KEEPALIVE_SECONDS", 0.1)
    prompt = tool_prompt("AskUserQuestion", QUESTION)
    with TestClient(create_app(sdk_settings)) as client:
        client.app.state.sdk_pool.asking.timeout = 0.6
        response = client.post(
            "/ask", headers=auth, json={"prompt": prompt, "stream": True, "interactive": True}
        )
    # A real, tiny event (#147): the relay forwards it sealed to the phone.
    assert response.text.count("event: keepalive\ndata: {}") >= 3
    # The question timed out: the stream says the interaction is closed, and why.
    assert 'event: interaction_closed\ndata: {"id": ' in response.text
    assert '"reason": "timed_out"' in response.text


def test_an_approval_never_lets_a_command_out_of_the_sandbox(sdk_settings, isolated_config):
    async def scenario():
        session_id = str(uuid.uuid4())
        live = sdk_runner.LiveSession(
            sdk_settings, session_id, sdk_runner.Launch(sdk_settings.ask_dir, None, None, None),
            resume=False, asking=Interactions(5), capable=True,
        )  # fmt: skip
        await live.start()
        tool_input = {"command": "rm old.txt", "dangerouslyDisableSandbox": True}
        turn = await live.send(tool_prompt("Bash", tool_input), answering=True)
        shown = []
        while True:
            item = await asyncio.wait_for(turn.queue.get(), 10)
            if isinstance(item, tuple) and item[0] == "interaction":
                shown.append(item)
            elif type(item).__name__ == "ResultMessage":
                break
        await live.close()
        return shown, decision(item.result)

    shown, sent = asyncio.run(scenario())
    assert shown == []
    assert sent == {"behavior": "deny", "message": sdk_runner.UNSANDBOXED_NOTE}


def test_the_audit_cross_check_counts_only_the_gates_records(tmp_path):
    watch = capable.AuditWatch("s", tmp_path)
    for step in ("asked", "approval_requested", "approved"):
        source = {} if step == "asked" else {"source": "bridge"}
        entry = {"ts": "2026-11-20T00:00:00", "session_id": "s", "decision": step, **source}
        gate.write_audit(tmp_path, entry)
    result = {"type": "tool_result", "is_error": False}
    watch({"type": "user", "message": {"content": [result]}})  # one result, one gate record
    with pytest.raises(ClaudeError):
        watch({"type": "user", "message": {"content": [result]}})  # bridge records do not count
