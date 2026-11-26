"""A note's reminder (#185): made with the note or later, kept through an edit, parked while
the note is in the trash, fired with a fixed text. Synthetic content only."""

import asyncio
import json
import subprocess
from datetime import datetime

import pytest

from lifeos_bridge import claude, consolidation, memory, push, reminders
from lifeos_bridge.claude import ClaudeError, RunResult

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


# ---- #191: the reminder keeps its note through the consolidation, and one note has one


def filed_by_consolidation(settings, brain, monkeypatch, captures: dict[str, str]):
    """Run the real proposal and approval with a stand-in model that files ``captures``."""
    operations = [{"op": "file_capture", "capture": c, "file": f} for c, f in captures.items()]

    async def run(settings, prompt, **kwargs):
        return RunResult(json.dumps({"operations": operations}), "", 1, 1)

    monkeypatch.setattr(claude, "run", run)
    monkeypatch.setattr(push, "send_hint", lambda *a, **kw: None)
    night = datetime(2027, 1, 15, 3, 0).astimezone()
    assert asyncio.run(consolidation.run(settings, lambda: night)) == "ok"
    done = consolidation.approve(settings, {"all": True}, lambda: night)
    assert done["failed"] == [] and done["committed"]


def test_a_filed_capture_keeps_its_reminder_and_still_opens(
    client, auth, brain, settings, monkeypatch
):
    (brain / "projects").mkdir()
    (brain / "projects" / "example-project.md").write_text("# Example\n\n## Facts\n")
    (brain / "notes").mkdir()
    (brain / "notes" / "ideas.md").write_text("# Ideas\n\n## Facts\n")
    git(brain, "add", "-A")
    git(brain, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "topics")
    one = client.post(
        "/notes", headers=auth, json={"text": "book the demo room", "remind_at": FUTURE}
    ).json()
    long = client.post(
        "/notes", headers=auth, json={"text": "poster idea\nwith a second line", "remind_at": PAST}
    ).json()
    plain = client.post("/notes", headers=auth, json={"text": "no reminder here"}).json()
    filed_by_consolidation(
        settings,
        brain,
        monkeypatch,
        {
            one["id"]: "projects/example-project.md",
            long["id"]: "notes/ideas.md",
            plain["id"]: "projects/example-project.md",
        },
    )
    assert memory.list_notes(brain, "demo room") == []  # it left the inbox

    # The reminders still name their notes, and the notes still open, read-only.
    for saved, text, file in (
        (one, "book the demo room", "projects/example-project.md"),
        (long, "poster idea\nwith a second line", "notes/ideas.md"),
    ):
        note = client.get(f"/notes/{saved['id']}", headers=auth)
        assert note.status_code == 200, note.text
        note = note.json()
        assert (note["kind"], note["text"], note["filed_in"]) == ("filed", text, file)
        assert note["reminder"]["id"] == saved["reminder"]["id"]
    fired = reminders.fire_due(notify_script=brain / "missing.sh")
    assert [r["note_id"] for r in fired] == [long["id"]]
    assert client.get(f"/notes/{long['id']}", headers=auth).json()["reminder"]["state"] == "fired"
    # A filed capture without a reminder opens too; it is in no list, and is not edited or
    # deleted as a capture (its words live in the topic file now).
    assert client.get(f"/notes/{plain['id']}", headers=auth).json()["kind"] == "filed"
    assert client.get("/notes", headers=auth).json()["notes"][0]["kind"] == "topic"
    assert client.patch(f"/notes/{one['id']}", headers=auth, json={"text": "x"}).status_code == 404
    assert client.delete(f"/notes/{one['id']}", headers=auth).status_code == 404
    # Its reminder can still be moved and removed.
    moved = client.post(
        "/reminders", headers=auth, json={"note_id": one["id"], "at": "2099-02-01T10:00:00+03:00"}
    )
    assert moved.status_code == 200 and moved.json()["id"] == one["reminder"]["id"]
    client.delete(f"/reminders/{one['reminder']['id']}", headers=auth)
    assert client.get(f"/notes/{one['id']}", headers=auth).json()["reminder"] is None


def test_a_note_has_one_reminder_to_come_and_shows_it(client, auth, brain):
    note = client.post("/notes", headers=auth, json={"text": "pay", "remind_at": PAST}).json()
    first = note["reminder"]["id"]
    reminders.fire_due(notify_script=brain / "missing.sh")
    # After the first has fired, a new one is made; the note shows the one to come.
    made = client.post("/reminders", headers=auth, json={"note_id": note["id"], "at": FUTURE})
    assert made.status_code == 201 and made.json()["id"] != first
    assert (
        client.get(f"/notes/{note['id']}", headers=auth).json()["reminder"]["id"]
        == (made.json()["id"])
    )
    # A second time for the same note moves the reminder instead of adding one.
    later = "2099-03-01T10:00:00+03:00"
    again = client.post("/reminders", headers=auth, json={"note_id": note["id"], "at": later})
    assert again.status_code == 200 and again.json()["id"] == made.json()["id"]
    assert again.json()["at"] == "2099-03-01T07:00:00Z"
    assert [r["state"] for r in reminders.load()] == ["fired", "scheduled"]
    # The fired one stays, so its notification still finds the note.
    listed = client.get("/reminders", headers=auth).json()["reminders"]
    assert first in [r["id"] for r in listed]


def test_fired_reminders_do_not_use_up_the_room_and_a_full_store_saves_no_note(
    client, auth, brain, monkeypatch
):
    monkeypatch.setattr(reminders, "MAX_REMINDERS", 2)
    for text in ("one", "two"):
        client.post("/notes", headers=auth, json={"text": text, "remind_at": PAST})
    reminders.fire_due(notify_script=brain / "missing.sh")
    for text in ("three", "four"):
        saved = client.post("/notes", headers=auth, json={"text": text, "remind_at": FUTURE})
        assert saved.status_code == 201
    full = client.post("/notes", headers=auth, json={"text": "five", "remind_at": FUTURE})
    assert full.status_code == 400
    assert memory.list_notes(brain, "five") == []  # refused before anything was written


def test_a_topic_note_keeps_its_reminder_through_the_trash(client, auth, brain):
    (brain / "notes").mkdir()
    (brain / "notes" / "sample-topic.md").write_text("---\ntitle: Sample topic\n---\n\nBody.\n")
    git(brain, "add", "-A")
    git(brain, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "topic")
    made = client.post("/reminders", headers=auth, json={"note_id": "n-sample-topic", "at": FUTURE})
    assert made.status_code == 201
    assert client.delete("/notes/n-sample-topic", headers=auth).status_code == 200
    assert reminders.load()[0]["state"] == "deleted"
    assert client.get("/reminders", headers=auth).json()["reminders"] == []
    restored = client.post("/notes/n-sample-topic/restore", headers=auth)
    assert restored.status_code == 200
    note = client.get("/notes/n-sample-topic", headers=auth).json()
    assert (note["reminder"]["id"], note["reminder"]["state"]) == (made.json()["id"], "scheduled")
