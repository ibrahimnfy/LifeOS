"""Context map. Folders and answers are synthetic."""

import dataclasses
import json
import subprocess

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge.app import create_app

ANSWER = {
    "title": "Example project",
    "what": "A small example.\nIt has two files.",
    "state": "The parser is unfinished.",
    "key_files": [
        {"path": "README.md", "about": "overview"},
        {"path": "missing.txt", "about": "does not exist"},
        {"path": "../outside.txt", "about": "outside the folder"},
        {"path": "/etc/passwd", "about": "absolute"},
    ],
}


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def world(settings, ask_dir, tmp_path):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("rules\n")
    (ask_dir / "INDEX.md").write_text("# Index\n\n## Map\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    project = tmp_path / "home" / "Example Project"
    project.mkdir()
    (project / "README.md").write_text("hello")
    (tmp_path / "home" / "outside.txt").write_text("x")
    (tmp_path / "home" / ".fake-claude-answer").write_text(
        "Here it is:\n```json\n" + json.dumps(ANSWER) + "\n```"
    )
    client = TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,))))
    return client, ask_dir, project


def test_requires_token(client):
    assert client.get("/context-map").status_code == 401
    assert client.post("/context-map/refresh", json={}).status_code == 401


def test_refresh_writes_one_file_per_folder_and_commits(world, auth):
    client, brain, project = world
    assert client.get("/context-map", headers=auth).json() == {
        "folders": [{"path": str(project), "file": None}]
    }
    body = client.post("/context-map/refresh", headers=auth, json={}).json()
    assert body == {"results": [{"ok": True, "file": "map/example-project.md", "changed": True}]}
    text = (brain / "map" / "example-project.md").read_text()
    assert "type: map" in text and "## Location\n`~/Example Project`" in text
    assert "A small example. It has two files." in text
    assert "- `README.md` — overview" in text
    assert "missing.txt" not in text and "outside.txt" not in text and "passwd" not in text
    assert "- [[map/example-project]] — `~/Example Project`" in (brain / "INDEX.md").read_text()
    assert git(brain, "log", "-1", "--format=%s") == "Update the context map: example-project\n"
    assert client.get("/context-map", headers=auth).json()["folders"][0]["file"] == (
        "map/example-project.md"
    )
    # the run was read-only and happened inside the folder
    call = json.loads((project / ".fake-claude-calls.jsonl").read_text().splitlines()[-1])
    assert call["args"][call["args"].index("--tools") + 1] == "Read,Glob,Grep"
    # a routine job: Sonnet, with Opus as the advisor on hard steps (routing.py)
    assert call["args"][call["args"].index("--model") + 1] == "sonnet"
    assert call["args"][call["args"].index("--advisor") + 1] == "opus"


def test_rerun_updates_in_place_and_keeps_facts(world, auth):
    client, brain, project = world
    client.post("/context-map/refresh", headers=auth, json={})
    file = brain / "map" / "example-project.md"
    file.write_text(
        file.read_text().replace("## Facts\n", "## Facts\n- [owner 2027-01-15] Mine.\n")
    )
    git(brain, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-qam", "fact")
    changed = dict(ANSWER, state="Now finished.")
    (project.parent / ".fake-claude-answer").write_text(json.dumps(changed))
    body = client.post("/context-map/refresh", headers=auth, json={"path": str(project)}).json()
    assert body["results"][0]["file"] == "map/example-project.md"
    text = file.read_text()
    assert "Now finished." in text and "The parser is unfinished." not in text
    assert "- [owner 2027-01-15] Mine." in text
    assert len(list((brain / "map").glob("*.md"))) == 1
    assert (brain / "INDEX.md").read_text().count("[[map/example-project]]") == 1


def test_folders_that_are_not_allowed_and_bad_answers(world, auth, tmp_path):
    client, brain, project = world
    other = tmp_path / "home" / "other"
    other.mkdir()
    refused = client.post("/context-map/refresh", headers=auth, json={"path": str(other)})
    assert refused.status_code == 404
    (project.parent / ".fake-claude-answer").write_text("I could not do that.")
    body = client.post("/context-map/refresh", headers=auth, json={}).json()
    assert body == {"results": [{"ok": False, "error": "bad_output"}]}
    assert not (brain / "map").exists()
