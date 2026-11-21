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
    chats = ask_dir.parent / "LifeOS-Chats"
    assert body == {
        "folders": [
            # a blank chat is the default: its own new folder under the chat root
            {"path": str(chats), "label": "Chats", "kind": "chat", "default": True, "colour": 0,
             "usable": True, "reason": None},
            {"path": str(ask_dir), "label": ask_dir.name, "kind": "memory", "default": False,
             "colour": 1},
            {"path": str(project), "label": "project", "kind": "project", "default": False,
             "colour": 2},
        ]
    }  # fmt: skip


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


def test_the_memory_folder_can_still_be_named(world, auth):
    client, ask_dir, _, _ = world
    body = {"prompt": "hello", "folder": str(ask_dir)}
    assert client.post("/ask", headers=auth, json=body).status_code == 200
    assert last_call(ask_dir)["cwd"] == str(ask_dir)


@pytest.mark.blank_chats
def test_default_is_a_blank_chat_in_its_own_folder(world, auth):
    client, ask_dir, project, _ = world
    assert client.post("/ask", headers=auth, json={"prompt": "hello"}).status_code == 200
    for folder in (ask_dir, project):
        assert not (folder / ".fake-claude-calls.jsonl").exists()
    log = ask_dir.parent / ".fake-claude-chat-calls.jsonl"
    cwd = json.loads(log.read_text().splitlines()[-1])["cwd"]
    assert cwd.startswith(str(ask_dir.parent / "LifeOS-Chats") + "/")


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
