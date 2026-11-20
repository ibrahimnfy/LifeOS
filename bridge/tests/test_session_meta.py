"""Groups, titles, pins and the reply-notification switch. All transcripts are synthetic."""

import hashlib
import json

from conftest import user_line


def snapshot(folder):
    return {
        str(p): hashlib.sha256(p.read_bytes()).hexdigest() + str(p.stat().st_mtime_ns)
        for p in sorted(folder.rglob("*"))
        if p.is_file()
    }


def listing(client, auth):
    return client.get("/sessions", headers=auth).json()


def make_group(client, auth, name="Thesis"):
    response = client.post("/session-groups", headers=auth, json={"name": name})
    assert response.status_code == 201
    return response.json()


def test_routes_require_the_token(client):
    assert client.get("/session-groups").status_code == 401
    assert client.post("/session-groups", json={"name": "x"}).status_code == 401
    assert client.patch("/sessions/abc", json={"pinned": True}).status_code == 401


def test_group_lifecycle(client, auth):
    group = make_group(client, auth)
    assert client.get("/session-groups", headers=auth).json() == {"groups": [group]}
    assert client.post("/session-groups", headers=auth, json={"name": "thesis"}).status_code == 409
    renamed = client.patch(f"/session-groups/{group['id']}", headers=auth, json={"name": "Side"})
    assert renamed.json() == {"id": group["id"], "name": "Side"}
    assert client.delete(f"/session-groups/{group['id']}", headers=auth).json() == {"deleted": True}
    assert client.get("/session-groups", headers=auth).json() == {"groups": []}
    assert client.delete(f"/session-groups/{group['id']}", headers=auth).status_code == 404


def test_group_name_is_validated(client, auth):
    for name in ["", "   ", "a\nb", "x" * 61, 5, None]:
        response = client.post("/session-groups", headers=auth, json={"name": name})
        assert response.status_code == 400, name


def test_title_group_pin_and_switch(client, auth, ask_dir, write_session, isolated_config):
    old = write_session(ask_dir, [user_line(ask_dir, "first")], mtime=1_800_000_000)
    new = write_session(ask_dir, [user_line(ask_dir, "second")], mtime=1_800_000_500)
    group = make_group(client, auth)
    changes = {"title": " My plan ", "group": group["id"], "pinned": True, "notify_replies": False}
    updated = client.patch(f"/sessions/{old}", headers=auth, json=changes)
    assert updated.status_code == 200
    body = updated.json()
    assert (body["title"], body["detected_title"]) == ("My plan", "first")
    assert (body["group"], body["pinned"], body["notify_replies"]) == (group["id"], True, False)

    body = listing(client, auth)
    assert [s["id"] for s in body["sessions"]] == [old, new]  # pinned first
    assert body["groups"] == [group]
    assert body["sessions"][1]["group"] is None
    assert body["sessions"][1]["auto_group"] == ask_dir.name

    meta_file = isolated_config / "sessions-meta.json"
    assert meta_file.stat().st_mode & 0o777 == 0o600
    assert old in json.loads(meta_file.read_text())["sessions"]


def test_clearing_returns_to_the_detected_values(client, auth, ask_dir, write_session):
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    client.patch(f"/sessions/{session}", headers=auth, json={"title": "Mine", "pinned": True})
    cleared = client.patch(
        f"/sessions/{session}", headers=auth, json={"title": None, "pinned": False}
    ).json()
    assert (cleared["title"], cleared["pinned"]) == ("first", False)


def test_deleting_a_group_ungroups_its_sessions(client, auth, ask_dir, write_session):
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    group = make_group(client, auth)
    client.patch(f"/sessions/{session}", headers=auth, json={"group": group["id"]})
    client.delete(f"/session-groups/{group['id']}", headers=auth)
    body = listing(client, auth)
    assert body["total"] == 1 and body["sessions"][0]["group"] is None


def test_bad_changes_are_refused(client, auth, ask_dir, write_session):
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    for changes in [{}, {"colour": "red"}, {"pinned": "yes"}, {"group": "nope"}, {"title": ""},
                    {"group": 7}, {"title": "x" * 121}]:  # fmt: skip
        response = client.patch(f"/sessions/{session}", headers=auth, json=changes)
        assert response.status_code in (400, 404), changes


def test_sessions_outside_the_allowed_folders_cannot_be_changed(
    client, auth, tmp_path, write_session, isolated_config
):
    other = tmp_path / "home" / "private-project"
    other.mkdir()
    session = write_session(other, [user_line(other, "secret work")])
    response = client.patch(f"/sessions/{session}", headers=auth, json={"pinned": True})
    assert response.status_code == 404
    assert not (isolated_config / "sessions-meta.json").exists()


def test_claude_session_storage_is_never_written(client, auth, ask_dir, tmp_path, write_session):
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    storage = tmp_path / "home" / ".claude"
    before = snapshot(storage)
    group = make_group(client, auth)
    client.patch(
        f"/sessions/{session}",
        headers=auth,
        json={"title": "Renamed", "group": group["id"], "pinned": True, "notify_replies": False},
    )
    listing(client, auth)
    client.delete(f"/session-groups/{group['id']}", headers=auth)
    assert snapshot(storage) == before


def test_a_damaged_metadata_file_does_not_break_the_listing(
    client, auth, ask_dir, write_session, isolated_config
):
    write_session(ask_dir, [user_line(ask_dir, "first")])
    (isolated_config / "sessions-meta.json").write_text("{not json")
    assert listing(client, auth)["sessions"][0]["title"] == "first"
