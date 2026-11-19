import dataclasses
import json
import uuid

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import claude
from lifeos_bridge.app import MAX_PROMPT_CHARS, create_app


def option(args, name):
    return args[args.index(name) + 1]


def parse_sse(text):
    events = []
    for block in text.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.split("\n"))
        events.append((lines["event"], json.loads(lines["data"])))
    return events


def test_ask_requires_token(client):
    assert client.post("/ask", json={"prompt": "hi"}).status_code == 401


def test_ask_returns_answer_and_session(client, auth, calls):
    response = client.post("/ask", json={"prompt": "hello"}, headers=auth)
    assert response.status_code == 200
    body = response.json()
    assert body["answer"] == "echo: hello"
    assert body["duration_ms"] == 12
    assert body["num_turns"] == 1
    assert body["denied_tools"] == []
    uuid.UUID(body["session_id"])
    (call,) = calls()
    assert call["prompt"] == "hello"
    assert "hello" not in call["args"], "the prompt must travel on stdin, not in the arguments"


def test_ask_uses_explicit_read_only_flags(client, auth, calls, ask_dir):
    client.post("/ask", json={"prompt": "hello"}, headers=auth)
    (call,) = calls()
    args = call["args"]
    assert args[0] == "-p"
    assert option(args, "--output-format") == "json"
    assert option(args, "--permission-mode") == "dontAsk"
    assert option(args, "--permission-prompts") == "none"
    assert option(args, "--tools") == "Read,Glob,Grep"
    assert "--restricted" in args
    assert "--strict-mcp-config" in args
    assert "--disable-slash-commands" in args
    assert option(args, "--max-turns") == "25"
    assert option(args, "--name").startswith("LifeOS phone ")
    for forbidden in ("--dangerously-skip-permissions", "--bare", "--allowedTools"):
        assert forbidden not in args
    assert "bypassPermissions" not in args
    assert call["cwd"] == str(ask_dir)


