"""The coach conversation: persistent, compacted when long, learning into the memory.

The stand-in claude answers; nothing real runs. Synthetic content only.
"""

import dataclasses
import json
import subprocess

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import coach_chat, memory, routing, runcontrol, sdk_runner, session_meta
from lifeos_bridge.app import create_app


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def brain(ask_dir):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    (ask_dir / "INDEX.md").write_text("# Index\n")
    (ask_dir / "profile.md").write_text("# Profile\n\n## Facts\n- [owner 2027-01-01] Old fact.\n")
    (ask_dir / "courses").mkdir()
    (ask_dir / "courses" / "example-course.md").write_text("# Example course\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


@pytest.fixture
def answers(tmp_path):
    """Sets what the stand-in claude answers next."""

    def write(answer):
        text = answer if isinstance(answer, str) else json.dumps(answer)
        (tmp_path / "home" / ".fake-claude-answer").write_text(text)

    return write


@pytest.fixture
def coach_client(settings, brain):
    configured = dataclasses.replace(settings, default_model="opus", default_effort="medium")
    return TestClient(create_app(configured))


def value(args, flag):
    return args[args.index(flag) + 1] if flag in args else None


def say(client, auth, text, **extra):
    return client.post("/coach/chat/messages", headers=auth, json={"prompt": text, **extra})


def test_one_persistent_session_on_the_coach_model(coach_client, settings, auth, calls):
    first = say(coach_client, auth, "hello coach")
    assert first.status_code == 200 and first.json()["answer"] == "echo: hello coach"
    session_id = first.json()["session_id"]
    assert session_id == coach_chat.state()["session_id"]
    say(coach_client, auth, "and today?")
    one, two = (call["args"] for call in calls())
    assert value(one, "--session-id") == session_id and value(two, "--resume") == session_id
    assert (value(one, "--model"), value(one, "--effort")) == ("opus", "high")  # coach_chat job
    context = value(one, "--append-system-prompt")
    assert "you are the owner's coach" in context and "Edit" not in value(one, "--tools")
    # It survives a restart, and it is the pinned "Coach" session in the list.
    again = TestClient(create_app(settings))
    assert again.get("/coach/chat", headers=auth).json()["session_id"] == session_id
    meta = session_meta.entry_for(session_id)
    assert (meta["title"], meta["pinned"]) == ("Coach", True)
    view = coach_client.get("/coach/chat", headers=auth).json()
    assert view["started"] and view["transcript_bytes"] > 0 and view["compactions"] == 0
    assert (view["model"], view["effort"]) == ("opus", "high")
    assert view["usage"]["today"]["runs"] == 2 and view["morning_usage"]["today"]["runs"] == 0


def test_the_coach_is_read_only_and_keeps_its_model(coach_client, auth, calls):
    assert say(coach_client, auth, "x", mode="capable").status_code == 400
    assert say(coach_client, auth, "x", model="haiku").status_code == 400
    session_id = coach_client.get("/coach/chat", headers=auth).json()["session_id"]
    say(coach_client, auth, "start")
    # Through the session routes too: same job, same refusal.
    url = f"/sessions/{session_id}/messages"
    refused = coach_client.post(url, headers=auth, json={"prompt": "x", "model": "haiku"})
    assert refused.status_code == 400
    assert coach_client.post(url, headers=auth, json={"prompt": "y"}).status_code == 200
    assert value(calls()[-1]["args"], "--model") == "opus"
    assert coach_client.post("/coach/chat/messages").status_code == 401


LEARNED = {
    "facts": [
        {"file": "profile.md", "text": "Works best before noon.", "source": "owner"},
        {"file": "courses/example-course.md", "text": "Finds the labs hard.", "source": "inferred"},
        {"file": "../outside.md", "text": "Never written."},
        {"file": "projects/missing.md", "text": "No such file."},
        {"file": "profile.md", "text": ""},
    ],
    "summary": "# Talked about mornings.\nOpen: plan the labs.",
}


def test_a_long_conversation_is_compacted_and_learns(
    coach_client, auth, brain, calls, answers, monkeypatch
):
    monkeypatch.setattr(coach_chat, "LIMIT_BYTES", 10)
    say(coach_client, auth, "first")
    old = coach_chat.state()["session_id"]
    answers(LEARNED)  # the compaction turn's answer (and the next message's, here)
    reply = say(coach_client, auth, "next")
    assert reply.status_code == 200
    compacted = reply.json()["compacted"]
    assert (
        compacted["facts"] == ["courses/example-course.md", "profile.md"] and compacted["summary"]
    )
    new = coach_chat.state()["session_id"]
    assert new != old and compacted["session_id"] == new == reply.json()["session_id"]
    turn, message = calls()[-2:]
    assert value(turn["args"], "--resume") == old and "about to be compacted" in turn["prompt"]
    assert value(message["args"], "--session-id") == new  # the message went to the new session
    assert "Talked about mornings" in value(message["args"], "--append-system-prompt")
    profile = (brain / "profile.md").read_text()
    assert (
        "- [owner 2027-01-01] Old fact.\n- [owner " in profile
        and "Works best before noon." in profile
    )
    course = (brain / "courses" / "example-course.md").read_text()
    assert "## Facts\n- [inferred " in course and "Finds the labs hard." in course
    log = (brain / "coach" / "conversation.md").read_text()
    assert "\\# Talked about mornings." in log and "Open: plan the labs." in log
    assert git(brain, "log", "-1", "--format=%s").strip() == "Compact the coach conversation"
    assert git(brain, "status", "--porcelain", "--untracked-files=no") == ""
    assert not (brain.parent / "outside.md").exists()
    view = coach_client.get("/coach/chat", headers=auth).json()
    assert view["compactions"] == 1 and view["earlier_sessions"] == [old]
    assert view["last_compaction"]["facts"] == 2
    assert session_meta.entry_for(old)["title"] == "Coach (earlier)"


def test_a_failed_compaction_still_starts_a_new_session(coach_client, auth, brain, answers):
    assert coach_client.post("/coach/chat/compact", headers=auth).status_code == 409
    say(coach_client, auth, "first")
    answers("this is not json")
    before = git(brain, "log", "--format=%s")
    done = coach_client.post("/coach/chat/compact", headers=auth).json()
    assert done["facts"] == [] and done["summary"] is False
    assert coach_chat.state()["last_compaction"]["result"] == "no_summary"
    assert git(brain, "log", "--format=%s") == before  # nothing written


def test_the_coach_on_the_sdk_runner_is_counted_apart(settings, brain, auth, monkeypatch):
    monkeypatch.setattr(sdk_runner, "GRACE", 0.3)
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    sdk = dataclasses.replace(settings, runner="sdk", default_model="opus")
    with TestClient(create_app(sdk)) as client:
        assert say(client, auth, "hello").json()["answer"] == "echo: hello"
        view = client.get("/coach/chat", headers=auth).json()
    assert view["usage"]["today"]["runs"] == 1
    jobs = {j["job"]: j for j in routing.overview(sdk)["jobs"]}
    assert jobs["chat"]["usage"]["today"]["runs"] == 0
    assert memory.now_local()  # the memory was not touched
