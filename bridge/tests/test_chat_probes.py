"""The guardrail probes for a blank chat's folder as the run folder (#163). Stand-ins only:
the runs are scripted; what the real Claude Code does is checked by scripts/verify-guardrails."""

import json

import pytest

from lifeos_bridge import capable, chats, guardcheck, verify


@pytest.fixture
def suite(settings):
    made = verify.Suite(settings, "cli")
    made.run_dir.mkdir(parents=True)
    made.config.mkdir(exist_ok=True)
    return made


def scripted(monkeypatch, behave=None):
    """``chat_run`` answers without a model: the first run writes the sandbox report."""
    runs = []

    def chat_run(suite, chat, steps, *, capable_mode=True, resume=None):
        runs.append({"chat": chat, "steps": steps, "capable": capable_mode, "resume": resume})
        number = len(runs)
        run = verify.Run(0, [], [], "FINISHED", ["Read", "Glob", "Grep"], "", f"session-{number}")
        if number == 1:
            cfg = json.loads((chat / "chat-probe-input.json").read_text())
            report = {verify.SANITY: {"blocked": False, "detail": "written"}}
            for name in cfg["write_targets"]:
                # as with the real sandbox: a write above the chat folder seems to work
                # inside it and never reaches the real folder
                report[f"write_{name}"] = {"blocked": name != "the_chat_root", "detail": "seen"}
            for name in cfg["read_targets"]:
                report[f"read_{name}"] = {"blocked": True, "detail": "PermissionError"}
            report["list_the_chat_root"] = {"blocked": False, "detail": f"entries: {chat.name}"}
            report["network_tcp"] = {"blocked": True, "detail": "OSError"}
            (chat / "chat-report.json").write_text(json.dumps(report))
            capable.audit_dir().mkdir(parents=True, exist_ok=True)
            entry = {"session_id": run.session_id, "tool": "Bash", "decision": "allowed",
                     "detail": {"command": "python3 chat-probe.py"}}  # fmt: skip
            (capable.audit_dir() / "2027-01.jsonl").write_text(json.dumps(entry) + "\n")
        if number == 2:
            run.tool_uses = [{"name": "Read", "input": {}}] * 2 + [{"name": "Write", "input": {}}]
            (chat / "filetool-ok.txt").write_text("probe")
        if behave is not None:
            behave(number, chat, run)
        return run

    monkeypatch.setattr(verify, "chat_run", chat_run)
    return runs


def by_name(suite):
    return {probe.name.removeprefix(verify.CHAT_PROBE_PREFIX): probe for probe in suite.probes}


def test_all_probes_pass_when_every_guardrail_holds(suite, monkeypatch):
    runs = scripted(monkeypatch)
    verify.probe_chat_folder(suite)
    assert all(probe.passed and not probe.inconclusive for probe in suite.probes), by_name(suite)
    assert all(probe.name.startswith(verify.CHAT_PROBE_PREFIX) for probe in suite.probes)
    # three runs: shell, file tools, then a read-only request in the same chat
    assert [(r["capable"], r["resume"]) for r in runs] == [
        (True, None), (True, None), (False, "session-2"),
    ]  # fmt: skip
    chat = runs[0]["chat"]
    assert chat.parent == chats.root(suite.settings) and chats.is_chat_folder(suite.settings, chat)
    assert chat.name.startswith(verify.CHAT_PROBE_DATE)
    # the suite's own folders are gone again; the chat root stays, and stays usable
    assert list(chats.root(suite.settings).iterdir()) == []
    assert chats.root_refusal(suite.settings) is None
    names = by_name(suite)
    assert "a chat root inside a git repository is refused" in names
    assert "sandbox: write_another_chat" in names and "sandbox: read_another_chat" in names
    assert "sandbox: write_the_chat_root" in names  # judged by the real folder, not the report
    assert "sandbox: the chat root does not show another chat" in names
    assert "read-only: no shell or write tool, nothing written" in names


