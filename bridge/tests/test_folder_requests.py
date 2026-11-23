"""Folders by asking (#179): the name search, the limits, the markers and the pick.

Stand-in claude only; the canned answer plays the run that wrote the marker line.
"""

import dataclasses
import json
import subprocess

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import allowed, conversations, folder_requests, push
from lifeos_bridge.app import create_app


@pytest.fixture
def home(ask_dir):
    return ask_dir.parent


@pytest.fixture
def allowed_file(isolated_config):
    return isolated_config / "allowed_paths"


@pytest.fixture
def world(settings, allowed_file):
    return dataclasses.replace(settings, allowed_paths_file=allowed_file)


@pytest.fixture
def client(world):
    return TestClient(create_app(world))


@pytest.fixture
def pushes(monkeypatch):
    sent = []

    def spy(event, ref="", **kw):
        sent.append((event, {key: value for key, value in kw.items() if value is not None}))

    monkeypatch.setattr(push, "send_hint", spy)
    return sent  # notices only below: a finished answer wakes the phones too ("answer")


def notices(sent):
    return [item for item in sent if item[0] == "notice"]


def say(home, answer):
    (home / ".fake-claude-answer").write_text(answer)


def ask(client, auth, prompt="find my thesis folder", **extra):
    return client.post("/ask", headers=auth, json={"prompt": prompt, **extra})


def audit_records(config):
    files = sorted((config / "audit").glob("*.jsonl"))
    return [json.loads(line) for f in files for line in f.read_text().splitlines()]


def folder(path):
    path.mkdir(parents=True)
    return path


def git_init(path):
    subprocess.run(["git", "init", "-q", str(path)], check=True)  # noqa: S603, S607


# ------------------------------------------------------------------ the search


def test_the_search_reads_folder_names_only_within_its_limits(world, home, isolated_config):
    wanted = folder(home / "Documents" / "Thesis")
    folder(home / ".hidden" / "Thesis")  # hidden: never entered
    folder(home / "snap" / "Thesis")  # a system folder: never entered
    folder(home / "work" / "node_modules" / "Thesis")  # a package folder: never entered
    folder(home / "LifeOS-Chats" / "Thesis")  # the chat root: never
    (home / "Thesis-file").write_text("a file is never a match")
    (home / "Thesis").symlink_to(wanted)  # a link is never a match, nor followed
    deep = folder(home / "a" / "b" / "c" / "d" / "e" / "f" / "Thesis")
    found = folder_requests.search(world, "thesis", home)
    assert found.equal == [wanted] and found.complete
    assert deep not in found.equal  # deeper than the bound
    assert folder_requests.search(world, "THESIS", home).equal == [wanted]  # any case


def test_names_compare_without_case_accents_or_punctuation(world, home):
    turkish = folder(home / "Ödevler_İleri")
    found = folder_requests.search(world, "odevler ileri", home)
    assert found.equal == [turkish]
    partial = folder(home / "Projects" / "thesis 2027 draft")
    found = folder_requests.search(world, "thesis", home)
    assert found.equal == [] and found.partial == [partial]


def test_a_search_that_hit_its_bound_says_so(world, home):
    for number in range(30):
        folder(home / f"f{number}")
    found = folder_requests.search(world, "f29", home, max_entries=5)
    assert not found.complete


# ------------------------------------------------------------------ where a match leads


def test_a_folder_inside_a_repository_stands_for_its_root(world, home):
    repo = folder(home / "code" / "site")
    git_init(repo)
    inner = folder(repo / "docs" / "Thesis")
    target = folder_requests.target_of(world, inner)
    assert target.path == repo and target.reason is None and not target.allowed


def test_the_limits_stay(world, home, isolated_config):
    for path, said in (
        (home, "home folder itself"),
        (folder(home / ".secrets"), "hidden"),
        (folder(home / "LifeOS-Chats"), "chats folder"),
        (isolated_config, "inside the home folder"),  # the settings folder of the tests
    ):
        target = folder_requests.target_of(world, path)
        assert said in str(target.reason), path
    outside = folder_requests.target_of(world, home.parent)
    assert "inside the home folder" in outside.reason
    # a repository whose root may not be allowed: the reason of the root
    git_init(home)
    inner = folder(home / "Thesis")
    target = folder_requests.target_of(world, inner)
    assert "git repository" in target.reason and "home folder itself" in target.reason


