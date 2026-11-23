"""Applying an update a chat in the program folder produced (#180). Stand-ins for the
tests, the build and the restart: the real steps never run here (conftest)."""

import asyncio
import dataclasses
import json
import os
import time

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import approvals, capable, paths, program, updates
from lifeos_bridge.app import create_app
from lifeos_bridge.updates import Steps as RealSteps  # taken before conftest replaces it
from test_ask import option

COMMIT = "a" * 40


@pytest.fixture
def repo(tmp_path, monkeypatch):
    folder = tmp_path / "home" / "lifeos"
    (folder / "bridge").mkdir(parents=True)
    git = folder / ".git"
    (git / "refs" / "heads").mkdir(parents=True)
    (git / "HEAD").write_text("ref: refs/heads/main\n")
    (git / "refs" / "heads" / "main").write_text(COMMIT + "\n")
    monkeypatch.setattr(paths, "repo_dir", lambda: folder.resolve())
    return folder.resolve()


@pytest.fixture
def allowed(settings, repo, isolated_config):
    data = {"runner": "sdk", "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "all_allowed_folders",
            "suite_version": capable.SUITE_VERSION, "program_folder": True}  # fmt: skip
    capable.marker_path().write_text(json.dumps(capable.sign_marker(data)))
    return dataclasses.replace(settings, allowed_paths=(repo,))


@pytest.fixture
def notices(monkeypatch):
    sent = []
    monkeypatch.setattr(updates, "_notify", lambda title, text: sent.append((title, text)))
    return sent


def records(config, prefix="update_"):
    folder = config / "audit"
    lines = [json.loads(line) for f in sorted(folder.glob("*.jsonl")) for line in f.open()]
    return [entry for entry in lines if str(entry.get("tool")).startswith(prefix)]


def commit(repo, value):
    (repo / ".git" / "refs" / "heads" / "main").write_text(value + "\n")


class FakeSteps:
    def __init__(self, fail=None, restart="restarting"):
        self.done = []
        self.fail = fail
        self.how = restart

    def _step(self, name):
        self.done.append(name)
        if name == self.fail:
            raise updates.StepFailed(name)

    def check(self, folder, commit):
        self._step("check")

    def tests(self, folder):
        self._step("tests")

    def lint(self, folder):
        self._step("lint")

    def build(self):
        self._step("build")
        return {"registered": True, "version_code": 7, "version_name": "0.1.7"}

    def restart(self):
        self._step("restart")
        return self.how


def wait_for(client, auth, state, limit=10):
    deadline = time.time() + limit
    while True:
        seen = client.get("/app/update", headers=auth).json()
        if seen["state"] == state:
            return seen
        assert time.time() < deadline, seen
        time.sleep(0.02)


# ---- the whole way, through the bridge ----------------------------------------------------


def test_a_request_applies_without_a_card_waits_steps_restart_and_applied(
    allowed, repo, auth, isolated_config, notices, ask_dir
):
    """#183: no approval card; the owner is told it started, and of the outcome."""
    (ask_dir.parent / ".fake-claude-answer").write_text("Committed.\n[[apply-update]]\n")
    with TestClient(create_app(allowed)) as client:
        steps = FakeSteps()
        applier = client.app.state.applier
        applier.steps, applier.poll, applier.restart_grace = steps, 0.01, 60
        body = {"prompt": "x", "mode": "capable", "folder": str(repo)}
        reply = client.post("/ask", headers=auth, json=body).json()
        assert reply["answer"] == "Committed."
        assert "update_requests" not in reply  # no card
        session_id = reply["session_id"]
        assert client.get(f"/sessions/{session_id}/interactions", headers=auth).json() == {
            "pending": []
        }
        seen = wait_for(client, auth, "restarting")
        assert seen["commit"] == COMMIT and seen["step"] == "restart"
        assert steps.done == ["check", "tests", "lint", "build", "check", "restart"]
        # From the moment nothing ran until the restart: no run begins.
        again = client.post("/ask", headers=auth, json=body)
        assert again.status_code == 503 and again.json()["error"] == "update_in_progress"
    assert approvals.load() == {}
    actions = [r["tool"] for r in records(isolated_config)]
    assert actions == ["update_requested", "update_restarting"]
    assert notices[0] == updates.STARTED
    # The new process: the update runs now.
    state = json.loads(updates.state_path().read_text())
    state["pid"] = os.getpid() + 1
    updates.state_path().write_text(json.dumps(state))
    updates.after_start()
    assert updates.public_state()["state"] == "applied"
    ready = "The tested update runs now. An app update is ready to install."
    assert notices[-1] == ("LifeOS: update applied", ready)
    assert records(isolated_config)[-1]["tool"] == "update_applied"


