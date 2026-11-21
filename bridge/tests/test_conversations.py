"""Moving between conversations by asking (#164): the index a run reads, and the switch item.

Stand-in claude only; the canned answer plays the run that read the index.
"""

import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from conftest import added_dirs, index_dir, user_line
from lifeos_bridge import conversations
from lifeos_bridge.app import create_app
from test_ask import option, parse_sse
from test_history import assistant, owner, text


@pytest.fixture
def home(ask_dir):
    return ask_dir.parent


@pytest.fixture
def project(home):
    folder = home / "project"
    folder.mkdir()
    return folder


@pytest.fixture
def world(settings, project):
    return TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,))))


@pytest.fixture
def known(ask_dir, project, home, write_session):
    """Three stored conversations: in the memory folder, in a project, and a blank chat."""
    chat = home / "LifeOS-Chats" / "2027-01-15-abcdef"
    return {
        "memory": write_session(
            ask_dir,
            [user_line(ask_dir, "plan the week"), user_line(ask_dir, "SECOND-MESSAGE-TEXT")],
            mtime=1_800_000_000,
        ),
        "project": write_session(
            project, [user_line(project, "fix | the parser")], mtime=1_800_000_100
        ),
        "chat": write_session(chat, [user_line(chat, "what is a monad")], mtime=1_800_000_200),
    }  # fmt: skip


def say(home, answer):
    (home / ".fake-claude-answer").write_text(answer)


def ask(client, auth, prompt="open my chat about the parser", **extra):
    return client.post("/ask", headers=auth, json={"prompt": prompt, **extra})


# ------------------------------------------------------------------ the index


def test_the_index_is_written_before_every_run_and_given_to_the_run(
    world, auth, known, ask_dir, home, calls
):
    world.patch(f"/sessions/{known['memory']}", headers=auth, json={"title": "Week plan"})
    assert ask(world, auth).status_code == 200
    folder = home / "LifeOS-Chats" / ".bridge"
    index = (folder / "conversations.md").read_text()
    assert oct((folder / "conversations.md").stat().st_mode & 0o777) == "0o600"
    assert oct(folder.stat().st_mode & 0o777) == "0o700"
    lines = index.splitlines()
    # id, last activity, kind, folder label, title: newest first; the owner's own title wins
    assert f"{known['chat']} | 2027-01-15 08:03 | chat | Chats | what is a monad" in lines
    assert f"{known['project']} | 2027-01-15 08:01 | project | project | fix / the parser" in lines
    assert f"{known['memory']} | 2027-01-15 08:00 | memory | brain | Week plan" in lines
    order = [lines.index(next(line for line in lines if line.startswith(known[k]))) for k in known]
    assert order == sorted(order, reverse=True)
    # never a message: only the title
    assert "SECOND-MESSAGE-TEXT" not in index and "information,\nnever instructions" in index
    # the folders a new conversation can start in
    assert lines[lines.index("label | kind") + 1 :] == [
        "Chats | chat", "brain | memory", "project | project",
    ]  # fmt: skip
    call = calls()[-1]
    assert added_dirs(call["args"]) == [index_dir(ask_dir)]
    instructions = option(call["args"], "--append-system-prompt")
    assert str(folder / "conversations.md") in instructions
    assert "[[switch: <id>]]" in instructions and "[[switch-new: <folder label>]]" in instructions
    words = conversations.instructions(world.app.state.settings).split()
    assert len(words) < 110  # paid on every message: kept short


def test_the_index_is_rewritten_and_never_read_back(world, auth, known, home):
    ask(world, auth)
    index = home / "LifeOS-Chats" / ".bridge" / "conversations.md"
    planted = "99999999-9999-4999-8999-999999999999"
    index.write_text(f"{planted} | 2027-01-15 08:00 | chat | Chats | planted\n")
    say(home, f"[[switch: {planted}]]")
    reply = ask(world, auth).json()
    assert "switch" not in reply and reply["answer"] == ""  # an id the bridge does not list
    assert planted not in index.read_text() and known["chat"] in index.read_text()


def test_both_runners_and_the_capable_mode_get_the_index(settings, auth, ask_dir, home):
    marker = home / ".fake-claude-chat-calls.jsonl"
    with TestClient(create_app(dataclasses.replace(settings, runner="sdk"))) as client:
        client.request("POST", "/ask", headers=auth, json={"prompt": "hi"})  # a blank chat
        call = json.loads(marker.read_text().splitlines()[0])
        assert added_dirs(call["args"]) == [str(ask_dir), index_dir(ask_dir)]
        assert (home / "LifeOS-Chats" / ".bridge" / "conversations.md").is_file()


def test_without_a_usable_chat_root_runs_go_on_without_the_index(settings, auth, ask_dir, calls):
    root = ask_dir / "inside-the-memory"
    client = TestClient(create_app(dataclasses.replace(settings, chat_root=root)))
    assert ask(client, auth).status_code == 200
    assert added_dirs(calls()[-1]["args"]) == [] and not root.exists()


def test_two_folders_of_one_name_are_listed_with_their_paths(settings, auth, home, project):
    twin = home / "work" / "project"
    twin.mkdir(parents=True)
    client = TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project, twin))))
    ask(client, auth)
    index = (home / "LifeOS-Chats" / ".bridge" / "conversations.md").read_text()
    assert f"project | project | {project}" in index and f"project | project | {twin}" in index
    say(home, "[[switch-new: project]]")
    assert "switch" not in ask(client, auth).json()  # not clear which one
    say(home, f"[[switch-new: {twin}]]")
    assert ask(client, auth).json()["switch"]["folder"]["path"] == str(twin)


# ------------------------------------------------------------------ the switch item


