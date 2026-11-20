"""Capable mode on the SDK runner, behind its own guardrail check (#106). Stand-in claude only."""

import asyncio
import dataclasses
import json
import threading
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, claude, gate, runcontrol, sdk_runner, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

VERSION = "fake-claude 1.0"


@pytest.fixture
def sdk_settings(settings, monkeypatch):
    monkeypatch.setattr(sdk_runner, "GRACE", 0.3)
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    return dataclasses.replace(settings, runner="sdk")


@pytest.fixture
def marker(isolated_config):
    """Write a marker the way scripts/verify-guardrails does (per runner)."""

    def write(runner="sdk", **fields):
        data = {
            "runner": runner,
            "claude_version": VERSION,
            "sdk_version": capable.sdk_version(),
            "all_passed": True,
            "scope": "all_allowed_folders",
        } | fields
        data = {k: v for k, v in data.items() if v is not None}
        capable.marker_path(runner).write_text(json.dumps(data))

    return write


def log(ask_dir):
    path = ask_dir / ".fake-claude-calls.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def launches(ask_dir):
    return [e for e in log(ask_dir) if e.get("sdk")]


def option(args, name):
    return args[args.index(name) + 1] if name in args else None


def check(settings, runner, run_dir=None):
    return asyncio.run(capable.check_available(settings, run_dir, runner=runner))


# ------------------------------------------------------------------- the markers


def test_each_runner_needs_its_own_marker(settings, marker):
    with pytest.raises(ClaudeError):
        check(settings, "sdk")
    marker("cli", sdk_version=None)
    check(settings, "cli")
    with pytest.raises(ClaudeError):
        check(settings, "sdk")  # a pass of claude -p says nothing about the SDK path
    marker("sdk")
    check(settings, "sdk")


def test_a_marker_from_before_runners_counts_for_cli_only(settings, isolated_config):
    legacy = {"claude_version": VERSION, "all_passed": True, "scope": "all_allowed_folders"}
    (isolated_config / "capable-mode.json").write_text(json.dumps(legacy))
    check(settings, "cli")
    with pytest.raises(ClaudeError):
        check(settings, "sdk")


@pytest.mark.parametrize(
    "fields",
    [
        {"sdk_version": "0.0.1"},
        {"sdk_version": None},
        {"claude_version": "older"},
        {"runner": "cli"},
        {"all_passed": False},
        {"all_passed": "yes"},
    ],
)
def test_the_sdk_marker_must_match_runner_and_both_versions(settings, isolated_config, fields):
    data = {"runner": "sdk", "claude_version": VERSION, "sdk_version": capable.sdk_version(),
            "all_passed": True, "scope": "all_allowed_folders"} | fields  # fmt: skip
    data = {k: v for k, v in data.items() if v is not None}
    capable.marker_path("sdk").write_text(json.dumps(data))
    with pytest.raises(ClaudeError) as caught:
        check(settings, "sdk")
    assert caught.value.code == "capable_mode_unavailable"


# ------------------------------------------------------------------- the routing


def test_without_any_marker_capable_is_refused(sdk_settings, auth, ask_dir):
    with TestClient(create_app(sdk_settings)) as client:
        response = client.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"})
    assert response.status_code == 503 and launches(ask_dir) == []


def test_until_the_sdk_path_passed_claude_p_is_the_fallback(sdk_settings, auth, ask_dir, marker):
    marker("cli", sdk_version=None)
    with TestClient(create_app(sdk_settings)) as client:
        first = client.post("/ask", headers=auth, json={"prompt": "read only first"}).json()
        session_id = first["session_id"]
        assert session_id in client.app.state.sdk_pool.live
        response = client.post(
            "/ask", headers=auth,
            json={"prompt": "capable now", "mode": "capable", "session_id": session_id},
        )  # fmt: skip
        assert response.status_code == 200
        # The SDK process of the session was closed first: never two writers.
        assert session_id not in client.app.state.sdk_pool.live
    cli_runs = [e for e in log(ask_dir) if "args" in e and not e.get("sdk")]
    assert cli_runs and "-p" in cli_runs[-1]["args"] and "--settings" in cli_runs[-1]["args"]
    hook = json.loads(option(cli_runs[-1]["args"], "--settings"))["hooks"]["PreToolUse"][0]
    assert "--ask-host" not in hook["hooks"][0]["command"]  # the claude -p gate refuses


def test_after_the_sdk_path_passed_capable_runs_through_it(sdk_settings, auth, ask_dir, marker):
    marker("sdk")
    with TestClient(create_app(sdk_settings)) as client:
        response = client.post("/ask", headers=auth, json={"prompt": "hello", "mode": "capable"})
        assert response.status_code == 200 and response.json()["answer"] == "echo: hello"
        session_id = response.json()["session_id"]
        again = client.post(
            "/ask", headers=auth, json={"prompt": "now read only", "session_id": session_id}
        )
        assert again.status_code == 200
    first, second = launches(ask_dir)
    args = first["args"]
    assert option(args, "--tools") == claude.CAPABLE_TOOLS + ",AskUserQuestion"
    assert option(args, "--permission-mode") == "default"
    settings_json = json.loads(option(args, "--settings"))
    assert settings_json["sandbox"]["enabled"] and settings_json["sandbox"]["failIfUnavailable"]
    assert settings_json["hooks"]["PreToolUse"][0]["hooks"][0]["command"].endswith("--ask-host")
    for name in ("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS"):
        assert name not in first["env"]
    # A read-only message on a capable session: a new process, read-only flags, resumed.
    assert option(second["args"], "--tools") == "Read,Glob,Grep,AskUserQuestion"
    assert "--settings" not in second["args"] and "--resume" in second["args"]


