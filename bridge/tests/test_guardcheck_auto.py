"""The automatic guardrail check (#107, #140): outcomes, interruptions, order. Stand-ins only."""

import asyncio
import json

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, guardcheck, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError
from test_guardcheck import available, signed


@pytest.fixture
def auto(settings, monkeypatch):
    """A GuardCheck with a stand-in check and recorded notifications.

    ``outcome["status"]`` is the exit status; ``passes`` writes a marker;
    ``failed_probe`` names the probe that failed in this check's own results;
    ``write_results`` False leaves only older results behind; ``hang`` never ends.
    """
    monkeypatch.delenv("LIFEOS_GUARD_AUTOCHECK", raising=False)
    notices, launches = [], []
    outcome = {"status": 0, "passes": True, "failed_probe": "sandbox: sudo",
               "write_results": True, "hang": False}  # fmt: skip

    async def launcher(runner, check_id):
        launches.append(runner)
        assert capable.running_flag(runner).exists()  # the mode is off while it runs
        with pytest.raises(ClaudeError):
            await capable.check_available(settings, None, runner=runner)
        if outcome["hang"]:
            await asyncio.sleep(3600)
        if outcome["write_results"]:
            probes = [{"name": outcome["failed_probe"], "passed": False}]
            report = {"check_id": check_id, "probes": {"terminal": probes}}
            guardcheck.results_path(runner).write_text(json.dumps(report))
        if outcome["status"] == 0 and outcome["passes"]:
            signed(runner)
        return outcome["status"]

    def make(the_settings=settings):
        return guardcheck.GuardCheck(
            the_settings, launcher=launcher, notify=lambda *text: notices.append(text)
        )

    return make, notices, launches, outcome


def tick(guard, now=None):
    async def go():
        await guard.tick(now)
        if guard.task is not None:
            await guard.task

    asyncio.run(go())


def cli_checked_before():
    guardcheck.results_path("cli").write_text("{}")


def test_the_sdk_runner_is_checked_even_when_not_selected(auto, settings):
    make, notices, launches, _ = auto
    assert settings.runner == "cli"
    tick(make())
    assert launches == ["sdk"]  # cli was never checked here: not started by itself
    assert notices == [guardcheck.STARTED, guardcheck.PASSED]
    assert available(settings, "sdk")


def test_one_restart_checks_both_one_after_the_other(auto, settings):
    make, _notices, launches, _ = auto
    cli_checked_before()
    tick(make())
    assert launches == ["cli", "sdk"]
    assert available(settings, "cli") and available(settings, "sdk")
    tick(make())  # a later start: nothing to do
    assert launches == ["cli", "sdk"]


def test_after_an_update_the_check_runs_once_and_the_mode_comes_back(auto, settings):
    make, notices, launches, _ = auto
    signed("cli", claude_version="fake-claude 0.9")
    signed("sdk")
    guard = make()
    tick(guard)
    assert launches == ["cli"]
    assert notices == [guardcheck.STARTED, guardcheck.PASSED]
    tick(guard)
    tick(make())
    assert launches == ["cli"]  # once per version pair


def test_a_failed_probe_of_this_check_is_final(auto, settings):
    make, notices, launches, outcome = auto
    signed("sdk")
    cli_checked_before()
    outcome.update(status=1, passes=False)
    guard = make()
    tick(guard)
    tick(guard, now=10**12)
    assert launches == ["cli"]
    assert notices == [guardcheck.STARTED, guardcheck.FAILED]
    assert guard.state()["cli"]["result"] == "failed"


def test_older_results_never_make_a_check_failed(auto, settings):
    make, notices, _launches, outcome = auto
    signed("sdk")
    old = {"check_id": "an-earlier-check", "probes": {"terminal": [{"name": "x", "passed": False}]}}
    guardcheck.results_path("cli").write_text(json.dumps(old))
    outcome.update(status=1, passes=False, write_results=False)  # crashed before writing
    guard = make()
    tick(guard, now=1000)
    assert guard.state()["cli"]["result"] == "not_run"
    assert guardcheck.FAILED not in notices


@pytest.mark.parametrize(
    ("status", "failed_probe"),
    [
        (124, "x"),  # the time limit
        (-9, "x"),  # killed
        (verify.BUSY, "x"),  # another check holds the lock (an orphan of a crashed bridge)
        (2, "x"),  # could not start (configuration)
        (1, "service environment: suite ran"),  # only the check's own environment failed
    ],
)
def test_a_check_that_did_not_finish_is_not_run_and_retried(auto, settings, status, failed_probe):
    make, notices, launches, outcome = auto
    signed("sdk")
    cli_checked_before()
    outcome.update(status=status, passes=False, failed_probe=failed_probe)
    guard = make()
    tick(guard, now=1000)
    assert guard.state()["cli"]["result"] == "not_run"
    tick(guard, now=1001)  # too early for a retry
    assert launches == ["cli"]
    later = 1000
    for _ in range(guardcheck.MAX_ATTEMPTS + 2):
        later += 10**9
        tick(guard, now=later)
    assert len(launches) == guardcheck.MAX_ATTEMPTS
    assert notices == [guardcheck.STARTED, guardcheck.INCOMPLETE]
    assert not available(settings)
    # The next start of the bridge runs it again from the beginning.
    restarted = make()
    restarted.recover()
    outcome.update(status=0, passes=True)
    tick(restarted, now=later + 1)
    assert len(launches) == guardcheck.MAX_ATTEMPTS + 1 and available(settings)


