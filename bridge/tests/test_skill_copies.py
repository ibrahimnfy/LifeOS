"""The copies of skills are readable by the run and never writable (#171). Stand-ins only."""

import dataclasses
import json
import subprocess
import sys

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import capable, claude, skills, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError
from lifeos_bridge.config import ConfigError, load_settings

VERSION = "fake-claude 1.0"


def personal(settings, name="review"):
    skill = settings.personal_claude_dir / "skills" / name
    (skill / "scripts").mkdir(parents=True)
    (skill / "SKILL.md").write_text("---\ndescription: Review.\n---\nRun scripts/check.sh.\n")
    (skill / "reference.md").write_text("The rules.\n")
    (skill / "scripts" / "check.sh").write_text("#!/bin/sh\necho ok\n")
    (skill / "scripts" / "check.sh").chmod(0o755)
    (skill / "SKILL.md").chmod(0o755)  # a skill's text never becomes a program
    return skill


def folder_skill(folder, name="tidy"):
    skill = folder / ".claude" / "skills" / name
    skill.mkdir(parents=True)
    (skill / "SKILL.md").write_text("---\ndescription: Tidy up.\n---\nTidy.\n")
    (skill / "notes.txt").write_text("notes\n")


def option(args, name):
    return args[args.index(name) + 1] if name in args else None


def options(args, name):
    return [args[i + 1] for i, arg in enumerate(args) if arg == name]


def modes(copy):
    return {p.relative_to(copy).as_posix(): p.stat().st_mode & 0o777 for p in copy.rglob("*")}


# ------------------------------------------------------------------- where the copies are


def test_the_copies_have_a_folder_of_their_own_outside_the_private_settings(settings, tmp_path):
    home = tmp_path / "home"
    assert settings.skill_copies_dir == home / ".local" / "share" / "lifeos" / "skill-copies"
    personal(settings)
    folder_skill(settings.ask_dir)
    # Copies of an older bridge, in the private settings folder, are removed.
    legacy = capable.config_dir() / "skill-snapshots" / "personal" / "0123"
    legacy.mkdir(parents=True)
    offer = skills.for_run(settings, settings.ask_dir, "s", folder_skills=True, personal=True)
    for copy in (offer.folder_dir, offer.personal_dir):
        assert copy.is_relative_to(settings.skill_copies_dir)
        assert not copy.is_relative_to(capable.config_dir())
    assert not (capable.config_dir() / "skill-snapshots").exists()
    assert settings.skill_copies_dir.stat().st_mode & 0o777 == 0o700


def test_the_folder_of_the_copies_can_be_set(tmp_path, monkeypatch):
    env = tmp_path / "config" / "env"
    env.write_text("LIFEOS_BRIDGE_TOKEN=" + "t" * 40 + "\n")
    env.chmod(0o600)
    assert (
        load_settings().skill_copies_dir == tmp_path / "home" / ".local/share/lifeos/skill-copies"
    )
    monkeypatch.setenv("LIFEOS_SKILL_COPIES_DIR", "~/copies")
    assert load_settings().skill_copies_dir == tmp_path / "home" / "copies"
    monkeypatch.setenv("LIFEOS_SKILL_COPIES_DIR", "copies")
    with pytest.raises(ConfigError):
        load_settings()


def test_files_of_a_copy_are_read_only_and_a_script_keeps_its_execute_bit(settings):
    personal(settings)
    copy = skills.personal_snapshot(settings.personal_claude_dir, settings.skill_copies_dir)
    assert modes(copy) == {
        ".claude-plugin": 0o700,
        ".claude-plugin/plugin.json": 0o400,
        "skills": 0o700,
        "skills/review": 0o700,
        "skills/review/SKILL.md": 0o400,
        "skills/review/reference.md": 0o400,
        "skills/review/scripts": 0o700,
        "skills/review/scripts/check.sh": 0o500,
    }
    assert (copy / "skills/review/scripts/check.sh").read_text() == "#!/bin/sh\necho ok\n"
    # A script that is made executable later is another copy.
    (settings.personal_claude_dir / "skills/review/scripts/check.sh").chmod(0o644)
    again = skills.personal_snapshot(settings.personal_claude_dir, settings.skill_copies_dir)
    assert again != copy and modes(again)["skills/review/scripts/check.sh"] == 0o400
    # The bridge can still remove a copy it made.
    old = 1_000_000_000
    import os

    os.utime(copy, (old, old))
    skills.personal_snapshot(settings.personal_claude_dir, settings.skill_copies_dir)
    assert not copy.exists()