# ------------------------------------------------------------------ markers through the bridge


def test_one_clear_match_is_added_and_opened(
    client, auth, home, allowed_file, isolated_config, pushes
):
    wanted = folder(home / "Documents" / "Thesis")
    (wanted / "chapter.md").write_text("never read")
    say(home, "Opening it.\n[[switch-find: Thesis]]")
    reply = ask(client, auth).json()
    assert reply["answer"] == "Opening it.\n\nA new chat opens in “Thesis”."  # named (#187)
    switch = reply["switch"]
    assert switch["action"] == "new" and switch["sessions"] == []
    assert switch["folder"] == {"path": str(wanted), "label": "Thesis", "kind": "project",
                                "colour": switch["folder"]["colour"]}  # fmt: skip
    lines = [line for line in allowed_file.read_text().splitlines() if not line.startswith("#")]
    assert lines == [str(wanted)]
    assert oct(allowed_file.stat().st_mode & 0o777) == "0o600"
    [record] = [r for r in audit_records(isolated_config) if r["tool"] == "AllowedPathAdded"]
    assert record["detail"] == {"path": str(wanted), "by": "chat"}
    # the phones get a sealed notice naming the folder, opening the Chat tab
    assert notices(pushes) == [("notice", {"opens": "session", "t": "Folder added: Thesis"})]
    # what the app then sends: the first message in that folder
    assert ask(client, auth, "hello", folder=switch["folder"]["path"]).status_code == 200


def test_an_allowed_folder_is_just_switched_to(world, auth, home, allowed_file, pushes):
    project = folder(home / "Thesis")
    allowed.add(allowed_file, str(project))
    pushes.clear()
    client = TestClient(create_app(world))
    before = allowed_file.read_text()
    say(home, "[[switch-find: thesis]]")
    switch = ask(client, auth).json()["switch"]
    assert switch["action"] == "new" and switch["folder"]["path"] == str(project)
    assert allowed_file.read_text() == before and notices(pushes) == []
    say(home, "[[switch-find: brain]]")  # the memory folder, by its label
    assert ask(client, auth).json()["switch"]["folder"]["kind"] == "memory"


def test_several_matches_give_a_pick_card(client, auth, home, allowed_file, pushes):
    made = [folder(home / f"place{n}" / "Notes") for n in range(7)]
    say(home, "Which one?\n[[switch-find: notes]]")
    reply = ask(client, auth).json()
    switch = reply["switch"]
    assert (
        switch["action"] == "choose_folder"
        and switch["sessions"] == []
        and switch["folder"] is None
    )
    assert len(switch["folders"]) == folder_requests.MAX_CHOICES
    first = switch["folders"][0]
    assert set(first) == {"path", "label", "kind", "colour", "allowed", "where"}
    assert first["label"] == "Notes" and first["allowed"] is False and first["where"] == "~/place0"
    assert {item["path"] for item in switch["folders"]} <= {str(p) for p in made}
    assert not allowed_file.exists() and notices(pushes) == []  # nothing added before the pick
    # the pick: added by the same rules, then the app opens a new chat there
    picked = client.post("/folders/open", headers=auth, json={"path": first["path"]})
    assert picked.status_code == 200
    colour = picked.json()["folder"]["colour"]
    assert picked.json() == {
        "added": True,
        "folder": {"path": first["path"], "label": "Notes", "kind": "project", "colour": colour},
    }
    assert notices(pushes) == [("notice", {"opens": "session", "t": "Folder added: Notes"})]
    again = client.post("/folders/open", headers=auth, json={"path": first["path"]})
    assert again.json()["added"] is False


def test_the_pick_keeps_every_limit(client, auth, home, allowed_file):
    for path in (str(home), str(folder(home / ".keys")), "relative", "/etc", ""):
        reply = client.post("/folders/open", headers=auth, json={"path": path})
        assert reply.status_code in (400, 403), path
    assert not allowed_file.exists()


