"""The LifeOS program folder as an allowed folder (#180): its rules, and what the bridge
runs by itself. Stand-in claude and scratch folders only; the real checkout is never used."""

import asyncio
import dataclasses
import json
import subprocess

import pytest

from lifeos_bridge import capable, claude, guardcheck, notifications, paths, program, verify
from lifeos_bridge.claude import ClaudeError


@pytest.fixture
def repo(tmp_path, monkeypatch):
    """A scratch program folder: a git root with the layout of the real one."""
    folder = tmp_path / "home" / "lifeos"
    (folder / "bridge" / "src").mkdir(parents=True)
    (folder / "bridge" / ".venv").mkdir()
    git = folder / ".git"
    (git / "refs" / "heads").mkdir(parents=True)
    (git / "HEAD").write_text("ref: refs/heads/main\n")
    (git / "refs" / "heads" / "main").write_text("a" * 40 + "\n")
    (folder / ".tools" / "android-user").mkdir(parents=True)
    # Another agent's worktree: a nested repository (a ``.git`` file) inside the root.
    worktree = folder / ".claude" / "worktrees" / "agent-1"
    worktree.mkdir(parents=True)
    (worktree / ".git").write_text(f"gitdir: {git}/worktrees/agent-1\n")
    monkeypatch.setattr(paths, "repo_dir", lambda: folder.resolve())
    return folder.resolve()


@pytest.fixture
def allowed(settings, repo):
    return dataclasses.replace(settings, allowed_paths=(repo,))


def sandbox_of(policy):
    return json.loads(policy.settings_json)


def test_a_run_in_the_program_folder_gets_its_rules(allowed, repo):
    policy = capable.build_policy(allowed, repo)
    data = sandbox_of(policy)
    files = data["sandbox"]["filesystem"]
    root = "/" + str(repo)
    # hidden: neither readable nor writable, by commands and by file tools
    for name in (".tools", ".claude"):
        assert str(repo / name) in files["denyRead"] and str(repo / name) in files["denyWrite"]
        assert f"Read({root}/{name}/**)" in data["permissions"]["deny"]
    # protected: readable, never written
    for name in ("bridge/.venv", ".git/hooks", ".git/info", ".git/worktrees", ".git/modules"):
        assert str(repo / name) in files["denyWrite"] and str(repo / name) not in files["denyRead"]
        assert f"Edit({root}/{name}/**)" in data["permissions"]["deny"]
    for name in (".git/config", ".git/config.worktree"):
        assert str(repo / name) in files["denyWrite"]
        assert f"Edit({root}/{name})" in data["permissions"]["deny"]
    # the folder itself is the run's to work in (its nested worktrees do not make it
    # "a folder inside another repository": it is the root, #139)
    assert f"Edit({root}/**)" in data["permissions"]["allow"]
    assert str(repo) in files["allowRead"]


def test_every_capable_run_keeps_out_of_the_hidden_parts(allowed, repo):
    """A run elsewhere can read the allowed folders: the hidden parts stay hidden."""
    data = sandbox_of(capable.build_policy(allowed, allowed.ask_dir))
    assert str(repo / ".tools") in data["sandbox"]["filesystem"]["denyRead"]
    assert str(repo / ".git/hooks") in data["sandbox"]["filesystem"]["denyWrite"]


def test_nothing_changes_while_the_program_folder_is_not_allowed(settings, repo):
    data = sandbox_of(capable.build_policy(settings, settings.ask_dir))
    assert str(repo / ".tools") not in data["sandbox"]["filesystem"]["denyRead"]
    flags = claude.run_flags(settings, session_id="s", resume=False)
    assert "--settings" not in flags


def test_read_only_runs_get_the_deny_rules_for_their_file_tools(allowed, repo):
    flags = claude.run_flags(allowed, session_id="s", resume=False)
    hidden = json.loads(flags[flags.index("--settings") + 1])
    assert hidden == {"permissions": {"deny": program.deny_rules(repo)}}
    assert f"Read(/{repo}/.tools/**)" in hidden["permissions"]["deny"]
    assert "sandbox" not in hidden  # read-only runs have no shell


def test_only_the_folder_itself_never_inside_or_around(allowed, repo):
    with pytest.raises(ClaudeError) as inside:
        capable.build_policy(dataclasses.replace(allowed, allowed_paths=(repo / "bridge",)),
                             repo / "bridge")  # fmt: skip
    assert inside.value.code == "folder_not_allowed"
    assert "itself" in paths.refusal_reason(repo / "bridge")
    assert "hidden" in paths.refusal_reason(repo / ".claude" / "worktrees" / "agent-1")