def test_a_failed_check_is_reported_and_nothing_applied(
    allowed, repo, auth, isolated_config, notices, ask_dir
):
    (ask_dir.parent / ".fake-claude-answer").write_text("Committed.\n[[apply-update]]\n")
    with TestClient(create_app(allowed)) as client:
        steps = FakeSteps(fail="tests")
        applier = client.app.state.applier
        applier.steps, applier.poll = steps, 0.01
        body = {"prompt": "x", "mode": "capable", "folder": str(repo)}
        client.post("/ask", headers=auth, json=body)
        seen = wait_for(client, auth, "failed")
        assert seen["step"] == "tests" and steps.done == ["check", "tests"]
    assert notices[0] == updates.STARTED
    assert notices[-1][0] == "LifeOS: update not applied"


def test_only_program_folder_chats_are_told_the_rule(allowed, repo, auth, ask_dir):
    with TestClient(create_app(allowed)) as client:
        client.post("/ask", headers=auth, json={"prompt": "x", "folder": str(repo)})
        client.post("/ask", headers=auth, json={"prompt": "y"})
    there = json.loads((repo / ".fake-claude-calls.jsonl").read_text().splitlines()[0])["args"]
    elsewhere = json.loads((ask_dir / ".fake-claude-calls.jsonl").read_text().splitlines()[0])
    assert "[[apply-update]]" in option(there, "--append-system-prompt")
    assert "[[apply-update]]" not in option(elsewhere["args"], "--append-system-prompt")


def test_nothing_from_a_read_only_run_or_another_folder(allowed, repo, auth, ask_dir):
    (ask_dir.parent / ".fake-claude-answer").write_text("Done.\n[[apply-update]]\n")
    with TestClient(create_app(allowed)) as client:
        plain = client.post("/ask", headers=auth, json={"prompt": "x", "folder": str(repo)})
        assert "update_requests" not in plain.json()
        elsewhere = client.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"})
        [card] = elsewhere.json()["update_requests"]
        assert card["state"] == "refused" and card["error_code"] == "not_program_folder"
        assert elsewhere.json()["answer"] == "Done."
    assert approvals.load() == {} and updates.read_state() == {}


def test_history_never_shows_or_turns_the_line_into_a_card():
    line = "Done.\n[[apply-update]]\n"
    assert updates.strip(line) == "Done."
    inline = "Mention [[apply-update]] inline stays"
    assert updates.strip(inline) == inline


# ---- the request ---------------------------------------------------------------------------


def propose(allowed, repo, session_id="s1", owner=True):
    return updates.from_answer(allowed, "Ready.\n[[apply-update]]", session_id, repo, owner=owner)


def test_a_request_schedules_exactly_the_commit_the_folder_is_at(allowed, repo, notices):
    text, refused, scheduled = propose(allowed, repo)
    assert (text, refused, scheduled) == ("Ready.", [], True)
    state = updates.read_state()
    assert state["phase"] == "waiting" and state["commit"] == COMMIT
    assert state["folder"] == str(repo) and state["session_id"] == "s1"
    assert notices == [updates.STARTED]
    assert approvals.load() == {}


def test_only_an_answer_to_the_owners_message_counts(allowed, repo, notices):
    text, [card], scheduled = propose(allowed, repo, owner=False)
    assert text == "Ready." and not scheduled
    assert card["state"] == "refused" and card["error_code"] == "not_owner"
    assert updates.read_state() == {} and notices == []


