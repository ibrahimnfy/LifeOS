"""Capable mode behind its gate. Uses the stand-in claude only."""

import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from conftest import added_dirs, index_dir, user_line
from lifeos_bridge import capable
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError, child_env
from test_ask import option, parse_sse


@pytest.fixture
def settings(settings):
    """Capable runs exist on the SDK runner only (#181)."""
    return dataclasses.replace(settings, runner="sdk")


@pytest.fixture
def enable(isolated_config):
    """Write the marker file the way scripts/verify-guardrails does."""

    def write(version="fake-claude 1.0", all_passed=True, scope="all_allowed_folders"):
        marker = {"runner": "sdk", "claude_version": version, "all_passed": all_passed,
                  "scope": scope, "sdk_version": capable.sdk_version()}  # fmt: skip
        capable.marker_path().write_text(json.dumps(capable.sign_marker(marker)))

    return write


def audit_entries(config):
    files = list((config / "audit").glob("*.jsonl"))
    return [json.loads(line) for f in files for line in f.read_text().splitlines()]


def ask(client, auth, prompt, **extra):
    return client.post("/ask", json={"prompt": prompt, "mode": "capable", **extra}, headers=auth)


def test_default_mode_is_read_only_even_when_capable_is_enabled(
    client, auth, calls, enable, ask_dir
):
    enable()
    client.post("/ask", json={"prompt": "hello"}, headers=auth)
    args = calls()[0]["args"]
    assert option(args, "--tools") == "Read,Glob,Grep,AskUserQuestion"
    # nothing is added but the conversation index, which a read-only run can only read
    assert "--settings" not in args and added_dirs(args) == [index_dir(ask_dir)]


def test_invalid_mode(client, auth):
    response = client.post("/ask", json={"prompt": "x", "mode": "god"}, headers=auth)
    assert response.status_code == 400


def test_capable_is_refused_without_marker(client, auth, calls):
    response = ask(client, auth, "hello")
    assert response.status_code == 503
    assert response.json()["error"] == "capable_mode_unavailable"
    assert calls() == []
    assert client.app.state.slots.active == set()


@pytest.mark.parametrize(
    "marker",
    [
        {"runner": "sdk", "claude_version": "fake-claude 1.0", "all_passed": True},  # unsigned
        {"claude_version": "fake-claude 1.0", "all_passed": False},
        {"claude_version": "fake-claude 1.0"},
        ["not", "an", "object"],
    ],
)
def test_capable_is_refused_with_wrong_or_incomplete_marker(
    client, auth, calls, isolated_config, marker
):
    capable.marker_path().write_text(json.dumps(marker))
    assert ask(client, auth, "hello").status_code == 503
    assert calls() == []


