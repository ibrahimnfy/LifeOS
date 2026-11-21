"""Blank chats (#163): an own folder per chat, no personal context, memory on request.

Stand-in claude only. What the real Claude Code does with these flags is
recorded in docs/bridge.md ("Blank chats").
"""

import dataclasses
import json
import os
from datetime import date
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import added_dirs, index_dir, user_line
from lifeos_bridge import approvals, capable, chats, claude, folders, memory, moves
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError
from lifeos_bridge.config import ConfigError, load_settings
from test_ask import option, parse_sse

pytestmark = pytest.mark.blank_chats


@pytest.fixture
def home(ask_dir):
    return ask_dir.parent


@pytest.fixture
def root(home):
    return home / "LifeOS-Chats"


def chat_folders(root):
    """The chats' folders under the root (the bridge's own index folder is not one)."""
    return sorted(p for p in root.iterdir() if p.name != ".bridge")


@pytest.fixture
def chat_calls(home):
    """The stand-in's calls in blank chats' folders (it leaves nothing in such a folder)."""

    def read():
        log = home / ".fake-claude-chat-calls.jsonl"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    return read


@pytest.fixture
def project(home):
    folder = home / "project"
    folder.mkdir()
    return folder


@pytest.fixture
def world(settings, project):
    return TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,))))


def ask(client, auth, prompt="hello", **extra):
    return client.post("/ask", headers=auth, json={"prompt": prompt, **extra})


def context_of(call):
    return option(call["args"], "--append-system-prompt")


def write_memory(brain):
    (brain / "CLAUDE.md").write_text("# How to use this memory\nMEMORY-RULES-TEXT\n")
    (brain / "INDEX.md").write_text("INDEX-TEXT\n")
    (brain / "profile.md").write_text("## Facts\n- [owner 2027-01-01] PROFILE-TEXT\n")
    (brain / "tasks.md").write_text("- [ ] TASK-TEXT\n")
    (brain / "daily").mkdir()
    (brain / "daily" / f"{memory.now_local().date().isoformat()}.md").write_text("DAILY-TEXT\n")


PERSONAL = ("MEMORY-RULES-TEXT", "INDEX-TEXT", "PROFILE-TEXT", "TASK-TEXT", "DAILY-TEXT", "=====")


# ------------------------------------------------------------------ an own, empty folder


def test_a_new_conversation_without_a_folder_runs_in_its_own_new_folder(
    client, auth, ask_dir, root, chat_calls
):
    first, second = ask(client, auth), ask(client, auth, "another")
    assert first.status_code == second.status_code == 200
    one, two = chat_calls()
    for call in (one, two):
        folder = Path(call["cwd"])
        assert folder.parent == root and chats.NAME.fullmatch(folder.name)
        assert folder.name.startswith(memory.now_local().date().isoformat() + "-")
    assert one["cwd"] != two["cwd"]  # every chat has a folder of its own
    assert not (ask_dir / ".fake-claude-calls.jsonl").exists()
    assert oct(root.stat().st_mode & 0o777) == "0o700"


def test_the_chat_root_itself_names_a_blank_chat_too(client, auth, root, chat_calls):
    assert ask(client, auth, folder=str(root)).status_code == 200
    assert Path(chat_calls()[0]["cwd"]).parent == root


def test_nothing_personal_is_given_to_a_blank_chat(client, auth, ask_dir, chat_calls):
    write_memory(ask_dir)
    assert ask(client, auth).status_code == 200
    [call] = chat_calls()
    text = context_of(call)
    assert not any(word in text + call["prompt"] for word in PERSONAL)
    # one short fixed instruction: where the memory is, and that it is used only when asked
    assert str(ask_dir) in text and "only when the owner asks" in text
    assert "[[move-files: " in text
    assert len(chats.context(client.app.state.settings)) < 700
    # the memory folder is in reach of the file tools; besides it only the conversation index
    assert added_dirs(call["args"]) == [str(ask_dir), index_dir(ask_dir)]
    assert option(call["args"], "--tools") == "Read,Glob,Grep" and "--restricted" in call["args"]
    # Claude Code is told to load no memory file of any level, and no auto-memory
    assert call["env"]["CLAUDE_CODE_DISABLE_CLAUDE_MDS"] == "1"
    assert call["env"]["CLAUDE_CODE_DISABLE_AUTO_MEMORY"] == "1"