def test_while_one_is_under_way_nothing_else_is_requested(allowed, repo, notices):
    assert propose(allowed, repo)[2] is True
    commit(repo, "c" * 40)
    _, [refused], scheduled = propose(allowed, repo, "s2")
    assert refused["error_code"] == "update_in_progress" and not scheduled
    assert updates.read_state()["commit"] == COMMIT


def test_a_request_that_cannot_be_recorded_starts_nothing(allowed, repo, monkeypatch, notices):
    def broken(*_a, **_k):
        raise updates.audit.AuditUnavailable("no")

    monkeypatch.setattr(updates.audit, "record", broken)
    _, [card], scheduled = propose(allowed, repo)
    assert card["state"] == "refused" and card["error_code"] == "audit_unavailable"
    assert not scheduled and updates.read_state() == {} and notices == []


def test_a_card_left_by_an_earlier_bridge_can_still_be_answered(allowed, repo):
    """Cards of a bridge before #183 that still wait: a no is a no; a yes for a changed
    commit is never applied."""
    entry = approvals.add(updates.KIND, "s1", str(repo), {"state": "ready", "commit": COMMIT})
    assert updates.answer(entry, False) == {"approved": False}
    commit(repo, "c" * 40)
    assert updates.answer(entry, True) == {"approved": False, "reason": "changed"}
    commit(repo, COMMIT)
    assert updates.answer(entry, True)["approved"] is True


# ---- the flow ------------------------------------------------------------------------------


def approved(repo):
    state = {"phase": "waiting", "commit": COMMIT, "folder": str(repo), "session_id": "s1"}
    updates.store.write_json(updates.state_path(), state)


def apply(settings, steps, blockers=lambda: [], **how):
    applier = updates.Applier(settings, blockers, steps, poll=0.01, **how)
    asyncio.run(applier.run())
    return applier


def test_it_waits_until_nothing_runs(settings, repo, notices):
    approved(repo)
    busy = iter([["runs"], ["queued messages"], ["coach"], []] + [[]] * 10)
    steps = FakeSteps(restart="manual")
    seen_while_busy = []

    def blockers():
        found = next(busy)
        if found:
            seen_while_busy.append(list(steps.done))
        return found

    applier = apply(settings, steps, blockers)
    assert seen_while_busy == [[], [], []]  # nothing started while anything ran
    assert steps.done == ["check", "tests", "lint", "build", "check", "restart"]
    state = updates.public_state()
    assert state["state"] == "applied" and state["restart_needed"] is True
    assert not applier.holding
    assert notices[0][0] == "LifeOS: update ready"


@pytest.mark.parametrize("step", ["check", "tests", "lint", "build"])
def test_a_failing_step_applies_nothing_and_says_which(settings, repo, notices, step,
                                                       isolated_config):  # fmt: skip
    approved(repo)
    steps = FakeSteps(fail=step)
    applier = apply(settings, steps)
    assert "restart" not in steps.done
    state = updates.public_state()
    assert state["state"] == "failed" and state["step"] == step
    assert not applier.holding
    [(title, text)] = notices
    assert title == "LifeOS: update not applied" and f'"{step}"' in text
    assert "Nothing was applied" in text
    assert records(isolated_config)[-1]["detail"]["step"] == step


def test_runs_that_never_end_make_it_fail_at_wait(settings, repo, notices):
    approved(repo)
    steps = FakeSteps()
    apply(settings, steps, lambda: ["runs"], wait_limit=0.05)
    assert steps.done == [] and updates.public_state()["step"] == "wait"


def test_a_restart_that_does_not_happen_is_a_failure(settings, repo, notices):
    approved(repo)
    apply(settings, FakeSteps(), restart_grace=0.01)
    state = updates.public_state()
    assert state["state"] == "failed" and state["step"] == "restart"
    assert "app update was registered" in notices[0][1]


def test_an_interrupted_update_is_reported_at_the_next_start(repo, notices, isolated_config):
    for phase in ("waiting", "testing", "building"):
        approved(repo)
        updates._save(phase=phase, step="tests")
        notices.clear()
        updates.after_start()
        assert updates.public_state()["state"] == "interrupted"
        assert notices[0][0] == "LifeOS: update interrupted"
    updates.after_start()  # nothing more to say
    assert len(notices) == 1


