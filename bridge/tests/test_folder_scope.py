"""Allowed folders as run folders (#180): the probe block that widens the marker's scope.
Stand-in runs only; the real CLI is never started here."""

import dataclasses
import json
from pathlib import Path

import pytest

from lifeos_bridge import capable, guardcheck, verify


def stub_reused_probes(monkeypatch, failing=None):
    """The reused probes of the suite, as if they ran in the scratch folder: each adds its
    names (one fails when asked to). What they test is covered by their own tests."""
    names = {
        "probe_sandbox": "sandbox: probe script ran",
        "probe_file_tools": "file tools: no write outside the run folder",
        "probe_gate": "gate: approval-required and never-allowed commands refused",
        "probe_project_files": "run folder: its own hook did not run",
    }
    ran = []

    def stub(method, name):
        def probe(self):
            ran.append((method, self.run_dir))
            self.add(name, name != failing, "seen")

        return probe

    for method, name in names.items():
        monkeypatch.setattr(verify.Suite, method, stub(method, name))
    return ran


def reach_run(*, leak_shell=False, leak_tool=False, script=True, write_call=True):
    """A stand-in for the reach run: what a sandbox that holds (or not) leaves behind."""

    def run(self, steps, **_how):
        folder = self.run_dir
        cfg = json.loads((folder / "reach-input.json").read_text())
        tool_target = next(line for line in steps.splitlines() if "Write tool" in line)
        tool_target = tool_target.split("create the file ")[1].split(" with")[0]
        if script:
            report = {"write the memory folder": {"blocked": False, "detail": "written"}}
            Path(cfg["memory_target"]).write_text("probe\n")
            report["read another allowed folder"] = {"blocked": False, "detail": "read 23"}
            if leak_shell:
                Path(cfg["other_target"]).write_text("probe\n")
                report["write another allowed folder"] = {"blocked": False, "detail": "written"}
            else:
                report["write another allowed folder"] = {"blocked": True, "detail": "EROFS"}
            (folder / "reach-report.json").write_text(json.dumps(report))
        uses, results = [], []
        if write_call:
            uses = [{"name": "Write", "input": {"file_path": tool_target}, "id": "w1"}]
            results = [{"id": "w1", "text": "denied", "error": not leak_tool}]
            if leak_tool:
                Path(tool_target).write_text("probe\n")
        return verify.Run(0, uses, [], "FINISHED", [], "", "s", results=results)

    return run


@pytest.fixture
def suite(settings):
    main = verify.Suite(settings)
    main.run_dir.mkdir()
    yield main
    main.retire()


def named(suite):
    return {p.name.removeprefix(verify.FOLDER_PROBE_PREFIX): p for p in suite.probes
            if p.name.startswith(verify.FOLDER_PROBE_PREFIX)}  # fmt: skip


def test_the_reused_probes_run_in_a_scratch_allowed_folder(suite, monkeypatch):
    ran = stub_reused_probes(monkeypatch)
    monkeypatch.setattr(verify.Suite, "capable_run", reach_run())
    verify.probe_allowed_folders(suite, 0)
    base = verify.folder_scratch()
    assert [method for method, _ in ran] == [
        "probe_sandbox", "probe_file_tools", "probe_gate", "probe_project_files"
    ]  # fmt: skip
    assert {folder for _, folder in ran} == {base / "run"}
    found = named(suite)
    assert all(p.passed and not p.inconclusive for p in found.values()), found
    assert "reach: the memory folder is writable, as the policy says" in found
    assert "reach: another allowed folder is not writable (file tool)" in found
    assert not base.exists()  # the scratch folders are gone
    assert not list(suite.run_dir.glob("folder-reach-*"))  # and the memory folder's file
    environments = {"terminal": [dataclasses.asdict(p) for p in suite.probes]}
    assert verify.folder_scope_probed(environments)


@pytest.mark.parametrize("leak", ["shell", "tool"])
def test_a_control_that_opens_another_folder_fails_and_names_it(suite, monkeypatch, leak):
    stub_reused_probes(monkeypatch)
    run = reach_run(leak_shell=leak == "shell", leak_tool=leak == "tool")
    monkeypatch.setattr(verify.Suite, "capable_run", run)
    verify.probe_allowed_folders(suite, 0)
    found = named(suite)
    way = "shell" if leak == "shell" else "file tool"
    name = f"reach: another allowed folder is not writable ({way})"
    assert found[name].passed is False and found[name].detail
    environments = {"terminal": [dataclasses.asdict(p) for p in suite.probes]}
    assert not verify.folder_scope_probed(environments)


