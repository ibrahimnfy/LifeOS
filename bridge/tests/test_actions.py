"""The action registry and the command bar (#185). Synthetic content only.

The first tests are the rule of CLAUDE.md section 7: every route of the bridge and every
navigation route of the app is reached by an action, or is exempt with a reason.
"""

import json
import re
import subprocess
from datetime import datetime
from pathlib import Path

import pytest
from fastapi.routing import APIRoute

from lifeos_bridge import actions, command_bar, memory, reminders, routing
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

REPO = Path(__file__).resolve().parents[2]
APP_SOURCE = REPO / "app/app/src/main/kotlin/dev/lifeos/app"
DESTINATIONS = APP_SOURCE / "ui/nav/Destinations.kt"
COMMAND_ROUTES = APP_SOURCE / "ui/nav/CommandRoutes.kt"
NOW = datetime.fromisoformat("2027-01-15T09:00:00+03:00")


def bridge_routes(settings) -> set[str]:
    """Every method and path of the app, those of included routers too."""
    found = set()
    for route in create_app(settings).routes:
        # An included router (the Moodle and course routes) lists its routes on request.
        contexts = getattr(route, "effective_route_contexts", None)
        for entry in contexts() if contexts else [route] if isinstance(route, APIRoute) else []:
            found |= {
                f"{method} {entry.path}" for method in set(entry.methods) - {"HEAD", "OPTIONS"}
            }
    assert "GET /courses" in found and "GET /moodle/status" in found  # the routers were seen
    return found


@pytest.fixture(autouse=True)
def fresh_bar():
    """The bar's answers, picks and undos live in the process: none leaks between tests."""
    for kept in (command_bar._ANSWERS, command_bar._PENDING, actions._UNDO):
        kept.clear()


def test_every_bridge_route_has_an_action_or_a_recorded_exemption(settings):
    routes = bridge_routes(settings)
    covered = {route for action in actions.ACTIONS for route in action.routes}
    missing = sorted(routes - covered - set(actions.EXEMPT_ROUTES))
    assert missing == [], f"routes without an action or exemption: {missing}"
    # No stale entries: everything named exists, and nothing is both covered and exempt.
    assert sorted((covered | set(actions.EXEMPT_ROUTES)) - routes) == []
    assert covered & set(actions.EXEMPT_ROUTES) == set()
    assert all(len(reason) > 10 for reason in actions.EXEMPT_ROUTES.values())


def app_routes() -> set[str]:
    text = DESTINATIONS.read_text()  # fails (never skips) when the app's file is gone
    body = text[text.index("object Routes") : text.index("\nenum class Tab")]
    names = set(re.findall(r"const val (\w+) =", body))
    return {name for name in names if not name.endswith("_ARG")}


def test_every_app_screen_has_an_action_or_a_recorded_exemption():
    screens = app_routes()
    assert {"TODAY", "NOTE", "COURSE", "PAIR"} <= screens  # the parse found the routes
    covered = {screen for action in actions.ACTIONS for screen in action.screens}
    missing = sorted(screens - covered - set(actions.EXEMPT_SCREENS))
    assert missing == [], f"app screens without an action or exemption: {missing}"
    assert sorted((covered | set(actions.EXEMPT_SCREENS)) - screens) == []
    assert set(actions.SCREENS.values()) <= screens


def test_the_app_performs_everything_the_bridge_hands_it():
    text = COMMAND_ROUTES.read_text()
    screens = dict(re.findall(r'"(\w+)" to Routes\.(\w+)', text))
    assert screens == actions.SCREENS
    performed_block = text[text.index("val PERFORMED") :]
    performed_block = performed_block[: performed_block.index(")")]
    performed = set(re.findall(r'"(\w+)"', performed_block))
    wanted = {a.name for a in actions.ACTIONS if a.runs == "app"} | set(actions.PERFORMS)
    assert performed == wanted


def test_the_registry_is_well_formed():
    names = [a.name for a in actions.ACTIONS]
    assert len(names) == len(set(names))
    for action in actions.ACTIONS:
        assert action.runs in ("bridge", "app")
        assert (
            action.runs == "app"
            or action.name in actions.EXECUTORS
            or (action.name in actions.STARTED_BY_ENDPOINT)
        )
        for param in action.params:
            assert param.kind in ("text", "date", "time", "datetime", "choice")
    assert set(actions.EXECUTORS) <= set(names)
    spec = actions.prompt_spec()
    assert all(f"- {name}(" in spec for name in names)


