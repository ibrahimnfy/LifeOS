"""The personal-skill probe writes nothing unless the owner allowed it (#91). Stand-ins only."""

import asyncio
import dataclasses
import json

import pytest

from lifeos_bridge import capable, guardcheck, verify
from lifeos_bridge.claude import ClaudeError
from lifeos_bridge.config import load_settings

VERSION = "fake-claude 1.0"


def good_run(**changes):
    run = verify.Run(0, [{"name": "Skill", "input": {}}], [], "", ["Read", "Bash", "Skill"], "",
                     "s", ["folder:approve-probe", "folder:shell-probe", "compact"])  # fmt: skip
    return dataclasses.replace(run, **changes)


READ_ONLY_RUN = verify.Run(0, [], [], "", ["Read", "Glob", "Grep"], "", "s", [])


def personal_dir(settings):
    return settings.ask_dir.parent / ".claude" / "skills"  # HOME is the test's home


def run_probe(settings, monkeypatch, run):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    suite.prepare_skills()
    monkeypatch.setattr(suite, "capable_run", lambda *a, **k: run)
    monkeypatch.setattr(verify, "execute", lambda *a, **k: READ_ONLY_RUN)
    monkeypatch.setattr(verify, "sdk_execute", lambda *a, **k: READ_ONLY_RUN)
    suite.probe_skills()
    return suite


def personal_probe(suite):
    [probe] = [p for p in suite.probes if p.name == verify.PERSONAL_PROBE_NAME]
    return probe


def test_off_by_default_and_nothing_is_written(settings, monkeypatch, tmp_path):
    monkeypatch.delenv("LIFEOS_GUARD_PERSONAL_SKILL_PROBE", raising=False)
    (tmp_path / "config" / "env").write_text("LIFEOS_BRIDGE_TOKEN=" + "t" * 40 + "\n")
    (tmp_path / "config" / "env").chmod(0o600)
    assert load_settings().guard_personal_skill_probe is False
    monkeypatch.setenv("LIFEOS_GUARD_PERSONAL_SKILL_PROBE", "on")
    assert load_settings().guard_personal_skill_probe is True
    suite = run_probe(settings, monkeypatch, good_run())
    assert not personal_dir(settings).exists()  # not even the folder was created
    suite.clean_outside()


def test_existing_personal_skills_none_offered_is_verified(settings, monkeypatch):
    (personal_dir(settings) / "ghost").mkdir(parents=True)
    probe = personal_probe(run_probe(settings, monkeypatch, good_run()))
    assert probe.passed and probe.verified
    assert sorted(p.name for p in personal_dir(settings).iterdir()) == ["ghost"]  # untouched


def test_an_offered_personal_skill_is_a_failure(settings, monkeypatch):
    (personal_dir(settings) / "ghost").mkdir(parents=True)
    leaked = good_run(init_commands=["folder:shell-probe", "ghost"])
    probe = personal_probe(run_probe(settings, monkeypatch, leaked))
    assert not probe.passed and probe.verified


def test_nothing_to_test_with_is_not_verifiable_not_failed(settings, monkeypatch):
    probe = personal_probe(run_probe(settings, monkeypatch, good_run()))
    assert probe.passed and not probe.verified
    assert "not verifiable" in probe.detail


def test_allowed_the_probe_skill_is_written_and_removed(settings, monkeypatch):
    allowed = dataclasses.replace(settings, guard_personal_skill_probe=True)
    leftover = personal_dir(settings) / verify.PERSONAL_PROBE_SKILL
    leftover.mkdir(parents=True)
    (leftover / "old.txt").write_text("left by a crashed check")
    suite = run_probe(allowed, monkeypatch, good_run())
    assert (leftover / "SKILL.md").exists() and not (leftover / "old.txt").exists()
    probe = personal_probe(suite)
    assert probe.passed and probe.verified
    suite.clean_outside()
    assert not leftover.exists()


# ------------------------------------------------------- what the check records and enables


def check_with(settings, monkeypatch, probes):
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    monkeypatch.setattr(verify.Suite, "run_all", lambda self: probes)
    as_dicts = [dataclasses.asdict(p) for p in probes]
    monkeypatch.setattr(verify, "service_suite", lambda *a: as_dicts)
    return verify.main([])


def test_an_unverifiable_probe_passes_the_check_but_keeps_skills_off(settings, monkeypatch):
    unverified = verify.Probe(verify.PERSONAL_PROBE_NAME, True, "x", verified=False)
    assert check_with(settings, monkeypatch, [verify.Probe("a", True), unverified]) == 0
    marker = json.loads(capable.marker_path("cli").read_text())
    assert marker["all_passed"] is True and marker["skills_verified"] is False
    assert marker["skills_unverified_reason"] == capable.SKILLS_UNVERIFIED_REASON
    assert marker["personal_skill_probe"] is False
    asyncio.run(capable.check_available(settings))  # the capable mode is on
    with pytest.raises(ClaudeError) as caught:
        asyncio.run(capable.skills_available(settings))
    assert caught.value.code == "skills_unavailable"
    assert caught.value.message == capable.SKILLS_UNVERIFIED_REASON


def test_a_verified_check_enables_skills(settings, monkeypatch):
    assert check_with(settings, monkeypatch, [verify.Probe("a", True)]) == 0
    marker = json.loads(capable.marker_path("cli").read_text())
    assert marker["skills_verified"] is True and marker["skills_unverified_reason"] is None
    asyncio.run(capable.skills_available(settings))


def test_unverified_is_final_until_the_setting_changes(settings, monkeypatch):
    monkeypatch.delenv("LIFEOS_GUARD_AUTOCHECK", raising=False)
    unverified = verify.Probe(verify.PERSONAL_PROBE_NAME, True, "x", verified=False)
    check_with(settings, monkeypatch, [unverified])
    guardcheck.results_path("cli").write_text("{}")  # checked before
    sdk_marker = json.loads(capable.marker_path("cli").read_text())
    sdk_marker["runner"] = "sdk"
    sdk_marker["suite_version"] = capable.suite_version("sdk")
    capable.marker_path("sdk").write_text(json.dumps(capable.sign_marker(sdk_marker)))
    launched = []

    async def launcher(runner, check_id):
        launched.append(runner)
        return 0

    def tick(the_settings):
        guard = guardcheck.GuardCheck(the_settings, launcher=launcher, notify=lambda *a: None)

        async def go():
            await guard.tick()
            if guard.task is not None:
                await guard.task

        asyncio.run(go())

    tick(settings)
    tick(settings)
    assert launched == []  # no loop: final for these versions and this setting
    tick(dataclasses.replace(settings, guard_personal_skill_probe=True))
    assert launched == ["cli", "sdk"]  # the owner allowed the probe skill: checked again