def test_the_coach_keeps_its_context_and_other_folders_get_none(
    world, auth, ask_dir, project, calls
):
    write_memory(ask_dir)
    assert (
        world.post("/coach/chat/messages", headers=auth, json={"prompt": "hi"}).status_code == 200
    )
    coach = calls()[-1]
    assert all(word in context_of(coach) for word in PERSONAL)
    assert "CLAUDE_CODE_DISABLE_CLAUDE_MDS" not in coach["env"]
    for folder in (project, ask_dir):  # a project conversation, and one named into the memory
        assert ask(world, auth, folder=str(folder)).status_code == 200
        call = json.loads((folder / ".fake-claude-calls.jsonl").read_text().splitlines()[-1])
        assert not any(word in context_of(call) for word in PERSONAL)
        assert added_dirs(call["args"]) == [index_dir(ask_dir)]  # not the memory folder
        assert str(ask_dir) not in context_of(call)


def test_an_old_conversation_in_the_memory_folder_keeps_working(
    client, auth, ask_dir, calls, write_session
):
    old = write_session(ask_dir, [user_line(ask_dir, "from before the change")])
    for path in ("/ask", f"/sessions/{old}/messages"):
        body = {"prompt": "go on"} | ({"session_id": old} if path == "/ask" else {})
        assert client.post(path, headers=auth, json=body).status_code == 200
        call = calls()[-1]
        assert call["cwd"] == str(ask_dir) and option(call["args"], "--resume") == old


# ------------------------------------------------------------------ the chat root


def refused(client, auth):
    response = ask(client, auth)
    assert response.status_code == 503 and response.json()["error"] == "chat_root_unusable"
    return response.json()["message"]


def test_a_root_with_an_instruction_file_is_refused(client, auth, root, chat_calls):
    root.mkdir()
    for name in chats.INSTRUCTION_FILES:
        (root / name).write_text("x")
        assert name in refused(client, auth)
        (root / name).unlink()
    assert chat_calls() == [] and chat_folders(root) == []
    assert ask(client, auth).status_code == 200


def test_a_root_that_is_or_is_inside_a_repository_is_refused(settings, auth, home, chat_calls):
    outer = home / "work"
    (outer / ".git").mkdir(parents=True)
    (outer / ".git" / "HEAD").write_text("ref: refs/heads/main\n")
    for root in (outer, outer / "LifeOS-Chats"):
        client = TestClient(create_app(dataclasses.replace(settings, chat_root=root)))
        assert "git repository" in refused(client, auth)
    assert chat_calls() == [] and not (outer / "LifeOS-Chats").exists()


def test_a_root_inside_or_around_the_memory_or_an_allowed_folder_is_refused(
    settings, auth, ask_dir, project, home, chat_calls
):
    places = [ask_dir, ask_dir / "LifeOS-Chats", project, project / "LifeOS-Chats", home]
    for root in places:
        changed = dataclasses.replace(settings, chat_root=root, allowed_paths=(project,))
        message = refused(TestClient(create_app(changed)), auth)
        assert "outside the memory folder" in message or "inside the home folder" in message
    assert chat_calls() == []
    assert not (ask_dir / "LifeOS-Chats").exists() and not (project / "LifeOS-Chats").exists()


def test_a_hidden_root_or_one_outside_home_is_refused(settings, auth, home, tmp_path):
    for root in (home / ".hidden" / "LifeOS-Chats", tmp_path / "elsewhere" / "LifeOS-Chats"):
        client = TestClient(create_app(dataclasses.replace(settings, chat_root=root)))
        refused(client, auth)
        assert not root.exists()


def test_folders_lists_the_blank_chat_first_and_says_when_it_is_refused(client, auth, root):
    first = client.get("/folders", headers=auth).json()["folders"][0]
    assert (first["kind"], first["default"], first["usable"]) == ("chat", True, True)
    root.mkdir()
    (root / "CLAUDE.md").write_text("x")
    first = client.get("/folders", headers=auth).json()["folders"][0]
    assert first["usable"] is False and "CLAUDE.md" in first["reason"]


def test_the_chat_root_setting(isolated_config, monkeypatch):
    env = isolated_config / "env"
    env.write_text("LIFEOS_BRIDGE_TOKEN=" + "t" * 40 + "\n")
    env.chmod(0o600)
    assert load_settings().chat_root == Path.home() / "LifeOS-Chats"
    monkeypatch.setenv("LIFEOS_CHAT_DIR", "~/Elsewhere")
    assert load_settings().chat_root == Path.home() / "Elsewhere"
    monkeypatch.setenv("LIFEOS_CHAT_DIR", "relative/path")
    with pytest.raises(ConfigError):
        load_settings()


# ------------------------------------------------------------------ removal and resume


