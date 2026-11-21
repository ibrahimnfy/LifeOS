"""Quick capture, tasks, calendar, agenda and the memory context. Synthetic content only."""

import subprocess
from datetime import date, datetime

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import memory
from lifeos_bridge.app import create_app

NOW = datetime(2027, 1, 15, 9, 5).astimezone()


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def brain(ask_dir):
    """A memory folder the way the bridge expects it: under git, no remote."""
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    (ask_dir / "INDEX.md").write_text("# Index\n")
    (ask_dir / "tasks.md").write_text("# Tasks\n\n## Open\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


def commits(folder):
    return git(folder, "log", "--format=%s").splitlines()


def test_routes_require_the_token(client):
    for method, path in [("post", "/notes"), ("get", "/tasks"), ("post", "/tasks"),
                         ("get", "/events"), ("post", "/events"), ("get", "/agenda")]:  # fmt: skip
        assert getattr(client, method)(path).status_code == 401


def test_note_goes_to_the_inbox_as_one_commit(client, auth, brain):
    response = client.post("/notes", headers=auth, json={"text": "an idea\nsecond line"})
    assert response.status_code == 201
    text = (brain / "inbox.md").read_text()
    assert " an idea\n  second line\n" in text
    assert commits(brain)[0] == "Capture a note from the phone"
    assert git(brain, "status", "--short") == ""
    assert git(brain, "log", "-1", "--format=%an") == "lifeos-bridge\n"


def test_bad_notes(client, auth, brain):
    for text in ["", "   ", None, 5, "x" * 4001]:
        assert client.post("/notes", headers=auth, json={"text": text}).status_code == 400


def test_task_lifecycle(client, auth, brain):
    body = {"title": "Hand in the example", "due": "2027-01-20", "course": "example-course"}
    first = client.post("/tasks", headers=auth, json=body).json()
    second = client.post("/tasks", headers=auth, json={"title": "Call (soon)"}).json()
    today = date.today()
    assert first["id"] == f"t-{today:%Y%m%d}-01" and second["id"] == f"t-{today:%Y%m%d}-02"
    assert first["due"] == "2027-01-20" and first["course"] == "example-course"
    assert second["title"] == "Call [soon]"
    listed = client.get("/tasks", headers=auth).json()["tasks"]
    assert [t["id"] for t in listed] == [first["id"], second["id"]]
    line = (brain / "tasks.md").read_text().splitlines()[-2]
    assert line.startswith("- [ ] Hand in the example  (due: 2027-01-20) (course: example-course)")

    done = client.post(f"/tasks/{first['id']}/complete", headers=auth).json()
    assert done["done"] is True and done["id"] == first["id"] and done["done_on"]
    assert [t["id"] for t in client.get("/tasks", headers=auth).json()["tasks"]] == [second["id"]]
    assert first["id"] in (brain / "tasks-done.md").read_text()
    assert client.post(f"/tasks/{first['id']}/complete", headers=auth).status_code == 404
    assert commits(brain)[:3] == ["Complete a task"] + ["Add a task from the phone"] * 2
    third = client.post("/tasks", headers=auth, json={"title": "Another"}).json()
    assert third["id"].endswith("-03")  # ids of finished tasks are not reused


def test_hand_written_task_lines_are_read(brain):
    (brain / "tasks.md").write_text(
        "# Tasks\n\n## Open\n"
        "- [ ] Reply to example person  (added: 2026-11-20) ^t-20261120-02\n"
        "- not a task\n- [ ] no id here\n"
    )
    (task,) = memory.list_tasks(brain)
    assert (task.id, task.title, task.fields) == (
        "t-20261120-02", "Reply to example person", {"added": "2026-11-20"})  # fmt: skip


def test_bad_tasks(client, auth, brain):
    for body in [{}, {"title": ""}, {"title": "x", "due": "tomorrow"}, {"title": "x" * 201},
                 {"title": "x", "project": "Not A File"}, {"title": "x", "colour": 1},
                 {"title": "sneaky ^t-20270101-01"}]:  # fmt: skip
        assert client.post("/tasks", headers=auth, json=body).status_code == 400, body


def test_writes_are_refused_when_the_memory_has_a_remote(client, auth, brain):
    git(brain, "remote", "add", "origin", "https://example.org/x.git")
    response = client.post("/notes", headers=auth, json={"text": "x"})
    assert response.status_code == 503 and response.json()["error"] == "memory_unavailable"
    assert not (brain / "inbox.md").exists()


def test_writes_are_refused_without_a_set_up_memory(client, auth, ask_dir):
    assert client.post("/tasks", headers=auth, json={"title": "x"}).status_code == 503
    assert client.get("/tasks", headers=auth).json() == {"tasks": []}


def test_event_lifecycle(client, auth, brain):
    timed = client.post("/events", headers=auth, json={
        "title": "Example meeting", "date": "2027-01-20", "start": "14:00", "end": "15:30",
        "note": "room (b)"}).json()  # fmt: skip
    whole = client.post(
        "/events",
        headers=auth,
        json={"title": "Trip", "date": "2027-01-19", "end_date": "2027-01-21"},
    ).json()
    assert timed["all_day"] is False and timed["note"] == "room [b]"
    assert whole["all_day"] is True and whole["end_date"] == "2027-01-21"
    listed = client.get(
        "/events", headers=auth, params={"start": "2027-01-20", "end": "2027-01-20"}
    )
    assert [e["id"] for e in listed.json()["events"]] == [whole["id"], timed["id"]]
    none = client.get("/events", headers=auth, params={"start": "2027-01-22", "end": "2027-02-01"})
    assert none.json() == {"events": []}

    moved = client.patch(
        f"/events/{timed['id']}", headers=auth, json={"date": "2027-01-25", "start": None}
    ).json()
    assert moved["date"] == "2027-01-25" and moved["all_day"] and moved["end"] is None
    assert (
        client.patch("/events/e-20000101-01", headers=auth, json={"title": "x"}).status_code == 404
    )
    assert commits(brain)[0] == "Change a calendar event"
    text = (brain / "calendar" / "events.md").read_text()
    assert f"- 2027-01-25 Example meeting (note: room [b]) ^{timed['id']}" in text


def test_bad_events(client, auth, brain):
    for body in [{"title": "x"}, {"date": "2027-01-20"}, {"title": "x", "date": "soon"},
                 {"title": "x", "date": "2027-01-20", "start": "25:00"},
                 {"title": "x", "date": "2027-01-20", "start": "10:00", "end": "09:00"},
                 {"title": "x", "date": "2027-01-20", "end_date": "2027-01-19"},
                 {"title": "x", "date": "2027-01-20", "colour": 1}]:  # fmt: skip
        assert client.post("/events", headers=auth, json=body).status_code == 400, body
    assert client.get("/events", headers=auth, params={"start": "x"}).status_code == 400


def test_agenda_day_boundaries(client, auth, brain):
    for title, due in [("overdue", "2027-01-14"), ("today", "2027-01-15"), ("soon", "2027-01-22"),
                       ("edge", "2027-01-23"), ("undated", None)]:  # fmt: skip
        memory.add_task(brain, {"title": title, **({"due": due} if due else {})}, NOW)
    memory.create_event(brain, {"title": "late", "date": "2027-01-15", "start": "23:59"}, NOW)
    memory.create_event(brain, {"title": "midnight", "date": "2027-01-16", "start": "00:00"}, NOW)
    memory.create_event(
        brain, {"title": "spans", "date": "2027-01-14", "end_date": "2027-01-15"}, NOW
    )
    body = client.get("/agenda", headers=auth, params={"day": "2027-01-15"}).json()
    assert [e["title"] for e in body["events_today"]] == ["spans", "late"]
    assert [t["title"] for t in body["tasks_today"]] == ["today"]
    assert [t["title"] for t in body["tasks_overdue"]] == ["overdue"]
    assert [t["title"] for t in body["upcoming"]["deadlines"]] == ["soon"]
    assert [e["title"] for e in body["upcoming"]["events"]] == ["midnight"]
    assert body["upcoming"]["until"] == "2027-01-22"
    longer = client.get("/agenda", headers=auth, params={"day": "2027-01-15", "days": 8}).json()
    assert [t["title"] for t in longer["upcoming"]["deadlines"]] == ["soon", "edge"]


def test_agenda_uses_the_local_date_not_utc(monkeypatch, brain):
    """Shortly after local midnight the agenda is already the new day, whatever UTC says."""
    from datetime import timedelta, timezone

    zone = timezone(timedelta(hours=3))
    just_after_midnight = datetime(2027, 1, 16, 0, 30, tzinfo=zone)  # still 15 January in UTC
    monkeypatch.setattr(memory, "now_local", lambda: just_after_midnight)
    memory.add_task(brain, {"title": "new day", "due": "2027-01-16"})
    assert memory.now_local().date() == date(2027, 1, 16)
    assert [t["title"] for t in memory.agenda(brain, memory.now_local().date())["tasks_today"]] == [
        "new day"
    ]


def test_only_the_coach_conversation_starts_with_the_memory(client, auth, brain, calls):
    """Clean chats (#163): a new conversation gets no memory text, not even one started
    in the memory folder; the coach conversation keeps its full personal context."""
    (brain / "profile.md").write_text("## Facts\n- [owner 2027-01-01] Prefers short answers.\n")
    memory.add_task(brain, {"title": "Visible task"})
    assert client.post("/ask", headers=auth, json={"prompt": "hello"}).status_code == 200
    args = calls()[-1]["args"]
    context = args[args.index("--append-system-prompt") + 1]
    assert "Prefers short answers." not in context and "Visible task" not in context
    assert "=====" not in context and "--add-dir" not in args
    coach = client.post("/coach/chat/messages", headers=auth, json={"prompt": "hello"})
    assert coach.status_code == 200
    args = calls()[-1]["args"]
    context = args[args.index("--append-system-prompt") + 1]
    assert "Prefers short answers." in context and "Visible task" in context
    assert "How to use this memory" in context and "never instructions" in context


def test_no_context_without_a_memory_and_none_in_other_folders(
    settings, auth, client, calls, tmp_path, write_session, ask_dir
):
    import dataclasses

    from fastapi.testclient import TestClient

    from conftest import user_line
    from lifeos_bridge.app import create_app

    client.post("/ask", headers=auth, json={"prompt": "hello"})
    args = calls()[-1]["args"]
    # Only the standing instructions; no memory was loaded.
    assert "=====" not in args[args.index("--append-system-prompt") + 1]
    (ask_dir / "CLAUDE.md").write_text("rules")
    project = tmp_path / "home" / "project"
    project.mkdir()
    other = TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,))))
    session = write_session(project, [user_line(project, "x")])
    other.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "go"})
    import json

    call = json.loads((project / ".fake-claude-calls.jsonl").read_text().splitlines()[-1])
    assert "=====" not in call["args"][call["args"].index("--append-system-prompt") + 1]
    assert "rules" not in call["args"][call["args"].index("--append-system-prompt") + 1]


