"""The automatic guardrail check (#107, #140, lean since #181): which check, when, outcomes,
interruptions. Stand-ins only."""

import asyncio
import dataclasses
import json
import time

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, guardcheck, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError
from test_guardcheck import available, signed

DAY = 24 * 3600


@pytest.fixture
def sdk(settings):
    return dataclasses.replace(settings, runner="sdk")


@pytest.fixture
def auto(sdk, monkeypatch):
    """A GuardCheck with a stand-in check and recorded notifications.

    ``outcome["status"]`` is the exit status; ``passes`` writes a marker; ``fails``
    switches the mode off as a failed check does; ``failed_probe`` names the probe that
    failed in this check's own results; ``write_results`` False leaves only older
    results behind; ``hang`` never ends. ``seen`` records whether a capable request
    would have been served while the check ran.
    """
    monkeypatch.delenv("LIFEOS_GUARD_AUTOCHECK", raising=False)
    notices, launches, seen = [], [], []
    outcome = {"status": 0, "passes": True, "fails": False, "failed_probe": "sandbox: sudo",
               "write_results": True, "hang": False}  # fmt: skip

    async def launcher(kind, check_id):
        launches.append(kind)
        try:
            await capable.check_available(sdk, None)
            seen.append(True)
        except ClaudeError:
            seen.append(False)
        if outcome["hang"]:
            await asyncio.sleep(3600)
        if outcome["write_results"]:
            probes = [{"name": outcome["failed_probe"], "passed": False}]
            report = {"check_id": check_id, "probes": {"terminal": probes}}
            guardcheck.results_path().write_text(json.dumps(report))
        if outcome["fails"]:
            capable.switch_off()
        if outcome["status"] == 0 and outcome["passes"]:
            today = time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime())
            signed(check=kind, date=today, full_date=today)
        return outcome["status"]

    def make(the_settings=sdk, hour=4, busy=False):
        guard = guardcheck.GuardCheck(
            the_settings, launcher=launcher, notify=lambda *text: notices.append(text)
        )
        guard.hour = lambda now: hour
        guard.busy = lambda: busy
        return guard

    return make, notices, launches, outcome, seen


def tick(guard, now=None):
    async def go():
        await guard.tick(now)
        if guard.task is not None:
            await guard.task

    asyncio.run(go())


def fresh_full_pass(**fields):
    """A marker of a full pass of today, for exactly the installed versions."""
    signed(date=time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime()), **fields)


def test_without_a_marker_the_full_check_runs_at_once(auto, sdk):
    make, notices, launches, _outcome, seen = auto
    tick(make(hour=14, busy=True))  # not a quiet time: the mode is off, so it runs anyway
    assert launches == ["full"] and seen == [False]
    assert notices == [guardcheck.STARTED, guardcheck.PASSED]
    assert available(sdk)
    tick(make())
    assert launches == ["full"]  # nothing more to do


def test_after_an_update_the_core_set_runs_and_the_mode_stays_on(auto, sdk):
    make, notices, launches, _outcome, seen = auto
    fresh_full_pass(claude_version="fake-claude 0.9")
    guard = make(hour=14, busy=True)  # the core check needs no quiet time
    tick(guard)
    assert launches == ["core"] and seen == [True]  # on while it ran
    assert notices == []  # nothing changed for the owner
    tick(guard)
    tick(make())
    assert launches == ["core"]  # once per version pair


def test_a_newer_core_set_runs_the_core_check(auto):
    make, _notices, launches, *_ = auto
    fresh_full_pass(core_version=0)
    tick(make())
    assert launches == ["core"]


def test_a_failed_probe_of_this_check_is_final(auto, sdk):
    make, notices, launches, outcome, _seen = auto
    fresh_full_pass(claude_version="fake-claude 0.9")
    outcome.update(status=verify.FAILED, passes=False, fails=True)
    guard = make()
    tick(guard)
    tick(guard, now=10**12)
    tick(make())
    assert launches == ["core"]  # no full check after a failure: final for these versions
    assert notices == [guardcheck.FAILED]
    assert guard.state()["current"]["result"] == "failed"
    assert not available(sdk)


def test_older_results_never_make_a_check_failed(auto, sdk):
    make, notices, _launches, outcome, _seen = auto
    fresh_full_pass(claude_version="fake-claude 0.9")
    old = {"check_id": "an-earlier-check", "probes": {"terminal": [{"name": "x", "passed": False}]}}
    guardcheck.results_path().write_text(json.dumps(old))
    outcome.update(status=verify.FAILED, passes=False, write_results=False)  # crashed early
    guard = make()
    tick(guard, now=1000)
    assert guard.state()["current"]["result"] == "not_run"
    assert guardcheck.FAILED not in notices and available(sdk)


