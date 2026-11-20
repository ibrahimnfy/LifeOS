"""New conversations in a chosen folder."""

import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge.app import create_app


@pytest.fixture
def world(settings, ask_dir, tmp_path):
    project = tmp_path / "home" / "project"
    project.mkdir()
    other = tmp_path / "home" / "other"
    other.mkdir()
    client = TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,))))
    return client, ask_dir, project, other


def last_call(folder):
    return json.loads((folder / ".fake-claude-calls.jsonl").read_text().splitlines()[-1])


def test_folders_lists_the_memory_folder_and_the_allowed_ones(world, auth):
    client, ask_dir, project, _ = world
    assert client.get("/folders").status_code == 401
    body = client.get("/folders", headers=auth).json()
    assert body == {
        "folders": [
            {"path": str(ask_dir), "label": ask_dir.name, "kind": "memory", "default": True},
            {"path": str(project), "label": "project", "kind": "allowed", "default": False},
        ]
    }


def test_new_conversation_runs_in_the_chosen_folder_and_stays_there(world, auth):
    client, ask_dir, project, _ = world
    first = client.post("/ask", headers=auth, json={"prompt": "hello", "folder": str(project)})
    assert first.status_code == 200
    assert last_call(project)["cwd"] == str(project)
    assert not (ask_dir / ".fake-claude-calls.jsonl").exists()
    session = first.json()["session_id"]
    # a later message needs no folder: the session's own folder is used
    again = client.post("/ask", headers=auth, json={"prompt": "again", "session_id": session})
    assert again.status_code == 200
    call = last_call(project)
    assert call["cwd"] == str(project) and "--resume" in call["args"]


def test_default_is_the_memory_folder(world, auth):
    client, ask_dir, _, _ = world
    assert client.post("/ask", headers=auth, json={"prompt": "hello"}).status_code == 200
    assert last_call(ask_dir)["cwd"] == str(ask_dir)


def test_folders_outside_the_list_are_refused(world, auth, tmp_path):
    client, _, project, other = world
    outside = [
        str(other),
        str(project / "sub"),
        "/etc",
        str(tmp_path / "home"),
        "relative",
        "",
        5,
        str(project) + "/../other",
    ]
    for folder in outside:
        response = client.post("/ask", headers=auth, json={"prompt": "x", "folder": folder})
        assert response.status_code in (400, 403), folder
    assert not (other / ".fake-claude-calls.jsonl").exists()


def test_unknown_session_ids_are_still_refused(world, auth):
    client, *_ = world
    response = client.post("/ask", headers=auth, json={"prompt": "x", "session_id": "nope"})
    assert response.status_code == 404