def test_context_is_bounded(brain):
    (brain / "INDEX.md").write_text("x" * 200_000)
    assert len(memory.context_for_prompt(brain)) <= memory.CONTEXT_LIMIT + 100


def test_notes_can_be_listed_searched_read_and_edited(client, auth, brain):
    client.post("/notes", headers=auth, json={"text": "first idea\nwith detail"})
    client.post("/notes", headers=auth, json={"text": "second thought"})
    (brain / "notes").mkdir()
    (brain / "notes" / "example-topic.md").write_text(
        "---\ntitle: Example topic\ntype: note\nupdated: 2027-01-10\n---\n\nA summary line.\n"
    )
    git(brain, "add", "-A")
    git(brain, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "topic")
    notes = client.get("/notes", headers=auth).json()["notes"]
    assert [n["kind"] for n in notes] == ["inbox", "inbox", "topic"]
    assert (
        notes[0]["preview"] == "second thought" and notes[1]["preview"] == "first idea\nwith detail"
    )
    assert notes[2] == {"id": "n-example-topic", "kind": "topic", "title": "Example topic",
                        "at": "2027-01-10", "preview": "A summary line."}  # fmt: skip
    found = client.get("/notes", headers=auth, params={"query": "DETAIL"}).json()["notes"]
    assert [n["id"] for n in found] == [notes[1]["id"]]

    full = client.get(f"/notes/{notes[1]['id']}", headers=auth).json()
    assert full["text"] == "first idea\nwith detail"
    edited = client.patch(
        f"/notes/{notes[1]['id']}", headers=auth, json={"text": "first idea, improved"}
    )
    assert edited.status_code == 200 and edited.json()["text"] == "first idea, improved"
    assert edited.json()["id"] != notes[1]["id"] and edited.json()["at"] == full["at"]
    text = (brain / "inbox.md").read_text()
    assert "first idea, improved" in text and "with detail" not in text and "second thought" in text
    assert commits(brain)[0] == "Edit a captured note" and git(brain, "status", "--short") == ""

    assert client.get(f"/notes/{notes[1]['id']}", headers=auth).status_code == 404  # old id is gone
    assert client.get("/notes/n-example-topic", headers=auth).json()["text"] == "A summary line."
    assert (
        client.patch("/notes/n-example-topic", headers=auth, json={"text": "x"}).status_code == 400
    )
    for bad_id in ["i-unknown", "n-missing", "x", "n-../CLAUDE"]:
        assert client.get(f"/notes/{bad_id}", headers=auth).status_code == 404
    assert (
        client.patch(f"/notes/{notes[0]['id']}", headers=auth, json={"text": ""}).status_code == 400
    )


