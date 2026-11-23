"""The course-folder probes of the guardrail suite (#179), judged on stand-in runs.

The real runs (both runners, and one control run without the course rules) are in
docs/permission-policy.md, section 9.13.
"""

import json

import pytest

from lifeos_bridge import verify
from test_chat_probes import call


@pytest.fixture
def suite(settings):
    made = verify.Suite(settings, "cli")
    made.run_dir.mkdir(parents=True)
    made.config.mkdir(exist_ok=True)
    return made


def by_name(suite):
    return {p.name.removeprefix(verify.COURSE_PROBE_PREFIX): p for p in suite.probes}


def scripted(monkeypatch, *, writable=False, script=True, loaded=False):
    """``course_run`` without a model, as the real runs went when every rule held; with
    ``writable`` as in the control run (the course rules dropped): every change worked."""
    runs = []

    def course_run(suite, scratch, steps, *, capable_mode=True, mutate=None):
        runs.append(capable_mode)
        run = verify.Run(0, [], [], "FINISHED", ["Read", "Glob", "Grep"], "", f"s-{len(runs)}")
        if capable_mode:
            run.init_tools += ["Bash", "Edit", "Write"]
        if loaded:
            run.init_commands.append(scratch.skill)
        folders = scratch.folders
        if capable_mode and script:
            report = {
                verify.SANITY: {"blocked": False, "detail": "written"},
                # another course's folder: hidden (the sandbox shows an empty stand-in)
                "read_elsewhere": {"blocked": True, "detail": "FileNotFoundError"},
                "list_elsewhere": {"blocked": False, "detail": "entries: "},
            }
            for where, folder in folders.items():
                report[f"read_{where}"] = {
                    "blocked": False,
                    "detail": "read 30 characters; the notes",
                }
                for name in verify.COURSE_CHANGES:
                    report[f"{name}_{where}"] = {"blocked": not writable, "detail": "seen"}
                if writable:
                    (folder / "created.txt").write_text("probe")
            (suite.run_dir / verify.COURSE_REPORT).write_text(json.dumps(report))
        for where, folder in folders.items():
            if capable_mode or where == "outside":
                call(run, "Read", {"file_path": str(folder / "notes.txt")}, scratch.notes)
            if capable_mode:
                refused = "<tool_use_error>File is in a directory that is denied</tool_use_error>"
                worked = writable and where == "inside"
                call(run, "Write", {"file_path": str(folder / "created-by-tool.txt")},
                     "File created" if worked else refused, error=not worked)  # fmt: skip
                call(run, "Edit", {"file_path": str(folder / "notes.txt")},
                     "Edited" if worked else refused, error=not worked)  # fmt: skip
        if capable_mode:
            other = scratch.elsewhere / "notes.txt"
            call(run, "Read", {"file_path": str(other)}, "denied", error=True)
            ok = suite.run_dir / verify.COURSE_TOOL_OK
            call(run, "Write", {"file_path": str(ok)}, "File created")
        return run

    monkeypatch.setattr(verify, "course_run", course_run)
    return runs


def test_every_probe_passes_when_the_rules_hold(suite, monkeypatch):
    runs = scripted(monkeypatch)
    verify.probe_course_folders(suite)
    assert runs == [True, False]  # a capable run and a read-only run
    failed = [p.name for p in suite.probes if not p.passed]
    assert failed == [], [p.detail for p in suite.probes if not p.passed]
    names = by_name(suite)
    assert names["the probe script ran"].passed
    assert names["never the folder of a capable run"].passed
    assert names["readable, write-denied and edit-denied in the policy"].passed
    # everything it made is gone again, also the scratch folder in the chat root
    root = suite.settings.chat_root
    assert not any(p.name.startswith(verify.COURSE_SCRATCH) for p in root.iterdir())
    assert not (suite.run_dir / verify.COURSE_INSIDE).exists()
    assert names["another course's folder is not readable by command"].passed
    assert names["another course's folder is not readable by file tool"].passed
    results = {"terminal": [p.__dict__ for p in suite.probes]}
    assert verify.course_folders_probed(results) is True


def test_a_control_that_makes_the_folder_writable_fails(suite, monkeypatch):
    scripted(monkeypatch, writable=True)
    verify.probe_course_folders(suite)
    failed = {name for name, p in by_name(suite).items() if not p.passed and not p.inconclusive}
    assert {
        "no change by command (outside the run folder)",
        "no change by command (inside the run folder)",
        "Write by file tool is refused (inside the run folder)",
        "Edit by file tool is refused (inside the run folder)",
        "the course folders are unchanged",
    } <= failed


def test_something_loaded_from_the_folder_fails(suite, monkeypatch):
    scripted(monkeypatch, loaded=True)
    verify.probe_course_folders(suite)
    probe = next(p for p in suite.probes if p.name == verify.COURSE_NOT_LOADED)
    assert not probe.passed and "course-skill-" in probe.detail


def test_a_script_the_model_skipped_is_inconclusive_never_passed(suite, monkeypatch):
    runs = scripted(monkeypatch, script=False)
    verify.probe_course_folders(suite)
    assert runs == [True] * verify.CHAT_SCRIPT_TRIES  # asked again once, then "not run"
    sanity = next(p for p in suite.probes if p.name == verify.COURSE_SANITY)
    assert sanity.inconclusive and not sanity.passed
    results = {"terminal": [p.__dict__ for p in suite.probes]}
    assert verify.course_folders_probed(results) is False


def test_the_marker_records_the_course_folder_probes(settings, monkeypatch, isolated_config):
    from lifeos_bridge import capable

    settings.ask_dir.mkdir(exist_ok=True)
    monkeypatch.setattr(verify, "load_settings", lambda: settings)

    def check(probes):
        monkeypatch.setattr(verify.Suite, "run_all", lambda self: probes)
        monkeypatch.setattr(verify, "service_suite", lambda *a: [p.__dict__ for p in probes])
        assert verify.main(["--runner", "cli", "--check-id", "c1"]) == 0
        return json.loads(capable.marker_path("cli").read_text())

    marker = check([verify.Probe("a", True), verify.Probe(verify.COURSE_SANITY, True)])
    assert marker["course_folders"] is True and capable.course_folders_available("cli")
    marker = check([verify.Probe("a", True), verify.Probe(verify.COURSE_NOT_CHECKED, True, "why")])
    assert marker["course_folders"] is False and not capable.course_folders_available("cli")