# ------------------------------------------------------------------- what a run is given


def test_a_run_may_read_its_copies_and_never_write_them(settings):
    personal(settings)
    folder_skill(settings.ask_dir)
    offer = skills.for_run(settings, settings.ask_dir, "s", folder_skills=True, personal=True)
    copies = [offer.folder_dir, offer.personal_dir]
    policy = capable.build_policy(
        settings, settings.ask_dir, skills_dir=copies[0], personal_skills_dir=copies[1]
    )
    data = json.loads(policy.settings_json)
    files = data["sandbox"]["filesystem"]
    # Sandboxed commands: readable, never writable.
    assert {str(c) for c in copies} <= set(files["allowRead"])
    assert files["denyWrite"] == [str(c) for c in copies]
    assert str(capable.config_dir()) not in files["allowRead"]
    assert str(settings.skill_copies_dir) not in files["allowRead"]  # only the run's own copies
    assert files["denyRead"] == list(capable.SANDBOX_HIDDEN)
    # File tools: readable as added folders, every change denied by rule, no allow rule.
    flags = claude.run_flags(settings, session_id="s", resume=False, policy=policy)
    assert options(flags, "--add-dir") == [str(c) for c in copies]
    assert options(flags, "--plugin-dir") == [str(c) for c in copies]
    for copy in copies:
        assert f"Edit(/{copy}/**)" in data["permissions"]["deny"]
        assert not [rule for rule in data["permissions"]["allow"] if str(copy) in rule]
        assert not [
            r for r in data["permissions"]["deny"] if r.startswith("Read(") and str(copy) in r
        ]
    assert data["permissions"]["allow"] == [f"Edit(/{settings.ask_dir}/**)"]
    # Without copies nothing of this is in the settings.
    plain = json.loads(capable.build_policy(settings, settings.ask_dir).settings_json)
    assert "denyWrite" not in plain["sandbox"]["filesystem"]
    assert not [r for r in plain["permissions"]["deny"] if "skill-copies" in r]
    # A read-only run gets none of it.
    read_only = claude.run_flags(settings, session_id="s", resume=False)
    assert "--add-dir" not in read_only and "--plugin-dir" not in read_only


@pytest.mark.parametrize(
    "place", ["private", "inside private", "memory", "allowed", "around", "repository"]
)
def test_copies_in_a_wrong_place_are_refused(settings, tmp_path, place):
    project = tmp_path / "home" / "projects" / "alpha"
    project.mkdir(parents=True)
    settings = dataclasses.replace(settings, allowed_paths=(project,))
    copy = {
        "private": capable.config_dir(),
        "inside private": capable.config_dir() / "copies" / "x",
        "memory": settings.ask_dir / "copies" / "x",
        "allowed": project / "copies",
        "around": tmp_path / "home",  # the memory folder would lie inside the copy
        # The sandbox opens the root of a repository that holds a folder of the run.
        "repository": tmp_path / "home" / "checkout" / "copies" / "x",
    }[place]
    copy.mkdir(parents=True, exist_ok=True)
    if place == "repository":
        (tmp_path / "home" / "checkout" / ".git").mkdir()
        (tmp_path / "home" / "checkout" / ".git" / "HEAD").write_text("ref: refs/heads/main\n")
    with pytest.raises(ClaudeError) as caught:
        capable.build_policy(settings, settings.ask_dir, personal_skills_dir=copy)
    assert caught.value.code == "skills_unavailable" and caught.value.status == 503
    with pytest.raises(ClaudeError):
        capable.build_policy(settings, settings.ask_dir, skills_dir=copy)