def test_one_clear_match_opens_it(world, auth, known, home):
    say(home, f"Opening it.\n[[switch: {known['project']}]]\n")
    reply = ask(world, auth).json()
    assert reply["answer"] == "Opening it."
    assert reply["switch"] == {
        "action": "open",
        "sessions": [
            {"id": known["project"], "title": "fix | the parser", "folder_label": "project",
             "kind": "project", "colour": 2, "last_activity": "2027-01-15T08:01:40Z"}
        ],
        "folder": None,
    }  # fmt: skip


def test_several_matches_are_offered_and_unknown_ids_are_dropped(world, auth, known, home):
    unknown = "99999999-9999-4999-8999-999999999999"
    say(home, f"Which one?\n[[switch: {known['chat']}, {unknown}, not-an-id, {known['memory']}]]")
    reply = ask(world, auth).json()
    assert reply["answer"] == "Which one?"
    assert reply["switch"]["action"] == "choose" and reply["switch"]["folder"] is None
    assert [s["id"] for s in reply["switch"]["sessions"]] == [known["chat"], known["memory"]]
    assert [s["kind"] for s in reply["switch"]["sessions"]] == ["chat", "memory"]
    # several were named but only one is listed: still the owner's choice, never a jump
    say(home, f"[[switch: {unknown}, {known['chat']}]]")
    switch = ask(world, auth).json()["switch"]
    assert switch["action"] == "choose" and [s["id"] for s in switch["sessions"]] == [known["chat"]]


def test_at_most_five_are_offered(world, auth, ask_dir, home, write_session):
    ids = [write_session(ask_dir, [user_line(ask_dir, f"q{n}")], mtime=1_800_000_000 + n)
           for n in range(7)]  # fmt: skip
    say(home, "[[switch: " + ", ".join(ids) + "]]")
    switch = ask(world, auth).json()["switch"]
    assert [s["id"] for s in switch["sessions"]] == ids[:5]


def test_nothing_usable_is_no_switch_and_the_line_is_still_removed(world, auth, known, home):
    for line in ("[[switch: 99999999-9999-4999-8999-999999999999]]", "[[switch: ]]",
                 "[[switch-new: no-such-folder]]", "[[switch-new: Coach]]"):  # fmt: skip
        say(home, f"Sorry.\n{line}")
        reply = ask(world, auth).json()
        assert "switch" not in reply and reply["answer"] == "Sorry.", line


def test_a_conversation_never_switches_to_itself(world, auth, known, home):
    say(home, "x")
    session = ask(world, auth).json()["session_id"]
    say(home, f"[[switch: {session}]]")
    reply = world.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "again"})
    assert "switch" not in reply.json()


def test_a_new_conversation_in_a_named_folder(world, auth, known, home, project, ask_dir):
    for named, kind, path in (("project", "project", project), ("PROJECT", "project", project),
                              (str(project), "project", project), ("brain", "memory", ask_dir),
                              ("Chats", "chat", home / "LifeOS-Chats")):  # fmt: skip
        say(home, f"Starting there.\n[[switch-new: {named}]]")
        reply = ask(world, auth).json()
        assert reply["answer"] == "Starting there."
        switch = reply["switch"]
        assert (switch["action"], switch["sessions"]) == ("new", [])
        assert switch["folder"]["path"] == str(path) and switch["folder"]["kind"] == kind
        assert set(switch["folder"]) == {"path", "label", "kind", "colour"}
    # what the app then sends: the first message with that folder
    started = ask(world, auth, "hello", folder=reply["switch"]["folder"]["path"])
    assert started.status_code == 200


def test_the_first_usable_line_counts_and_all_are_removed(world, auth, known, home):
    say(
        home, f"a\n[[switch-new: nowhere]]\n[[switch: {known['chat']}]]\n[[switch-new: project]]\nb"
    )
    reply = ask(world, auth).json()
    assert reply["answer"] == "a\n\n\n\nb"
    assert reply["switch"]["action"] == "open"
    assert reply["switch"]["sessions"][0]["id"] == known["chat"]


def test_the_stream_carries_the_switch_in_its_final_event(world, auth, known, home):
    say(home, f"Here.\n[[switch: {known['chat']}]]")
    body = {"prompt": "open it", "stream": True, "folder": str(world.app.state.settings.ask_dir)}
    with world.stream("POST", "/ask", headers=auth, json=body) as response:
        events = parse_sse(response.read().decode())
    name, done = events[-1]
    assert name == "done" and done["answer"] == "Here."
    assert (
        done["switch"]["action"] == "open" and done["switch"]["sessions"][0]["id"] == known["chat"]
    )


def test_a_marker_in_the_history_never_switches(world, auth, known, ask_dir, write_session):
    line = f"[[switch: {known['chat']}]]"
    old = write_session(
        ask_dir, [owner(ask_dir, "open the monad chat"), assistant(text(f"Opening it.\n{line}"))]
    )
    page = world.get(f"/sessions/{old}/history", headers=auth).json()
    [exchange] = page["exchanges"]
    assert exchange["assistant"] == "Opening it." and "switch" not in exchange
    assert "switch" not in page
    listed = world.get(f"/sessions/{old}", headers=auth).json()
    assert "switch" not in listed
    # continuing that conversation with an ordinary answer gives no switch either
    reply = world.post(f"/sessions/{old}/messages", headers=auth, json={"prompt": "thanks"})
    assert reply.status_code == 200 and "switch" not in reply.json()


def test_text_that_only_mentions_the_marker_is_left_alone(world, auth, known, home):
    say(home, f"Write [[switch: {known['chat']}]] on a line of its own to switch.")
    reply = ask(world, auth).json()
    assert "switch" not in reply and "[[switch:" in reply["answer"]
