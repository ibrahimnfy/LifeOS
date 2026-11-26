"""The change permission of chats opened by asking or from the bar, and relayed messages
(#190). Stand-in claude only; synthetic content only."""

import asyncio
import dataclasses
import json
from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import (
    actions,
    capable,
    claude,
    command_bar,
    moodle_sync,
    paths,
    permission,
    updates,
)
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError
from lifeos_bridge.runcontrol import RunControl

NOW = datetime.fromisoformat("2027-01-15T09:00:00+03:00")
COMMIT = "a" * 40
REQUEST = "Rename the parser module and fix its tests"


@pytest.fixture
def settings(settings):
    """Capable runs exist on the SDK runner only (#181)."""
    return dataclasses.replace(settings, runner="sdk")


@pytest.fixture(autouse=True)
def nothing_kept():
    for kept in (command_bar._ANSWERS, command_bar._PENDING, actions._UNDO, permission._relayed):
        kept.clear()


@pytest.fixture
def home(ask_dir):
    return ask_dir.parent


@pytest.fixture
def project(home):
    folder = home / "project"
    folder.mkdir()
    return folder


@pytest.fixture
def repo(home, monkeypatch):
    """The program folder: where an update can be asked for."""
    folder = home / "lifeos"
    (folder / "bridge").mkdir(parents=True)
    git = folder / ".git"
    (git / "refs" / "heads").mkdir(parents=True)
    (git / "HEAD").write_text("ref: refs/heads/main\n")
    (git / "refs" / "heads" / "main").write_text(COMMIT + "\n")
    monkeypatch.setattr(paths, "repo_dir", lambda: folder.resolve())
    return folder.resolve()


def passed(**fields):
    """A marker as a full guardrail check leaves it."""
    data = {"runner": "sdk", "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "all_allowed_folders",
            "suite_version": capable.SUITE_VERSION, "program_folder": True} | fields  # fmt: skip
    capable.marker_path().write_text(json.dumps(capable.sign_marker(data)))


@pytest.fixture
def world(settings, project, repo, isolated_config, monkeypatch):
    passed()
    monkeypatch.setattr(updates, "_notify", lambda title, text: None)
    return dataclasses.replace(settings, allowed_paths=(project, repo))


def say(home, answer):
    (home / ".fake-claude-answer").write_text(answer)


# ------------------------------------------------------------------ the clamp


def test_capable_is_granted_only_where_a_capable_run_would_be_allowed(
    settings, project, isolated_config
):
    assert permission.granted(settings, project, "capable") == "read_only"  # no check passed
    passed()
    assert permission.granted(settings, project, "capable") == "capable"
    assert permission.granted(settings, project, "read_only") == "read_only"
    assert permission.granted(settings, project, None) == "read_only"
    assert permission.granted(settings, project, "anything") == "read_only"
    # the claude -p runner has no capable mode at all
    cli = dataclasses.replace(settings, runner="cli")
    assert permission.granted(cli, project, "capable") == "read_only"


def test_a_blank_chat_gets_it_only_after_a_check_that_covered_chat_folders(
    settings, isolated_config
):
    passed()
    assert permission.granted(settings, None, "capable") == "read_only"
    passed(chat_folders=True)
    assert permission.granted(settings, None, "capable") == "capable"
    assert permission.granted(settings, None, "capable", "chat") == "capable"


def test_course_chats_and_the_coach_stay_read_only(
    settings, project, home, isolated_config, monkeypatch
):
    passed()
    courses = home / "courses"
    (courses / "algebra").mkdir(parents=True)
    monkeypatch.setattr(moodle_sync, "download_dir", lambda: courses)
    assert permission.granted(settings, courses / "algebra", "capable") == "read_only"
    assert permission.granted(settings, courses, "capable") == "read_only"
    assert permission.granted(settings, project, "capable", "course") == "read_only"
    assert permission.granted(settings, project, "capable", "coach") == "read_only"
    assert permission.granted(settings, project, "capable") == "capable"


def test_the_grant_and_a_capable_run_share_one_check(settings, project, monkeypatch):
    seen = []
    monkeypatch.setattr(capable, "require_available", lambda s, folder=None: seen.append(folder))
    assert capable.grant(settings, project, "capable") == "capable"
    asyncio.run(capable.check_available(settings, project))
    assert seen == [project, project]