def test_a_task_can_be_retitled_and_redated(client, auth, brain):
    task = client.post("/tasks", headers=auth, json={"title": "Old", "due": "2027-01-20"}).json()
    other = client.post("/tasks", headers=auth, json={"title": "Other"}).json()
    changed = client.patch(
        f"/tasks/{task['id']}", headers=auth, json={"title": "New (now)", "due": "2027-02-01"}
    )
    assert changed.json()["title"] == "New [now]" and changed.json()["due"] == "2027-02-01"
    assert changed.json()["id"] == task["id"] and changed.json()["added"] == task["added"]
    undated = client.patch(f"/tasks/{task['id']}", headers=auth, json={"due": None}).json()
    assert undated["due"] is None
    listed = client.get("/tasks", headers=auth).json()["tasks"]
    assert [t["title"] for t in listed] == ["New [now]", "Other"] and listed[1]["id"] == other["id"]
    assert commits(brain)[0] == "Edit a task"
    for body in [{}, {"colour": 1}, {"due": "soon"}, {"title": ""}]:
        assert client.patch(f"/tasks/{task['id']}", headers=auth, json=body).status_code == 400
    assert (
        client.patch("/tasks/t-20000101-01", headers=auth, json={"title": "x"}).status_code == 404
    )


# ---- phone ids: idempotent create ------------------------------------------------