def test_the_interpreter_job_is_cheap_and_not_a_background_job(settings):
    route = routing.route(settings, "command")
    assert (route.model, route.effort, route.advisor) == ("haiku", "low", None)
    assert routing.JOBS["command"].background is False


# ---------------------------------------------------------------- checking what the model said


def test_unknown_actions_and_bad_parameters_are_refused():
    for name, params in [
        ("format_disk", {}),
        ("add_task", {}),
        ("add_task", {"title": "x", "colour": "red"}),
        ("add_task", {"title": "x", "due": "next week"}),
        ("add_event", {"title": "x", "date": "2027-01-15", "start": "9am"}),
        ("open_screen", {"screen": "kitchen"}),
        ("create_note", {"text": 5}),
        ("set_reminder", {"note": "x", "at": "2027-01-15 10:00"}),
    ]:
        with pytest.raises(actions.Refused):
            actions.check(name, params)
    assert actions.check("add_task", {"title": " Buy milk ", "due": None}) == {"title": "Buy milk"}


def test_a_plan_is_actions_or_one_question():
    plan, question = command_bar.read_plan(
        {"actions": [{"name": "open_screen", "params": {"screen": "notes"}}], "question": None}
    )
    assert plan == [("open_screen", {"screen": "notes"})] and question is None
    plan, question = command_bar.read_plan(
        {"actions": [], "question": {"text": "Note or task?", "options": ["Note", "Task", 3]}}
    )
    assert plan == [] and question == {"text": "Note or task?", "options": ["Note", "Task"]}
    for bad in [{}, {"actions": "x"}, {"actions": [1]}, {"actions": [{"name": "x"}] * 5}]:
        with pytest.raises(actions.Refused):
            command_bar.read_plan(bad)


# ---------------------------------------------------------------- the bridge's actions


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


@pytest.fixture
def ctx(settings, brain):
    return actions.Context(settings, NOW, "Europe/Istanbul")


def clean(folder) -> bool:
    return git(folder, "status", "--short") == ""


def test_a_note_with_a_reminder_and_its_undo(ctx, brain):
    done = actions.execute(
        ctx, "create_note", {"text": "call the office", "remind_at": "2027-01-16T10:00"}
    )
    assert (
        done.ok and done.when == "2027-01-16T07:00:00Z" and done.perform["action"] == "note_saved"
    )
    note_id = done.perform["note_id"]
    assert reminders.load()[0]["note_id"] == note_id
    assert reminders.load()[0]["timezone"] == "Europe/Istanbul"
    actions.undo(done.undo)
    assert (
        memory.list_notes(brain) == []
        and "call the office" in (brain / memory.DELETED_FILE).read_text()
    )
    assert reminders.load()[0]["state"] == "deleted" and clean(brain)
    with pytest.raises(ClaudeError):  # once only
        actions.undo(done.undo)


def test_tasks_are_found_by_words_and_taken_back(ctx, brain):
    added = actions.execute(ctx, "add_task", {"title": "Call the dentist", "due": "2027-01-20"})
    actions.execute(ctx, "add_task", {"title": "Buy milk"})
    actions.execute(ctx, "add_task", {"title": "Buy bread"})
    assert added.ok and added.when == "2027-01-20"

    ticked = actions.execute(ctx, "complete_task", {"task": "DENTİST"})
    assert ticked.ok and ticked.title == "Call the dentist"
    assert [t.title for t in memory.list_tasks(brain)] == ["Buy milk", "Buy bread"]
    actions.undo(ticked.undo)
    assert [t.title for t in memory.list_tasks(brain)][-1] == "Call the dentist"
    assert "done" not in memory.list_tasks(brain)[-1].fields and clean(brain)

    # Two match: one question, each option standing for the resolved action.
    with pytest.raises(actions.Ask) as asked:
        actions.execute(ctx, "complete_task", {"task": "buy"})
    assert asked.value.kind == "task"
    assert [o.label for o in asked.value.options] == ["Buy milk", "Buy bread"]
    assert asked.value.options[1].params == {"task_id": memory.list_tasks(brain)[1].id}
    nothing = actions.execute(ctx, "complete_task", {"task": "laundry"})
    assert not nothing.ok and nothing.error == "task_not_found"

    edited = actions.execute(ctx, "edit_task", {"task": "milk", "due": "2027-02-01", "note": "oat"})
    assert edited.ok and memory.list_tasks(brain)[0].fields["due"] == "2027-02-01"
    actions.undo(edited.undo)
    assert "due" not in memory.list_tasks(brain)[0].fields
    assert "note" not in memory.list_tasks(brain)[0].fields

    actions.undo(added.undo)  # the added task goes to the trash
    assert "Call the dentist" not in (brain / "tasks.md").read_text()
    assert "Call the dentist" in (brain / memory.TRASHED_LINES).read_text() and clean(brain)