def test_a_capable_run_through_the_api_is_given_its_copies_to_read(
    settings, ask_dir, auth, write_session
):
    data = {"runner": "cli", "claude_version": VERSION, "sdk_version": capable.sdk_version(),
            "all_passed": True, "scope": "all_allowed_folders",
            "suite_version": capable.suite_version("cli")}  # fmt: skip
    capable.marker_path("cli").write_text(json.dumps(capable.sign_marker(data)))
    personal(settings)
    folder_skill(ask_dir)
    session = write_session(ask_dir, [user_line(ask_dir, "first")])
    with TestClient(create_app(settings)) as client:
        client.patch(f"/sessions/{session}", headers=auth, json={"skills": True})
        url = f"/sessions/{session}/messages"
        assert (
            client.post(url, headers=auth, json={"prompt": "x", "mode": "capable"}).status_code
            == 200
        )
        assert client.post(url, headers=auth, json={"prompt": "y"}).status_code == 200
    log = ask_dir / ".fake-claude-calls.jsonl"
    capable_call, read_only = [json.loads(line) for line in log.read_text().splitlines()][-2:]
    copies = options(capable_call["args"], "--plugin-dir")
    assert len(copies) == 2 and options(capable_call["args"], "--add-dir") == copies
    assert all("/skill-copies/" in copy for copy in copies)
    files = json.loads(option(capable_call["args"], "--settings"))["sandbox"]["filesystem"]
    assert files["denyWrite"] == copies and set(copies) <= set(files["allowRead"])
    assert "--add-dir" not in read_only["args"] and "--plugin-dir" not in read_only["args"]


# ------------------------------------------------------------------- the probes


@pytest.fixture
def suite(settings):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    suite.canary_file.write_text(suite.canary)
    suite.prepare_skills()
    suite.prepare_personal()
    suite.prepare_skill_copies()
    return suite


def stand_in(
    suite, monkeypatch, *, report=True, reads=True, changed=(), readable=(), effect=None,
    shell_reads=True, writes=True,
):  # fmt: skip
    """A capable run that did the copy steps, as far as the arguments say."""
    suite.tries = 0

    def capable_run(steps, **_kwargs):
        suite.tries += 1
        offer = verify.skills_offer(suite.settings, suite.run_dir, "s", True)
        steps = suite.note_copies(offer, steps)
        assert "{" not in steps  # every placeholder was filled in
        given = json.loads((suite.run_dir / verify.COPIES_FILE).read_text())
        done = report(suite) if callable(report) else report
        if done:
            body = {
                "copies": {
                    kind: {
                        "read": suite.copy_marks[kind] if shell_reads else "unreadable: OSError",
                        "changed": list(changed),
                    }
                    for kind in given["copies"]
                },
                "elsewhere": {
                    name: {"blocked": name not in readable, "detail": "x"}
                    for name in given["elsewhere"]
                },
            }
            (suite.run_dir / verify.COPY_REPORT).write_text(json.dumps(body))
        if effect is not None:
            effect(suite, offer)
        uses, results = [], []
        if reads:
            for kind, copy in suite.copies_of_run.items():
                uses.append(
                    {"name": "Read", "input": {"file_path": f"{copy}/skills/x/reference.txt"}}
                )
                results.append(suite.copy_marks[kind])
        if writes:  # tried, and refused
            for copy in suite.copies_of_run.values():
                uses.append({"name": "Write", "input": {"file_path": f"{copy}/skills/x/added"}})
        folder = capable.audit_dir()
        folder.mkdir(parents=True, exist_ok=True)
        entry = {"session_id": "s", "tool": "Bash", "decision": "allowed",
                 "detail": {"command": f"python3 /copies/{verify.COPY_CHECK}"}}  # fmt: skip
        with (folder / "2026-11.jsonl").open("a") as handle:
            handle.write(json.dumps(entry) + "\n")
        return verify.Run(0, uses, results, "FINISHED", ["Read", "Bash", "Skill"], "", "s", [])

    monkeypatch.setattr(suite, "capable_run", capable_run)
    return suite