def test_what_no_files_means(settings, root):
    root.mkdir()
    folder = root / "2027-01-15-aaaaaa"
    (folder / "a" / "b").mkdir(parents=True)
    (folder / "c").mkdir()
    assert not chats.holds_files(folder)  # folders only, at any depth
    for name, make in {
        "a/b/deep.txt": lambda p: p.write_text("x"),
        ".hidden": lambda p: p.write_text(""),
        "link-to-file": lambda p: p.symlink_to(root),
        "c/link-to-folder": lambda p: p.symlink_to(folder / "a"),
    }.items():
        make(folder / name)
        assert chats.holds_files(folder), name
        assert not chats.remove_if_empty(settings, folder) and folder.is_dir()
        (folder / name).unlink()
    assert chats.remove_if_empty(settings, folder) and not folder.exists()


def test_only_the_bridges_own_chat_folders_are_ever_removed(settings, root, home):
    root.mkdir()
    for name in ("notes", "2027-01-15-zzzzzz", "2027-01-15-aaaaaa-copy"):
        (root / name).mkdir()
    kept, gone = root / "2027-01-15-bbbbbb", root / "2027-01-15-cccccc"
    kept.mkdir()
    gone.mkdir()
    elsewhere = home / "2027-01-15-dddddd"
    elsewhere.mkdir()
    assert not chats.remove_if_empty(settings, elsewhere) and not chats.remove_if_empty(
        settings, root
    )
    assert chats.sweep(settings, keep=[kept]) == [gone]
    assert sorted(p.name for p in root.iterdir()) == [
        "2027-01-15-aaaaaa-copy", "2027-01-15-bbbbbb", "2027-01-15-zzzzzz", "notes",
    ]  # fmt: skip


def test_a_chat_without_files_leaves_no_folder_and_can_be_continued(client, auth, root, chat_calls):
    session = ask(client, auth).json()["session_id"]
    folder = Path(chat_calls()[0]["cwd"])
    assert not folder.exists() and chat_folders(root) == []
    listed = client.get("/sessions", headers=auth).json()["sessions"]
    assert [(s["id"], s["folder"], s["kind"]) for s in listed] == [(session, str(folder), "chat")]
    # both ways of continuing create the same path again, and resume the same session there
    for path in ("/ask", f"/sessions/{session}/messages"):
        body = {"prompt": "again"} | ({"session_id": session} if path == "/ask" else {})
        assert client.post(path, headers=auth, json=body).status_code == 200
        call = chat_calls()[-1]
        assert call["cwd"] == str(folder) and option(call["args"], "--resume") == session
        assert not folder.exists()
    assert client.get(f"/sessions/{session}/history", headers=auth).status_code == 200


def test_a_chat_with_files_keeps_its_folder(client, auth, chat_calls):
    session = ask(client, auth, "FAKE_WRITE result.txt").json()["session_id"]
    folder = Path(chat_calls()[0]["cwd"])
    assert (folder / "result.txt").is_file()
    client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "more"})
    assert chat_calls()[-1]["cwd"] == str(folder) and (folder / "result.txt").is_file()


def test_a_first_message_that_is_refused_leaves_no_folder(client, auth, root, chat_calls):
    response = ask(client, auth, mode="capable")  # no guardrail marker: refused before it runs
    assert response.status_code == 503 and chat_calls() == []
    assert chat_folders(root) == []
    assert client.app.state.slots.active == set()


def test_a_repeated_first_message_makes_no_second_folder(client, auth, root, chat_calls):
    chosen = "11111111-2222-4333-8444-555555555555"
    body = {"prompt": "FAKE_WRITE kept.txt", "new_session_id": chosen}
    assert client.post("/ask", headers=auth, json=body).json()["session_id"] == chosen
    again = client.post("/ask", headers=auth, json=body).json()
    assert again == {"repeat": True, "session_id": chosen, "running": False}
    assert len(chat_calls()) == 1 and len(chat_folders(root)) == 1
    other = client.post("/ask", headers=auth, json=body | {"prompt": "something else"})
    assert other.status_code == 409 and len(chat_folders(root)) == 1


def test_a_stream_of_a_blank_chat(client, auth, chat_calls):
    with client.stream("POST", "/ask", headers=auth, json={"prompt": "hi", "stream": True}) as r:
        events = parse_sse(r.read().decode())
    assert events[-1][0] == "done" and events[-1][1]["answer"] == "echo: hi"
    assert not Path(chat_calls()[0]["cwd"]).exists()


def test_a_file_a_chat_made_can_be_offered(client, auth, home, chat_calls):
    session = ask(client, auth, "FAKE_WRITE report.txt").json()["session_id"]
    (home / ".fake-claude-answer").write_text("Here it is.\n[[file: report.txt]]\n")
    reply = client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "send it"})
    [card] = reply.json()["files"]
    assert card["name"] == "report.txt" and "files_refused" not in reply.json()