def test_child_environment_is_an_allow_list(client, auth, calls, monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", "leak")
    monkeypatch.setenv("CLAUDECODE", "1")
    monkeypatch.setenv("CLAUDE_CODE_SESSION_ID", "leak")
    monkeypatch.setenv("LIFEOS_BRIDGE_TOKEN", "leak")
    client.post("/ask", json={"prompt": "hello"}, headers=auth)
    env = calls()[0]["env"]
    assert "HOME" in env and "PATH" in env
    assert not [k for k in env if k.startswith(("ANTHROPIC", "CLAUDE", "LIFEOS"))]


def test_model_is_passed_when_configured(settings, auth, calls):
    client = TestClient(create_app(dataclasses.replace(settings, claude_model="some-model")))
    client.post("/ask", json={"prompt": "hello"}, headers=auth)
    assert option(calls()[0]["args"], "--model") == "some-model"


def test_ask_continues_a_session(client, auth, calls):
    first = client.post("/ask", json={"prompt": "one"}, headers=auth).json()
    second = client.post(
        "/ask", json={"prompt": "two", "session_id": first["session_id"]}, headers=auth
    )
    assert second.status_code == 200
    assert second.json()["session_id"] == first["session_id"]
    args = calls()[1]["args"]
    assert option(args, "--resume") == first["session_id"]
    assert "--session-id" not in args and "--name" not in args
    assert option(args, "--tools") == "Read,Glob,Grep"
    assert option(args, "--permission-mode") == "dontAsk"


@pytest.mark.parametrize(
    "session_id",
    [str(uuid.uuid4()), "not-a-uuid", "../../etc/passwd", "", 5],
)
def test_unknown_or_malformed_session_is_refused_before_running(client, auth, calls, session_id):
    response = client.post("/ask", json={"prompt": "x", "session_id": session_id}, headers=auth)
    assert response.status_code == 404
    assert response.json()["error"] == "session_not_found"
    assert calls() == []


@pytest.mark.parametrize("body", [{}, {"prompt": ""}, {"prompt": "   "}, {"prompt": 3}, [1]])
def test_bad_request_bodies(client, auth, body):
    response = client.post("/ask", json=body, headers=auth)
    assert response.status_code == 400
    assert response.json()["error"] == "bad_request"


def test_body_that_is_not_json(client, auth):
    response = client.post("/ask", content=b"hello", headers=auth)
    assert response.status_code == 400


def test_oversized_prompt(client, auth, calls):
    response = client.post("/ask", json={"prompt": "x" * (MAX_PROMPT_CHARS + 1)}, headers=auth)
    assert response.status_code == 413
    assert response.json()["error"] == "prompt_too_large"
    assert calls() == []


def test_oversized_body(client, auth):
    response = client.post("/ask", json={"prompt": "x", "pad": "y" * 1_100_000}, headers=auth)
    assert response.status_code == 413
    assert response.json()["error"] == "too_large"


def test_error_result_from_claude(client, auth):
    response = client.post("/ask", json={"prompt": "FAKE_ERROR"}, headers=auth)
    assert response.status_code == 502
    assert response.json() == {"error": "error_max_turns", "message": "turn limit"}


def test_denied_tools_are_reported_by_name_only(client, auth):
    response = client.post("/ask", json={"prompt": "FAKE_DENIED"}, headers=auth)
    assert response.status_code == 200
    assert response.json()["denied_tools"] == ["Write"]
    assert "secret" not in response.text


def test_crash_and_garbage_output(client, auth):
    crash = client.post("/ask", json={"prompt": "FAKE_CRASH"}, headers=auth)
    assert crash.status_code == 502
    assert crash.json()["error"] == "claude_failed"
    assert "something broke" not in crash.text
    garbage = client.post("/ask", json={"prompt": "FAKE_GARBAGE"}, headers=auth)
    assert garbage.status_code == 502
    assert garbage.json()["error"] == "bad_output"


def test_missing_claude_command(settings, auth):
    client = TestClient(create_app(dataclasses.replace(settings, claude_bin="/nonexistent/claude")))
    response = client.post("/ask", json={"prompt": "hello"}, headers=auth)
    assert response.status_code == 503
    assert response.json()["error"] == "claude_not_found"


def test_missing_working_directory(settings, auth, tmp_path):
    client = TestClient(create_app(dataclasses.replace(settings, ask_dir=tmp_path / "gone")))
    response = client.post("/ask", json={"prompt": "hello"}, headers=auth)
    assert response.status_code == 503
    assert response.json()["error"] == "working_directory_missing"


def test_timeout_stops_the_process(settings, auth, monkeypatch):
    stopped = []
    original_stop = claude._stop

    async def quick_stop(process, grace):
        await original_stop(process, 0.2)
        stopped.append(process.returncode)

    monkeypatch.setattr(claude, "_stop", quick_stop)
    app = create_app(dataclasses.replace(settings, ask_timeout=1))
    client = TestClient(app)
    response = client.post("/ask", json={"prompt": "FAKE_SLEEP"}, headers=auth)
    assert response.status_code == 504
    assert response.json()["error"] == "timeout"
    assert stopped and stopped[0] is not None, "the child must be gone after a timeout"
    assert app.state.slots.active == set(), "the slot must be released"


def test_concurrency_limit(settings, auth):
    app = create_app(settings)
    client = TestClient(app)
    app.state.slots.active.update({"a", "b"})
    response = client.post("/ask", json={"prompt": "hello"}, headers=auth)
    assert response.status_code == 429
    assert response.json()["error"] == "busy"


def test_same_session_cannot_run_twice_at_once(settings, auth):
    app = create_app(settings)
    client = TestClient(app)
    first = client.post("/ask", json={"prompt": "one"}, headers=auth).json()
    app.state.slots.active.add(first["session_id"])
    response = client.post(
        "/ask", json={"prompt": "two", "session_id": first["session_id"]}, headers=auth
    )
    assert response.status_code == 409
    assert response.json()["error"] == "session_in_use"


def test_slot_is_released_after_success_and_failure(settings, auth):
    app = create_app(settings)
    client = TestClient(app)
    client.post("/ask", json={"prompt": "hello"}, headers=auth)
    client.post("/ask", json={"prompt": "FAKE_CRASH"}, headers=auth)
    assert app.state.slots.active == set()


def test_stream_sends_session_deltas_and_done(settings, auth, calls):
    app = create_app(settings)
    client = TestClient(app)
    response = client.post("/ask", json={"prompt": "hello", "stream": True}, headers=auth)
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["session", "delta", "delta", "done"]
    assert "".join(data["text"] for name, data in events if name == "delta") == "echo: hello"
    done = events[-1][1]
    assert done["answer"] == "echo: hello"
    assert done["session_id"] == events[0][1]["session_id"]
    args = calls()[0]["args"]
    assert option(args, "--output-format") == "stream-json"
    assert "--verbose" in args and "--include-partial-messages" in args
    assert option(args, "--tools") == "Read,Glob,Grep"
    assert app.state.slots.active == set()


def test_stream_reports_errors_as_events(client, auth):
    for prompt, code in [("FAKE_ERROR", "error_max_turns"), ("FAKE_CRASH", "claude_failed")]:
        response = client.post("/ask", json={"prompt": prompt, "stream": True}, headers=auth)
        events = parse_sse(response.text)
        assert events[-1][0] == "error"
        assert events[-1][1]["error"] == code


def test_stream_validation_errors_are_plain_json(client, auth):
    response = client.post("/ask", json={"prompt": "", "stream": True}, headers=auth)
    assert response.status_code == 400


def test_project_dir_encoding(tmp_path):
    folder = tmp_path / "my_app.v2"
    folder.mkdir()
    encoded = claude.project_dir(folder, home=tmp_path).name
    assert encoded.endswith("-my-app-v2")
    assert set(encoded) <= set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-")