def test_nothing_found_or_refused_is_said_in_the_answer(client, auth, home, allowed_file):
    say(home, "Let me look.\n[[switch-find: nowhere-to-be-found]]")
    reply = ask(client, auth).json()
    assert "switch" not in reply
    assert reply["answer"].startswith("Let me look.\n\nNo folder named “nowhere-to-be-found”")
    folder(home / ".private" / "Vault")
    say(home, f"[[switch-find: {home / '.private' / 'Vault'}]]")
    reply = ask(client, auth).json()
    assert "cannot be used" in reply["answer"] and "hidden" in reply["answer"]
    assert "switch" not in reply and not allowed_file.exists()


def test_removing_deletes_only_that_line(world, auth, home, allowed_file, isolated_config, pushes):
    keep, drop = folder(home / "Keep"), folder(home / "Old-site")
    (drop / "index.html").write_text("stays")
    allowed.add(allowed_file, str(keep))
    allowed.add(allowed_file, str(drop))
    pushes.clear()
    client = TestClient(create_app(world))
    say(home, "Done.\n[[switch-remove: old-site]]")
    reply = ask(client, auth).json()
    assert reply["answer"].startswith(
        "Done.\n\nThe folder “Old-site” is no longer an allowed folder"
    )
    assert "switch" not in reply
    assert [line for line in allowed_file.read_text().splitlines() if not line.startswith("#")] == [
        str(keep)
    ]
    assert (drop / "index.html").read_text() == "stays"  # never a file
    [record] = [r for r in audit_records(isolated_config) if r["tool"] == "AllowedPathRemoved"]
    assert record["detail"] == {"path": str(drop), "by": "chat"}
    assert notices(pushes) == [("notice", {"opens": "session", "t": "Folder removed: Old-site"})]
    say(home, "[[switch-remove: brain]]")  # the memory folder is not in the list
    assert "nothing was changed" in ask(client, auth).json()["answer"]


def test_markers_act_only_for_a_message_the_owner_sent(world, home, allowed_file):
    folder(home / "Thesis")
    answer = "Ok.\n[[switch-find: Thesis]]\n[[switch-remove: Thesis]]"
    text, switch = folder_requests.from_answer(world, answer, owner=False)
    assert (text, switch) == ("Ok.", None)
    assert not allowed_file.exists()
    # the history never shows them either
    assert conversations.strip(answer) == "Ok."


@pytest.fixture
def spied(monkeypatch):
    """Whether each answer the bridge read for folder lines was the owner's."""
    seen = []
    plain = folder_requests.from_answer

    def spy(settings, answer, *, owner, home=None):
        seen.append(owner)
        return plain(settings, answer, owner=owner, home=home)

    monkeypatch.setattr(folder_requests, "from_answer", spy)
    return seen


def test_a_message_from_the_phone_is_the_owners(world, auth, home, spied):
    folder(home / "Thesis")
    client = TestClient(create_app(world))
    say(home, "[[switch-find: Thesis]]")
    ask(client, auth)
    assert spied == [True]
    # a client cannot say otherwise about a turn: "_" keys of a request are dropped
    ask(client, auth, _owner=False)
    assert spied == [True, True]
    session = ask(client, auth).json()["session_id"]
    client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "again"})
    assert spied == [True, True, True, True]


def test_the_coach_compaction_never_acts_on_them(world, auth, home, ask_dir, allowed_file, spied):
    """The coach compaction is the bridge's own turn: no ``_owner``, so no folder changes."""
    from test_coach_chat import git

    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    folder(home / "Thesis")
    client = TestClient(create_app(world))
    client.post("/coach/chat/messages", headers=auth, json={"prompt": "first"})
    assert spied == [] and not allowed_file.exists()  # "echo: first": no folder line
    say(home, "[[switch-find: Thesis]]")
    assert client.post("/coach/chat/compact", headers=auth).status_code == 200
    assert spied == [False] and not allowed_file.exists()
    reply = client.post("/coach/chat/messages", headers=auth, json={"prompt": "work in Thesis"})
    assert spied == [False, True] and reply.json()["switch"]["action"] == "new"


