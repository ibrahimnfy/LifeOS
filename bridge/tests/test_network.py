"""The open network of capable runs (#182). Stand-ins only; the real runs are documented in
docs/permission-policy.md, section 9.17."""

import asyncio
import base64
import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, claude, network, sdk_runner, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.interactions import Interactions

TOKEN = "gho_" + "x" * 36


@pytest.fixture
def sdk(settings):
    return dataclasses.replace(settings, runner="sdk")


def marker(**fields):
    data = {"runner": "sdk", "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "all_allowed_folders",
            "suite_version": capable.SUITE_VERSION, "core_version": capable.CORE_VERSION,
            "open_network": True} | fields  # fmt: skip
    capable.marker_path().write_text(json.dumps(capable.sign_marker(data)))


def sandbox(policy):
    return json.loads(policy.settings_json)["sandbox"]


def test_every_host_name_and_no_ip_address(settings):
    domains = network.allowed_domains()
    assert len(domains) > 1000 and "*.com" in domains and "*.org" in domains
    assert all(d.startswith("*.") and "*" not in d[2:] for d in domains)
    # nothing an IP address (any spelling) or the computer's own names could match
    for host in ("1", "0", "localhost", "1]"):
        assert f"*.{host}" not in domains
    assert all(not d[2:].isdigit() for d in domains)


def test_closed_unless_asked(settings):
    policy = capable.build_policy(settings, settings.ask_dir)
    assert sandbox(policy)["network"] == network.CLOSED
    assert "WebFetch" not in policy.tools and policy.env == ()


def test_open_without_a_github_token(settings):
    policy = capable.build_policy(settings, settings.ask_dir, open_network=True)
    box = sandbox(policy)
    assert box["network"]["allowedDomains"] == network.allowed_domains()
    assert box["network"]["strictAllowlist"] is True
    assert box["network"]["allowAllUnixSockets"] is False
    assert "tlsTerminate" not in box["network"] and "credentials" not in box
    assert policy.tools.endswith(",WebFetch,WebSearch")
    allow = json.loads(policy.settings_json)["permissions"]["allow"]
    assert "WebFetch" in allow and "WebSearch" in allow
    assert dict(policy.env) == {"GIT_TERMINAL_PROMPT": "0"}
    # the rest of the sandbox is unchanged
    assert box["filesystem"]["denyRead"][0] == "~/" and box["allowUnsandboxedCommands"] is False


def test_open_with_a_github_token_masks_it(settings):
    policy = capable.build_policy(settings, settings.ask_dir, open_network=True, github_token=TOKEN)
    box = sandbox(policy)
    assert box["network"]["tlsTerminate"] == {}
    masks = {entry["name"]: entry for entry in box["credentials"]["envVars"]}
    assert masks["GH_TOKEN"]["mode"] == "mask"
    assert set(masks["GH_TOKEN"]["injectHosts"]) == {"api.github.com", "uploads.github.com",
                                                     "github.com"}  # fmt: skip
    header = masks[network.HEADER_VARIABLE]
    assert header["mode"] == "mask" and header["onExtractNoMatch"] == "deny"
    assert header["injectHosts"] == ["github.com"]
    env = dict(policy.env)
    assert env["GH_TOKEN"] == TOKEN
    assert env["GIT_CONFIG_KEY_0"] == "http.https://github.com/.extraHeader"
    value = env["GIT_CONFIG_VALUE_0"]
    assert base64.b64decode(value.split()[-1]).decode() == f"x-access-token:{TOKEN}"
    assert env["GIT_CONFIG_VALUE_1"] == "git@github.com:" and env["GIT_CONFIG_COUNT"] == "3"
    # never in the settings (a command line) and never in the policy's representation
    assert TOKEN not in policy.settings_json and TOKEN not in repr(policy)


def test_the_token_is_read_with_gh_and_checked(tmp_path):
    gh = tmp_path / "gh"
    gh.write_text(f"#!/bin/sh\necho {TOKEN}\n")
    gh.chmod(0o700)
    assert network.github_token(str(gh)) == TOKEN
    gh.write_text("#!/bin/sh\necho 'not logged in' >&2\nexit 1\n")
    assert network.github_token(str(gh)) is None
    gh.write_text("#!/bin/sh\necho 'two words'\n")
    assert network.github_token(str(gh)) is None
    assert network.github_token(str(tmp_path / "missing")) is None


def test_the_marker_decides_the_open_network(isolated_config):
    assert not capable.open_network_available()
    marker()
    assert capable.open_network_available()
    marker(open_network=False)
    assert not capable.open_network_available()
    marker(core_version=0)
    assert not capable.open_network_available()


