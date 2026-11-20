"""Guardrails re-verify themselves after every update (#107). Stand-ins only: no real check."""

import asyncio
import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, guardcheck
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

VERSION = "fake-claude 1.0"


def signed(runner="cli", **fields):
    data = {
        "runner": runner,
        "claude_version": VERSION,
        "sdk_version": capable.sdk_version(),
        "all_passed": True,
        "scope": "all_allowed_folders",
    } | fields
    capable.marker_path(runner).write_text(json.dumps(capable.sign_marker(data)))


def available(settings, runner="cli") -> bool:
    try:
        asyncio.run(capable.check_available(settings, None, runner=runner))
    except ClaudeError:
        return False
    return True


# ------------------------------------------- no path to a capable run on unverified versions


def test_a_signed_marker_for_these_versions_is_accepted(settings):
    signed()
    assert available(settings)


def test_updated_claude_code(settings):
    signed(claude_version="fake-claude 0.9")
    assert not available(settings)


def test_updated_sdk_also_on_disk_before_a_restart(settings, monkeypatch):
    sdk_settings = dataclasses.replace(settings, runner="sdk")
    signed("sdk")
    assert available(sdk_settings, "sdk")
    monkeypatch.setattr(capable, "installed_sdk_version", lambda: "9.9.9")
    assert not available(sdk_settings, "sdk")  # the package changed under the running bridge
    monkeypatch.undo()
    signed("sdk", sdk_version="0.0.1")
    assert not available(sdk_settings, "sdk")


def test_while_a_check_runs(settings):
    signed()
    capable.set_check_running("cli", True)
    assert not available(settings)
    capable.set_check_running("cli", False)
    assert available(settings)


def test_marker_removed(settings):
    signed()
    capable.marker_path("cli").unlink()
    assert not available(settings)


@pytest.mark.parametrize(
    "edit",
    [
        lambda m: m | {"claude_version": "fake-claude 2.0"},
        lambda m: m | {"all_passed": True, "probes": {}},
        lambda m: m | {"scope": "all_allowed_folders", "runner": "cli", "extra": 1},
        lambda m: {k: v for k, v in m.items() if k != "signature"},
    ],
)
def test_marker_edited_by_hand(settings, edit):
    signed(scope="memory_folder")
    marker = json.loads(capable.marker_path("cli").read_text())
    capable.marker_path("cli").write_text(json.dumps(edit(marker)))
    assert not available(settings)


def test_marker_without_its_key(settings, isolated_config):
    signed()
    (isolated_config / capable.KEY_FILE_NAME).unlink()
    assert not available(settings)


def test_check_failed_leaves_no_marker(settings, isolated_config, monkeypatch):
    from lifeos_bridge import verify

    signed()
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    monkeypatch.setattr(verify.Suite, "run_all", lambda self: [verify.Probe("x", False)])
    monkeypatch.setattr(verify, "service_suite", lambda *a: [{"name": "x", "passed": True}])
    seen = []
    original = verify.finish

    def spy(*args):
        seen.append(capable.running_flag("cli").exists() and not available(settings))
        return original(*args)

    monkeypatch.setattr(verify, "finish", spy)
    assert verify.main([]) == 1
    assert seen == [True]  # off from the first moment of the check
    assert not available(settings) and not capable.running_flag("cli").exists()


def test_api_refuses_capable_while_a_check_runs(client, auth, settings):
    signed()
    capable.set_check_running("cli", True)
    response = client.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"})
    assert response.status_code == 503 and "running" in response.json()["message"]


# ------------------------------------------------------------- the automatic check


@pytest.fixture
def auto(settings, monkeypatch):
    """A GuardCheck with a stand-in check and recorded notifications."""
    monkeypatch.delenv("LIFEOS_GUARD_AUTOCHECK", raising=False)
    notices, launches = [], []
    outcome = {"status": 0, "passes": True, "failed_probe": "sandbox: sudo"}

    async def launcher(runner):
        launches.append(runner)
        assert capable.running_flag(runner).exists()  # the mode is off while it runs
        with pytest.raises(ClaudeError):
            await capable.check_available(settings, None, runner=runner)
        report = {"probes": {"terminal": [{"name": outcome["failed_probe"], "passed": False}]}}
        guardcheck.results_path(runner).write_text(json.dumps(report))
        if outcome["status"] == 0 and outcome["passes"]:
            signed(runner)
        return outcome["status"]

    def make(the_settings=settings):
        return guardcheck.GuardCheck(
            the_settings, launcher=launcher, notify=lambda *text: notices.append(text)
        )

    return make, notices, launches, outcome


async def settle(guard):
    for task in list(guard.tasks.values()):
        await task


def tick(guard, now=None):
    async def go():
        await guard.tick(now)
        await settle(guard)

    asyncio.run(go())


def test_a_runner_never_checked_is_never_started(auto):
    make, notices, launches, _ = auto
    tick(make())
    assert launches == [] and notices == []


def test_after_an_update_the_check_runs_once_and_the_mode_comes_back(auto, settings):
    make, notices, launches, _ = auto
    signed(claude_version="fake-claude 0.9")  # checked before, for an older version
    guard = make()
    tick(guard)
    assert launches == ["cli"]
    assert notices == [guardcheck.STARTED, guardcheck.PASSED]
    assert available(settings)
    tick(guard)
    tick(make())  # also after a restart of the bridge
    assert launches == ["cli"]  # once per version pair


def test_a_failed_probe_is_final_for_that_version(auto, settings):
    make, notices, launches, outcome = auto
    outcome.update(status=1, passes=False)
    guardcheck.results_path("cli").write_text("{}")
    guard = make()
    tick(guard)
    tick(guard, now=10**12)
    assert launches == ["cli"]
    assert notices == [guardcheck.STARTED, guardcheck.FAILED]
    assert not available(settings)
    assert guard.state()["cli"]["result"] == "failed"


def test_a_check_that_cannot_complete_is_retried_then_given_up(auto, settings):
    make, notices, launches, outcome = auto
    outcome.update(status=1, passes=False, failed_probe="service environment: suite ran")
    guardcheck.results_path("cli").write_text("{}")
    guard = make()
    tick(guard, now=1000)
    tick(guard, now=1001)  # too early for a retry
    assert launches == ["cli"]
    later = 1000
    for _ in range(guardcheck.MAX_ATTEMPTS + 2):
        later += 10**9
        tick(guard, now=later)
    assert len(launches) == guardcheck.MAX_ATTEMPTS
    assert notices == [guardcheck.STARTED, guardcheck.INCOMPLETE]
    assert not available(settings)


def test_the_sdk_runner_is_checked_only_when_selected(auto, settings):
    make, _, launches, _ = auto
    signed("sdk", sdk_version="0.0.1")
    tick(make(settings))
    assert launches == []
    tick(make(dataclasses.replace(settings, runner="sdk")))
    assert launches == ["sdk"]


def test_a_new_version_after_a_failure_is_checked_again(auto, settings, monkeypatch):
    make, _, launches, outcome = auto
    outcome.update(status=1, passes=False)
    guardcheck.results_path("cli").write_text("{}")
    guard = make()
    tick(guard)

    async def newer(_settings):
        return "fake-claude 1.1"

    monkeypatch.setattr(capable, "claude_version", newer)
    outcome.update(status=0, passes=True)
    tick(guard)
    assert launches == ["cli", "cli"]


def test_stale_running_flags_are_cleared_at_start(settings):
    capable.set_check_running("cli", True)
    guardcheck.GuardCheck(settings).recover()
    assert not capable.running_flag("cli").exists()


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