BY_COMMAND = "skill copies: a sandboxed command reads the supporting files of the run's skills"
AS_COMMAND = "skill copies: a script of a skill runs as a sandboxed command, in the audit log"
BY_TOOL = "skill copies: the file tools read the supporting files of the run's skills"
OWN_ONLY = "skill copies: nothing else became readable (private settings, another copy)"
SHELL_KEPT = "skill copies: a sandboxed command cannot change a copy"
UNCHANGED = "skill copies: no copy a probe run was given has changed (file tools or commands)"
TOOL_KEPT = "skill copies: the file tools cannot change a copy"
OUTSIDE = "skill copies: they lie outside the private settings folder"


def results(suite):
    return {p.name: p.passed for p in suite.probes}


def test_the_copy_probes_pass_when_reading_works_and_nothing_changed(suite, monkeypatch):
    stand_in(suite, monkeypatch)
    suite.probe_no_self_install()  # the run that carries the copy steps, switch on
    suite.probe_skill_copies(suite.copy_steps_run)
    assert list(results(suite))[1:] == [
        BY_COMMAND, AS_COMMAND, OWN_ONLY, SHELL_KEPT, BY_TOOL, TOOL_KEPT, UNCHANGED, OUTSIDE,
    ]  # fmt: skip
    assert all(results(suite).values()) and suite.tries == 1
    # Both kinds of copy were given to that run, with a supporting file each.
    given = json.loads((suite.run_dir / verify.COPIES_FILE).read_text())
    assert set(given["copies"]) == {"personal", "folder"}
    for kind, copy in given["copies"].items():
        text = (suite.settings.skill_copies_dir / copy["root"] / copy["reference"]).read_text()
        assert text.strip() == suite.copy_marks[kind]
    assert set(given["elsewhere"]) == {"private settings", "a copy the run was not given"}
    # The check removes its copies and the decoy again.
    suite.clean_outside()
    left = [p for p in suite.settings.skill_copies_dir.rglob("*") if p.is_file()]
    assert left == []


def test_the_copy_probes_catch_each_failure(suite, monkeypatch, settings):
    def tool_wrote(suite, offer):
        (offer.personal_dir / "skills" / verify.PERSONAL_PROBE / verify.COPY_ADDED).write_text("x")

    def loosened(suite, offer):
        (offer.folder_dir / "skills" / verify.FOLDER_PROBE_SKILL / "SKILL.md").chmod(0o600)

    cases = {
        SHELL_KEPT: {"changed": ["append to a file"]},
        OWN_ONLY: {"readable": ["private settings"]},
        BY_COMMAND: {"shell_reads": False},
        UNCHANGED: {"effect": tool_wrote},  # last: the written file stays in the copy
    }
    for name, arguments in cases.items():
        stand_in(suite, monkeypatch, **arguments)
        suite.probes.clear()
        suite.copy_states.clear()
        suite.probe_skill_copies()
        failed = [p.name for p in suite.probes if not p.passed and not p.inconclusive]
        assert name in failed, name
        if name != UNCHANGED:
            assert failed == [name]
        else:  # a file the file tool wrote into a copy fails both probes about it
            assert failed == [TOOL_KEPT, UNCHANGED]
    stand_in(suite, monkeypatch, readable=["a copy the run was not given"])
    suite.probes.clear()
    suite.probe_skill_copies()
    assert results(suite)[OWN_ONLY] is False
    stand_in(suite, monkeypatch, effect=loosened)
    suite.probes.clear()
    suite.copy_states.clear()
    suite.probe_skill_copies()
    assert results(suite)[UNCHANGED] is False  # also a changed mode counts


