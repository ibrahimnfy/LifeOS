import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge.app import create_app


@pytest.fixture
def notifications(tmp_path):
    return tmp_path / "notifications.log"


@pytest.fixture
def client(settings, isolated_config, tmp_path, notifications):
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf "%s|%s|%s\\n" "$1" "$2" "$3" >> "{notifications}"\n')
    script.chmod(0o755)
    configured = dataclasses.replace(
        settings, allowed_paths_file=isolated_config / "allowed_paths", notify_script=script
    )
    return TestClient(create_app(configured))


@pytest.fixture
def project(tmp_path):
    folder = tmp_path / "home" / "projects" / "alpha"
    folder.mkdir(parents=True)
    return folder


def request(client, auth, method, path):
    return client.request(method, "/allowed-paths", json={"path": str(path)}, headers=auth)


def test_requires_token(client):
    assert client.get("/allowed-paths").status_code == 401
    assert client.post("/allowed-paths", json={"path": "/x"}).status_code == 401
    assert client.request("DELETE", "/allowed-paths", json={"path": "/x"}).status_code == 401


def test_starts_empty(client, auth):
    assert client.get("/allowed-paths", headers=auth).json() == {"paths": []}


def test_add_list_remove(client, auth, project, isolated_config, notifications):
    added = request(client, auth, "POST", project)
    assert added.status_code == 200 and added.json()["added"] is True
    assert added.json()["paths"] == [{"path": str(project), "active": True, "reason": None}]
    file = isolated_config / "allowed_paths"
    assert str(project) in file.read_text() and file.read_text().startswith("#")
    assert file.stat().st_mode & 0o777 == 0o600

    again = request(client, auth, "POST", project)
    assert again.json()["added"] is False

    removed = request(client, auth, "DELETE", project)
    assert removed.status_code == 200 and removed.json()["paths"] == []
    assert str(project) not in file.read_text() and file.read_text().startswith("#")

    log = [
        json.loads(line)
        for line in (isolated_config / "allowed_paths.log").read_text().splitlines()
    ]
    assert [(e["action"], e["path"]) for e in log] == [
        ("added", str(project)),
        ("removed", str(project)),
    ]
    notes = notifications.read_text().splitlines()
    assert len(notes) == 2 and "added" in notes[0] and "removed" in notes[1]
    assert str(project) not in notifications.read_text(), "notifications must not contain the path"
    audited = [
        (entry["tool"], entry["detail"])
        for path in (isolated_config / "audit").glob("*.jsonl")
        for entry in map(json.loads, path.read_text().splitlines())
    ]
    assert audited == [
        ("AllowedPathAdded", {"path": str(project)}),
        ("AllowedPathRemoved", {"path": str(project)}),
    ]


def test_no_change_without_its_audit_record(client, auth, project, isolated_config):
    (isolated_config / "audit").write_text("not a folder")
    refused = request(client, auth, "POST", project)
    assert refused.status_code == 503 and refused.json()["error"] == "audit_unavailable"
    assert client.get("/allowed-paths", headers=auth).json()["paths"] == []


def test_change_applies_without_restart(client, auth, project, write_session):
    session_id = write_session(project, [user_line(project, "project work")])
    assert client.get("/sessions", headers=auth).json()["sessions"] == []
    request(client, auth, "POST", project)
    assert [s["id"] for s in client.get("/sessions", headers=auth).json()["sessions"]] == [
        session_id
    ]
    request(client, auth, "DELETE", project)
    assert client.get("/sessions", headers=auth).json()["sessions"] == []


def test_the_context_map_run_never_becomes_a_session(client, auth, project, write_session):
    """#141: the map run that adding a folder starts used to appear as a session, by timing."""
    import time

    session_id = write_session(project, [user_line(project, "project work")])
    with client:  # a running event loop, so the background map run really runs
        request(client, auth, "POST", project)
        log = project / ".fake-claude-calls.jsonl"
        deadline = time.time() + 10
        while not log.exists():
            assert time.time() < deadline
            time.sleep(0.02)
        time.sleep(1.0)  # long after the stand-in would have written a transcript
        args = json.loads(log.read_text().splitlines()[0])["args"]
        assert "--no-session-persistence" in args and "--session-id" not in args
        listed = client.get("/sessions", headers=auth).json()["sessions"]
        assert [s["id"] for s in listed] == [session_id]


def test_refusals(client, auth, tmp_path, isolated_config, notifications):
    home = tmp_path / "home"
    (home / ".ssh").mkdir()
    (home / "projects" / ".git").mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir()
    (home / "link").symlink_to(home / ".ssh")
    (home / "note.txt").write_text("x")
    cases = {
        home: "home folder itself",
        "~": "home folder itself",
        "/": "inside the home",
        "/etc": "inside the home",
        "/usr/bin": "inside the home",
        outside: "inside the home",
        home / ".ssh": "hidden",
        home / "projects" / ".git": "hidden",
        home / "link": "hidden",
        home / "missing": "does not exist",
        home / "note.txt": "not a folder",
        "relative/folder": "absolute",
        isolated_config: "inside the home",
    }
    for path, expected in cases.items():
        response = request(client, auth, "POST", path)
        assert response.status_code == 400, path
        assert response.json()["error"] == "path_not_allowed"
        assert expected in response.json()["message"], path
    assert client.get("/allowed-paths", headers=auth).json() == {"paths": []}
    assert not notifications.exists()


@pytest.mark.parametrize("body", [{}, {"path": ""}, {"path": 5}, {"path": "/a\n/b"}])
def test_bad_bodies(client, auth, body):
    assert client.post("/allowed-paths", json=body, headers=auth).status_code == 400


def test_remove_unknown(client, auth, project):
    response = request(client, auth, "DELETE", project)
    assert response.status_code == 404 and response.json()["error"] == "path_not_listed"


def test_hand_edited_bad_entries_are_shown_inactive_and_removable(
    client, auth, isolated_config, tmp_path
):
    (isolated_config / "allowed_paths").write_text("# mine\n/etc\n")
    (listed,) = client.get("/allowed-paths", headers=auth).json()["paths"]
    assert listed["path"] == "/etc" and listed["active"] is False and listed["reason"]
    assert request(client, auth, "DELETE", "/etc").status_code == 200
    assert (isolated_config / "allowed_paths").read_text() == "# mine\n"


def test_notify_failure_does_not_fail_the_request(
    settings, auth, isolated_config, project, tmp_path
):
    configured = dataclasses.replace(
        settings,
        allowed_paths_file=isolated_config / "allowed_paths",
        notify_script=tmp_path / "missing.sh",
    )
    client = TestClient(create_app(configured))
    assert request(client, auth, "POST", project).status_code == 200