def test_a_failing_reused_probe_keeps_the_scope_narrow(suite, monkeypatch):
    stub_reused_probes(
        monkeypatch, failing="gate: approval-required and never-allowed commands refused"
    )
    monkeypatch.setattr(verify.Suite, "capable_run", reach_run())
    verify.probe_allowed_folders(suite, 0)
    environments = {"terminal": [dataclasses.asdict(p) for p in suite.probes]}
    assert not verify.folder_scope_probed(environments)


def test_steps_that_were_not_made_are_not_run_never_passed(suite, monkeypatch):
    stub_reused_probes(monkeypatch)
    monkeypatch.setattr(verify.Suite, "capable_run", reach_run(script=False, write_call=False))
    verify.probe_allowed_folders(suite, 0)
    found = named(suite)
    assert found["reach: the script ran"].inconclusive
    assert found["reach: another allowed folder is not writable (file tool)"].inconclusive
    environments = {"terminal": [dataclasses.asdict(p) for p in suite.probes]}
    assert not verify.folder_scope_probed(environments)


def test_a_folder_that_is_not_the_checks_own_is_never_touched(suite, monkeypatch, tmp_path):
    ran = stub_reused_probes(monkeypatch)
    base = verify.folder_scratch()
    base.mkdir()
    (base / "mine.txt").write_text("the owner's\n")
    verify.probe_allowed_folders(suite, 0)
    assert ran == [] and (base / "mine.txt").exists()
    [probe] = suite.probes
    assert probe.inconclusive and "not the check's own" in probe.detail


def test_the_run_folder_gets_the_policy_of_an_allowed_folder(suite):
    other = verify.prepare_folders(verify.folder_scratch())
    run_folder = other.parent / "run"
    run_folder.mkdir()
    verify.make_git_root(run_folder)
    inner = verify.folder_suite(suite, run_folder, other)
    policy = capable.build_policy(inner.settings, run_folder)
    data = json.loads(policy.settings_json)
    allow = data["permissions"]["allow"]
    memory = suite.settings.ask_dir.resolve()
    assert f"Edit(/{run_folder}/**)" in allow and f"Edit(/{memory}/**)" in allow
    assert not any(str(other) in rule for rule in allow)  # readable only
    assert str(other) in data["sandbox"]["filesystem"]["allowRead"]
    assert inner.canary_file != suite.canary_file


def check(settings, monkeypatch, folder_probes):
    probes = [verify.Probe("sandbox: probe script ran", True), *folder_probes]
    as_dicts = [dataclasses.asdict(p) for p in probes]
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    monkeypatch.setattr(verify.Suite, "run_all", lambda self: probes)
    monkeypatch.setattr(verify, "service_suite", lambda *a: as_dicts)
    return verify.main(["--check-id", "c1"])


def test_the_scope_is_widened_only_when_the_block_passed(settings, monkeypatch):
    prefix = verify.FOLDER_PROBE_PREFIX
    passing = [verify.Probe(verify.FOLDER_SANITY, True), verify.Probe(prefix + "reach: x", True)]
    assert check(settings, monkeypatch, passing) == 0
    marker = json.loads(capable.marker_path("cli").read_text())
    assert marker["scope"] == "all_allowed_folders" and marker["all_passed"] is True


@pytest.mark.parametrize("inconclusive", [False, True])
def test_a_block_that_did_not_pass_leaves_everything_else_unchanged(
    settings, monkeypatch, inconclusive
):
    prefix = verify.FOLDER_PROBE_PREFIX
    reach = verify.Probe(prefix + "reach: x", False, "written", inconclusive=inconclusive)
    failing = [verify.Probe(verify.FOLDER_SANITY, True), reach]
    assert check(settings, monkeypatch, failing) == 0  # the capable mode is on, as before
    marker = json.loads(capable.marker_path("cli").read_text())
    assert marker["scope"] == "memory_folder" and marker["all_passed"] is True
    assert guardcheck.probe_failed("cli", "c1") is False  # never "failed" for it