# ------------------------------------------------------------------ the SDK runner


def test_a_live_sdk_session_keeps_its_folder_until_it_closes(settings, auth, chat_calls):
    """The folder is the working directory of the session's live process: it is removed
    only once that process is closed, never under it."""
    with TestClient(create_app(dataclasses.replace(settings, runner="sdk"))) as client:
        session = ask(client, auth).json()["session_id"]
        folder = Path(next(c for c in chat_calls() if c.get("sdk"))["cwd"])
        assert folder.is_dir() and client.app.state.sweep_chats() == []
        client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "again"})
        assert len({c["pid"] for c in chat_calls()}) == 1  # the same live process answered
        pool = client.app.state.sdk_pool
        client.portal.call(pool.drop, session)
        assert client.app.state.sweep_chats() == [folder] and not folder.exists()
        # the next message starts a new process in the same path, from the transcript
        reply = client.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "back"})
        assert reply.status_code == 200 and folder.is_dir()
        resumed = [c for c in chat_calls() if c.get("sdk")][-1]
        assert resumed["cwd"] == str(folder) and option(resumed["args"], "--resume") == session
    assert not folder.exists()  # at shutdown nothing runs any more


# ------------------------------------------------------------------ the capable mode


def marker(runner="cli", **changes):
    data = {"runner": runner, "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "memory_folder",
            "suite_version": capable.SUITE_VERSIONS[runner], "skills_verified": True,
            "chat_folders": True} | changes  # fmt: skip
    capable.marker_path(runner).write_text(json.dumps(capable.sign_marker(data)))


def test_a_capable_run_in_a_chat_folder_has_the_same_guardrails(
    world, auth, ask_dir, project, root, chat_calls
):
    marker()
    assert ask(world, auth, "FAKE_WRITE made.txt", mode="capable").status_code == 200
    [call] = chat_calls()
    folder = Path(call["cwd"])
    granted = json.loads(option(call["args"], "--settings"))
    sandbox = granted["sandbox"]
    assert sandbox["enabled"] and sandbox["failIfUnavailable"]
    assert not sandbox["allowUnsandboxedCommands"] and sandbox["network"]["allowedDomains"] == []
    # write scope: the chat's own folder and the memory folder, as in every capable run
    allowed = [rule for rule in granted["permissions"]["allow"] if rule.startswith("Edit(")]
    assert allowed == [f"Edit(/{folder}/**)", f"Edit(/{ask_dir}/**)"]
    index = index_dir(ask_dir)
    assert added_dirs(call["args"]) == [str(ask_dir), index]
    # reads: the allowed folders and the conversation index; never the chat root, so never
    # another chat. The index is never writable.
    readable = sandbox["filesystem"]["allowRead"]
    assert sorted(readable) == sorted([str(folder), str(ask_dir), str(project), index])
    assert str(root) not in readable and sandbox["filesystem"]["denyWrite"] == [index]
    assert granted["hooks"]["PreToolUse"][0]["hooks"][0]["command"]  # the gate and the audit log
    assert call["env"]["CLAUDE_CODE_DISABLE_CLAUDE_MDS"] == "1"
    assert "XDG_RUNTIME_DIR" not in call["env"]
    assert (folder / "made.txt").is_file()


def test_capable_in_a_chat_folder_waits_for_the_check_that_probed_one(client, auth, chat_calls):
    for changes in (
        {"chat_folders": False},
        {"suite_version": capable.CHAT_SUITE_VERSIONS["cli"] - 1},
    ):
        marker(**changes)
        response = ask(client, auth, mode="capable")
        assert response.status_code == 503 and "chat folders" in response.json()["message"]
    assert chat_calls() == []
    # the memory folder is not affected by that
    marker(chat_folders=False)
    in_memory = ask(client, auth, mode="capable", folder=str(client.app.state.settings.ask_dir))
    assert in_memory.status_code == 200


def test_the_policy_refuses_what_is_not_a_chat_folder_of_a_usable_root(settings, root, ask_dir):
    root.mkdir()
    chat = root / "2027-01-15-abcdef"
    chat.mkdir()
    assert capable.build_policy(settings, chat).clean is True
    for folder in (root, root / "notes", chat / "sub"):
        folder.mkdir(exist_ok=True)
        with pytest.raises(ClaudeError) as error:
            capable.build_policy(settings, folder)
        assert error.value.code == "folder_not_allowed"
    (root / "CLAUDE.md").write_text("x")
    with pytest.raises(ClaudeError) as error:
        capable.build_policy(settings, chat)
    assert error.value.code == "chat_root_unusable"