# ------------------------------------------------------------------ a chat opened by asking


def test_a_new_chat_takes_over_the_mode_of_the_chat_it_came_from(world, auth, home, project):
    say(home, f"Starting there.\n[[switch-new: project | {REQUEST}]]")
    with TestClient(create_app(world)) as client:
        body = {"prompt": "work in the project folder", "folder": str(project)}
        plain = client.post("/ask", headers=auth, json=body).json()
        assert plain["switch"]["forward"] == REQUEST and "mode" not in plain["switch"]["folder"]
        granted = client.post("/ask", headers=auth, json=body | {"mode": "capable"}).json()
        assert granted["switch"]["forward"] == REQUEST
        assert granted["switch"]["folder"]["mode"] == "capable"


def test_the_inherited_mode_is_clamped(world, auth, home, project, monkeypatch):
    with TestClient(create_app(world)) as client:
        body = {"prompt": "x", "folder": str(project), "mode": "capable"}
        # a blank chat: this check did not cover chat folders
        say(home, "Ok.\n[[switch-new: Chats | summarise it]]")
        assert "mode" not in client.post("/ask", headers=auth, json=body).json()["switch"]["folder"]
        # a course's folder stays read-only
        monkeypatch.setattr(permission, "in_course_folder", lambda folder: True)
        say(home, "Ok.\n[[switch-new: project | summarise it]]")
        assert "mode" not in client.post("/ask", headers=auth, json=body).json()["switch"]["folder"]


# ------------------------------------------------------------------ relayed messages (point 4)


class StandInSteps:
    """The steps of an update, done by nobody (conftest forbids the real ones)."""

    def check(self, folder, commit):
        pass

    def tests(self, folder):
        pass

    def lint(self, folder):
        pass

    def build(self):
        return {"registered": False}

    def restart(self):
        return "manual"


def update_card(reply):
    return [(card["state"], card["error_code"]) for card in reply.get("update_requests", [])]


def test_a_request_a_switch_carried_never_applies_an_update(world, auth, home, project, repo):
    """The forwarded first message is written by a model: with the inherited mode it runs
    capable in the program folder, and its answer asks for an update. Nothing is applied,
    whatever the app says about the message."""
    with TestClient(create_app(world)) as client:
        say(home, f"Starting there.\n[[switch-new: lifeos | {REQUEST}]]")
        first = client.post("/ask", headers=auth, json={
            "prompt": "x", "folder": str(project), "mode": "capable"}).json()  # fmt: skip
        assert first["switch"]["folder"]["mode"] == "capable"
        say(home, "Committed.\n[[apply-update]]\n")
        # the app as it is installed today: the same text, no flag, no origin
        body = {"prompt": first["switch"]["forward"], "folder": str(repo), "mode": "capable"}
        reply = client.post("/ask", headers=auth, json=body).json()
        assert reply["answer"] == "Committed."
        assert update_card(reply) == [("refused", "not_owner")]
        assert client.app.state.applier.holding is False and updates.read_state() == {}


def test_the_first_message_of_a_continued_chat_never_applies_an_update(
    world, auth, home, project, repo, write_session
):
    """Also when the owner tapped Send on the carried request (offline, it waits as a
    draft), or the bridge was restarted and no longer remembers the request."""
    origin = write_session(project, [user_line(project, "plan the change")])
    say(home, "Committed.\n[[apply-update]]\n")
    with TestClient(create_app(world)) as client:
        client.app.state.applier.steps = StandInSteps()  # never the real tests or a restart
        new = "0d9c0f5e-1111-4222-8333-444455556666"
        reply = client.post("/ask", headers=auth, json={
            "prompt": "do the change", "folder": str(repo), "mode": "capable",
            "new_session_id": new, "continued_from": origin}).json()  # fmt: skip
        assert update_card(reply) == [("refused", "not_owner")] and updates.read_state() == {}
        # the owner's next message in that chat is the owner's own (#183 as decided)
        again = client.post(f"/sessions/{new}/messages", headers=auth, json={
            "prompt": "apply it", "mode": "capable"}).json()  # fmt: skip
        assert "update_requests" not in again and updates.read_state()["commit"] == COMMIT