@pytest.mark.parametrize(
    ("status", "failed_probe"),
    [
        (124, "x"),  # the time limit
        (-9, "x"),  # killed
        (verify.BUSY, "x"),  # another check holds the lock (an orphan of a crashed bridge)
        (2, "x"),  # could not start (configuration)
        (verify.NOT_RUN, "x"),  # a probe's scenario did not happen (inconclusive)
        (verify.FAILED, "service environment: suite ran"),  # only its own environment failed
        (verify.FAILED, "sandbox: probe script ran"),
    ],
)
def test_a_check_that_did_not_finish_is_not_run_and_retried(auto, sdk, status, failed_probe):
    make, notices, launches, outcome, seen = auto
    fresh_full_pass(claude_version="fake-claude 0.9")
    outcome.update(status=status, passes=False, failed_probe=failed_probe)
    guard = make()
    tick(guard, now=1000)
    assert guard.state()["current"]["result"] == "not_run"
    tick(guard, now=1001)  # too early for a retry
    assert launches == ["core"]
    later = 1000
    for _ in range(guardcheck.MAX_ATTEMPTS + 2):
        later += 10**9
        tick(guard, now=later)
    assert len(launches) == guardcheck.MAX_ATTEMPTS
    assert notices == [guardcheck.INCOMPLETE]
    assert all(seen) and available(sdk)  # never off: not run is not failed
    # The next start of the bridge runs it again from the beginning.
    restarted = make()
    restarted.recover()
    outcome.update(status=0, passes=True)
    tick(restarted, now=later + 1)
    assert len(launches) == guardcheck.MAX_ATTEMPTS + 1 and available(sdk)


def test_a_bridge_stop_during_the_check_is_not_run_and_reruns_at_the_next_start(auto, sdk):
    make, notices, launches, outcome, _seen = auto
    fresh_full_pass(claude_version="fake-claude 0.9")
    outcome.update(hang=True)
    guard = make()

    async def stop_midway():
        await guard.tick()
        await asyncio.sleep(0.05)  # the check is running
        await guard.close()

    asyncio.run(stop_midway())
    entry = guard.state()["current"]
    assert entry["result"] == "not_run" and entry["attempts"] == 0
    assert available(sdk)
    outcome.update(hang=False)
    restarted = make()
    restarted.recover()
    tick(restarted)
    assert launches == ["core", "core"] and available(sdk)
    assert notices == []


def test_a_crash_of_the_bridge_leaves_running_which_becomes_not_run(auto, sdk, isolated_config):
    make, _, launches, *_ = auto
    fresh_full_pass(claude_version="fake-claude 0.9")
    guard = make()
    guard._save(current={"kind": "core", "key": "whatever", "attempts": 2, "result": "running"})
    for name in capable.STALE_FILE_NAMES:  # what bridges before #181 left behind
        (isolated_config / name).write_text("x")
    restarted = make()
    restarted.recover()
    assert restarted.state()["current"]["result"] == "not_run"
    assert not any((isolated_config / name).exists() for name in capable.STALE_FILE_NAMES)
    tick(restarted)
    assert launches == ["core"] and available(sdk)


def test_a_new_version_after_a_failure_is_checked_again(auto, sdk, monkeypatch):
    make, _, launches, outcome, _seen = auto
    fresh_full_pass(claude_version="fake-claude 0.9")
    outcome.update(status=verify.FAILED, passes=False, fails=True)
    guard = make()
    tick(guard)

    async def newer(_settings):
        return "fake-claude 1.1"

    monkeypatch.setattr(capable, "claude_version", newer)
    outcome.update(status=0, passes=True, fails=False)
    tick(guard)
    assert launches == ["core", "full"]  # no marker any more: only a full check turns it on
    assert available(sdk)


def test_the_full_suite_runs_monthly_at_a_quiet_time(auto):
    make, notices, launches, *_ = auto
    now = time.time()
    signed(date=time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime(now - 31 * DAY)))
    tick(make(hour=14), now=now)  # not quiet
    tick(make(hour=4, busy=True), now=now)  # quiet hour, but something runs
    assert launches == []
    guard = make(hour=4)
    tick(guard, now=now)
    assert launches == ["full"] and notices == []
    signed(date=time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime(now - 31 * DAY)))
    tick(make(hour=4), now=now + DAY)  # its pass was lost: still at most once a month
    assert launches == ["full"]
    tick(make(hour=4), now=now + guardcheck.FULL_EVERY + 1)
    assert launches == ["full", "full"]