def test_the_nested_repository_rule_covers_chat_folders(settings, home, ask_dir):
    outer = home / "work"
    (outer / ".git").mkdir(parents=True)
    (outer / ".git" / "HEAD").write_text("ref: refs/heads/main\n")
    nested = dataclasses.replace(settings, chat_root=outer / "LifeOS-Chats")
    chat = outer / "LifeOS-Chats" / "2027-01-15-abcdef"
    chat.mkdir(parents=True)
    with pytest.raises(ClaudeError) as error:
        capable.build_policy(nested, chat)
    assert error.value.code == "chat_root_unusable"
    with pytest.raises(ClaudeError) as error:  # the rule itself, whatever the root check says
        capable.check_repositories([chat, ask_dir], ask_dir)
    assert error.value.code == "folder_inside_repository"


# ------------------------------------------------------------------ moving files to a project


@pytest.fixture
def chat(world, auth, home, project, chat_calls):
    """A blank chat that made two files; ``propose(line)`` sends a message answered with it."""
    session = ask(world, auth, "FAKE_WRITE notes.txt").json()["session_id"]
    folder = Path(chat_calls()[0]["cwd"])
    (folder / "sub").mkdir()
    (folder / "sub" / "data.csv").write_text("1,2\n")

    def propose(line):
        (home / ".fake-claude-answer").write_text(f"I can move them.\n{line}\n")
        reply = world.post(f"/sessions/{session}/messages", headers=auth, json={"prompt": "move"})
        return reply.json()

    def answer(card, approve=True):
        url = f"/sessions/{session}/interactions/{card['id']}"
        return world.post(url, headers=auth, json={"approve": approve})

    return dataclasses.make_dataclass("Chat", ["session", "folder", "propose", "answer"])(
        session, folder, propose, answer
    )


def audit_actions(config):
    lines = [
        line for f in (config / "audit").glob("*.jsonl") for line in f.read_text().splitlines()
    ]
    return [json.loads(line)["tool"] for line in lines]


def test_a_proposal_becomes_a_card_and_an_approval_moves_the_files(
    world, auth, chat, project, isolated_config
):
    reply = chat.propose("[[move-files: project | notes.txt | sub/data.csv]]")
    assert reply["answer"] == "I can move them."
    [card] = reply["file_moves"]
    assert card["kind"] == "move_files" and card["state"] == "ready"
    assert (card["target"], card["target_label"]) == (str(project), "project")
    assert card["files"] == [{"path": "notes.txt", "size": 16}, {"path": "sub/data.csv", "size": 4}]
    assert card["total_size"] == 20 and card["folder"] == str(chat.folder)
    pending = world.get(f"/sessions/{chat.session}/interactions", headers=auth).json()["pending"]
    assert [item["id"] for item in pending] == [card["id"]]
    assert (chat.folder / "notes.txt").is_file() and not (project / "notes.txt").exists()

    done = chat.answer(card).json()
    assert done["moved"] is True and done["files"] == ["notes.txt", "sub/data.csv"]
    assert (project / "notes.txt").read_text() == "made by the run\n"
    assert (project / "sub" / "data.csv").read_text() == "1,2\n"
    assert oct((project / "notes.txt").stat().st_mode & 0o777) == "0o644"
    assert not chat.folder.exists()  # its files moved away: the chat leaves no folder
    actions = audit_actions(isolated_config)
    assert [a for a in actions if a.startswith("move_files")] == [
        "move_files_proposed", "move_files_approved", "move_files_done",
    ]  # fmt: skip
    assert chat.answer(card).status_code == 404  # answered once


def test_nothing_is_ever_overwritten(chat, project):
    (project / "notes.txt").write_text("the owner's own file\n")
    [card] = chat.propose("[[move-files: project | notes.txt]]")["file_moves"]
    assert card["state"] == "refused" and card["error_code"] == "file_exists"
    # also when the file appears between the card and the answer: nothing moves, the card stays
    [card] = chat.propose("[[move-files: project | sub/data.csv | notes.txt]]")["file_moves"]
    assert card["state"] == "refused"
    (project / "notes.txt").unlink()
    [card] = chat.propose("[[move-files: project | sub/data.csv | notes.txt]]")["file_moves"]
    (project / "notes.txt").write_text("the owner's own file\n")
    answer = chat.answer(card)
    assert answer.status_code == 409 and answer.json()["error"] == "file_exists"
    assert (project / "notes.txt").read_text() == "the owner's own file\n"
    assert not (project / "sub").exists() and (chat.folder / "sub" / "data.csv").is_file()
    (project / "notes.txt").unlink()
    assert chat.answer(card).json()["moved"] is True


