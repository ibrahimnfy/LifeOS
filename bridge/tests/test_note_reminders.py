"""A note's reminder (#185): made with the note or later, kept through an edit, parked while
the note is in the trash, fired with a fixed text. Synthetic content only."""

import subprocess
from datetime import datetime

import pytest

from lifeos_bridge import reminders
from lifeos_bridge.claude import ClaudeError

FUTURE = "2099-01-15T10:00:00+03:00"
PAST = "2000-01-15T10:00:00+03:00"
PHONE_NOTE = "a1b2c3d4e5f60718"


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def brain(ask_dir):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


def test_a_note_is_saved_with_its_reminder_once(client, auth, brain):
    body = {"text": "call the example office", "client_id": PHONE_NOTE, "remind_at": FUTURE,
            "timezone": "Europe/Istanbul"}  # fmt: skip
    saved = client.post("/notes", headers=auth, json=body)
    assert saved.status_code == 201
    reminder = saved.json()["reminder"]
    assert reminder["note_id"] == saved.json()["id"] and reminder["session_id"] is None
    assert (reminder["at"], reminder["state"]) == ("2099-01-15T07:00:00Z", "scheduled")
    # The phone sends it again (an unclear attempt): still one note and one reminder.
    again = client.post("/notes", headers=auth, json=body)
    assert again.status_code == 200 and again.json()["reminder"]["id"] == reminder["id"]
    assert len(reminders.load()) == 1
    # The note shows its reminder; the session reminders do not include it.
    note = client.get(f"/notes/{saved.json()['id']}", headers=auth).json()
    assert note["reminder"]["id"] == reminder["id"]
    listed = client.get("/reminders", headers=auth, params={"note_id": note["id"]}).json()
    assert [r["id"] for r in listed["reminders"]] == [reminder["id"]]
    # A note without one says so.
    plain = client.post("/notes", headers=auth, json={"text": "no reminder"}).json()
    assert plain["reminder"] is None
    assert client.get(f"/notes/{plain['id']}", headers=auth).json()["reminder"] is None


def test_a_bad_reminder_time_saves_nothing(client, auth, brain):
    for at in ["tomorrow", "2099-01-15T10:00:00", 5]:
        response = client.post("/notes", headers=auth, json={"text": "x", "remind_at": at})
        assert response.status_code == 400, at
    assert not (brain / "inbox.md").exists() and reminders.load() == []


def test_a_time_that_passed_while_waiting_fires_at_once(client, auth, brain):
    saved = client.post("/notes", headers=auth, json={"text": "late", "remind_at": PAST}).json()
    assert saved["reminder"]["state"] == "scheduled"
    fired = reminders.fire_due(notify_script=brain / "missing.sh")
    assert [r["id"] for r in fired] == [saved["reminder"]["id"]]


def test_a_reminder_is_set_on_an_existing_note(client, auth, brain):
    note = client.post("/notes", headers=auth, json={"text": "later"}).json()
    made = client.post("/reminders", headers=auth, json={"note_id": note["id"], "at": FUTURE})
    assert made.status_code == 201 and made.json()["note_id"] == note["id"]
    missing = client.post("/reminders", headers=auth, json={"note_id": "i-0000", "at": FUTURE})
    assert missing.status_code == 404
    # One reminder belongs to one session or one note, never to both.
    with pytest.raises(ClaudeError):
        reminders.create("s", {"at": FUTURE}, note_id=note["id"])


def test_the_reminder_follows_an_edit_and_the_trash(client, auth, brain):
    note = client.post("/notes", headers=auth, json={"text": "draft", "remind_at": FUTURE}).json()
    reminder_id = note["reminder"]["id"]
    edited = client.patch(f"/notes/{note['id']}", headers=auth, json={"text": "final"}).json()
    assert edited["id"] != note["id"]
    assert reminders.load()[0]["note_id"] == edited["id"]

    client.delete(f"/notes/{edited['id']}", headers=auth)
    assert reminders.load()[0]["state"] == "deleted"
    assert client.get("/reminders", headers=auth).json()["reminders"] == []
    assert (
        client.patch(f"/reminders/{reminder_id}", headers=auth, json={"at": FUTURE}).status_code
        == 404
    )
    # A parked reminder never fires, even when its time comes.
    later = datetime.fromisoformat("2100-01-01T00:00:00+00:00")
    assert reminders.fire_due(lambda: later, notify_script=brain / "missing.sh") == []

    client.post(f"/notes/{edited['id']}/restore", headers=auth)
    assert reminders.load()[0]["state"] == "scheduled" and "state_before" not in reminders.load()[0]


def test_a_note_reminder_fires_with_its_own_fixed_text(client, auth, brain, tmp_path):
    log = tmp_path / "notify.log"
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf "%s|%s\\n" "$1" "$2" >> "{log}"\n')
    script.chmod(0o755)
    client.post("/notes", headers=auth, json={"text": "secret words", "remind_at": PAST})
    reminders.fire_due(notify_script=script)
    assert log.read_text() == "LifeOS: note reminder|A reminder for one of your notes is due.\n"