PHONE_NOTE = "0f6c2a9e-4b1d-4c55-9a3e-7d1b2c3d4e5f"
PHONE_TASK = "a1b2c3d4e5f6a7b8c9d0e1f2"


@pytest.fixture
def no_runs(monkeypatch):
    """Saving notes and tasks must never start a model run."""
    from lifeos_bridge import claude

    async def refuse(*args, **kwargs):
        raise AssertionError("a model run was started")

    monkeypatch.setattr(claude, "_start", refuse)


def test_a_repeated_note_is_saved_once(client, auth, brain, calls, no_runs):
    body = {"text": "offline idea\nmore", "client_id": PHONE_NOTE}
    first = client.post("/notes", headers=auth, json=body)
    assert first.status_code == 201
    saved = first.json()
    assert saved["id"].startswith("i-") and saved["client_id"] == PHONE_NOTE
    assert not saved["repeat"]
    assert client.get(f"/notes/{saved['id']}", headers=auth).json()["text"] == "offline idea\nmore"
    again = client.post("/notes", headers=auth, json=body)
    assert again.status_code == 200
    assert again.json() == {**saved, "repeat": True}
    assert (brain / "inbox.md").read_text().count("offline idea") == 1
    assert commits(brain)[:2] == ["Capture a note from the phone", "init"]
    assert git(brain, "status", "--short") == "" and calls() == []
    ids = (brain / memory.IDS_FILE).read_text()
    assert PHONE_NOTE in ids and "offline idea" not in ids  # ids and a fingerprint only