def test_an_sdk_version_change_switches_the_sdk_path_off(sdk_settings, auth, ask_dir, marker):
    marker("sdk", sdk_version="0.0.1")
    with TestClient(create_app(sdk_settings)) as client:
        response = client.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"})
    assert response.status_code == 503 and launches(ask_dir) == []


def test_a_tool_result_without_audit_record_stops_the_sdk_run(sdk_settings, auth, marker):
    marker("sdk")
    with TestClient(create_app(sdk_settings)) as client:
        response = client.post(
            "/ask", headers=auth, json={"prompt": "FAKE_UNAUDITED", "mode": "capable"}
        )
    assert response.status_code == 502 and response.json()["error"] == "audit_mismatch"


def test_capable_approval_through_the_api(sdk_settings, auth, ask_dir, marker, isolated_config):
    marker("sdk")
    prompt = "FAKE_TOOL Bash " + json.dumps({"command": "rm old.txt"})
    with TestClient(create_app(sdk_settings)) as client:
        holder = {}

        def run():
            holder["response"] = client.post(
                "/ask", headers=auth, json={"prompt": prompt, "mode": "capable", "stream": True}
            )

        thread = threading.Thread(target=run)
        thread.start()
        pool = client.app.state.sdk_pool
        deadline = time.time() + 10
        while not pool.asking.items:
            assert time.time() < deadline
            time.sleep(0.02)
        pending = next(iter(pool.asking.items.values()))
        assert pending.kind == "approval" and pending.data["command"] == "rm old.txt"
        url = f"/sessions/{pending.session_id}/interactions/{pending.id}"
        assert client.post(url, headers=auth, json={"approve": True}).status_code == 200
        thread.join(10)
    done = json.loads(holder["response"].text.strip().splitlines()[-1][6:])
    assert json.loads(done["answer"]) == {
        "behavior": "allow",
        "updatedInput": {"command": "rm old.txt"},
    }


def test_a_non_streaming_capable_request_cannot_be_asked(sdk_settings, auth, marker):
    marker("sdk")
    prompt = "FAKE_TOOL Bash " + json.dumps({"command": "rm old.txt"})
    with TestClient(create_app(sdk_settings)) as client:
        response = client.post("/ask", headers=auth, json={"prompt": prompt, "mode": "capable"})
    assert json.loads(response.json()["answer"])["message"] == gate.APPROVAL_NOTE


# ------------------------------------------------------------------- the probe suite


def test_the_suite_runs_capable_steps_through_the_sdk_runner(settings, monkeypatch):
    seen = []

    def fake_sdk(settings, policy, prompt, cwd, session_id, **kwargs):
        seen.append((policy, kwargs))
        return verify.Run(0, [], [], "", [], "", session_id)

    monkeypatch.setattr(verify, "sdk_execute", fake_sdk)
    suite = verify.Suite(settings, "sdk")
    suite.run_dir = settings.ask_dir
    suite.capable_run("1. something\n", approve=True)
    [(policy, kwargs)] = seen
    hook = json.loads(policy.settings_json)["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
    assert hook.endswith("--ask-host") and kwargs["approve"] is True


def test_sdk_execute_reads_a_run(settings, ask_dir):
    sid = str(uuid.uuid4())
    run = verify.sdk_execute(settings, claude.READ_ONLY, "hello", ask_dir, sid)
    assert run.exit_status == 0 and run.answer == "echo: hello"


def test_sdk_execute_answers_approvals(settings, ask_dir):
    policy = capable.build_policy(settings, ask_dir, ask_host=True)
    prompt = "FAKE_TOOL Bash " + json.dumps({"command": "rm a.txt"})
    yes = verify.sdk_execute(settings, policy, prompt, ask_dir, str(uuid.uuid4()), approve=True)
    no = verify.sdk_execute(settings, policy, prompt, ask_dir, str(uuid.uuid4()))
    assert json.loads(yes.answer)["behavior"] == "allow"
    assert json.loads(no.answer)["behavior"] == "deny"


def write_env(config):
    (config / "env").write_text("LIFEOS_BRIDGE_TOKEN=" + "t" * 40 + "\n")
    (config / "env").chmod(0o600)


def test_verify_writes_the_marker_of_its_runner_only(
    settings, isolated_config, monkeypatch, marker
):
    marker("cli", sdk_version=None)
    marker("sdk")
    monkeypatch.setattr(verify, "load_settings", lambda: settings)
    passed = [verify.Probe("x", True)]
    monkeypatch.setattr(verify.Suite, "run_all", lambda self: passed)
    monkeypatch.setattr(verify, "service_suite", lambda *a: [dataclasses.asdict(p) for p in passed])
    assert verify.main(["--runner", "sdk"]) == 0
    written = json.loads(capable.marker_path("sdk").read_text())
    assert written["runner"] == "sdk" and written["sdk_version"] == capable.sdk_version()
    assert written["claude_version"] == VERSION and written["all_passed"] is True
    assert capable.marker_path("cli").exists()  # the other runner's marker is untouched

    failed = [verify.Probe("x", False)]
    monkeypatch.setattr(verify.Suite, "run_all", lambda self: failed)
    assert verify.main(["--runner", "sdk"]) == 1
    assert not capable.marker_path("sdk").exists()  # off at once, and stays off
    assert capable.marker_path("cli").exists()
