"""Deleting a note (#184): to the memory folder's trash, never destroyed; restored under the
same id. Synthetic content only."""

import subprocess
from datetime import datetime

import pytest

from lifeos_bridge import memory


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


def commit_all(folder, message="setup"):
    git(folder, "add", "-A")
    git(folder, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", message)


@pytest.fixture
def brain(ask_dir):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    (ask_dir / "tasks.md").write_text("# Tasks\n\n## Open\n")
    commit_all(ask_dir, "init")
    return ask_dir


def commits(folder):
    return git(folder, "log", "--format=%s").splitlines()


def test_a_capture_is_deleted_to_the_trash_and_restored_in_place(client, auth, brain):
    first, second, third = (
        memory.add_note(brain, text, now=datetime(2027, 1, 15, 9, minute).astimezone())
        for minute, text in [(1, "first\nmore"), (2, "second"), (3, "third")]
    )
    before = (brain / "inbox.md").read_text()

    gone = client.delete(f"/notes/{second['id']}", headers=auth)
    assert gone.status_code == 200
    assert gone.json()["deleted"] is True and gone.json()["id"] == second["id"]
    assert gone.json()["kind"] == "inbox"
    assert "second" not in (brain / "inbox.md").read_text()
    assert "second" in (brain / memory.DELETED_FILE).read_text()
    assert commits(brain)[0] == "Delete a captured note (moved to the trash)"
    assert git(brain, "status", "--short") == ""
    listed = [n["id"] for n in client.get("/notes", headers=auth).json()["notes"]]
    assert listed == [third["id"], first["id"]]
    assert client.get(f"/notes/{second['id']}", headers=auth).status_code == 404
    assert client.delete(f"/notes/{second['id']}", headers=auth).status_code == 404

    back = client.post(f"/notes/{second['id']}/restore", headers=auth)
    assert back.status_code == 200 and back.json()["id"] == second["id"]
    assert back.json()["text"] == "second" and back.json()["repeat"] is False
    assert (brain / "inbox.md").read_text() == before  # in its old place, by time
    assert "second" not in (brain / memory.DELETED_FILE).read_text()
    assert commits(brain)[0] == "Restore a deleted note"
    # An undo sent twice does no harm; an unknown id is not found.
    again = client.post(f"/notes/{second['id']}/restore", headers=auth)
    assert again.status_code == 200 and again.json()["repeat"] is True
    assert client.post("/notes/i-000000000000/restore", headers=auth).status_code == 404

    # A multi-line capture keeps its lines through the trash.
    client.delete(f"/notes/{first['id']}", headers=auth)
    assert "  more" in (brain / memory.DELETED_FILE).read_text()
    restored = client.post(f"/notes/{first['id']}/restore", headers=auth).json()
    assert restored["text"] == "first\nmore" and (brain / "inbox.md").read_text() == before


def test_a_topic_note_moves_to_the_trash_folder_and_back(client, auth, brain):
    (brain / "notes").mkdir()
    (brain / "notes" / "example-topic.md").write_text("---\ntitle: Example\n---\n\nBody one.\n")
    commit_all(brain, "topic")

    assert client.delete("/notes/n-example-topic", headers=auth).json()["kind"] == "topic"
    assert not (brain / "notes" / "example-topic.md").exists()
    assert (brain / ".trash" / "notes" / "example-topic.md").read_text().endswith("Body one.\n")
    assert git(brain, "status", "--short") == ""
    assert client.get("/notes/n-example-topic", headers=auth).status_code == 404

    assert client.post("/notes/n-example-topic/restore", headers=auth).json()["title"] == "Example"
    assert (brain / "notes" / "example-topic.md").is_file() and git(brain, "status", "-s") == ""

    # Deleted twice: the older copy in the trash stays, under a name of its own.
    client.delete("/notes/n-example-topic", headers=auth)
    (brain / "notes" / "example-topic.md").write_text("Body two.\n")
    commit_all(brain, "again")
    assert client.post("/notes/n-example-topic/restore", headers=auth).status_code == 409
    client.delete("/notes/n-example-topic", headers=auth)
    kept = sorted(p.name for p in (brain / ".trash" / "notes").iterdir())
    assert len(kept) == 2 and kept[0] == "example-topic.md" and git(brain, "status", "-s") == ""
    assert (brain / ".trash" / "notes" / "example-topic.md").read_text() == "Body two.\n"


def test_a_topic_note_never_committed_is_deleted_too(client, auth, brain):
    (brain / "notes").mkdir()
    (brain / "notes" / "fresh-topic.md").write_text("Written by hand, not committed yet.\n")
    assert client.delete("/notes/n-fresh-topic", headers=auth).status_code == 200
    assert (brain / ".trash" / "notes" / "fresh-topic.md").is_file()
    assert git(brain, "status", "--short") == ""
    assert client.post("/notes/n-fresh-topic/restore", headers=auth).status_code == 200
    assert (brain / "notes" / "fresh-topic.md").is_file() and git(brain, "status", "-s") == ""


def test_a_topic_note_is_edited_in_place_and_keeps_its_id(client, auth, brain):
    """Every note of the list can be edited (#202): a topic note's body is replaced, its
    front matter and its id stay."""
    (brain / "notes").mkdir()
    (brain / "notes" / "example-topic.md").write_text(
        "---\ntitle: Example\nupdated: 2027-01-10\n---\n\nBody one.\n"
    )
    (brain / "notes" / "plain-topic.md").write_text("No front matter.\n")
    commit_all(brain, "topics")

    edited = client.patch(
        "/notes/n-example-topic", headers=auth, json={"text": "Body two.\nMore. "}
    )
    assert edited.status_code == 200
    assert (edited.json()["id"], edited.json()["title"]) == ("n-example-topic", "Example")
    assert edited.json()["text"] == "Body two.\nMore." and edited.json()["at"] != "2027-01-10"
    text = (brain / "notes" / "example-topic.md").read_text()
    assert text.startswith("---\ntitle: Example\nupdated: ") and text.endswith(
        "---\n\nBody two.\nMore.\n"
    )
    assert commits(brain)[0] == "Edit a note" and git(brain, "status", "--short") == ""

    plain = client.patch("/notes/n-plain-topic", headers=auth, json={"text": "Changed."})
    assert plain.json()["text"] == "Changed."
    assert (brain / "notes" / "plain-topic.md").read_text() == "Changed.\n"

    long = "x" * (memory.MAX_TOPIC_TEXT + 1)
    assert (
        client.patch("/notes/n-plain-topic", headers=auth, json={"text": long}).status_code == 400
    )
    assert client.patch("/notes/n-plain-topic", headers=auth, json={"text": " "}).status_code == 400
    assert client.patch("/notes/n-none", headers=auth, json={"text": "x"}).status_code == 404
    # Longer than a capture may be, but fine for a topic note.
    medium = "y" * (memory.MAX_TEXT + 1)
    assert (
        client.patch("/notes/n-plain-topic", headers=auth, json={"text": medium}).status_code == 200
    )


def test_a_topic_note_with_any_file_name_can_be_opened_edited_and_deleted(client, auth, brain):
    """A file made by hand may have a name that is no id (an underscore, a space, other
    letters): the list showed it, yet it could not be opened or deleted (#202)."""
    (brain / "notes").mkdir()
    names = ["my_notes.md", "Two words.md", "d\u00fc\u015f\u00fcnce.md", "plain.md"]
    for name in names:
        (brain / "notes" / name).write_text(f"Text of {name}\n")
    commit_all(brain, "odd names")

    listed = client.get("/notes", headers=auth).json()["notes"]
    assert len(listed) == 4 and len({n["id"] for n in listed}) == 4
    assert "n-plain" in [n["id"] for n in listed]
    for note in listed:
        assert memory.NOTE_ID.fullmatch(note["id"])
        got = client.get(f"/notes/{note['id']}", headers=auth)
        assert got.status_code == 200 and got.json()["text"].startswith("Text of ")
        assert (
            client.patch(f"/notes/{note['id']}", headers=auth, json={"text": "changed"}).json()[
                "id"
            ]
            == note["id"]
        )
        assert client.delete(f"/notes/{note['id']}", headers=auth).status_code == 200
    assert sorted(p.name for p in (brain / "notes").iterdir()) == []
    assert sorted(p.name for p in (brain / ".trash" / "notes").iterdir()) == sorted(names)
    for note in listed:
        back = client.post(f"/notes/{note['id']}/restore", headers=auth)
        assert back.status_code == 200 and back.json()["text"] == "changed"
        assert client.post(f"/notes/{note['id']}/restore", headers=auth).json()["repeat"] is True
    assert sorted(p.name for p in (brain / "notes").iterdir()) == sorted(names)
    assert git(brain, "status", "--short") == ""


def test_bad_note_ids_are_not_deleted(client, auth, brain):
    for bad_id in ["i-unknown", "n-missing", "x", "n-..", "CLAUDE"]:
        assert client.delete(f"/notes/{bad_id}", headers=auth).status_code == 404
        assert client.post(f"/notes/{bad_id}/restore", headers=auth).status_code == 404
    assert client.delete("/notes/i-x").status_code == 401
    assert (brain / "CLAUDE.md").is_file()