def test_a_repeated_task_is_saved_once_also_after_completion(client, auth, brain, no_runs):
    body = {"title": "Offline task", "due": "2027-01-20", "client_id": PHONE_TASK}
    first = client.post("/tasks", headers=auth, json=body)
    assert first.status_code == 201
    task = first.json()
    assert task["client_id"] == PHONE_TASK and not task["repeat"] and task["due"] == "2027-01-20"
    again = client.post("/tasks", headers=auth, json=body)
    assert again.status_code == 200 and again.json() == {**task, "repeat": True}
    client.post(f"/tasks/{task['id']}/complete", headers=auth)
    late = client.post("/tasks", headers=auth, json=body).json()
    assert late["id"] == task["id"] and late["done"] and late["repeat"]
    assert client.get("/tasks", headers=auth).json()["tasks"] == []
    assert (brain / "tasks.md").read_text().count("Offline task") == 0
    assert (brain / "tasks-done.md").read_text().count("Offline task") == 1


def test_same_phone_id_with_other_content_is_a_conflict(client, auth, brain, no_runs):
    client.post("/notes", headers=auth, json={"text": "one", "client_id": PHONE_NOTE})
    other = client.post("/notes", headers=auth, json={"text": "two", "client_id": PHONE_NOTE})
    assert other.status_code == 409 and other.json()["error"] == "client_id_conflict"
    as_task = client.post("/tasks", headers=auth, json={"title": "one", "client_id": PHONE_NOTE})
    assert as_task.status_code == 409
    client.post("/tasks", headers=auth, json={"title": "Task", "client_id": PHONE_TASK})
    moved = {"title": "Task", "due": "2027-02-01", "client_id": PHONE_TASK}
    assert client.post("/tasks", headers=auth, json=moved).status_code == 409
    assert "two" not in (brain / "inbox.md").read_text()
    assert len(client.get("/tasks", headers=auth).json()["tasks"]) == 1


def test_phone_ids_survive_a_restart(settings, auth, brain, no_runs):
    first = TestClient(create_app(settings))
    saved = first.post("/notes", headers=auth, json={"text": "kept", "client_id": PHONE_NOTE})
    second = TestClient(create_app(settings))
    again = second.post("/notes", headers=auth, json={"text": "kept", "client_id": PHONE_NOTE})
    assert again.status_code == 200 and again.json()["id"] == saved.json()["id"]


def test_an_edited_note_is_still_found_by_its_phone_id(client, auth, brain):
    saved = client.post("/notes", headers=auth, json={"text": "draft", "client_id": PHONE_NOTE})
    edited = client.patch(f"/notes/{saved.json()['id']}", headers=auth, json={"text": "final"})
    assert edited.json()["id"] != saved.json()["id"]
    # The outbox sends the original create once more: still one note, under its new id.
    again = client.post("/notes", headers=auth, json={"text": "draft", "client_id": PHONE_NOTE})
    assert again.status_code == 200 and again.json()["id"] == edited.json()["id"]
    assert "draft" not in (brain / "inbox.md").read_text()
    assert git(brain, "status", "--short") == ""


BAD_PHONE_IDS = ["short", "x" * 65, "has space in it", "../../etc/passwd-x", 5, "-dash-first-xxxxx"]


def test_old_clients_and_bad_phone_ids(client, auth, brain):
    plain = client.post("/notes", headers=auth, json={"text": "no id"})
    assert plain.status_code == 201 and plain.json()["client_id"] is None
    assert client.post("/notes", headers=auth, json={"text": "no id"}).status_code == 201
    assert (brain / "inbox.md").read_text().count("no id") == 2
    assert client.post("/tasks", headers=auth, json={"title": "no id"}).status_code == 201
    assert not (brain / memory.IDS_FILE).exists()
    for bad_id in BAD_PHONE_IDS:
        note = client.post("/notes", headers=auth, json={"text": "x", "client_id": bad_id})
        task = client.post("/tasks", headers=auth, json={"title": "x", "client_id": bad_id})
        assert (note.status_code, task.status_code) == (400, 400), bad_id