def test_a_refusal_moves_nothing(chat, project, isolated_config):
    [card] = chat.propose("[[move-files: project | notes.txt]]")["file_moves"]
    assert chat.answer(card, approve=False).json() == {
        "answered": True,
        "id": card["id"],
        "moved": False,
    }
    assert (chat.folder / "notes.txt").is_file() and not (project / "notes.txt").exists()
    assert "move_files_refused" in audit_actions(isolated_config)


def test_only_regular_files_of_the_chat_itself_are_moved(chat, project, home, ask_dir):
    secret = home / "secret.txt"
    secret.write_text("not the chat's\n")
    (chat.folder / "link.txt").symlink_to(secret)
    (chat.folder / "linked").symlink_to(home)
    (chat.folder / ".claude" / "skills").mkdir(parents=True)
    (chat.folder / ".claude" / "skills" / "SKILL.md").write_text("x")
    (chat.folder / ".mcp.json").write_text("{}")
    cases = {
        "link.txt": "file_not_regular",
        "linked/secret.txt": "file_not_in_chat",
        "../secret.txt": "file_not_in_chat",
        str(secret): "file_not_in_chat",
        ".claude/skills/SKILL.md": "file_hidden",
        ".mcp.json": "file_hidden",
        "sub": "file_not_regular",
        "missing.txt": "file_missing",
    }
    for name, code in cases.items():
        [card] = chat.propose(f"[[move-files: project | {name}]]")["file_moves"]
        assert (card["state"], card["error_code"]) == ("refused", code), name
    assert list(project.iterdir()) == [] and secret.is_file()


def test_only_an_allowed_folder_other_than_the_memory_is_a_target(chat, project, home, ask_dir):
    (project / "docs").mkdir()
    (project / ".git").mkdir()
    (home / "other").mkdir()
    hidden, missing, chat_root = project / ".git", project / "missing", chat.folder.parent
    for target in (str(ask_dir), "brain", str(home / "other"), "other", str(home), "/etc",
                   str(hidden), str(missing), str(chat_root)):  # fmt: skip
        [card] = chat.propose(f"[[move-files: {target} | notes.txt]]")["file_moves"]
        assert (card["state"], card["error_code"]) == ("refused", "target_not_allowed"), target
    [card] = chat.propose("[[move-files: Project/docs | notes.txt]]")["file_moves"]
    assert card["state"] == "ready" and card["target_label"] == "project/docs"
    assert chat.answer(card).json()["moved"] is True and (project / "docs" / "notes.txt").is_file()


def test_a_file_that_changed_since_the_card_is_not_moved(chat, project):
    [card] = chat.propose("[[move-files: project | notes.txt]]")["file_moves"]
    (chat.folder / "notes.txt").write_text("something else entirely\n")
    answer = chat.answer(card)
    assert answer.status_code == 409 and answer.json()["error"] == "files_changed"
    assert not (project / "notes.txt").exists()


def test_without_an_audit_record_nothing_moves(chat, project, isolated_config, monkeypatch):
    [card] = chat.propose("[[move-files: project | notes.txt]]")["file_moves"]
    record = moves.audit.record

    def unwritable(action, detail, **extra):
        raise moves.audit.AuditUnavailable(action)

    monkeypatch.setattr(moves.audit, "record", unwritable)
    answer = chat.answer(card)
    assert answer.status_code == 503 and answer.json()["error"] == "audit_unavailable"
    assert not (project / "notes.txt").exists() and (chat.folder / "notes.txt").is_file()
    # ...and a proposal that cannot be recorded is no card at all
    [refused] = chat.propose("[[move-files: project | sub/data.csv]]")["file_moves"]
    assert (refused["state"], refused["error_code"]) == ("refused", "audit_unavailable")
    monkeypatch.setattr(moves.audit, "record", record)
    assert chat.answer(card).json()["moved"] is True  # the card was still waiting


def test_a_move_line_outside_a_blank_chat_is_only_text(world, auth, home, project, calls):
    (home / ".fake-claude-answer").write_text("[[move-files: project | notes.txt]]")
    reply = ask(world, auth, folder=str(project)).json()
    assert "file_moves" not in reply and "move-files" in reply["answer"]
    assert approvals.load() == {}


def test_too_many_proposals_and_expiry(chat, project, isolated_config):
    lines = "\n".join(["[[move-files: project | notes.txt]]"] * (moves.MAX_PROPOSALS + 1))
    cards = chat.propose(lines)["file_moves"]
    assert [c["state"] for c in cards] == ["ready"] * moves.MAX_PROPOSALS + ["refused"]
    for entry in approvals.take_expired(now=10**11):
        moves.expire(entry)
    assert approvals.load() == {} and "move_files_expired" in audit_actions(isolated_config)
    assert (chat.folder / "notes.txt").is_file()


