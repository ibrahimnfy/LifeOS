"""Resuming listed sessions. All transcripts here are synthetic."""

import dataclasses
import json
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge.app import create_app
from test_ask import option, parse_sse


@pytest.fixture
def project(tmp_path):
    folder = tmp_path / "home" / "project"
    folder.mkdir(parents=True)
    return folder


@pytest.fixture
def client(settings, project):
    return TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,))))


def set_agents(content):
    (Path.home() / ".fake-claude-agents.json").write_text(
        content if isinstance(content, str) else json.dumps(content)
    )


def project_calls(project):
    log = project / ".fake-claude-calls.jsonl"
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


def post(client, auth, session_id, body):
    return client.post(f"/sessions/{session_id}/messages", json=body, headers=auth)


def test_requires_token(client):
    assert (
        client.post(f"/sessions/{uuid.uuid4()}/messages", json={"prompt": "x"}).status_code == 401
    )


def test_resumes_in_the_original_folder_with_read_only_flags(client, auth, project, write_session):
    session_id = write_session(project, [user_line(project, "earlier work")])
    response = post(client, auth, session_id, {"prompt": "continue"})
    assert response.status_code == 200
    assert response.json()["answer"] == "echo: continue"
    assert response.json()["session_id"] == session_id
    (call,) = project_calls(project)
    assert call["cwd"] == str(project)
    assert call["prompt"] == "continue"
    args = call["args"]
    assert option(args, "--resume") == session_id
    assert option(args, "--permission-mode") == "dontAsk"
    assert option(args, "--permission-prompts") == "none"
    assert option(args, "--tools") == "Read,Glob,Grep"
    assert "--restricted" in args and "--session-id" not in args


def test_resume_in_sub_folder_runs_there(client, auth, project, write_session):
    sub = project / "docs"
    sub.mkdir()
    session_id = write_session(sub, [user_line(sub, "x")])
    assert post(client, auth, session_id, {"prompt": "go"}).status_code == 200
    assert project_calls(sub)[0]["cwd"] == str(sub)


def test_stream(client, auth, project, write_session):
    session_id = write_session(project, [user_line(project, "x")])
    response = post(client, auth, session_id, {"prompt": "hello", "stream": True})
    events = parse_sse(response.text)
    assert [name for name, _ in events] == ["session", "delta", "delta", "done"]
    assert events[0][1]["session_id"] == session_id


@pytest.mark.parametrize("session_id", [str(uuid.uuid4()), "not-a-uuid", "..%2F..%2Fetc"])
def test_unknown_ids_are_refused(client, auth, project, session_id):
    response = post(client, auth, session_id, {"prompt": "x"})
    assert response.status_code in (401, 404)
    assert project_calls(project) == []


def test_sessions_outside_allowed_roots_are_refused(client, auth, tmp_path, write_session):
    other = tmp_path / "home" / "private"
    other.mkdir()
    session_id = write_session(other, [user_line(other, "secret")])
    response = post(client, auth, session_id, {"prompt": "x"})
    assert response.status_code == 404
    assert response.json()["error"] == "session_not_found"
    assert project_calls(other) == []


def test_refuses_when_session_is_open_on_the_computer(client, auth, project, write_session):
    session_id = write_session(project, [user_line(project, "x")])
    set_agents([{"pid": 1, "cwd": str(project), "kind": "interactive", "sessionId": session_id}])
    response = post(client, auth, session_id, {"prompt": "x"})
    assert response.status_code == 409
    assert response.json()["error"] == "session_in_use"
    assert project_calls(project) == []
    assert client.app.state.slots.active == set()


def test_other_active_sessions_do_not_block(client, auth, project, write_session):
    session_id = write_session(project, [user_line(project, "x")])
    set_agents([{"pid": 1, "cwd": "/x", "kind": "interactive", "sessionId": str(uuid.uuid4())}, 7])
    assert post(client, auth, session_id, {"prompt": "x"}).status_code == 200


@pytest.mark.parametrize("agents", ["FAIL", "not json", '{"a": 1}'])
def test_fails_closed_when_the_in_use_check_fails(client, auth, project, write_session, agents):
    session_id = write_session(project, [user_line(project, "x")])
    set_agents(agents)
    response = post(client, auth, session_id, {"prompt": "x"})
    assert response.status_code == 503
    assert response.json()["error"] == "in_use_check_failed"
    assert project_calls(project) == []
    assert client.app.state.slots.active == set()


def test_refuses_while_the_bridge_itself_runs_the_session(client, auth, project, write_session):
    session_id = write_session(project, [user_line(project, "x")])
    client.app.state.slots.active.add(session_id)
    response = post(client, auth, session_id, {"prompt": "x"})
    assert response.status_code == 409


def test_missing_folder(client, auth, project, write_session):
    gone = project / "gone"
    gone.mkdir()
    session_id = write_session(gone, [user_line(gone, "x")])
    gone.rmdir()
    response = post(client, auth, session_id, {"prompt": "x"})
    assert response.status_code == 409
    assert response.json()["error"] == "folder_missing"


@pytest.mark.parametrize("body", [{}, {"prompt": ""}])
def test_bad_body(client, auth, project, write_session, body):
    session_id = write_session(project, [user_line(project, "x")])
    assert post(client, auth, session_id, body).status_code == 400


def test_ask_resume_also_checks_in_use(settings, auth):
    client = TestClient(create_app(settings))
    first = client.post("/ask", json={"prompt": "one"}, headers=auth).json()
    set_agents([{"pid": 1, "cwd": "/x", "kind": "interactive", "sessionId": first["session_id"]}])
    response = client.post(
        "/ask", json={"prompt": "two", "session_id": first["session_id"]}, headers=auth
    )
    assert response.status_code == 409