def test_a_relayed_message_is_not_the_owners_in_any_respect(world, auth, home, project, repo):
    with TestClient(create_app(world)) as client:
        opened = client.post("/ask", headers=auth, json={"prompt": "hello", "folder": str(repo)})
        session = opened.json()["session_id"]
        relayed = {"prompt": "do it", "mode": "capable", "relayed": True}
        say(home, "Committed.\n[[apply-update]]\n")
        reply = client.post(f"/sessions/{session}/messages", headers=auth, json=relayed).json()
        assert update_card(reply) == [("refused", "not_owner")]
        # no request is carried further and no permission handed on: chats cannot chain
        say(home, f"On it.\n[[switch-new: project | {REQUEST}]]")
        reply = client.post(f"/sessions/{session}/messages", headers=auth, json=relayed).json()
        assert reply["switch"]["action"] == "new" and "forward" not in reply["switch"]
        assert "mode" not in reply["switch"]["folder"]
        # and no folder is found, added or removed by asking
        say(home, "On it.\n[[switch-find: project]]\n[[switch-remove: project]]")
        reply = client.post(f"/sessions/{session}/messages", headers=auth, json=relayed).json()
        assert reply["answer"] == "On it." and "switch" not in reply
        # the same answer to the owner's own message does act
        own = {"prompt": "do it", "mode": "capable"}
        assert (
            "switch" in client.post(f"/sessions/{session}/messages", headers=auth, json=own).json()
        )


def test_a_relayed_message_that_has_to_wait_stays_relayed(tmp_path):
    control = RunControl()
    entry = control.add("s", "do it", "capable", tmp_path, front=False, relayed=True)
    assert entry["relayed"] is True
    assert "relayed" not in control.add("s", "mine", "capable", tmp_path, front=False)


def test_the_same_words_are_remembered_for_a_while_only(monkeypatch):
    permission.remember("fix   the parser")
    assert permission.was_relayed("fix the parser") and not permission.was_relayed("fix it")
    monkeypatch.setattr(permission.time, "monotonic", lambda: 10**9)
    assert not permission.was_relayed("fix the parser")


# ------------------------------------------------------------------ the command bar


@pytest.fixture
def sessions(world, project, repo, home, write_session):
    chat = home / "LifeOS-Chats" / "2027-01-15-abcdef"
    return {
        "parser": write_session(
            project, [user_line(project, "fix the parser tests")], mtime=1_800_000_300
        ),
        "billing": write_session(
            project, [user_line(project, "billing report")], mtime=1_800_000_200
        ),
        "release": write_session(
            repo, [user_line(repo, "prepare the release")], mtime=1_800_000_100
        ),
        "monad": write_session(chat, [user_line(chat, "what is a monad")], mtime=1_800_000_000),
    }  # fmt: skip


def context(client, busy=frozenset()):
    """The bar's context over the sessions the bridge lists, as ``POST /command`` builds it."""
    settings = client.app.state.settings
    listed = client.app.state.known_sessions
    return actions.Context(settings, NOW, "Europe/Istanbul", listed, lambda: busy)


@pytest.fixture
def bar(world, sessions):
    with TestClient(create_app(world)) as client:
        yield client


def test_the_registry_offers_the_two_modes_that_exist():
    needed = {"new_chat": {}, "continue_chat": {"chat": "x"}, "work_in_folder": {"folder": "x"}}
    for name, params in needed.items():
        [param] = [p for p in actions.BY_NAME[name].params if p.name == "changes"]
        assert param.choices == ("read_only", "may_change") and not param.required
        assert actions.check(name, params | {"changes": "may_change"})["changes"] == "may_change"
        with pytest.raises(actions.Refused):
            actions.check(name, params | {"changes": "full_auto"})
    assert set(actions.CHANGES.values()) == set(permission.MODES)
    assert actions.BY_NAME["new_chat"].runs == "bridge"  # so its mode is clamped here