# ------------------------------------------------------------------ sessions and their folders


def test_sessions_say_which_folder_they_belong_to(
    world, auth, ask_dir, project, root, write_session
):
    root.mkdir()
    in_chats = []
    for n in (1, 2):
        folder = root / f"2027-01-1{n}-aaaaaa"
        in_chats.append(write_session(folder, [user_line(folder, "c")], mtime=1_800_000_000 + n))
    sub = project / "sub"
    in_project = write_session(sub, [user_line(sub, "p")], mtime=1_800_000_100)
    in_memory = write_session(ask_dir, [user_line(ask_dir, "m")], mtime=1_800_000_200)
    world.get("/coach/chat", headers=auth)  # the coach conversation gets its session id
    coach = world.get("/coach/chat", headers=auth).json()["session_id"]
    stored = claude.project_dir(ask_dir) / f"{coach}.jsonl"  # the coach's own transcript
    stored.write_text(json.dumps(user_line(ask_dir, "coach")) + "\n")
    os.utime(stored, (1_800_000_300, 1_800_000_300))
    body = world.get("/sessions", headers=auth).json()
    by_id = {s["id"]: s for s in body["sessions"]}
    assert {by_id[i]["kind"] for i in in_chats} == {"chat"}
    assert {by_id[i]["folder_key"] for i in in_chats} == {"chats"}
    assert {by_id[i]["folder_label"] for i in in_chats} == {"Chats"}
    assert (by_id[in_project]["kind"], by_id[in_project]["folder_label"]) == ("project", "project")
    assert by_id[in_project]["folder_key"] == str(project)  # a sub-folder belongs to its root
    assert (by_id[in_memory]["kind"], by_id[in_memory]["folder_key"]) == ("memory", str(ask_dir))
    assert (by_id[coach]["kind"], by_id[coach]["folder_label"]) == ("coach", "Coach")
    tiles = {f["key"]: f for f in body["folders"]}
    assert [f["key"] for f in body["folders"]] == ["chats", "coach", str(ask_dir), str(project)]
    # all blank chats form one entry
    assert (tiles["chats"]["count"], tiles["chats"]["kind"], tiles["chats"]["name"]) == (
        2,
        "chat",
        "Chats",
    )
    assert tiles["chats"]["last_activity"] == by_id[in_chats[1]]["last_activity"]
    assert [tiles[k]["count"] for k in ("coach", str(ask_dir), str(project))] == [1, 1, 1]
    assert all(by_id[i]["colour"] == tiles[by_id[i]["folder_key"]]["colour"] for i in by_id)
    one = world.get(f"/sessions/{in_chats[0]}", headers=auth).json()
    assert (one["kind"], one["folder_key"], one["colour"]) == (
        "chat",
        "chats",
        tiles["chats"]["colour"],
    )


def test_a_folder_keeps_its_colour_and_a_new_one_takes_the_next_free(isolated_config):
    assert folders.colours(["chats", "/a", "/b"]) == {"chats": 0, "/a": 1, "/b": 2}
    assert folders.colours(["chats", "/b", "/c"]) == {"chats": 0, "/b": 2, "/c": 1}  # /a is gone
    assert folders.colours(["/c", "/a", "chats", "/b"]) == {"chats": 0, "/b": 2, "/c": 1, "/a": 3}
    stored = json.loads((isolated_config / folders.COLOURS_FILE).read_text())
    assert stored == {"chats": 0, "/b": 2, "/c": 1, "/a": 3}
    (isolated_config / folders.COLOURS_FILE).write_text('{"chats": "red", "/b": -1, "/c": true}')
    assert folders.colours(["chats", "/b", "/c"]) == {"chats": 0, "/b": 1, "/c": 2}


def test_the_course_folder_is_its_own_kind(settings, auth, home, isolated_config):
    courses, project = home / "courses", home / "project"
    courses.mkdir()
    project.mkdir()
    (isolated_config / "env").write_text(f"MOODLE_DOWNLOAD_DIR={courses}\n")
    (isolated_config / "env").chmod(0o600)
    changed = dataclasses.replace(settings, allowed_paths=(project, courses))
    listed = TestClient(create_app(changed)).get("/folders", headers=auth).json()["folders"]
    assert [(f["label"], f["kind"]) for f in listed] == [
        ("Chats", "chat"), ("brain", "memory"), ("project", "project"), ("courses", "course"),
    ]  # fmt: skip


