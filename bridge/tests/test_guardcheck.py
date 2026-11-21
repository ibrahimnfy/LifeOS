"""Guardrails re-verify themselves after every update (#107). Stand-ins only: no real check."""

import asyncio
import dataclasses
import json

import pytest

from lifeos_bridge import capable
from lifeos_bridge.claude import ClaudeError

VERSION = "fake-claude 1.0"


def signed(runner="cli", **fields):
    data = {
        "runner": runner,
        "claude_version": VERSION,
        "sdk_version": capable.sdk_version(),
        "all_passed": True,
        "scope": "all_allowed_folders",
        "suite_version": capable.SUITE_VERSION,
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
    with monkeypatch.context() as patch:  # never monkeypatch.undo(): it would end the isolation
        patch.setattr(capable, "installed_sdk_version", lambda: "9.9.9")
        assert not available(sdk_settings, "sdk")  # the package changed under the running bridge
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