def test_a_computer_session_is_found_by_its_words_and_gets_the_message(bar, sessions):
    done = actions.execute(context(bar), "continue_chat", {
        "chat": "parser tests", "message": "run them again and fix what fails",
        "changes": "may_change"})  # fmt: skip
    assert done.ok and done.title == "fix the parser tests" and done.note is None
    assert done.mode == "capable"
    assert done.perform == {
        "action": "open_chat", "session_id": sessions["parser"], "mode": "capable",
        "text": "run them again and fix what fails", "send": True,
    }  # fmt: skip
    # by its folder's name too, when that names one session
    there = actions.execute(context(bar), "continue_chat", {"chat": "lifeos", "message": "go on"})
    assert there.perform["session_id"] == sessions["release"] and there.perform["send"] is True
    assert there.mode == "read_only"  # nothing asked for changes


def test_unsure_or_unavailable_means_read_only(bar, sessions, isolated_config):
    plain = actions.execute(context(bar), "continue_chat", {"chat": "billing"})
    assert plain.mode == "read_only" and plain.perform["mode"] == "read_only"
    assert "send" not in plain.perform and "text" not in plain.perform
    # a blank chat, with a check that did not cover chat folders
    blank = actions.execute(context(bar), "new_chat", {"text": "write it", "changes": "may_change"})
    assert blank.perform == {"action": "new_chat", "mode": "read_only", "text": "write it"}
    capable.marker_path().unlink()  # capable mode is off on the computer
    off = actions.execute(
        context(bar), "continue_chat", {"chat": "billing", "changes": "may_change"}
    )
    assert off.mode == "read_only"  # fmt: skip


def test_a_busy_chat_is_opened_with_the_message_in_its_field(bar, sessions):
    params = {"chat": "billing", "message": "add the totals", "changes": "may_change"}
    busy = actions.execute(context(bar, {sessions["billing"]}), "continue_chat", params)
    assert busy.ok and busy.note == "chat_busy" and "send" not in busy.perform
    assert busy.perform["session_id"] == sessions["billing"]
    assert busy.perform["text"] == "add the totals"
    unknown = actions.execute(context(bar, None), "continue_chat", params)
    assert unknown.note == "chat_busy" and "send" not in unknown.perform
    # the default context cannot tell either: it never sends
    bare = actions.Context(bar.app.state.settings, NOW, None, bar.app.state.known_sessions)
    assert "send" not in actions.execute(bare, "continue_chat", params).perform


def test_a_chat_that_is_not_found_leads_to_the_sessions(bar, sessions):
    done = actions.execute(context(bar), "continue_chat", {"chat": "zebra", "message": "go on"})
    assert done.ok and done.note == "chat_not_found" and done.mode is None
    assert done.perform == {"action": "open_screen", "screen": "sessions"}


def test_several_matches_are_one_question_and_the_pick_keeps_the_request(bar, sessions):
    params = {"chat": "project", "message": "go on", "changes": "may_change"}
    with pytest.raises(actions.Ask) as asked:
        actions.execute(context(bar), "continue_chat", params)
    options = asked.value.options
    assert asked.value.kind == "chat" and len(options) == 2
    assert {o.label for o in options} == {"fix the parser tests", "billing report"}
    assert all(o.detail.startswith("project · ") for o in options)
    picked = next(o for o in options if o.label == "billing report")
    done = actions.execute(context(bar), picked.action, picked.params)
    assert done.perform["session_id"] == sessions["billing"] and done.perform["send"] is True
    assert done.perform["text"] == "go on" and done.mode == "capable"
    # words that match only partly never act either
    with pytest.raises(actions.Ask):
        actions.execute(context(bar), "continue_chat", {"chat": "parser zebra", "message": "x"})


def test_the_bar_end_to_end_with_a_stand_in_interpreter(bar, sessions, auth, monkeypatch):
    seen = []

    async def interpreter(settings, text, language, now, previous):
        seen.append(text)
        return {"actions": [{"name": "continue_chat", "params": {
            "chat": "project", "message": "add the totals", "changes": "may_change"}}]}  # fmt: skip

    monkeypatch.setattr(command_bar, "interpret", interpreter)
    asked = {"request_id": "r" * 20, "text": "go to the project chat and add the totals"}
    first = bar.post("/command", headers=auth, json=asked).json()
    assert first["results"] == [] and first["question"]["kind"] == "chat"
    choice = next(o for o in first["question"]["options"] if o["label"] == "billing report")
    done = bar.post("/command", headers=auth, json={
        "request_id": "s" * 20, "choice": choice["choice"]}).json()  # fmt: skip
    [result] = done["results"]
    assert result["mode"] == "capable" and result["perform"]["session_id"] == sessions["billing"]
    assert result["perform"]["text"] == "add the totals"
    # the session is free, as the bridge's own look at the computer's processes says
    assert result["perform"]["send"] is True and "note" not in result
    # the interpreter saw the owner's words and nothing of the sessions
    assert seen == ["go to the project chat and add the totals"]