def test_a_streamed_answer_carries_the_folder_switch(client, auth, home):
    from test_ask import parse_sse

    folder(home / "Thesis")
    say(home, "[[switch-find: Thesis]]")
    reply = ask(client, auth, stream=True)
    done = next(data for event, data in parse_sse(reply.text) if event == "done")
    assert done["switch"]["action"] == "new" and done["answer"] == "A new chat opens in “Thesis”."


def test_a_find_carries_the_owners_request_also_on_a_card(client, auth, home, allowed_file):
    """#187: the owner asked to go to a folder and work there; the work starts at once."""
    wanted = folder(home / "Desktop" / "Thesis")
    request = "Write the introduction as an HTML file, from the notes in this folder"
    say(home, f"Opening it.\n[[switch-find: Thesis | {request}]]")
    reply = ask(client, auth).json()
    switch = reply["switch"]
    assert switch["action"] == "new" and switch["folder"]["path"] == str(wanted)
    assert switch["forward"] == request
    assert reply["answer"] == "Opening it.\n\nA new chat opens in “Thesis”."
    # several matches: the request stays with the card, so a pick still forwards it
    for n in range(2):
        folder(home / f"place{n}" / "Notes")
    say(home, f"[[switch-find: notes | {request}]]")
    switch = ask(client, auth).json()["switch"]
    assert switch["action"] == "choose_folder" and switch["forward"] == request
    # nothing found: nothing to forward, and the note says so
    say(home, f"[[switch-find: nowhere-to-be-found | {request}]]")
    reply = ask(client, auth).json()
    assert "switch" not in reply and "No folder named “nowhere-to-be-found”" in reply["answer"]
    # too long: the switch stays, the request is not carried, and the answer says so
    say(home, "[[switch-find: Thesis | " + "y" * (conversations.MAX_FORWARD + 1) + "]]")
    reply = ask(client, auth).json()
    assert reply["switch"]["action"] == "new" and "forward" not in reply["switch"]
    assert reply["answer"].endswith(conversations.TOO_LONG)


def test_a_find_for_another_turn_carries_nothing(world, home, allowed_file):
    folder(home / "Thesis")
    answer = "Ok.\n[[switch-find: Thesis | write the introduction]]"
    assert folder_requests.from_answer(world, answer, owner=False) == ("Ok.", None)
    _, switch = folder_requests.from_answer(world, answer, owner=True, home=home)
    assert switch["forward"] == "write the introduction"


def test_a_folder_switch_wins_over_a_conversation_line(world, auth, home, write_session, ask_dir):
    other = write_session(ask_dir, [user_line(ask_dir, "plan the week")])
    folder(home / "Thesis")
    client = TestClient(create_app(world))
    say(home, f"[[switch: {other}]]\n[[switch-find: Thesis]]")
    switch = ask(client, auth).json()["switch"]
    assert switch["action"] == "new"


def test_a_course_folder_of_the_owner_opens_that_course_never_becomes_allowed(
    world, auth, home, allowed_file, isolated_config
):
    own = folder(home / "Desktop" / "EX101 notes")
    (isolated_config / "course_folders").write_text(f"5\t{own}\n")
    say(home, "[[switch-find: EX101 notes]]")
    # without a course folder of the sync for it: refused, with the reason
    reply = ask(TestClient(create_app(world)), auth).json()
    assert "read-only course folders" in reply["answer"] and "switch" not in reply
    # with one: a new chat in that course's folder of the sync, where it is readable
    sync = folder(home / "courses" / "ex-101-5")
    (isolated_config / "env").write_text(f"MOODLE_DOWNLOAD_DIR={sync.parent}\n")
    (isolated_config / "env").chmod(0o600)
    state = {"courses": {"5": {"id": 5, "folder": "ex-101-5", "shortname": "EX 101"}}}
    (isolated_config / "moodle-sync.json").write_text(json.dumps(state))
    client = TestClient(create_app(dataclasses.replace(world, allowed_paths=(sync.parent,))))
    switch = ask(client, auth).json()["switch"]
    assert switch["action"] == "new"
    assert switch["folder"]["path"] == str(sync) and switch["folder"]["kind"] == "course"
    assert switch["folder"]["label"] == "EX 101"
    assert not allowed_file.exists()
    assert ask(client, auth, "hello", folder=str(sync)).status_code == 200
