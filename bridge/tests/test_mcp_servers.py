"""MCP servers approved one by one (#108). Stand-ins only: no server is ever started."""

import dataclasses
import json
import os

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import approvals, audit, capable, claude, mcp_servers, push, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

SERVER = {"command": "npx", "args": ["-y", "some-server"], "env": {"API_TOKEN": "secret-value"}}


def define(folder, servers):
    (folder / ".mcp.json").write_text(json.dumps({"mcpServers": servers}))


def records(config):
    folder = config / "audit"
    return [json.loads(line) for f in sorted(folder.glob("*.jsonl")) for line in f.open()]


def no_audit(*_a, **_k):
    raise audit.AuditUnavailable("x")


def approve(folder, name="tools", session_id="s1"):
    card = mcp_servers.request(session_id, folder, name)
    return mcp_servers.answer(approvals.take(session_id, card["id"]), True)


# ------------------------------------------------------------------- listing


def test_only_the_folders_own_definitions_are_read(ask_dir, tmp_path):
    define(ask_dir.parent, {"parent": SERVER})  # a parent folder's file is not read
    assert mcp_servers.definitions(ask_dir) == {}
    define(ask_dir, {"tools": SERVER, "bad name!": SERVER, "x": "not a dict"})
    assert list(mcp_servers.definitions(ask_dir)) == ["tools"]
    (ask_dir / ".mcp.json").write_text("{broken")
    assert mcp_servers.definitions(ask_dir) == {}
    elsewhere = tmp_path / "elsewhere.json"
    elsewhere.write_text(json.dumps({"mcpServers": {"tools": SERVER}}))
    (ask_dir / ".mcp.json").unlink()
    os.symlink(elsewhere, ask_dir / ".mcp.json")
    assert mcp_servers.definitions(ask_dir) == {}  # a link is never followed


def test_the_listing_shows_what_runs_but_no_secret_values(ask_dir):
    remote = {"type": "http", "url": "https://x.org/mcp", "headers": {"Authorization": "Bearer t"}}
    define(ask_dir, {"tools": SERVER, "remote": remote})
    listed = {s["name"]: s for s in mcp_servers.listing(ask_dir)}
    tools = listed["tools"]
    assert tools["command"] == "npx" and tools["args"] == ["-y", "some-server"]
    assert tools["env_names"] == ["API_TOKEN"] and "secret-value" not in json.dumps(listed)
    assert listed["remote"]["url"] == "https://x.org/mcp"
    assert listed["remote"]["header_names"] == ["Authorization"]
    assert "Bearer t" not in json.dumps(listed)
    assert all(s["state"] == "not_approved" and s["outside_sandbox"] for s in listed.values())


# ------------------------------------------------------------------- approval


def test_approval_binds_to_the_exact_definition(ask_dir, isolated_config):
    define(ask_dir, {"tools": SERVER})
    card = mcp_servers.request("s1", ask_dir, "tools")
    assert card["kind"] == "mcp_server" and card["server"]["name"] == "tools"
    assert "outside the sandbox" in card["warning"] and "definition_hash" not in card
    assert mcp_servers.answer(approvals.take("s1", card["id"]), True) == {
        "approved": True,
        "name": "tools",
    }
    assert mcp_servers.listing(ask_dir)[0]["state"] == "approved"
    assert list(mcp_servers.for_run(ask_dir)) == ["tools"]
    define(ask_dir, {"tools": dict(SERVER, args=["-y", "other-server"])})
    assert mcp_servers.listing(ask_dir)[0]["state"] == "changed"
    assert mcp_servers.for_run(ask_dir) == {}  # a changed server needs a new approval
    steps = [r["tool"] for r in records(isolated_config)]
    assert steps == ["mcp_server_approval_requested", "mcp_server_approved"]
    assert all(r["session_id"] == "" for r in records(isolated_config))


def test_a_definition_changed_before_the_answer_is_not_approved(ask_dir):
    define(ask_dir, {"tools": SERVER})
    card = mcp_servers.request("s1", ask_dir, "tools")
    define(ask_dir, {"tools": dict(SERVER, command="sh")})
    done = mcp_servers.answer(approvals.take("s1", card["id"]), True)
    assert done["approved"] is False and mcp_servers.for_run(ask_dir) == {}


