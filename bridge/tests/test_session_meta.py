"""Titles, pins and the reply-notification switch. All transcripts are synthetic.

Custom session groups were removed (#164): the app shows sessions by folder.
"""

import hashlib
import json

from conftest import user_line
from lifeos_bridge import session_meta


def snapshot(folder):
    return {
        str(p): hashlib.sha256(p.read_bytes()).hexdigest() + str(p.stat().st_mtime_ns)
        for p in sorted(folder.rglob("*"))
        if p.is_file()
    }


def listing(client, auth):
    return client.get("/sessions", headers=auth).json()


def test_routes_require_the_token(client):
    assert client.patch("/sessions/abc", json={"pinned": True}).status_code == 401


def test_session_groups_are_gone_from_the_api(client, auth, ask_dir, write_session):
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    one = "/session-groups/abc"
    for method, path in [("GET", "/session-groups"), ("POST", "/session-groups"),
                         ("PATCH", one), ("DELETE", one)]:  # fmt: skip
        response = client.request(method, path, headers=auth, json={"name": "Thesis"})
        assert response.status_code in (404, 405), (method, path)
    # ...and so is the `group` field: not accepted, not reported
    refused = client.patch(f"/sessions/{session}", headers=auth, json={"group": "abc"})
    assert refused.status_code == 400 and "group" not in refused.json()["message"]
    body = listing(client, auth)
    assert "groups" not in body
    for item in (body["sessions"][0], client.get(f"/sessions/{session}", headers=auth).json()):
        assert "group" not in item and "auto_group" not in item
        assert item["folder_label"] == ask_dir.name  # what the app groups by instead
    from lifeos_bridge import relay

    assert "/session-groups" not in relay.ALLOWED_PREFIXES


def test_metadata_stored_with_groups_still_works(
    client, auth, ask_dir, write_session, isolated_config
):
    """A file written before the removal: its groups are ignored without an error, pins and
    titles are kept, and the next change leaves the old fields behind."""
    grouped = write_session(ask_dir, [user_line(ask_dir, "first")], mtime=1_800_000_000)
    plain = write_session(ask_dir, [user_line(ask_dir, "second")], mtime=1_800_000_500)
    meta_file = isolated_config / "sessions-meta.json"
    old = {
        "groups": [{"id": "abc123", "name": "Thesis"}, "damaged"],
        "sessions": {
            grouped: {"title": "My plan", "group": "abc123", "pinned": True},
            plain: {"group": "gone"},
            "not-an-entry": 5,
        },
    }
    meta_file.write_text(json.dumps(old))
    body = listing(client, auth)
    assert [s["id"] for s in body["sessions"]] == [grouped, plain]  # the pin still counts
    first, second = body["sessions"]
    assert (first["title"], first["pinned"]) == ("My plan", True)
    assert (second["title"], second["pinned"]) == ("second", False)
    assert "group" not in first and "groups" not in body
    assert session_meta.entry_for(grouped) == session_meta.DEFAULTS | {
        "title": "My plan", "pinned": True,
    }  # fmt: skip

    renamed = client.patch(f"/sessions/{plain}", headers=auth, json={"title": "Renamed"})
    assert renamed.status_code == 200 and renamed.json()["title"] == "Renamed"
    stored = json.loads(meta_file.read_text())
    assert "groups" not in stored
    assert stored["sessions"][grouped] == {"title": "My plan", "pinned": True}
    assert (
        stored["sessions"][plain]["title"] == "Renamed" and "group" not in stored["sessions"][plain]
    )
    assert "not-an-entry" not in stored["sessions"]


def test_title_pin_and_switch(client, auth, ask_dir, write_session, isolated_config):
    old = write_session(ask_dir, [user_line(ask_dir, "first")], mtime=1_800_000_000)
    new = write_session(ask_dir, [user_line(ask_dir, "second")], mtime=1_800_000_500)
    changes = {"title": " My plan ", "pinned": True, "notify_replies": False}
    updated = client.patch(f"/sessions/{old}", headers=auth, json=changes)
    assert updated.status_code == 200
    body = updated.json()
    assert (body["title"], body["detected_title"]) == ("My plan", "first")
    assert (body["pinned"], body["notify_replies"]) == (True, False)

    body = listing(client, auth)
    assert [s["id"] for s in body["sessions"]] == [old, new]  # pinned first
    assert body["sessions"][1]["pinned"] is False

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


def test_bad_changes_are_refused(client, auth, ask_dir, write_session):
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    for changes in [{}, {"colour": "red"}, {"pinned": "yes"}, {"group": "nope"}, {"title": ""},
                    {"group": None}, {"title": "x" * 121}]:  # fmt: skip
        response = client.patch(f"/sessions/{session}", headers=auth, json=changes)
        assert response.status_code == 400, changes


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
    client.patch(
        f"/sessions/{session}",
        headers=auth,
        json={"title": "Renamed", "pinned": True, "notify_replies": False},
    )
    listing(client, auth)
    assert snapshot(storage) == before


def test_a_damaged_metadata_file_does_not_break_the_listing(
    client, auth, ask_dir, write_session, isolated_config
):
    write_session(ask_dir, [user_line(ask_dir, "first")])
    (isolated_config / "sessions-meta.json").write_text("{not json")
    assert listing(client, auth)["sessions"][0]["title"] == "first"