def bar_continues(client, auth, monkeypatch, request_id, message="add the totals"):
    async def interpreter(settings, text, language, now, previous):
        return {"actions": [{"name": "continue_chat", "params": {
            "chat": "billing", "message": message, "changes": "may_change"}}]}  # fmt: skip

    monkeypatch.setattr(command_bar, "interpret", interpreter)
    asked = {"request_id": request_id * 20, "text": "go to the billing chat and add the totals"}
    [result] = client.post("/command", headers=auth, json=asked).json()["results"]
    return result


def test_a_session_open_elsewhere_or_unknown_gets_no_message(bar, sessions, auth, monkeypatch):
    async def held(settings, timeout=20.0, *, ignore_pids=frozenset()):
        return {sessions["billing"]}

    async def unknown(settings, timeout=20.0, *, ignore_pids=frozenset()):
        raise ClaudeError("claude_failed", 502, "no answer")

    monkeypatch.setattr(claude, "sessions_in_use", held)
    result = bar_continues(bar, auth, monkeypatch, "a")
    assert result["note"] == "chat_busy" and "send" not in result["perform"]
    assert result["perform"]["text"] == "add the totals" and result["ok"] is True
    # the look at the computer's processes is reused for ten seconds: a new bridge asks anew
    with TestClient(create_app(bar.app.state.settings)) as other:
        monkeypatch.setattr(claude, "sessions_in_use", unknown)
        result = bar_continues(other, auth, monkeypatch, "b")
        assert result["note"] == "chat_busy" and "send" not in result["perform"]


def test_a_message_of_the_bar_never_applies_an_update(
    world, auth, home, repo, write_session, monkeypatch
):
    """The bar's message is the interpreter's wording: sent into a chat of the program
    folder with changes allowed, its answer asks for an update. Nothing is applied, also
    when the app does not say that it sent the message itself."""
    release = write_session(repo, [user_line(repo, "prepare the release")])
    params = {"chat": "release", "message": "commit and apply it", "changes": "may_change"}

    async def interpreter(settings, text, language, now, previous):
        return {"actions": [{"name": "continue_chat", "params": params}]}

    monkeypatch.setattr(command_bar, "interpret", interpreter)
    with TestClient(create_app(world)) as client:
        client.app.state.applier.steps = StandInSteps()
        asked = {"request_id": "u" * 20, "text": "go to the release chat, commit and apply"}
        [result] = client.post("/command", headers=auth, json=asked).json()["results"]
        perform = result["perform"]
        assert perform["send"] is True and perform["mode"] == "capable"
        say(home, "Committed.\n[[apply-update]]\n")
        reply = client.post(f"/sessions/{release}/messages", headers=auth, json={
            "prompt": perform["text"], "mode": perform["mode"]}).json()  # fmt: skip
        assert update_card(reply) == [("refused", "not_owner")] and updates.read_state() == {}


def test_the_interpreter_is_shown_no_session_and_no_folder(bar, sessions):
    spec = command_bar.instructions()
    for title in ("parser", "billing", "release", "monad", "lifeos"):
        assert title not in spec.replace("LifeOS", "")
    assert '"read_only"' in spec and '"may_change"' in spec and "When unsure" in spec


def test_work_in_a_folder_carries_the_text_and_the_mode(bar, project):
    done = actions.execute(context(bar), "work_in_folder", {
        "folder": "project", "text": "tidy the readme", "changes": "may_change"})  # fmt: skip
    assert done.ok and done.mode == "capable"
    assert done.perform["action"] == "new_chat_in" and done.perform["text"] == "tidy the readme"
    assert done.perform["mode"] == "capable" and done.perform["folder"]["path"] == str(project)
