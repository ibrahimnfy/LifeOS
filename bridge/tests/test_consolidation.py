"""The consolidation proposal (#150): propose, then approve. A stand-in run; nothing real."""

import asyncio
import json
import subprocess
from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import claude, consolidation, memory, push
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import RunResult

NIGHT = datetime(2027, 1, 15, 3, 0).astimezone()
PROJECT = (
    "---\ntitle: Example project\ntype: project\nstatus: active\n---\n\nSummary.\n\n## Facts\n"
    "- [owner 2027-01-01] Demo is due in March.\n"
    "- [inferred 2027-01-05] The demo is due in March.\n"
    "- [owner 2027-01-02] Works on it in the evenings.\n"
    "- [owner 2027-01-03] Usually works on it in the evening.\n\n## Links\n"
)


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def brain(ask_dir):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    (ask_dir / "INDEX.md").write_text("# Index\n")
    (ask_dir / "profile.md").write_text("# Profile\n\n## Facts\n- [owner 2027-01-01] Likes tea.\n")
    (ask_dir / "projects").mkdir()
    (ask_dir / "projects" / "example-project.md").write_text(PROJECT)
    (ask_dir / "inbox.md").write_text(
        "# Inbox\n\n- 2027-01-14 09:00 Ask about the demo room\n"
        "- 2027-01-14 10:00 Idea for the poster\n  with a second line\n"
    )
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


def capture_ids(brain):
    return [e["id"] for e in memory._inbox_entries((brain / "inbox.md").read_text())]


@pytest.fixture
def proposes(monkeypatch):
    """A stand-in for the run: answers the given operations and records how it was asked."""
    asked = {"calls": [], "operations": []}

    async def run(settings, prompt, **kwargs):
        asked["calls"].append({**kwargs, "prompt": prompt})
        return RunResult(json.dumps({"operations": asked["operations"]}), "", 1, 1)

    monkeypatch.setattr(claude, "run", run)
    return asked


@pytest.fixture
def hints(monkeypatch):
    sent = []
    monkeypatch.setattr(push, "send_hint", lambda event, ref="", **kw: sent.append((event, kw)))
    return sent


def proposal_for(brain):
    room, poster = capture_ids(brain)
    return [
        {"op": "file_capture", "capture": room, "file": "projects/example-project.md",
         "why": "It is about the demo."},
        {"op": "file_capture", "capture": poster, "file": "projects/example-project.md"},
        {"op": "merge_facts", "file": "projects/example-project.md",
         "keep": "Works on it in the evenings.", "drop": "Usually works on it in the evening.",
         "why": "The same habit twice."},
        {"op": "add_fact", "file": "profile.md", "text": "Prefers quiet mornings for writing.",
         "why": "Seen in several notes."},
        # Not proposed: an owner fact dropped for an inferred one, an owner fact again,
        # an unknown capture, a file outside the topic files, junk.
        {"op": "merge_facts", "file": "projects/example-project.md",
         "keep": "The demo is due in March.", "drop": "Demo is due in March."},
        {"op": "add_fact", "file": "profile.md", "text": "likes  TEA."},
        {"op": "file_capture", "capture": "i-000000000000", "file": "profile.md"},
        {"op": "add_fact", "file": "../outside.md", "text": "x"},
        {"op": "delete_everything"},
        "junk",
    ]  # fmt: skip


def run(settings):
    return asyncio.run(consolidation.run(settings, lambda: NIGHT))


def test_a_run_proposes_and_writes_nothing(settings, brain, proposes, hints):
    proposes["operations"] = proposal_for(brain)
    before = git(brain, "log", "--format=%H")
    assert run(settings) == "ok"
    (asked,) = proposes["calls"]
    assert (asked["model"], asked["advisor"], asked["job"]) == ("sonnet", "opus", "consolidation")
    assert asked["cwd"] == brain and asked["persist"] is False and "tools" not in asked
    assert (
        capture_ids(brain)[0] in asked["prompt"]
        and "projects/example-project.md" in asked["prompt"]
    )
    view = consolidation.view()
    ops = view["proposal"]["operations"]
    assert [o["op"] for o in ops] == ["file_capture", "file_capture", "merge_facts", "add_fact"]
    assert [o["id"] for o in ops] == ["op1", "op2", "op3", "op4"] and view["waiting"] == 4
    assert ops[0]["capture_text"] == "Ask about the demo room" and ops[0]["why"]
    assert all(o["status"] == "pending" and "fingerprint" not in o for o in ops)
    assert git(brain, "log", "--format=%H") == before  # nothing applied by itself
    assert git(brain, "status", "--porcelain") == ""
    assert hints == [("coach", {"opens": "consolidation", "counts": None})]