def test_copy_steps_the_model_did_not_do_are_asked_again_then_inconclusive(suite, monkeypatch):
    stand_in(suite, monkeypatch, report=False, reads=False)
    suite.probe_skill_copies()
    assert suite.tries == verify.SCENARIO_TRIES
    unclear = {p.name for p in suite.probes if p.inconclusive}
    assert unclear == {BY_COMMAND, AS_COMMAND, OWN_ONLY, SHELL_KEPT, BY_TOOL}
    assert results(suite)[TOOL_KEPT] is True  # the writes were tried, and nothing was written
    assert not any(p.passed for p in suite.probes if p.inconclusive)
    # What does not need the model's steps is judged all the same.
    assert results(suite)[UNCHANGED] is True and results(suite)[OUTSIDE] is True
    # Done at the second try: passes, and no third run.
    suite.probes.clear()
    stand_in(suite, monkeypatch, report=lambda suite: suite.tries == 2, reads=True)
    suite.probe_skill_copies()
    assert suite.tries == 2 and all(results(suite).values())
    assert not [p for p in suite.probes if p.inconclusive]
    # Only the reads by the file tools are missing: only that probe is inconclusive.
    suite.probes.clear()
    stand_in(suite, monkeypatch, reads=False)
    suite.probe_skill_copies()
    assert {p.name for p in suite.probes if p.inconclusive} == {BY_TOOL}
    # No write by a file tool was tried: asked again, and an unchanged copy is no pass.
    suite.probes.clear()
    stand_in(suite, monkeypatch, writes=False)
    suite.probe_skill_copies()
    assert suite.tries == verify.SCENARIO_TRIES
    assert {p.name for p in suite.probes if p.inconclusive} == {TOOL_KEPT}
    assert results(suite)[UNCHANGED] is True


def test_the_script_of_the_probe_reports_what_it_could_read_and_change(tmp_path):
    """The script itself, outside any sandbox: a copy that can be written is reported."""
    work, copy = tmp_path / "work", tmp_path / "copy"
    skill = copy / "skills" / "x"
    skill.mkdir(parents=True)
    work.mkdir()
    (skill / "reference.txt").write_text("the mark\n")
    (skill / "reference.txt").chmod(0o400)
    (tmp_path / "secret.txt").write_text("secret")
    config = {
        "copies": {"personal": {"root": str(copy), "reference": "skills/x/reference.txt"}},
        "elsewhere": {"here": str(tmp_path / "secret.txt"), "missing": str(tmp_path / "none")},
        "report": verify.COPY_REPORT,
    }
    (work / verify.COPIES_FILE).write_text(json.dumps(config))
    (work / "check.py").write_text(verify.COPY_CHECK_SCRIPT)
    done = subprocess.run(
        [sys.executable, "check.py"], cwd=work, capture_output=True, text=True, timeout=30
    )
    assert done.returncode == 0 and "COPY CHECK COMPLETED" in done.stdout
    assert "secret" not in done.stdout and "the mark" not in done.stdout
    report = json.loads((work / verify.COPY_REPORT).read_text())
    assert report["copies"]["personal"]["read"] == "the mark\n"
    changed = report["copies"]["personal"]["changed"]
    assert "create a file in the copy" in changed and "make a file writable and append" in changed
    assert "append to a file" not in changed  # the file itself was read-only
    assert report["elsewhere"]["here"]["blocked"] is False
    assert report["elsewhere"]["missing"]["blocked"] is True
    assert verify.tree_state(copy) != []


def test_the_state_of_a_copy_notices_content_modes_and_new_entries(tmp_path):
    copy = tmp_path / "copy"
    (copy / "skills").mkdir(parents=True)
    (copy / "skills" / "a.txt").write_text("a")
    before = verify.tree_state(copy)
    assert verify.tree_state(copy) == before
    (copy / "skills" / "a.txt").chmod(0o600)
    assert verify.tree_state(copy) != before
    (copy / "skills" / "a.txt").chmod(0o644)
    (copy / "skills" / "a.txt").write_text("b")
    assert verify.tree_state(copy) != before
    (copy / "skills" / "a.txt").write_text("a")
    (copy / "skills" / "new").mkdir()
    assert verify.tree_state(copy) != before