def test_an_older_full_suite_waits_for_the_monthly_slot(auto, monkeypatch):
    make, _notices, launches, *_ = auto
    fresh_full_pass(suite_version=capable.SUITE_VERSION - 1)
    guard = make(hour=14)
    tick(guard)
    assert launches == []  # not a quiet time
    guard.hour = lambda now: 4
    tick(guard)
    assert launches == ["full"]


def test_a_core_check_after_an_update_runs_before_the_monthly_one(auto):
    make, _notices, launches, *_ = auto
    now = time.time()
    signed(claude_version="fake-claude 0.9",
           date=time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime(now - 40 * DAY)))  # fmt: skip
    tick(make(hour=4), now=now)
    assert launches == ["core"]


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


# ------------------------------------------------------- the check's own clean-up


def test_only_one_check_at_a_time(settings, monkeypatch):
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    held = verify.take_lock()
    assert held is not None
    assert verify.main([]) == verify.BUSY  # nothing was touched
    held.close()
    assert verify.take_lock() is not None


@pytest.mark.parametrize(("argv", "expected"), [
    (["--full"], ["stop service", "suite full"]),
    ([], ["suite core"]),  # the core set runs once and never touches the service unit
])  # fmt: skip
def test_a_full_check_first_stops_what_an_interrupted_one_left(settings, monkeypatch, argv,
                                                               expected):  # fmt: skip
    order = []
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    monkeypatch.setattr(verify, "stop_leftover_service", lambda: order.append("stop service"))

    def run_all(self, core=False):
        order.append("suite " + ("core" if core else "full"))
        return [verify.Probe("x", True)]

    monkeypatch.setattr(verify.Suite, "run_all", run_all)
    monkeypatch.setattr(verify, "service_suite", lambda *a: [{"name": "x", "passed": True}])
    assert verify.main([*argv, "--check-id", "abc"]) == 0
    assert order == expected
    report = json.loads(guardcheck.results_path().read_text())
    assert report["check_id"] == "abc" and report["check"] == expected[-1].split()[-1]


def test_the_service_run_has_a_fixed_name_and_never_reads_old_results(tmp_path, monkeypatch):
    seen = {}
    out = tmp_path / "out.json"
    out.write_text(json.dumps([{"name": "old", "passed": True}]))  # left by an interrupted run
    monkeypatch.setattr(verify.shutil, "which", lambda name: "/usr/bin/" + name)
    monkeypatch.setattr(
        verify.subprocess, "run", lambda command, **kw: seen.setdefault("command", command)
    )
    assert verify.service_suite(tmp_path, out) is None  # the new run wrote nothing
    assert f"--unit={verify.SERVICE_UNIT}" in seen["command"]
    assert "--full" in seen["command"] and "--suite-only" in seen["command"]


def test_the_probe_model_is_the_routing_job(settings):
    assert verify.Suite(settings).settings.claude_model == "haiku"
    chosen = dataclasses.replace(
        settings, model_routing={"guardrail_check": ("sonnet", "low", None, False)}
    )
    suite = verify.Suite(chosen)
    assert (suite.settings.claude_model, suite.settings.default_effort) == ("sonnet", "low")
    # LIFEOS_BACKGROUND_MODEL never moves it
    assert (
        verify.Suite(dataclasses.replace(settings, background_model="opus")).settings.claude_model
        == "haiku"
    )


def test_the_scratch_folder_of_an_interrupted_check_is_moved_away(settings):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    (suite.run_dir / "left-over.txt").write_text("x")
    suite.prepare(0)
    assert not (suite.run_dir / "left-over.txt").exists()
    assert any((settings.ask_dir / ".trash").iterdir())  # moved to the trash, never deleted
    suite.clean_outside()
    suite.retire()


def test_a_check_leaves_the_memory_tree_clean(settings):
    import subprocess

    def git(*args):
        command = ["git", "-C", str(settings.ask_dir), *args]
        return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603

    memory = settings.ask_dir
    git("init", "-q")
    suite = verify.Suite(settings)
    suite.run_dir.mkdir(exist_ok=True)
    (suite.run_dir / "probe.txt").write_text("x")
    suite.retire()
    assert git("status", "--porcelain", "--", ".trash", "guardrail-probe") == ""
    exclude = (memory / ".git" / "info" / "exclude").read_text()
    verify.keep_out_of_status(memory)
    assert (memory / ".git" / "info" / "exclude").read_text() == exclude  # written once


def test_plan_runs_are_documented():
    from pathlib import Path

    docs = (Path(__file__).resolve().parents[2] / "docs" / "bridge.md").read_text()
    assert f"about {guardcheck.PLAN_RUNS['core']} short runs" in docs
    assert f"about {2 * guardcheck.PLAN_RUNS['full']} short runs" in docs