def test_a_refusal_or_unknown_server(ask_dir):
    define(ask_dir, {"tools": SERVER})
    card = mcp_servers.request("s1", ask_dir, "tools")
    assert mcp_servers.answer(approvals.take("s1", card["id"]), False)["approved"] is False
    assert mcp_servers.for_run(ask_dir) == {}
    with pytest.raises(ClaudeError) as caught:
        mcp_servers.request("s1", ask_dir, "missing")
    assert caught.value.status == 404


def test_no_approval_without_its_audit_record(ask_dir, monkeypatch):
    define(ask_dir, {"tools": SERVER})
    card = mcp_servers.request("s1", ask_dir, "tools")

    def unavailable(*_a, **_k):
        raise audit.AuditUnavailable("x")

    monkeypatch.setattr(audit, "record", unavailable)
    with pytest.raises(ClaudeError):
        mcp_servers.answer(approvals.take("s1", card["id"]), True)
    assert mcp_servers.for_run(ask_dir) == {}


def test_the_same_definition_asked_again_returns_the_waiting_card(ask_dir, isolated_config):
    define(ask_dir, {"tools": SERVER})
    first = mcp_servers.request("s1", ask_dir, "tools")
    again = mcp_servers.request("s1", ask_dir, "tools")
    assert first["already_waiting"] is False and again["already_waiting"] is True
    assert again["id"] == first["id"] and len(approvals.pending("s1")) == 1
    steps = [r["tool"] for r in records(isolated_config)]
    assert steps == ["mcp_server_approval_requested"]  # asked once
    other = mcp_servers.request("s2", ask_dir, "tools")  # another session: its own card
    assert other["already_waiting"] is False and other["id"] != first["id"]
    define(ask_dir, {"tools": dict(SERVER, command="sh")})
    changed = mcp_servers.request("s1", ask_dir, "tools")  # another definition: a new card
    assert changed["already_waiting"] is False and changed["id"] != first["id"]
    approvals.take_expired(now=9e12)
    define(ask_dir, {"tools": SERVER})
    assert mcp_servers.request("s1", ask_dir, "tools")["already_waiting"] is False


def test_a_refused_answer_leaves_the_card_waiting(ask_dir, monkeypatch):
    define(ask_dir, {"tools": SERVER})
    card = mcp_servers.request("s1", ask_dir, "tools")

    def handle(entry):
        return mcp_servers.answer(entry, True)

    with monkeypatch.context() as patch:
        patch.setattr(audit, "record", no_audit)
        with pytest.raises(ClaudeError) as caught:
            approvals.settle("s1", card["id"], handle)
        assert caught.value.code == "audit_unavailable"
    assert [p["id"] for p in approvals.pending("s1")] == [card["id"]]  # still there
    assert approvals.settle("s1", card["id"], handle)["approved"] is True
    assert approvals.pending("s1") == []
    with pytest.raises(ClaudeError) as caught:
        approvals.settle("s1", card["id"], handle)
    assert caught.value.code == "not_pending"


def test_revoke(ask_dir):
    define(ask_dir, {"tools": SERVER})
    approve(ask_dir)
    assert mcp_servers.revoke(ask_dir, "tools", "s1") is True
    assert mcp_servers.for_run(ask_dir) == {} and not mcp_servers.revoke(ask_dir, "tools", "s1")


# ------------------------------------------------------------------- runs


def test_only_capable_runs_get_exactly_the_approved_servers(settings, ask_dir):
    define(ask_dir, {"tools": SERVER, "other": SERVER})
    approve(ask_dir)
    servers = mcp_servers.for_run(ask_dir)
    policy = capable.build_policy(settings, ask_dir, mcp=servers)
    flags = claude.run_flags(settings, session_id="s", resume=False, policy=policy)
    assert json.loads(flags[flags.index("--mcp-config") + 1]) == {"mcpServers": {"tools": SERVER}}
    assert "--strict-mcp-config" in flags
    allow = json.loads(policy.settings_json)["permissions"]["allow"]
    assert "mcp__tools__*" in allow and not any("other" in rule for rule in allow)
    plain = claude.run_flags(settings, session_id="s", resume=False)
    assert "--mcp-config" not in plain
    stray = dataclasses.replace(claude.READ_ONLY, mcp_config="{}")
    assert "--mcp-config" not in claude.run_flags(
        settings, session_id="s", resume=False, policy=stray
    )