# ------------------------------------------------------------------ the runs themselves


def test_read_only_policy_and_flags(settings, ask_dir):
    policy = chats.read_only_policy(settings)
    assert policy.tools == claude.READ_ONLY_TOOLS and not policy.capable and policy.clean
    flags = claude.run_flags(settings, session_id="s", resume=False, policy=policy)
    assert option(flags, "--add-dir") == str(ask_dir) and "--settings" not in flags
    assert "--add-dir" not in claude.run_flags(settings, session_id="s", resume=False)
    assert claude.child_env({"HOME": "/h", "SECRET": "x"}, clean=True) == {
        "HOME": "/h", **claude.NO_MEMORY_FILES_ENV,
    }  # fmt: skip
    assert claude.child_env({"HOME": "/h"}) == {"HOME": "/h"}


def test_new_folders_have_the_date_and_a_short_id(settings, root):
    made = {chats.new_folder(settings, date(2027, 1, 15)) for _ in range(20)}
    assert len(made) == 20 and all(chats.is_chat_folder(settings, folder) for folder in made)
    assert all(folder.name.startswith("2027-01-15-") and len(folder.name) == 17 for folder in made)
    assert not chats.is_chat_folder(settings, root) and not chats.is_chat_folder(
        settings, root / "x"
    )
    assert chats.in_chat_root(settings, next(iter(made)) / "sub") and not chats.in_chat_root(
        settings, root
    )


# ------------------------------------------------------------------ what lies under the chat root


@pytest.mark.parametrize("place", ["home", "documents", "hidden"])
def test_a_chat_root_around_other_work_never_makes_that_work_known(
    settings, auth, home, write_session, place
):
    """Sessions of any other folder are never listed, continued or offered: also when the
    chat root is set to the home folder, to a folder with other work, or to a hidden one."""
    root = {"home": home, "documents": home / "Documents", "hidden": home / ".local"}[place]
    private = root / "private-project"
    private.mkdir(parents=True)
    (private / "secret.txt").write_text("not a chat's file\n")
    other = write_session(private, [user_line(private, "secret work")])
    chat = root / "2027-01-15-abcdef"
    chat.mkdir()
    (chat / "made.txt").write_text("a chat's file\n")
    mine = write_session(chat, [user_line(chat, "a blank chat")])
    client = TestClient(create_app(dataclasses.replace(settings, chat_root=root)))

    listed = [s["id"] for s in client.get("/sessions", headers=auth).json()["sessions"]]
    assert other not in listed
    assert (mine in listed) == (place == "documents")  # only a root that may be one counts
    for method, path in [("GET", f"/sessions/{other}"), ("GET", f"/sessions/{other}/history"),
                         ("PATCH", f"/sessions/{other}"),
                         ("POST", f"/sessions/{other}/messages")]:  # fmt: skip
        response = client.request(method, path, headers=auth, json={"prompt": "x", "pinned": True})
        assert response.status_code == 404, (method, path)
    resumed = client.post("/ask", headers=auth, json={"prompt": "x", "session_id": other})
    assert resumed.status_code == 404
    assert not (private / ".fake-claude-calls.jsonl").exists()
    # ...and a file there is never offered, while a chat's own file is
    changed = dataclasses.replace(settings, chat_root=root)
    from lifeos_bridge import files

    assert files.refusal(changed, private / "secret.txt") == "outside the allowed folders"
    assert files.refusal(changed, root / "loose.txt") == "outside the allowed folders"
    if place == "documents":
        assert files.refusal(changed, chat / "made.txt") is None
        # the index a run reads names the chat, never the other folder's session
        assert client.post("/ask", headers=auth, json={"prompt": "hi"}).status_code == 200
        index = (root / ".bridge" / "conversations.md").read_text()
        assert mine in index and other not in index


def test_where_a_chat_root_may_lie(settings, home, ask_dir, isolated_config):
    def reason(root):
        return chats.placement_refusal(dataclasses.replace(settings, chat_root=root))

    assert reason(home / "LifeOS-Chats") is None and reason(home / "a" / "b") is None
    for root in (home, home.parent, home / ".config" / "chats", ask_dir, ask_dir / "chats",
                 isolated_config, isolated_config / "chats"):  # fmt: skip
        assert reason(root) is not None, root
    assert chats.session_ok(settings, home / "project")  # not under the root: not its business
    root = home / "LifeOS-Chats"
    assert chats.session_ok(settings, root / "2027-01-15-abcdef" / "sub")
    for folder in (root, root / "notes", root / ".bridge", root / "2027-01-15-abcdef-copy"):
        assert not chats.session_ok(settings, folder), folder