def test_capable_run_flags_and_single_settings_json(client, auth, calls, enable, ask_dir, tmp_path):
    enable()
    response = ask(client, auth, "hello")
    assert response.status_code == 200
    assert response.json()["answer"] == "echo: hello"
    assert response.json()["refused_actions"] == []
    call = calls()[0]
    args = call["args"]
    assert option(args, "--tools") == "Read,Glob,Grep,Edit,Write,Bash,AskUserQuestion"
    # The bridge answers permission prompts itself (SDK runner): only the owner's yes.
    assert option(args, "--permission-mode") == "default"
    assert option(args, "--permission-prompt-tool") == "stdio"
    assert option(args, "--input-format") == "stream-json"
    assert "--restricted" in args and "--strict-mcp-config" in args
    # Nothing is granted on the command line.
    for flag in ("--allowedTools", "--allowed-tools", "--dangerously-skip-permissions"):
        assert flag not in args
    # the only added folder is the conversation index: readable, never writable
    index = index_dir(ask_dir)
    assert "bypassPermissions" not in args and added_dirs(args) == [index]

    settings = json.loads(option(args, "--settings"))
    sandbox = settings["sandbox"]
    assert sandbox["enabled"] is True
    assert sandbox["failIfUnavailable"] is True
    assert sandbox["allowUnsandboxedCommands"] is False
    assert sandbox["excludedCommands"] == []
    assert sandbox["network"] == {
        "allowedDomains": [],
        "strictAllowlist": True,
        "allowAllUnixSockets": False,
    }
    assert "~/" in sandbox["filesystem"]["denyRead"]
    assert "/run/user" in sandbox["filesystem"]["denyRead"]
    assert sorted(sandbox["filesystem"]["allowRead"]) == sorted([str(ask_dir), index])
    assert sandbox["filesystem"]["denyWrite"] == [index]
    assert settings["permissions"]["allow"] == [f"Edit(/{ask_dir}/**)"]
    denied = settings["permissions"]["deny"]
    assert "Read(~/.config/lifeos/**)" in denied and "Read(~/.ssh/**)" in denied
    assert f"Edit(/{index}/**)" in denied
    hook = settings["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
    # The copy of the gate taken when the bridge started (#180), standard library only.
    assert " -I -S " in hook and "/pinned/gate.py --audit-dir" in hook

    env = call["env"]
    assert "XDG_RUNTIME_DIR" not in env and "DBUS_SESSION_BUS_ADDRESS" not in env


def test_session_bus_variables_only_reach_read_only_runs():
    environ = {"HOME": "/h", "XDG_RUNTIME_DIR": "/run/user/1", "DBUS_SESSION_BUS_ADDRESS": "x"}
    assert "XDG_RUNTIME_DIR" in child_env(environ)
    assert set(child_env(environ, capable=True)) == {"HOME"}


def test_allowed_command_is_audited_before_it_runs(client, auth, enable, isolated_config):
    enable()
    response = ask(client, auth, "FAKE_TOOL ls -la")
    assert response.status_code == 200
    assert response.json()["answer"] == "ran the command"
    (entry,) = audit_entries(isolated_config)
    assert entry["detail"] == {"command": "ls -la"} and entry["decision"] == "allowed"
    assert entry["session_id"] == response.json()["session_id"]


def test_approval_required_command_is_refused_with_explanation(
    client, auth, enable, isolated_config
):
    enable()
    response = ask(client, auth, "FAKE_TOOL git reset --hard")
    body = response.json()
    assert response.status_code == 200
    assert "approval" in body["answer"]
    assert body["refused_actions"] == ["force"]
    # the gate sent it to the bridge, which could not ask anyone (not streamed): refused
    decisions = [e["decision"] for e in audit_entries(isolated_config)]
    assert decisions == ["asked", "approval_requested", "refused"]


def test_run_is_stopped_when_a_tool_ran_without_audit_record(client, auth, enable):
    enable()
    response = ask(client, auth, "FAKE_SKIPHOOK ls")
    assert response.status_code == 502
    assert response.json()["error"] == "audit_mismatch"
    assert client.app.state.slots.active == set()


def test_unwritable_audit_log_blocks_the_command(client, auth, enable, isolated_config):
    enable()
    (isolated_config / "audit").write_text("a file where the folder should be")
    response = ask(client, auth, "FAKE_TOOL ls")
    assert "audit log" in response.json()["answer"]


def test_capable_stream(client, auth, enable):
    enable()
    response = ask(client, auth, "FAKE_TOOL rm -rf x", stream=True)
    events = parse_sse(response.text)
    assert events[0][0] == "session" and events[-1][0] == "done"
    assert events[-1][1]["refused_actions"] == ["deletion"]


def test_capable_resume_adds_the_memory_folder_and_next_request_is_read_only(
    settings, auth, enable, tmp_path, write_session, ask_dir
):
    enable()
    project = tmp_path / "home" / "project"
    project.mkdir()
    session_id = write_session(project, [user_line(project, "x")])
    url = f"/sessions/{session_id}/messages"
    with TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,)))) as client:
        capable_run = client.post(url, json={"prompt": "go", "mode": "capable"}, headers=auth)
        assert capable_run.status_code == 200
        assert client.post(url, json={"prompt": "again"}, headers=auth).status_code == 200
    first, second = [
        entry
        for line in (project / ".fake-claude-calls.jsonl").read_text().splitlines()
        if "args" in (entry := json.loads(line))
    ]
    assert added_dirs(first["args"]) == [str(ask_dir), index_dir(ask_dir)]
    granted = json.loads(option(first["args"], "--settings"))
    assert granted["permissions"]["allow"] == [f"Edit(/{project}/**)", f"Edit(/{ask_dir}/**)"]
    assert sorted(granted["sandbox"]["filesystem"]["allowRead"]) == sorted(
        [str(ask_dir), str(project), index_dir(ask_dir)]
    )
    assert option(second["args"], "--tools") == "Read,Glob,Grep,AskUserQuestion"
    assert "--settings" not in second["args"]


def test_folders_that_may_never_be_used(settings, tmp_path, monkeypatch):
    from lifeos_bridge import paths

    with pytest.raises(ClaudeError) as outside:
        capable.build_policy(settings, tmp_path / "home")
    assert outside.value.code == "folder_not_allowed"
    hidden = tmp_path / "home" / ".hidden"
    hidden.mkdir()
    with pytest.raises(ClaudeError):
        capable.build_policy(dataclasses.replace(settings, ask_dir=hidden), hidden)
    inner = settings.ask_dir / "inner"
    inner.mkdir()
    monkeypatch.setattr(paths, "repo_dir", lambda: inner)
    with pytest.raises(ClaudeError) as program:  # around the program folder (#180)
        capable.build_policy(settings, settings.ask_dir)
    assert "program folder itself" in program.value.message


def test_a_check_that_covered_only_the_memory_folder_keeps_other_folders_refused(
    settings, auth, enable, tmp_path, write_session
):
    enable(scope="memory_folder")
    project = tmp_path / "home" / "project"
    project.mkdir()
    session_id = write_session(project, [user_line(project, "x")])
    with TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,)))) as client:
        response = client.post(
            f"/sessions/{session_id}/messages",
            json={"prompt": "go", "mode": "capable"},
            headers=auth,
        )
        assert response.status_code == 503
        assert response.json()["error"] == "capable_mode_unavailable"
        assert not (project / ".fake-claude-calls.jsonl").exists()
        assert ask(client, auth, "go").status_code == 200  # the memory folder itself is covered


def test_verify_refuses_to_run_without_a_memory_folder(settings, monkeypatch, capsys, tmp_path):
    from lifeos_bridge import verify

    missing = dataclasses.replace(settings, ask_dir=tmp_path / "home" / "nowhere")
    monkeypatch.setattr(verify, "load_settings", lambda: missing)
    assert verify.main([]) == 2
    assert "memory folder" in capsys.readouterr().err


def test_verify_reads_a_run_from_its_stream(settings, ask_dir):
    import uuid

    from lifeos_bridge import claude, verify

    run = verify.sdk_execute(settings, claude.READ_ONLY, "hello", ask_dir, str(uuid.uuid4()))
    assert run.exit_status == 0 and run.answer