def marker(config, runner="cli", **extra):
    data = {"runner": runner, "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "memory_folder",
            "suite_version": capable.SUITE_VERSIONS[runner], **extra}  # fmt: skip
    capable.marker_path(runner).write_text(json.dumps(capable.sign_marker(data)))


def test_capable_runs_there_need_both_scope_blocks(allowed, repo, isolated_config):
    def check(settings, folder):
        asyncio.run(capable.check_available(settings, folder))

    everywhere = "all_allowed_folders"
    other = allowed.ask_dir.parent / "project"
    other.mkdir()
    both = dataclasses.replace(allowed, allowed_paths=(repo, other))
    marker(isolated_config)  # memory folder only, no program-folder probes
    for folder in (repo, other):
        with pytest.raises(ClaudeError) as refused:
            check(both, folder)
    assert "memory folder only" in refused.value.message
    # the program folder's own probes alone are not enough...
    marker(isolated_config, program_folder=True)
    with pytest.raises(ClaudeError) as refused:
        check(both, repo)
    assert "program folder" in refused.value.message
    # ...nor the allowed-folder block alone: other folders work, the program folder not
    marker(isolated_config, scope=everywhere)
    check(both, other)
    with pytest.raises(ClaudeError):
        check(both, repo)
    older = capable.PROGRAM_SUITE_VERSIONS["cli"] - 1
    marker(isolated_config, scope=everywhere, program_folder=True, suite_version=older)
    with pytest.raises(ClaudeError):
        check(both, repo)
    marker(isolated_config, scope=everywhere, program_folder=True)
    check(both, repo)
    check(both, other)


def test_the_commit_is_read_from_gits_files(tmp_path):
    folder = tmp_path / "r"
    git = folder / ".git"
    (git / "refs" / "heads").mkdir(parents=True)
    (git / "HEAD").write_text("ref: refs/heads/main\n")
    assert program.head_commit(folder) is None
    (git / "packed-refs").write_text("# pack-refs\n" + "b" * 40 + " refs/heads/main\n")
    assert program.head_commit(folder) == "b" * 40
    (git / "refs" / "heads" / "main").write_text("c" * 40 + "\n")
    assert program.head_commit(folder) == "c" * 40
    (git / "HEAD").write_text("d" * 40 + "\n")
    assert program.head_commit(folder) == "d" * 40
    (git / "HEAD").write_text("ref: refs/../../etc\n")
    assert program.head_commit(folder) is None
    # a worktree: its own HEAD, the branches in the common folder
    tree = tmp_path / "tree"
    tree.mkdir()
    own = git / "worktrees" / "tree"
    own.mkdir(parents=True)
    (own / "HEAD").write_text("ref: refs/heads/main\n")
    (own / "commondir").write_text("../..\n")
    (tree / ".git").write_text(f"gitdir: {own}\n")
    assert program.head_commit(tree) == "c" * 40
    assert program.head_commit(None) is None


def test_the_gate_runs_from_a_private_copy_with_the_standard_library_only(settings):
    program.pin()
    base = program.gate_base()
    assert base is not None and base[1:3] == ["-I", "-S"]
    copy = base[3]
    assert copy.startswith(str(settings.ask_dir.parent.parent / "config" / "pinned"))
    hook = capable.gate_command()
    assert hook.startswith(" ".join(base[:3])) and "--audit-dir" in hook
    # it works as it is: an obvious deletion is refused, a plain command allowed
    payload = {"session_id": "s", "cwd": str(settings.ask_dir), "hook_event_name": "PreToolUse",
               "tool_name": "Bash", "tool_input": {"command": "rm -rf notes"}}  # fmt: skip
    done = subprocess.run(  # noqa: S603
        [*base, "--audit-dir", str(capable.audit_dir())], input=json.dumps(payload),
        capture_output=True, text=True, timeout=30, check=False,
    )  # fmt: skip
    assert done.returncode == 2, done.stderr
    payload["tool_input"] = {"command": "ls"}
    done = subprocess.run(  # noqa: S603
        [*base, "--audit-dir", str(capable.audit_dir())], input=json.dumps(payload),
        capture_output=True, text=True, timeout=30, check=False,
    )  # fmt: skip
    assert done.returncode == 0, done.stderr


def test_notifications_use_the_copy_of_notify_sh_taken_at_start(monkeypatch):
    program.pin()
    monkeypatch.delenv("LIFEOS_NOTIFY_SCRIPT")
    pinned = program.notify_script()
    assert pinned is not None and pinned.parent.name == "pinned"
    assert notifications.default_script() == pinned
    monkeypatch.setenv("LIFEOS_NOTIFY_SCRIPT", "off")
    assert notifications.default_script() is None