@pytest.mark.parametrize("opened", [True, False])
def test_a_capable_run_gets_the_network_the_check_proved(sdk, auth, ask_dir, monkeypatch, opened):
    marker(open_network=opened)
    monkeypatch.setattr(network, "github_token", lambda: TOKEN)
    with TestClient(create_app(sdk)) as client:
        reply = client.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"})
    assert reply.status_code == 200
    log = ask_dir / ".fake-claude-calls.jsonl"
    [launch] = [e for e in map(json.loads, log.read_text().splitlines()) if "args" in e]
    box = json.loads(launch["args"][launch["args"].index("--settings") + 1])["sandbox"]
    assert (box["network"]["allowedDomains"] != []) is opened
    assert (launch["env"].get("GH_TOKEN") == TOKEN) is opened
    assert ("WebFetch" in launch["args"][launch["args"].index("--tools") + 1]) is opened


# ------------------------------------------------------------------- approvals


def live(settings, cwd):
    launch = sdk_runner.Launch(cwd, None, None, None, capable.build_policy(settings, cwd))
    return sdk_runner.LiveSession(settings, "s1", launch, resume=False, asking=Interactions(5))


def test_a_push_of_the_memory_folder_is_refused_without_a_card(settings, monkeypatch):
    session = live(settings, settings.ask_dir)
    asked = []

    async def ask(kind, data):
        asked.append(data)
        return "answered", {"approve": True}

    monkeypatch.setattr(session, "_ask", ask)
    result = asyncio.run(session._approval({"command": "git push origin main"}))
    assert isinstance(result, sdk_runner.PermissionResultDeny) and asked == []
    assert "memory folder" in result.message


def test_an_approved_push_elsewhere_runs_as_shown(settings, tmp_path, monkeypatch):
    project = tmp_path / "home" / "project"
    project.mkdir()
    allowed = dataclasses.replace(settings, allowed_paths=(project,))
    session = live(allowed, project)
    cards = []

    async def ask(kind, data):
        cards.append(data)
        return "answered", {"approve": True}

    monkeypatch.setattr(session, "_ask", ask)
    named = asyncio.run(session._approval({"command": f"git -C {settings.ask_dir} push"}))
    assert isinstance(named, sdk_runner.PermissionResultDeny) and cards == []
    result = asyncio.run(session._approval({"command": "git push origin main"}))
    assert isinstance(result, sdk_runner.PermissionResultAllow)
    assert result.updated_input == {"command": "git push origin main"}
    assert "network" not in cards[0] and cards[0]["category"] == "push"


def test_the_policy_env_reaches_the_process(sdk, ask_dir):
    policy = capable.build_policy(sdk, ask_dir, open_network=True, github_token=TOKEN)
    run = verify.sdk_execute(sdk, policy, "hello", ask_dir, "00000000-0000-4000-8000-00000000000a")
    assert run.exit_status == 0
    log = ask_dir / ".fake-claude-calls.jsonl"
    [launch] = [e for e in map(json.loads, log.read_text().splitlines()) if "args" in e]
    assert launch["env"]["GH_TOKEN"] == TOKEN and launch["env"]["GIT_TERMINAL_PROMPT"] == "0"


# ------------------------------------------------------------------- the probes


def test_the_probe_targets_cover_every_spelling_of_the_computer():
    targets = verify.local_targets(4000, 8787)
    hosts = [host for host, _ in targets]
    for spelling in ("127.0.0.1", "localhost", "127.1", "2130706433", "::1", "0.0.0.0"):  # noqa: S104
        assert spelling in hosts
    assert ("127.0.0.1", 8787) in targets


def test_the_network_sanity_decides_open_network_only():
    good = {"terminal": [{"name": verify.NETWORK_SANITY, "passed": True}]}
    bad = {"terminal": [{"name": verify.NETWORK_SANITY, "passed": False}]}
    assert verify.network_probed(good) and not verify.network_probed(bad)
    # not working is no failed guardrail: the runs simply keep the closed network
    assert verify.guardrail_failures(bad) == []


def test_the_listener_counts_what_reaches_it():
    import socket

    hits: list[str] = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(4)
        verify.count_connections(listener, hits)
        socket.create_connection(listener.getsockname(), timeout=2).close()
        for _ in range(50):
            if hits:
                break
            asyncio.run(asyncio.sleep(0.02))
    assert hits == ["127.0.0.1"]


def test_claude_p_runs_never_get_the_policy_env(settings):
    policy = capable.build_policy(settings, settings.ask_dir, open_network=True, github_token=TOKEN)
    flags = claude.run_flags(settings, session_id="s", resume=False, policy=policy)
    assert TOKEN not in " ".join(flags)
