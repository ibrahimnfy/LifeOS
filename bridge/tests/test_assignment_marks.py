"""Assignments marked by hand (#196) and deleted tasks (#197). Synthetic content only."""

import subprocess

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import actions, assignment_marks, memory, moodle_sync
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def brain(ask_dir):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    (ask_dir / "tasks.md").write_text("# Tasks\n\n## Open\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


def clean(folder) -> bool:
    return git(folder, "status", "--short") == ""


def synced_task(brain, assignment=41, title="Homework 1"):
    task = memory.add_synced_task(
        brain, title, {"due": "2027-02-01", "assignment": f"moodle-{assignment}"}
    )
    memory.commit(brain, "A synced task", ["tasks.md"])
    return task


def open_titles(brain):
    return [task.title for task in memory.list_tasks(brain)]


def test_done_completes_the_task_and_clearing_opens_it_again(brain):
    task = synced_task(brain)
    marked = assignment_marks.set_mark(brain, 41, "done", task_id=task.id)
    assert marked == {"mark": "done", "previous": None, "task": task.id}
    assert open_titles(brain) == [] and "Homework 1" in (brain / "tasks-done.md").read_text()
    assert assignment_marks.read(brain)[41]["closed"] == "completed" and clean(brain)

    cleared = assignment_marks.set_mark(brain, 41, None, task_id=task.id)
    assert cleared["previous"] == "done" and cleared["mark"] is None
    assert open_titles(brain) == ["Homework 1"] and assignment_marks.read(brain) == {}
    assert "done" not in memory.list_tasks(brain)[0].fields and clean(brain)


def test_removed_trashes_the_task_and_clearing_brings_it_back(brain):
    task = synced_task(brain)
    assignment_marks.set_mark(brain, 41, "removed")  # found by its assignment field
    assert open_titles(brain) == [] and assignment_marks.read(brain)[41]["closed"] == "trashed"
    assert "Homework 1" in (brain / memory.TRASHED_LINES).read_text() and clean(brain)

    assignment_marks.set_mark(brain, 41, None)
    assert [t.id for t in memory.list_tasks(brain)] == [task.id] and clean(brain)
    assert "Homework 1" not in (brain / memory.TRASHED_LINES).read_text()


def test_a_mark_changes_from_done_to_removed_and_repeats_change_nothing(brain):
    task = synced_task(brain)
    assignment_marks.set_mark(brain, 41, "done", task_id=task.id)
    again = assignment_marks.set_mark(brain, 41, "done", task_id=task.id)
    assert again["previous"] == "done" and clean(brain)
    changed = assignment_marks.set_mark(brain, 41, "removed", task_id=task.id)
    assert changed["previous"] == "done" and assignment_marks.read(brain)[41]["mark"] == "removed"
    assert "Homework 1" in (brain / memory.TRASHED_LINES).read_text()
    assert "Homework 1" not in (brain / "tasks-done.md").read_text()
    assignment_marks.set_mark(brain, 41, None)
    assert open_titles(brain) == ["Homework 1"]


def test_a_task_the_owner_finished_before_is_left_alone(brain):
    task = synced_task(brain)
    memory.complete_task(brain, task.id)
    marked = assignment_marks.set_mark(brain, 41, "done", task_id=task.id)
    assert marked["task"] is None and "closed" not in assignment_marks.read(brain)[41]
    assignment_marks.set_mark(brain, 41, None)
    assert open_titles(brain) == []  # the mark never closed it, so clearing does not open it


def test_an_assignment_without_a_task_is_marked_too_and_bad_marks_are_refused(brain):
    assert assignment_marks.set_mark(brain, 7, "removed")["task"] is None
    assert assignment_marks.read(brain)[7]["mark"] == "removed"
    with pytest.raises(ClaudeError):
        assignment_marks.set_mark(brain, 7, "maybe")


def test_a_deleted_task_comes_back_by_restore(brain, settings, auth):
    client = TestClient(create_app(settings))
    made = client.post("/tasks", json={"title": "Buy milk"}, headers=auth).json()
    task_id = made["id"]
    gone = client.delete(f"/tasks/{task_id}", headers=auth)
    assert gone.status_code == 200 and open_titles(brain) == []
    assert "Buy milk" in (brain / memory.TRASHED_LINES).read_text() and clean(brain)
    assert client.delete(f"/tasks/{task_id}", headers=auth).status_code == 404

    back = client.post(f"/tasks/{task_id}/restore", headers=auth)
    assert back.status_code == 200 and back.json()["id"] == task_id
    assert open_titles(brain) == ["Buy milk"] and clean(brain)
    assert client.post(f"/tasks/{task_id}/restore", headers=auth).status_code == 200  # repeat
    assert open_titles(brain) == ["Buy milk"]
    assert client.post("/tasks/t-20270101-99/restore", headers=auth).status_code == 404

    client.post(f"/tasks/{task_id}/complete", headers=auth)
    assert client.post(f"/tasks/{task_id}/reopen", headers=auth).status_code == 200
    assert open_titles(brain) == ["Buy milk"]


# ---- the actions


COURSES = [
    {
        "id": 5,
        "name": "Example course A",
        "shortname": "EX 101",
        "folder": "example-course-a-5",
        "assignments": [
            {"id": 41, "name": "Homework 1", "files": ["f1", "f2"], "task": None},
            {"id": 42, "name": "Homework 2", "files": []},
        ],
        "files": {
            "f1": {"dir": "assignments/homework-1-41", "local": "brief.pdf"},
            "f2": {"dir": "assignments/homework-1-41", "local": "missing.pdf"},
        },
    }
]


@pytest.fixture
def ctx(settings, brain, monkeypatch):
    monkeypatch.setattr(actions, "_courses", lambda: COURSES)
    return actions.Context(settings, memory.now_local(), None)


def test_mark_assignment_by_words_and_its_undo(ctx, brain):
    task = synced_task(brain)
    marked = actions.execute(ctx, "mark_assignment", {"assignment": "homework 1", "mark": "done"})
    assert marked.ok and marked.title == "Homework 1" and marked.when == "done"
    assert open_titles(brain) == [] and assignment_marks.read(brain)[41]["task"] == task.id
    actions.undo(marked.undo)
    assert open_titles(brain) == ["Homework 1"] and assignment_marks.read(brain) == {}

    removed = actions.execute(
        ctx, "mark_assignment", {"assignment": "homework 1", "mark": "removed"}
    )
    opened = actions.execute(ctx, "mark_assignment", {"assignment": "homework 1", "mark": "open"})
    assert removed.ok and opened.ok and open_titles(brain) == ["Homework 1"]
    actions.undo(opened.undo)  # the undo of taking a mark off puts the mark back
    assert assignment_marks.read(brain)[41]["mark"] == "removed"

    with pytest.raises(actions.Ask) as asked:
        actions.execute(ctx, "mark_assignment", {"assignment": "homework", "mark": "done"})
    assert [o.label for o in asked.value.options] == ["Homework 1", "Homework 2"]
    assert asked.value.options[0].params == {"mark": "done", "course_id": 5, "assignment_id": 41}
    missing = actions.execute(ctx, "mark_assignment", {"assignment": "essay", "mark": "done"})
    assert not missing.ok and missing.error == "assignment_not_found"


def test_delete_task_by_words_and_its_undo(ctx, brain):
    actions.execute(ctx, "add_task", {"title": "Call the dentist"})
    gone = actions.execute(ctx, "delete_task", {"task": "dentist"})
    assert gone.ok and gone.title == "Call the dentist" and open_titles(brain) == []
    actions.undo(gone.undo)
    assert open_titles(brain) == ["Call the dentist"] and clean(brain)


def test_ask_course_opens_a_read_only_chat_and_sends_the_question(ctx, monkeypatch, tmp_path):
    from lifeos_bridge import folder_requests, moodle_api, permission

    course_dir = tmp_path / "courses" / "example-course-a-5"
    (course_dir / "assignments/homework-1-41").mkdir(parents=True)
    (course_dir / "assignments/homework-1-41/brief.pdf").write_bytes(b"x")
    monkeypatch.setattr(moodle_sync, "download_dir", lambda: tmp_path / "courses")
    item = {"path": str(course_dir), "label": "EX 101", "kind": "course", "colour": 1}
    monkeypatch.setattr(folder_requests, "course_folder", lambda settings, course: item)
    assert moodle_api.local_files(COURSES[0], COURSES[0]["assignments"][0]) == [
        "assignments/homework-1-41/brief.pdf"
    ]

    asked = actions.execute(
        ctx, "ask_course", {"text": "explain the file", "assignment": "homework 1"}
    )
    assert asked.ok and asked.mode == "read_only" and asked.title == "Homework 1"
    perform = asked.perform
    assert (perform["action"], perform["folder"], perform["mode"], perform["send"]) == (
        "new_chat_in", item, "read_only", True,
    )  # fmt: skip
    assert perform["text"] == (
        "explain the file\n\nAssignment: Homework 1\n"
        "Its files (paths in this folder): assignments/homework-1-41/brief.pdf"
    )
    assert permission.was_relayed(perform["text"])  # never the owner's own message

    ctx.language = "tr"
    by_course = actions.execute(ctx, "ask_course", {"text": "özetle", "course": "ex 101"})
    assert by_course.perform["text"] == "özetle" and by_course.title == "Example course A"
    turkish = actions.execute(ctx, "ask_course", {"text": "anlat", "assignment": "homework 2"})
    assert turkish.perform["text"] == "anlat\n\nÖdev: Homework 2"

    monkeypatch.setattr(folder_requests, "course_folder", lambda settings, course: None)
    nowhere = actions.execute(ctx, "ask_course", {"text": "x", "course": "ex 101"})
    assert not nowhere.ok and nowhere.error == "course_folder_missing"
    neither = actions.execute(ctx, "ask_course", {"text": "x"})
    assert not neither.ok and neither.error == "course_not_found"


def test_new_chats_send_their_text_as_a_relayed_message(ctx):
    from lifeos_bridge import permission

    blank = actions.execute(ctx, "new_chat", {"text": "what is a monad"})
    assert blank.perform["text"] == "what is a monad" and blank.perform["send"] is True
    assert permission.was_relayed("what is a monad")
    empty = actions.execute(ctx, "new_chat", {})
    assert "send" not in empty.perform and "text" not in empty.perform


@pytest.mark.parametrize(
    ("started", "ok", "note", "error"),
    [
        ("started", True, None, None),
        ("running", True, "sync_running", None),
        ("too_soon", True, "sync_too_soon", None),
        ("not_configured", False, None, "moodle_not_signed_in"),
    ],
)
def test_sync_courses_says_what_happened(ctx, monkeypatch, started, ok, note, error):
    monkeypatch.setattr(moodle_sync, "start", lambda settings: started)
    monkeypatch.setattr(moodle_sync, "status", lambda: {"last_sync": "2027-01-15T06:00:00Z"})
    outcome = actions.execute(ctx, "sync_courses", {})
    assert (outcome.ok, outcome.note, outcome.error) == (ok, note, error)
    assert outcome.when == ("2027-01-15T06:00:00Z" if ok else None)
