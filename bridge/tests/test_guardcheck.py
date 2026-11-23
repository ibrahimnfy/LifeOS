"""The capable mode and its marker (#107, lean since #181). Stand-ins only: no real check."""

import asyncio
import dataclasses
import json

import pytest

from lifeos_bridge import capable, verify
from lifeos_bridge.claude import ClaudeError

VERSION = "fake-claude 1.0"


def signed(**fields):
    data = {
        "runner": "sdk",
        "claude_version": VERSION,
        "sdk_version": capable.sdk_version(),
        "all_passed": True,
        "scope": "all_allowed_folders",
        "suite_version": capable.SUITE_VERSION,
        "core_version": capable.CORE_VERSION,
    } | fields
    capable.marker_path().write_text(json.dumps(capable.sign_marker(data)))


@pytest.fixture
def sdk(settings):
    return dataclasses.replace(settings, runner="sdk")


def available(settings) -> bool:
    try:
        asyncio.run(capable.check_available(settings, None))
    except ClaudeError:
        return False
    return True


def test_a_signed_marker_is_accepted_on_the_sdk_runner(sdk):
    signed()
    assert available(sdk)


def test_the_claude_p_runner_has_no_capable_mode(settings, sdk):
    signed()
    assert settings.runner == "cli" and not available(settings)
    with pytest.raises(ClaudeError) as refused:
        asyncio.run(capable.check_available(settings, None))
    assert "LIFEOS_RUNNER=sdk" in refused.value.message


def test_an_update_keeps_the_last_verified_state(sdk, monkeypatch):
    """#181: a newer Claude Code or Agent SDK never switches the mode off by itself."""
    signed(claude_version="fake-claude 0.9", sdk_version="0.0.1")
    assert available(sdk)
    monkeypatch.setattr(capable, "installed_sdk_version", lambda: "9.9.9")
    assert available(sdk)
    assert capable.core_outdated("fake-claude 1.0", "9.9.9")  # the core check is due


def test_core_outdated(sdk):
    signed()
    assert not capable.core_outdated(VERSION, capable.sdk_version())
    assert capable.core_outdated("fake-claude 2.0", capable.sdk_version())
    signed(core_version=0)
    assert capable.core_outdated(VERSION, capable.sdk_version())
    capable.marker_path().unlink()
    assert not capable.core_outdated("anything", "else")  # no marker: the full check is due


def test_marker_removed(sdk):
    signed()
    capable.marker_path().unlink()
    assert not available(sdk)


@pytest.mark.parametrize(
    "edit",
    [
        lambda m: m | {"claude_version": "fake-claude 2.0"},
        lambda m: m | {"all_passed": True, "probes": {}},
        lambda m: m | {"scope": "all_allowed_folders", "extra": 1},
        lambda m: m | {"runner": "cli"},
        lambda m: {k: v for k, v in m.items() if k != "signature"},
    ],
)
def test_marker_edited_by_hand(sdk, edit):
    signed(scope="memory_folder")
    marker = json.loads(capable.marker_path().read_text())
    capable.marker_path().write_text(json.dumps(edit(marker)))
    assert not available(sdk)


def test_marker_without_its_key(sdk, isolated_config):
    signed()
    (isolated_config / capable.KEY_FILE_NAME).unlink()
    assert not available(sdk)


def fake_check(monkeypatch, settings, probes, seen=None):
    monkeypatch.setattr(verify, "load_settings", lambda: settings)

    def run_all(self, core=False):
        if seen is not None:
            seen.append(available(settings))  # what a capable request sees meanwhile
        return probes

    monkeypatch.setattr(verify.Suite, "run_all", run_all)
    monkeypatch.setattr(verify, "service_suite", lambda *a: [{"name": "x", "passed": True}])


def test_the_mode_stays_on_while_a_check_runs_and_a_failure_switches_it_off(sdk, monkeypatch):
    signed()
    seen: list[bool] = []
    fake_check(monkeypatch, sdk, [verify.Probe("sandbox: sudo", False)], seen)
    assert verify.main([]) == verify.FAILED
    assert seen == [True]  # on while the check ran
    assert not available(sdk)  # off once a guardrail probe failed


@pytest.mark.parametrize(
    "probes",
    [
        [verify.Probe("gate: x", False, inconclusive=True)],  # the scenario did not happen
        [verify.Probe("sandbox: probe script ran", False)],  # the check's own failure
    ],
)
def test_a_check_that_did_not_complete_changes_nothing(sdk, monkeypatch, probes):
    signed(claude_version="fake-claude 0.9")
    before = capable.marker_path().read_text()
    fake_check(monkeypatch, sdk, probes)
    assert verify.main([]) == verify.NOT_RUN
    assert capable.marker_path().read_text() == before and available(sdk)


def test_a_core_pass_keeps_what_the_full_check_proved(sdk, monkeypatch):
    signed(claude_version="fake-claude 0.9", course_folders=True, chat_folders=True,
           program_folder=True, suite_version=8, date="2026-01-01T00:00:00+00:00")  # fmt: skip
    fake_check(monkeypatch, sdk, [verify.Probe("sandbox: sudo", True)])
    monkeypatch.setattr(verify, "chat_folders_probed", lambda env: True)
    monkeypatch.setattr(verify, "program_folder_probed", lambda env: True)
    assert verify.main(["--check-id", "c1"]) == verify.PASSED
    marker = capable.read_marker()
    assert marker["check"] == "core" and marker["claude_version"] == VERSION
    assert marker["core_version"] == capable.CORE_VERSION
    assert marker["suite_version"] == 8 and marker["full_date"].startswith("2026-01-01")
    assert marker["scope"] == "all_allowed_folders" and marker["course_folders"] is True
    assert marker["chat_folders"] is True and marker["program_folder"] is True


def test_a_core_pass_alone_never_switches_the_mode_on(sdk, monkeypatch):
    fake_check(monkeypatch, sdk, [verify.Probe("sandbox: sudo", True)])
    assert verify.main([]) == verify.PASSED
    assert not available(sdk)  # never verified by a full check


def test_api_refuses_capable_on_the_claude_p_runner(client, auth):
    signed()
    response = client.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"})
    assert response.status_code == 503 and "LIFEOS_RUNNER=sdk" in response.json()["message"]