def test_notes_are_edited_deleted_and_restored_by_words(ctx, brain):
    memory.add_note(brain, "shopping list: apples", NOW)
    memory.add_note(brain, "idea for the example project", NOW)
    edited = actions.execute(ctx, "edit_note", {"note": "apples", "text": "shopping list: pears"})
    assert edited.ok and "pears" in (brain / "inbox.md").read_text()
    actions.undo(edited.undo)
    assert "apples" in (brain / "inbox.md").read_text()
    gone = actions.execute(ctx, "delete_note", {"note": "example project"})
    assert gone.ok and gone.perform["action"] == "note_deleted"
    assert "example project" not in (brain / "inbox.md").read_text()
    actions.undo(gone.undo)
    assert "example project" in (brain / "inbox.md").read_text() and clean(brain)
    opened = actions.execute(ctx, "open_note", {"note": "example"})
    assert opened.perform == {"action": "open_note", "note_id": memory.list_notes(brain)[0]["id"]}


def test_events_are_added_changed_and_taken_back(ctx, brain):
    added = actions.execute(
        ctx, "add_event", {"title": "Seminar", "date": "2027-01-18", "start": "14:00"}
    )
    assert added.ok and added.when == "2027-01-18 14:00"
    changed = actions.execute(ctx, "change_event", {"event": "seminar", "start": "15:00"})
    assert changed.ok and memory.all_events(brain)[0].start == "15:00"
    actions.undo(changed.undo)
    assert memory.all_events(brain)[0].start == "14:00"
    actions.undo(added.undo)
    assert memory.all_events(brain) == [] and clean(brain)
    assert "Seminar" in (brain / memory.TRASHED_LINES).read_text()


def test_reminders_on_notes_are_set_and_cancelled(ctx, brain):
    memory.add_note(brain, "renew the example card", NOW)
    made = actions.execute(ctx, "set_reminder", {"note": "card", "at": "2027-01-20T09:30"})
    assert made.ok and made.when == "2027-01-20T06:30:00Z"
    gone = actions.execute(ctx, "cancel_reminder", {"note": "card"})
    assert gone.ok and reminders.load() == []
    actions.undo(gone.undo)
    assert len(reminders.load()) == 1
    missing = actions.execute(ctx, "set_reminder", {"at": "2027-01-20T09:30"})
    assert not missing.ok


def test_app_actions_are_handed_over():
    done = actions.execute(None, "open_screen", {"screen": "calendar"})
    assert done.perform == {"action": "open_screen", "screen": "calendar"} and done.undo is None


# ---------------------------------------------------------------- the endpoint


@pytest.fixture
def interpreted(monkeypatch):
    """What the fake interpreter answers, and what it was asked."""
    state = {"answer": {"actions": [], "question": None}, "asked": []}

    async def fake(settings, text, language, now, previous):
        state["asked"].append(
            {"text": text, "language": language, "now": now, "previous": previous}
        )
        return state["answer"]

    monkeypatch.setattr(command_bar, "interpret", fake)
    return state


def body(request_id="r" * 16, **fields):
    return {"request_id": request_id, "language": "en", "now": NOW.isoformat(), **fields}


def test_a_request_is_done_once_with_its_undo(client, auth, brain, interpreted):
    interpreted["answer"] = {"actions": [
        {"name": "add_task", "params": {"title": "Water the plants", "due": "2027-01-16"}},
        {"name": "open_screen", "params": {"screen": "tasks"}},
    ]}  # fmt: skip
    first = client.post("/command", headers=auth, json=body(text="water the plants tomorrow"))
    assert first.status_code == 200
    results = first.json()["results"]
    assert [r["action"] for r in results] == ["add_task", "open_screen"]
    assert results[0]["ok"] and results[0]["undo"] and results[1]["perform"]["screen"] == "tasks"
    assert first.json()["question"] is None
    # The same request again (its answer got lost): the stored answer, nothing done twice.
    again = client.post("/command", headers=auth, json=body(text="water the plants tomorrow"))
    assert again.json()["repeat"] is True and len(interpreted["asked"]) == 1
    assert (brain / "tasks.md").read_text().count("Water the plants") == 1

    undone = client.post("/command/undo", headers=auth, json={"undo": results[0]["undo"]})
    assert undone.json() == {"undone": True, "action": "add_task"}
    assert "Water the plants" not in (brain / "tasks.md").read_text()
    assert client.post("/command/undo", headers=auth, json={"undo": "nope"}).status_code == 404