def test_every_module_is_imported_at_start():
    assert program.import_all() == []


def test_the_automatic_check_waits_while_the_code_differs(settings, monkeypatch):
    monkeypatch.setenv("LIFEOS_GUARD_AUTOCHECK", "on")
    program.pin()
    check = guardcheck.GuardCheck(settings, launcher=None, notify=lambda *_: None)
    asked = []

    async def due(now):
        asked.append(now)
        return []

    monkeypatch.setattr(check, "due", due)
    asyncio.run(check.tick(1.0))
    assert asked == [1.0]
    original = program.code_fingerprint
    monkeypatch.setattr(program, "code_fingerprint", lambda: "changed")
    assert program.code_changed()
    asyncio.run(check.tick(2.0))
    assert asked == [1.0]  # not even looked at: the code changed since the start
    monkeypatch.setattr(program, "code_fingerprint", original)
    check.held = lambda: True  # an approved update is being applied
    asyncio.run(check.tick(3.0))
    assert asked == [1.0]


# ---- the guardrail probes of the program folder ---------------------------------------


def fake_run(suite, folder, *, leak_hook=False, script=True, read_tools=True):
    """A stand-in for a run in the copy: what a sandbox that holds (or not) would leave."""

    def run(_suite, chosen, _steps):
        assert chosen == folder
        if script:
            report = {"list build tools": {"blocked": False, "detail": "entries: "}}
            for name in verify.PROGRAM_READ_TARGETS:
                report["read " + name] = {"blocked": True, "detail": "FileNotFoundError"}
            for path in verify.PROGRAM_SANITY_TARGETS:
                (folder / path).write_text("probe\n")
            if leak_hook:
                (folder / ".git" / "hooks" / "pre-commit").write_text("probe\n")
            (folder / "program-report.json").write_text(json.dumps(report))
        uses, results = [], []
        if read_tools:
            target = folder / verify.PROGRAM_READ_TARGETS["build tools"]
            hook = str(folder / ".git" / "hooks" / "post-commit")
            uses = [{"name": "Read", "input": {"file_path": str(target)}, "id": "r1"},
                    {"name": "Write", "input": {"file_path": hook}, "id": "w1"}]  # fmt: skip
            results = [{"id": "r1", "text": "denied", "error": True},
                       {"id": "w1", "text": "denied", "error": True}]  # fmt: skip
        return verify.Run(0, uses, [], "FINISHED", [], "", "s", results=results)

    return run


def probes_named(suite):
    return {p.name.removeprefix(verify.PROGRAM_PROBE_PREFIX): p for p in suite.probes
            if p.name.startswith(verify.PROGRAM_PROBE_PREFIX)}  # fmt: skip


@pytest.mark.parametrize("leak", [False, True])
def test_the_probes_judge_on_disk(settings, monkeypatch, leak):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    folder = suite.run_dir / verify.PROGRAM_COPY
    monkeypatch.setattr(verify, "program_run", fake_run(suite, folder, leak_hook=leak))
    verify.probe_program_folder(suite)
    found = probes_named(suite)
    assert found["the probe script ran"].passed
    assert found["the folder itself stays writable (work, commits)"].passed
    assert found["shell: git hooks not written"].passed is not leak
    assert all(p.passed for name, p in found.items() if name != "shell: git hooks not written")
    environments = {"terminal": [dataclasses.asdict(p) for p in suite.probes]}
    # a leak withholds the program folder only (the block widens the scope, #180)
    assert verify.program_folder_probed(environments) is not leak
    suite.retire()


def test_a_skipped_shell_step_is_not_run_never_passed(settings, monkeypatch):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    folder = suite.run_dir / verify.PROGRAM_COPY
    monkeypatch.setattr(verify, "program_run", fake_run(suite, folder, script=False))
    verify.probe_program_folder(suite)
    found = probes_named(suite)
    assert found["the probe script ran"].inconclusive
    environments = {"terminal": [dataclasses.asdict(p) for p in suite.probes]}
    assert not verify.program_folder_probed(environments)
    suite.retire()


def test_the_copy_is_built_like_the_bridge_builds_a_program_folder_run(settings):
    """The policy the probe runs with: the program folder's rules, for the copy."""
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    folder = suite.run_dir / verify.PROGRAM_COPY
    verify.prepare_program_copy(folder)
    with_copy = dataclasses.replace(settings, allowed_paths=(folder,))
    data = sandbox_of(capable.build_policy(with_copy, folder, program_folder=folder))
    assert str(folder / ".tools") in data["sandbox"]["filesystem"]["denyRead"]
    assert str(folder / ".git/hooks") in data["sandbox"]["filesystem"]["denyWrite"]
    suite.retire()