# ---- the real steps, without running anything ----------------------------------------------


def test_the_restart_is_left_to_the_owner_outside_the_service(monkeypatch, isolated_config):
    monkeypatch.setattr(updates, "own_unit", lambda: "session-2.scope")
    assert RealSteps().restart() == "manual"
    monkeypatch.setattr(updates, "own_unit", lambda: "lifeos-bridge.service")
    (isolated_config / "env").write_text("LIFEOS_SERVICE_NAME=lifeos-other.service\n")
    (isolated_config / "env").chmod(0o600)
    assert updates.service_name() == "lifeos-other.service"
    assert RealSteps().restart() == "manual"  # not the unit it was told to restart


def test_the_check_refuses_another_commit_before_running_git(repo):
    with pytest.raises(updates.StepFailed) as failed:
        RealSteps().check(repo, "f" * 40)
    assert failed.value.step == "check" and "another commit" in failed.value.reason


def test_own_unit(tmp_path):
    cgroup = tmp_path / "cgroup"
    cgroup.write_text("0::/user.slice/user-1000.slice/user@1000.service/app.slice/"
                      "lifeos-bridge.service\n")  # fmt: skip
    assert updates.own_unit(cgroup) == "lifeos-bridge.service"
    cgroup.write_text("0::/user.slice/user-1000.slice/session-2.scope\n")
    assert updates.own_unit(cgroup) is None
    assert updates.own_unit(tmp_path / "missing") is None


def test_the_build_gets_its_settings_from_the_env_file(isolated_config):
    (isolated_config / "env").write_text("LIFEOS_JAVA_HOME=/opt/jdk\nLIFEOS_BRIDGE_TOKEN=x\n")
    (isolated_config / "env").chmod(0o600)
    env = updates.build_environment({"PATH": "/bin", "LIFEOS_GRADLE_USER_HOME": "/g"})
    assert env["JAVA_HOME"] == "/opt/jdk" and env["GRADLE_USER_HOME"] == "/g"
    assert "ANDROID_HOME" not in env and env["PATH"] == "/bin"


def test_the_running_commit_is_pinned_at_start(repo):
    program.pin()
    assert program.running_commit() == COMMIT
    assert updates.public_state()["running_commit"] == COMMIT


def test_a_run_started_by_a_request_holds_the_update(allowed, auth, monkeypatch):
    """A coach refresh runs inside its request, outside the run slots: it still counts."""
    import threading

    from lifeos_bridge import coach

    entered, release = threading.Event(), threading.Event()

    async def slow_refresh(_settings, clock=None):
        entered.set()
        await asyncio.to_thread(release.wait, 10)
        return {"ok": True}

    monkeypatch.setattr(coach, "refresh", slow_refresh)
    with TestClient(create_app(allowed)) as client:
        applier = client.app.state.applier
        # (the bridge's own jobs may run at start, as the coach's morning plan: not asserted)
        assert "requests" not in applier.blockers()
        worker = threading.Thread(
            target=lambda: client.post("/coach/plan/refresh", headers=auth), daemon=True
        )
        worker.start()
        assert entered.wait(10)
        assert "requests" in applier.blockers()
        release.set()
        worker.join(10)
        assert "requests" not in applier.blockers()


def test_other_agents_worktree_sessions_never_reach_the_phone(allowed, repo, auth, write_session):
    from conftest import user_line

    tree = repo / ".claude" / "worktrees" / "agent-1"
    tree.mkdir(parents=True)
    own = write_session(repo, [user_line(repo, "Work on LifeOS")])
    hidden = write_session(tree, [user_line(tree, "An agent's own work")])
    with TestClient(create_app(allowed)) as client:
        listed = {s["id"] for s in client.get("/sessions", headers=auth).json()["sessions"]}
        assert own in listed and hidden not in listed
        gone = client.get(f"/sessions/{hidden}/history", headers=auth)
        assert gone.status_code == 404


@pytest.fixture
def settings(settings):
    """Capable runs exist on the SDK runner only (#181)."""
    import dataclasses

    return dataclasses.replace(settings, runner="sdk")