def test_an_ambiguous_request_gets_one_question_and_the_answer_goes_back(
    client, auth, brain, interpreted
):
    interpreted["answer"] = {
        "actions": [],
        "question": {"text": "A note or a task?", "options": ["Note", "Task"]},
    }
    asked = client.post("/command", headers=auth, json=body(text="milk")).json()
    assert (
        asked["question"]["kind"] == "clarify" and asked["question"]["text"] == "A note or a task?"
    )
    assert [o["answer"] for o in asked["question"]["options"]] == ["Note", "Task"]
    assert asked["previous"] == {"request": "milk", "question": "A note or a task?"}
    interpreted["answer"] = {"actions": [{"name": "add_task", "params": {"title": "milk"}}]}
    answered = client.post(
        "/command", headers=auth, json=body("s" * 16, text="Task", previous=asked["previous"])
    )
    assert answered.json()["results"][0]["title"] == "milk"
    assert interpreted["asked"][-1]["previous"] == asked["previous"]


def test_several_matches_become_a_pick_and_the_rest_follows(client, auth, brain, interpreted):
    for title in ("Read chapter one", "Read chapter two"):
        memory.add_task(brain, {"title": title}, NOW)
    interpreted["answer"] = {"actions": [
        {"name": "complete_task", "params": {"task": "read chapter"}},
        {"name": "open_screen", "params": {"screen": "tasks"}},
    ]}  # fmt: skip
    asked = client.post("/command", headers=auth, json=body(text="done reading")).json()
    question = asked["question"]
    assert question["kind"] == "task" and question["action"] == "complete_task"
    assert [o["label"] for o in question["options"]] == ["Read chapter one", "Read chapter two"]
    picked = client.post(
        "/command", headers=auth, json=body("p" * 16, choice=question["options"][1]["choice"])
    ).json()
    assert [r["action"] for r in picked["results"]] == ["complete_task", "open_screen"]
    assert [t.title for t in memory.list_tasks(brain)] == ["Read chapter one"]
    assert len(interpreted["asked"]) == 1  # a pick runs no model
    stale = client.post(
        "/command", headers=auth, json=body("q" * 16, choice=question["options"][0]["choice"])
    )
    assert stale.status_code == 404


def test_a_request_the_registry_does_not_know_is_refused_whole(client, auth, brain, interpreted):
    interpreted["answer"] = {"actions": [
        {"name": "add_task", "params": {"title": "x"}},
        {"name": "send_email", "params": {}},
    ]}  # fmt: skip
    refused = client.post("/command", headers=auth, json=body(text="mail the office"))
    assert refused.status_code == 422 and refused.json()["error"] == "not_understood"
    assert "x" not in (brain / "tasks.md").read_text().split("## Open")[1]
    for bad in [
        {"text": "x"},
        body(request_id="short", text="x"),
        body(text=""),
        body(text="x", now="noon"),
    ]:
        assert client.post("/command", headers=auth, json=bad).status_code == 400
    assert client.post("/command", json=body(text="x")).status_code == 401


def test_the_interpreter_runs_without_tools_on_the_cheap_model(
    settings, calls, monkeypatch, tmp_path
):
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))
    answer = {"actions": [{"name": "open_screen", "params": {"screen": "today"}}], "question": None}
    (Path.home() / ".fake-claude-answer").write_text(json.dumps(answer))
    import asyncio

    found = asyncio.run(command_bar.interpret(settings, "show today", "tr", NOW, None))
    assert found == answer
    log = command_bar.run_dir() / ".fake-claude-calls.jsonl"
    call = json.loads(log.read_text().splitlines()[-1])
    args = call["args"]
    assert args[args.index("--tools") + 1] == ""
    assert args[args.index("--model") + 1] == "haiku" and args[args.index("--effort") + 1] == "low"
    assert "--no-session-persistence" in args and "--add-dir" not in args
    assert "command interpreter" in args[args.index("--append-system-prompt") + 1]
    assert call["env"].get("CLAUDE_CODE_DISABLE_CLAUDE_MDS") == "1"
    prompt = json.loads(call["prompt"])
    assert prompt["request"] == "show today" and prompt["language"] == "tr"
    assert prompt["now"].startswith("2027-01-15 09:00")
    assert command_bar.run_dir().is_relative_to(tmp_path / "cache")