def marker(suite):
    data = {"runner": "cli", "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "all_allowed_folders",
            "suite_version": suite, "skills_verified": True}  # fmt: skip
    capable.marker_path("cli").write_text(json.dumps(capable.sign_marker(data)))


def calls(ask_dir):
    path = ask_dir / ".fake-claude-calls.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def test_api_from_listing_to_a_run(
    settings, ask_dir, auth, write_session, isolated_config, monkeypatch
):
    marker(capable.SUITE_VERSION)
    define(ask_dir, {"tools": SERVER})
    session_id = write_session(ask_dir, [user_line(ask_dir, "first")])
    pushes = []
    monkeypatch.setattr(push, "send_hint", lambda *a, **_k: pushes.append(a))
    with TestClient(create_app(settings)) as client:
        base = f"/sessions/{session_id}/mcp-servers"
        listed = client.get(base, headers=auth).json()
        assert listed["servers"][0]["state"] == "not_approved" and "sandbox" in listed["note"]
        card = client.post(f"{base}/tools/approval", headers=auth).json()
        again = client.post(f"{base}/tools/approval", headers=auth).json()
        assert again["id"] == card["id"] and again["already_waiting"] is True
        assert pushes == [("approval", session_id)]  # one push for one card
        pending = client.get(f"/sessions/{session_id}/interactions", headers=auth).json()
        assert [p["id"] for p in pending["pending"]] == [card["id"]]
        url = f"/sessions/{session_id}/interactions/{card['id']}"
        with monkeypatch.context() as patch:
            patch.setattr(audit, "record", no_audit)
            refused = client.post(url, headers=auth, json={"approve": True})
        assert refused.status_code == 503 and refused.json()["error"] == "audit_unavailable"
        assert client.post(url, headers=auth, json={"approve": True}).json()["approved"] is True
        assert client.get(base, headers=auth).json()["servers"][0]["state"] == "approved"

        messages = f"/sessions/{session_id}/messages"
        ran = client.post(messages, headers=auth, json={"prompt": "x", "mode": "capable"}).json()
        assert ran["mcp_servers"] == [{"name": "tools", "outside_sandbox": True}]
        assert "outside the sandbox" in ran["mcp_note"]
        assert "--mcp-config" in calls(ask_dir)[-1]["args"]
        read_only = client.post(messages, headers=auth, json={"prompt": "y"}).json()
        assert "mcp_servers" not in read_only
        assert "--mcp-config" not in calls(ask_dir)[-1]["args"]
        # The history shows which answer had servers (#157).
        page = client.get(f"/sessions/{session_id}/history?limit=3", headers=auth).json()
        had = [(e["user"], [s["name"] for s in e["mcp_servers"]]) for e in page["exchanges"]]
        assert had[-2:] == [("x", ["tools"]), ("y", [])]

        assert client.delete(f"{base}/tools", headers=auth).json() == {"revoked": True}
        again = client.post(messages, headers=auth, json={"prompt": "z", "mode": "capable"})
        assert "mcp_servers" not in again.json()
        assert "--mcp-config" not in calls(ask_dir)[-1]["args"]
    used = [r for r in records(isolated_config) if r["tool"] == "mcp_servers_used"]
    assert len(used) == 1 and used[0]["detail"]["names"] == ["tools"]


def test_approved_servers_wait_for_the_check_with_the_mcp_probes(
    settings, ask_dir, auth, write_session
):
    marker(capable.MCP_SUITE_VERSION - 1)
    define(ask_dir, {"tools": SERVER})
    approve(ask_dir)
    session_id = write_session(ask_dir, [user_line(ask_dir, "first")])
    with TestClient(create_app(settings)) as client:
        response = client.post(
            f"/sessions/{session_id}/messages", headers=auth,
            json={"prompt": "x", "mode": "capable"},
        )  # fmt: skip
    assert response.status_code == 503 and response.json()["error"] == "mcp_unavailable"
    assert calls(ask_dir) == []  # nothing started


def test_the_check_has_probes_for_it(settings):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    suite.probe_mcp_servers()
    assert [(p.name, p.passed) for p in suite.probes] == [
        ("mcp: an unapproved server is never passed to a run", True),
        ("mcp: an approved server is passed (sanity)", True),
        ("mcp: a changed server is never passed to a run", True),
    ]
    assert mcp_servers.for_run(suite.run_dir) == {}  # its approval was removed again