def test_a_bridge_stop_during_the_check_is_not_run_and_reruns_at_the_next_start(auto, settings):
    make, notices, launches, outcome = auto
    signed("sdk")
    cli_checked_before()
    outcome.update(hang=True)
    guard = make()

    async def stop_midway():
        await guard.tick()
        await asyncio.sleep(0.05)  # the check is running
        await guard.close()

    asyncio.run(stop_midway())
    entry = guard.state()["cli"]
    assert entry["result"] == "not_run" and entry["attempts"] == 0
    assert not capable.running_flag("cli").exists()
    outcome.update(hang=False)
    restarted = make()
    restarted.recover()
    tick(restarted)
    assert launches == ["cli", "cli"] and available(settings)
    assert notices.count(guardcheck.STARTED) == 1  # told once per version pair


def test_a_crash_of_the_bridge_leaves_running_which_becomes_not_run(auto, settings):
    make, _, launches, _ = auto
    signed("sdk")
    cli_checked_before()
    guard = make()
    guard._save("cli", {"versions": "whatever", "attempts": 2, "result": "running"})
    capable.set_check_running("cli", True)
    restarted = make()
    restarted.recover()
    assert restarted.state()["cli"]["result"] == "not_run"
    assert not capable.running_flag("cli").exists()
    tick(restarted)
    assert launches == ["cli"] and available(settings)


def test_a_new_version_after_a_failure_is_checked_again(auto, settings, monkeypatch):
    make, _, launches, outcome = auto
    signed("sdk")
    cli_checked_before()
    outcome.update(status=1, passes=False)
    guard = make()
    tick(guard)

    async def newer(_settings):
        return "fake-claude 1.1"

    monkeypatch.setattr(capable, "claude_version", newer)
    outcome.update(status=0, passes=True)
    tick(guard)
    assert launches[:2] == ["cli", "cli"]


def test_notification_texts_are_fixed():
    for title, text in (
        guardcheck.STARTED,
        guardcheck.PASSED,
        guardcheck.FAILED,
        guardcheck.INCOMPLETE,
    ):
        assert title.startswith("LifeOS: ") and "/" not in text and "{" not in text


def test_the_bridge_starts_the_watcher_and_stops_it(settings, monkeypatch):
    started = []

    async def forever(self):
        started.append(True)
        await asyncio.sleep(3600)

    monkeypatch.setattr(guardcheck.GuardCheck, "run_forever", forever)
    with TestClient(create_app(settings)):
        pass
    assert started == [True]


# ------------------------------------------------------- the check's own clean-up


def test_only_one_check_at_a_time(settings, monkeypatch):
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    held = verify.take_lock()
    assert held is not None
    assert verify.main([]) == verify.BUSY  # nothing was touched
    held.close()
    assert verify.take_lock() is not None


def test_a_check_first_stops_what_an_interrupted_one_left(settings, monkeypatch):
    order = []
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    monkeypatch.setattr(verify, "stop_leftover_service", lambda: order.append("stop service"))

    def run_all(self):
        order.append("suite")
        return [verify.Probe("x", True)]

    monkeypatch.setattr(verify.Suite, "run_all", run_all)
    monkeypatch.setattr(verify, "service_suite", lambda *a: [{"name": "x", "passed": True}])
    assert verify.main(["--check-id", "abc"]) == 0
    assert order == ["stop service", "suite"]
    report = json.loads(guardcheck.results_path("cli").read_text())
    assert report["check_id"] == "abc"


def test_the_service_run_has_a_fixed_name_and_never_reads_old_results(tmp_path, monkeypatch):
    seen = {}
    out = tmp_path / "out.json"
    out.write_text(json.dumps([{"name": "old", "passed": True}]))  # left by an interrupted run
    monkeypatch.setattr(verify.shutil, "which", lambda name: "/usr/bin/" + name)
    monkeypatch.setattr(
        verify.subprocess, "run", lambda command, **kw: seen.setdefault("command", command)
    )
    assert verify.service_suite(tmp_path, out, "sdk") is None  # the new run wrote nothing
    assert f"--unit={verify.SERVICE_UNIT}" in seen["command"]
    assert seen["command"][-2:] == ["--runner", "sdk"]


def test_the_scratch_folder_of_an_interrupted_check_is_moved_away(settings):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    (suite.run_dir / "left-over.txt").write_text("x")
    suite.prepare(0)
    assert not (suite.run_dir / "left-over.txt").exists()
    assert any((settings.ask_dir / ".trash").iterdir())  # moved to the trash, never deleted
    suite.clean_outside()
    suite.retire()


def test_plan_runs_of_one_restart_are_documented():
    from pathlib import Path

    docs = (Path(__file__).resolve().parents[2] / "docs" / "bridge.md").read_text()
    total = 2 * sum(guardcheck.PLAN_RUNS.values())
    assert f"about {total} short runs" in docs