@pytest.mark.parametrize(
    ("broken", "failed"),
    [
        ("leak", "another chat's file is not readable (shell, file tool, link, read-only)"),
        ("write", "nothing was written outside the chat folder"),
        ("memory file", "no memory file is loaded, not even the run folder's own"),
        ("delete", "gate: the file to delete survived"),
        ("write tool", "read-only: no shell or write tool, nothing written"),
    ],
)
def test_a_broken_guardrail_fails_its_probe(suite, monkeypatch, broken, failed):
    def behave(number, chat, run):
        cfg = json.loads((chat / "chat-probe-input.json").read_text())
        if broken == "leak" and number == 2:
            run.tool_results.append(cfg["canary"])
        if broken == "write" and number == 2:
            (chats.root(suite.settings) / "leaked").mkdir(exist_ok=True)
            for target in cfg["write_targets"].values():
                if "LifeOS-Chats" in target and "0000-00-00" not in target:
                    open(target + "-tool", "w").close()
        if broken == "memory file" and number == 3:
            run.answer = (chat / "CLAUDE.md").read_text().split()[-1]
        if broken == "delete" and number == 1:
            (chat / "victim.txt").unlink()
        if broken == "write tool" and number == 3:
            run.init_tools.append("Write")

    scripted(monkeypatch, behave)
    verify.probe_chat_folder(suite)
    names = by_name(suite)
    assert not names[failed].passed
    assert [n for n, p in names.items() if not p.passed] == [failed]


def test_what_the_sandbox_let_through_fails_its_probe(suite, monkeypatch):
    def behave(number, chat, run):
        if number == 1:
            cfg = json.loads((chat / "chat-probe-input.json").read_text())
            report = json.loads((chat / "chat-report.json").read_text())
            report["read_another_chat"] = {"blocked": False, "detail": "read 5 characters"}
            others = sorted(p.name for p in chat.parent.iterdir())
            report["list_the_chat_root"] = {
                "blocked": False,
                "detail": "entries: " + ", ".join(others),
            }
            (chat / "chat-report.json").write_text(json.dumps(report))
            open(cfg["write_targets"]["another_chat"], "w").close()  # a real write got out

    scripted(monkeypatch, behave)
    verify.probe_chat_folder(suite)
    names = by_name(suite)
    failed = {name for name, probe in names.items() if not probe.passed}
    assert failed == {
        "sandbox: read_another_chat",
        "sandbox: write_another_chat",
        "sandbox: the chat root does not show another chat",
        "nothing was written outside the chat folder",
    }


def test_an_unusable_chat_root_is_not_a_failure_but_chat_folders_stay_off(suite, monkeypatch):
    root = chats.root(suite.settings)
    root.mkdir()
    (root / "CLAUDE.md").write_text("x")
    runs = scripted(monkeypatch)
    verify.probe_chat_folder(suite)
    [probe] = suite.probes
    assert probe.passed and probe.name == verify.CHAT_NOT_CHECKED and "CLAUDE.md" in probe.detail
    assert runs == []
    results = [probe.__dict__ for probe in suite.probes]
    assert verify.chat_folders_probed({"terminal": results}) is False


def test_left_over_probe_folders_are_cleared_first(suite, monkeypatch):
    root = chats.root(suite.settings)
    old = root / (verify.CHAT_PROBE_DATE + "abcdef")
    real = root / "2027-01-15-abcdef"
    (old / "sub").mkdir(parents=True)
    (old / "sub" / "left.txt").write_text("from an interrupted check")
    real.mkdir()
    (real / "kept.txt").write_text("a real chat's file")
    scripted(monkeypatch)
    verify.probe_chat_folder(suite)
    assert not old.exists() and (real / "kept.txt").is_file()


def test_the_marker_says_whether_chat_folders_were_probed(settings, monkeypatch):
    def check(probes):
        monkeypatch.setattr(verify, "load_settings", lambda: settings)
        monkeypatch.setattr(verify.Suite, "run_all", lambda self: probes)
        monkeypatch.setattr(verify, "service_suite", lambda *a: [p.__dict__ for p in probes])
        assert verify.main(["--runner", "cli", "--check-id", "c1"]) == 0
        return json.loads(capable.marker_path("cli").read_text())

    settings.ask_dir.mkdir(exist_ok=True)
    marker = check([verify.Probe("a", True), verify.Probe(verify.CHAT_SANITY, True)])
    assert marker["chat_folders"] is True and marker["suite_version"] == 5
    marker = check([verify.Probe("a", True), verify.Probe(verify.CHAT_NOT_CHECKED, True, "why")])
    assert marker["chat_folders"] is False and marker["all_passed"] is True


def test_suite_versions_and_plan_runs():
    assert capable.SUITE_VERSIONS == {"cli": 5, "sdk": 6}
    assert capable.CHAT_SUITE_VERSIONS == {"cli": 5, "sdk": 6}
    # three short runs per runner and environment for the chat-folder probes
    assert guardcheck.PLAN_RUNS == {"cli": 14, "sdk": 18}
