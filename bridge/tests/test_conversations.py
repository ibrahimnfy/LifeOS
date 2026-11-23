"""Moving between conversations by asking (#164): the index a run reads, and the switch item.

Stand-in claude only; the canned answer plays the run that read the index.
"""

import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from conftest import added_dirs, index_dir, user_line
from lifeos_bridge import conversations, session_links
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
    # #187: the request goes with a new-chat line, and a folder is never called missing
    assert "' | <request>'" in instructions and "never call a folder missing" in instructions
    words = conversations.instructions(world.app.state.settings).split()
    assert len(words) < 160  # paid on every message: kept short


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


# ------------------------------------------------------------- the request carried along (#187)

REQUEST = "Solve the hw2 questions as an HTML file, from the course materials"


def test_a_new_chat_line_carries_the_owners_request(world, auth, known, home, project):
    say(home, f"Starting there.\n[[switch-new: project | {REQUEST}]]")
    reply = ask(world, auth).json()
    assert reply["answer"] == "Starting there."
    switch = reply["switch"]
    assert switch["action"] == "new" and switch["folder"]["path"] == str(project)
    assert switch["forward"] == REQUEST
    # only what follows the first "|": a bar inside the request stays
    say(home, "[[switch-new: project | compare a | b]]")
    assert ask(world, auth).json()["switch"]["forward"] == "compare a | b"
    # without a request, or with an empty one, there is nothing to forward
    for line in ("[[switch-new: project]]", "[[switch-new: project |   ]]"):
        say(home, line)
        switch = ask(world, auth).json()["switch"]
        assert switch["action"] == "new" and "forward" not in switch, line


def test_a_carried_request_is_cleaned_and_bounded(world, auth, known, home):
    # a bell, a right-to-left override, a zero-width space, a delete; a joiner stays
    hidden = {name: chr(code) for name, code in
              (("rlo", 0x202E), ("zwsp", 0x200B), ("zwj", 0x200D))}  # fmt: skip
    say(home, "[[switch-new: project | fix\x07 the{rlo} parser{zwsp}  now\x7f]]".format(**hidden))
    assert ask(world, auth).json()["switch"]["forward"] == "fix the parser now"
    say(home, "[[switch-new: project | a{zwj}b]]".format(**hidden))
    assert ask(world, auth).json()["switch"]["forward"] == "a{zwj}b".format(**hidden)
    exact = "x" * conversations.MAX_FORWARD
    say(home, f"[[switch-new: project | {exact}]]")
    assert ask(world, auth).json()["switch"]["forward"] == exact
    # longer: never cut (a cut request could ask for something else), dropped with a note
    say(home, f"Starting there.\n[[switch-new: project | {exact}y]]")
    reply = ask(world, auth).json()
    assert reply["switch"]["action"] == "new" and "forward" not in reply["switch"]
    assert reply["answer"] == "Starting there.\n\n" + conversations.TOO_LONG


def test_only_the_owners_fresh_answer_carries_a_request(world, known, home):
    settings = world.app.state.settings
    answer = f"Ok.\n[[switch-new: project | {REQUEST}]]"
    text, switch = conversations.from_answer(settings, answer, "s", [], owner=False)
    assert text == "Ok." and switch["action"] == "new" and "forward" not in switch
    _, switch = conversations.from_answer(settings, answer, "s", [], owner=True)
    assert switch["forward"] == REQUEST
    # the history never shows the line, request and all
    assert conversations.strip(answer) == "Ok."


def test_a_request_is_never_sent_into_an_existing_conversation(world, auth, known, home):
    say(home, f"Opening it.\n[[switch: {known['project']} | {REQUEST}]]")
    switch = ask(world, auth).json()["switch"]
    assert switch["action"] == "open" and "forward" not in switch
    assert [s["id"] for s in switch["sessions"]] == [known["project"]]


def test_a_chat_started_by_a_switch_and_its_origin_name_each_other(
    world, auth, known, home, project, ask_dir, write_session
):
    # the old chat: its last answer asked for the switch (as Claude Code stores it)
    earlier = owner(ask_dir, "hello")
    last = owner(ask_dir, "go to the project and fix the parser there")
    old = write_session(ask_dir, [
        earlier, assistant(text("Hi.")),
        last, assistant(text(f"Starting there.\n[[switch-new: project | {REQUEST}]]")),
    ])  # fmt: skip
    new = "11111111-1111-4111-8111-111111111111"
    started = ask(world, auth, REQUEST, folder=str(project), new_session_id=new, continued_from=old)
    assert started.status_code == 200 and started.json()["session_id"] == new
    page = world.get(f"/sessions/{old}/history", headers=auth).json()
    first, latest = page["exchanges"]
    assert first["continued_in"] is None and latest["id"] == last["uuid"]
    assert latest["assistant"] == "Starting there."  # the line is never shown
    moved = latest["continued_in"]
    assert moved["id"] == new and moved["folder_label"] == "project" and moved["kind"] == "project"
    assert page["continued_from"] is None
    page = world.get(f"/sessions/{new}/history", headers=auth).json()
    assert page["continued_from"]["id"] == old
    assert [e["continued_in"] for e in page["exchanges"]] == [None]
    # ids only in the record, never a message or a folder
    stored = (session_links.config_dir() / session_links.FILE_NAME).read_text()
    assert REQUEST not in stored and str(project) not in stored and "hello" not in stored


def test_a_wrong_or_unknown_origin_links_nothing(world, auth, known, home, project):
    bad = ask(world, auth, "x", new_session_id="22222222-2222-4222-8222-222222222222",
              continued_from="../etc")  # fmt: skip
    assert bad.status_code == 400
    unknown = "33333333-3333-4333-8333-333333333333"
    new = "44444444-4444-4444-8444-444444444444"
    assert ask(world, auth, "x", new_session_id=new, continued_from=unknown).status_code == 200
    assert world.get(f"/sessions/{new}/history", headers=auth).json()["continued_from"] is None
    assert session_links.continued_from(new) is None
