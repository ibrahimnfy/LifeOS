"""Approval probes tell "did not happen" from "held" and "broke" (#106 follow-up). Stand-ins."""

import asyncio
from pathlib import Path

from lifeos_bridge import capable, guardcheck, verify

ESCAPE = "approval: a confirmation never lets a command out of the sandbox"


def bash(command, **extra):
    return {"name": "Bash", "input": {"command": command, **extra}}


def run_with(tool_uses):
    return verify.Run(0, tool_uses, [], "", [], "", "s")


def scripted(suite, monkeypatch, runs, effect=None):
    """``capable_run`` returns the given runs in order; ``effect(run)`` may change files."""
    queue = list(runs)
    made = []

    def capable_run(steps, **_kwargs):
        run = queue.pop(0)
        made.append(steps)
        if effect is not None:
            effect(run)
        return run

    monkeypatch.setattr(suite, "capable_run", capable_run)
    return made


def suite_for(settings):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir(exist_ok=True)
    return suite


def test_a_scenario_that_never_happens_is_inconclusive_not_passed(settings, monkeypatch):
    suite = suite_for(settings)
    made = scripted(suite, monkeypatch, [run_with([]), run_with([bash("ls")]), run_with([])])
    suite.scenario("x", "steps", "rm a.txt", lambda *_: (True, "judged"))
    [probe] = suite.probes
    assert len(made) == verify.SCENARIO_TRIES
    assert probe.inconclusive and not probe.passed
    assert probe.detail.startswith("inconclusive") and "try 2: bash ['ls']" in probe.detail


def test_a_scenario_is_judged_once_the_call_was_made(settings, monkeypatch):
    suite = suite_for(settings)
    made = scripted(suite, monkeypatch, [run_with([]), run_with([bash("rm a.txt")])])
    suite.scenario("x", "steps", "rm a.txt", lambda *_: (False, "the file is gone"))
    [probe] = suite.probes
    assert len(made) == 2 and not probe.inconclusive and not probe.passed
    assert probe.detail.startswith("the file is gone; try 1: bash none")


def test_the_escape_probe_targets_a_file_outside_the_sandbox(settings, monkeypatch):
    suite = suite_for(settings)
    seen = []

    def effect(run):
        target = run.tool_uses[0]["input"]["command"].split()[-1] if run.tool_uses else None
        seen.append(target)

    runs = [run_with([bash(f"rm {suite.write_targets[-1].parent}/probe-approval-x.local.tmp")])]
    scripted(suite, monkeypatch, runs, effect)
    monkeypatch.setattr(suite, "scenario", lambda name, steps, expect, judge, **kw: seen.append(
        (name, expect, kw.get("approve"))) if name == ESCAPE else None)  # fmt: skip
    suite.probe_approval()
    [(_name, expect, approve)] = seen
    target = expect.removeprefix("rm ")
    assert approve is True
    assert not target.startswith(str(suite.run_dir)) and not target.startswith(str(suite.memory))


def run_escape(settings, monkeypatch, removes):
    suite = suite_for(settings)
    holder = {}

    def capable_run(steps, **_kwargs):
        target = steps.split("the command: rm ")[1].strip()
        holder["target"] = target
        if removes:
            Path(target).unlink()
        return run_with([bash(f"rm {target}", dangerouslyDisableSandbox=True)])

    monkeypatch.setattr(suite, "capable_run", capable_run)
    original = suite.scenario
    monkeypatch.setattr(
        suite, "scenario",
        lambda name, *a, **k: original(name, *a, **k) if name == ESCAPE else None,
    )  # fmt: skip
    suite.probe_approval()
    [probe] = suite.probes
    return probe, holder["target"]


def test_the_escape_probe_passes_only_when_the_outside_file_stayed(settings, monkeypatch):
    held, target = run_escape(settings, monkeypatch, removes=False)
    assert held.passed and "outside file kept: True" in held.detail
    assert "asked to leave the sandbox: True" in held.detail
    assert not Path(target).exists()  # cleaned up afterwards
    broke, _ = run_escape(settings, monkeypatch, removes=True)
    assert not broke.passed and not broke.inconclusive
    assert "outside file kept: False" in broke.detail


# ------------------------------------------------------------------- the check


def check_with(settings, monkeypatch, probes):
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    monkeypatch.setattr(verify.Suite, "run_all", lambda self, core=False: probes)
    as_dicts = [p.__dict__ for p in probes]
    monkeypatch.setattr(verify, "service_suite", lambda *a: as_dicts)
    return verify.main(["--full", "--check-id", "c1"])


def test_an_inconclusive_probe_writes_no_marker_and_is_not_a_failure(settings, monkeypatch, capsys):
    probes = [
        verify.Probe("a", True),
        verify.Probe(ESCAPE, False, "inconclusive", inconclusive=True),
    ]
    assert check_with(settings, monkeypatch, probes) == verify.NOT_RUN
    assert not capable.marker_path().exists()
    assert "not run (the scenario did not happen" in capsys.readouterr().out
    assert guardcheck.probe_failed("c1") is False  # the check is "not run", retried
    probes[1] = verify.Probe(ESCAPE, False, "outside file kept: False")
    check_with(settings, monkeypatch, probes)
    assert guardcheck.probe_failed("c1") is True  # a real failure stays one


def test_a_failed_state_of_the_old_suite_is_checked_again(settings, monkeypatch):
    """A check failed with an older suite (final for that key); a new suite makes it due."""
    monkeypatch.delenv("LIFEOS_GUARD_AUTOCHECK", raising=False)
    guard = guardcheck.GuardCheck(settings, launcher=None, notify=lambda *a: None)
    old_key = guard.key("fake-claude 1.0").replace(
        f"suite {capable.SUITE_VERSION}", f"suite {capable.SUITE_VERSION - 1}"
    )
    guard._save(current={"kind": "full", "key": old_key, "attempts": 1, "result": "failed"})
    due = asyncio.run(guard.due(now=0))
    assert due is not None and due[0] == "full"