def test_approved_operations_are_applied_in_one_commit(settings, brain, proposes, hints):
    proposes["operations"] = proposal_for(brain)
    run(settings)
    calls = len(proposes["calls"])
    done = consolidation.approve(settings, {"operations": ["op1", "op3", "op4"]}, lambda: NIGHT)
    assert (done["applied"], done["failed"], done["committed"]) == (["op1", "op3", "op4"], [], True)
    assert done["waiting"] == 1 and len(proposes["calls"]) == calls  # no model run
    assert git(brain, "log", "-1", "--format=%an|%s").strip() == (
        "lifeos-consolidation|Consolidate the memory (approved by the owner)"
    )
    project = (brain / "projects" / "example-project.md").read_text()
    assert "- [owner 2027-01-14] Ask about the demo room\n" in project
    assert (
        "- [owner 2027-01-03, superseded 2027-01-15] Usually works on it in the evening." in project
    )
    assert "- [owner 2027-01-02] Works on it in the evenings." in project  # kept as it was
    assert project.count("\n- [") == 5  # no fact line disappeared
    assert (
        "[inferred 2027-01-15] Prefers quiet mornings for writing."
        in (brain / "profile.md").read_text()
    )
    inbox = (brain / "inbox.md").read_text()
    assert "demo room" not in inbox and "Idea for the poster" in inbox
    assert (
        "Ask about the demo room  → projects/example-project.md"
        in (brain / ".trash" / "inbox-filed.md").read_text()
    )
    assert git(brain, "status", "--porcelain") == ""
    # The last one: a multi-line capture keeps its lines, in a section of its own.
    last = consolidation.approve(settings, {"all": True}, lambda: NIGHT)
    assert last["applied"] == ["op2"] and last["proposal"] is None and last["waiting"] == 0
    project = (brain / "projects" / "example-project.md").read_text()
    assert (
        "## From the inbox\n- 2027-01-14 10:00:\n  Idea for the poster\n  with a second line\n"
        in project
    )


def test_rejected_operations_are_not_proposed_again_unchanged(settings, brain, proposes, hints):
    proposes["operations"] = proposal_for(brain)
    run(settings)
    done = consolidation.reject({"operations": ["op4"]})
    assert done["rejected"] == ["op4"] and done["waiting"] == 3
    consolidation.reject({"all": True})
    assert consolidation.view()["proposal"] is None
    before = git(brain, "log", "--format=%H")
    proposes["operations"] = [
        *proposal_for(brain)[3:4],  # the same fact again: not proposed
        {"op": "add_fact", "file": "profile.md", "text": "Prefers quiet mornings for reading."},
    ]
    run(settings)
    ops = consolidation.view()["proposal"]["operations"]
    assert [o["text"] for o in ops] == ["Prefers quiet mornings for reading."]
    assert git(brain, "log", "--format=%H") == before


def test_an_operation_that_no_longer_applies_fails_alone(settings, brain, proposes, hints):
    proposes["operations"] = proposal_for(brain)
    run(settings)
    first = capture_ids(brain)[0]
    memory.update_note(brain, first, "Ask about the demo room and the projector")  # edited
    done = consolidation.approve(settings, {"operations": ["op1", "op4"]}, lambda: NIGHT)
    assert done["applied"] == ["op4"] and done["failed"] == ["op1"]
    failed = next(o for o in done["proposal"]["operations"] if o["id"] == "op1")
    assert (failed["status"], failed["reason"]) == ("failed", "no longer applies")
    assert "projector" in (brain / "inbox.md").read_text()


def test_due_at_night_once_and_not_while_a_proposal_waits(settings, brain, proposes, hints):
    evening = datetime(2027, 1, 15, 1, 0).astimezone()
    assert consolidation.due(settings, lambda: evening) is False  # before 02:00
    assert consolidation.due(settings, lambda: NIGHT) is True  # captures wait in the inbox
    proposes["operations"] = proposal_for(brain)
    run(settings)
    later = datetime(2027, 1, 16, 3, 0).astimezone()
    assert consolidation.due(settings, lambda: later) is False  # a proposal waits
    consolidation.reject({"all": True})
    assert consolidation.due(settings, lambda: later) is True
    assert consolidation.due(settings, lambda: NIGHT) is False  # already tried that day


def test_endpoints(settings, brain, auth, proposes, hints):
    client = TestClient(create_app(settings))
    assert client.get("/memory/consolidation").status_code == 401
    empty = client.get("/memory/consolidation", headers=auth).json()
    assert empty["proposal"] is None and empty["waiting"] == 0
    assert client.post("/memory/consolidation/approve", headers=auth,
                       json={"all": True}).status_code == 404  # fmt: skip
    proposes["operations"] = proposal_for(brain)
    run(settings)
    assert client.post("/memory/consolidation/approve", headers=auth, json={}).status_code == 400
    assert client.post("/memory/consolidation/approve", headers=auth,
                       json={"operations": ["op9"]}).status_code == 404  # fmt: skip
    started = client.post("/memory/consolidation/run", headers=auth)
    assert started.status_code == 409 and started.json()["reason"] == "waiting"
    shown = client.get("/memory/consolidation", headers=auth).json()
    assert set(shown) == {"proposal", "waiting", "running", "last_run", "last_result"}
    assert set(shown["proposal"]) == {"id", "created_at", "operations"}
    done = client.post("/memory/consolidation/approve", headers=auth,
                       json={"operations": ["op4"]}).json()  # fmt: skip
    assert done["applied"] == ["op4"] and done["committed"] is True
    rejected = client.post("/memory/consolidation/reject", headers=auth, json={"all": True})
    assert rejected.json()["proposal"] is None
