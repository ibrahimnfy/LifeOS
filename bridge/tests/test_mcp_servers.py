"""MCP servers approved one by one (#108). Stand-ins only: no server is ever started."""

import dataclasses
import json
import os
from pathlib import Path

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
    define(ask_dir, {"tools": SERVER, "twin": SERVER})  # same definition, another name
    twin = mcp_servers.request("s1", ask_dir, "twin")
    assert twin["already_waiting"] is False and twin["server"]["name"] == "twin"
    assert mcp_servers.request("s1", ask_dir, "twin")["id"] == twin["id"]
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
    # Passed as a file in the private settings folder, never on the command line (#162).
    config = Path(flags[flags.index("--mcp-config") + 1])
    assert config.is_relative_to(capable.config_dir()) and config.stat().st_mode & 0o777 == 0o600
    assert json.loads(config.read_text()) == {"mcpServers": {"tools": SERVER}}
    assert "secret-value" not in " ".join(flags)
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
            "suite_version": suite}  # fmt: skip
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
        assert ran["mcp_servers"] == [{"name": "tools", "scope": "folder", "outside_sandbox": True}]
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


# ------------------------------------------------------- personal (user-scope) servers (#162)

REMOTE = {"type": "http", "url": "https://example.org/mcp",
          "headers": {"Authorization": "Bearer header-secret"}}  # fmt: skip
PERSONAL = {"type": "stdio", "command": "npx", "args": ["-y", "own-server"],
            "env": {"TOKEN": "env-secret"}}  # fmt: skip


def define_personal(settings, servers, **more):
    """A file shaped like Claude Code's own: user scope at the top, other things beside it."""
    local = {"/some/project": {"mcpServers": {"local-only": PERSONAL}, "history": ["x"]}}
    data = {"userID": "u", "mcpServers": servers, "projects": local, **more}
    settings.personal_mcp_file.write_text(json.dumps(data))
    return settings.personal_mcp_file


def approve_personal(folder, source, name="mine", session_id="s1"):
    card = mcp_servers.request(session_id, folder, name, "personal", source)
    return mcp_servers.answer(approvals.take(session_id, card["id"]), True, source)


def test_only_the_user_scope_servers_of_the_personal_file_are_read(settings, ask_dir, tmp_path):
    assert mcp_servers.personal_definitions(settings.personal_mcp_file) == {}  # no file
    source = define_personal(settings, {"mine": PERSONAL, "remote": REMOTE, "bad name": PERSONAL})
    assert sorted(mcp_servers.personal_definitions(source)) == ["mine", "remote"]
    listed = mcp_servers.listing(ask_dir, source)
    assert [(s["name"], s["scope"], s["state"]) for s in listed] == [
        ("mine", "personal", "not_approved"),
        ("remote", "personal", "not_approved"),
    ]  # nothing of another scope ("local-only"), ever
    assert mcp_servers.listing(ask_dir) == []  # without the file: the folder's own only
    # Larger than the folder file's limit is fine: the file also holds Claude Code's state.
    define_personal(settings, {"mine": PERSONAL}, padding="x" * (mcp_servers.MAX_BYTES * 2))
    assert list(mcp_servers.personal_definitions(source)) == ["mine"]
    for broken in ("not json", "[]", '{"mcpServers": []}'):
        source.write_text(broken)
        assert mcp_servers.personal_definitions(source) == {}
    target = tmp_path / "elsewhere.json"
    target.write_text(json.dumps({"mcpServers": {"mine": PERSONAL}}))
    source.unlink()
    source.symlink_to(target)
    assert mcp_servers.personal_definitions(source) == {}  # a link is never followed


def test_secret_values_of_personal_servers_are_never_shown_or_logged(
    settings, ask_dir, isolated_config
):
    source = define_personal(settings, {"mine": PERSONAL, "remote": REMOTE})
    listed = mcp_servers.listing(ask_dir, source)
    assert listed[0]["env_names"] == ["TOKEN"] and listed[1]["header_names"] == ["Authorization"]
    card = mcp_servers.request("s1", ask_dir, "remote", "personal", source)
    assert card["scope"] == "personal" and card["server"]["scope"] == "personal"
    assert card["server"]["url"] == "https://example.org/mcp"
    done = mcp_servers.answer(approvals.take("s1", card["id"]), True, source)
    assert done == {"approved": True, "name": "remote"}
    approve_personal(ask_dir, source)
    servers = mcp_servers.for_run(ask_dir, source)
    policy = capable.build_policy(settings, ask_dir, mcp=servers)
    flags = claude.run_flags(settings, session_id="s", resume=False, policy=policy)
    stored = (isolated_config / mcp_servers.FILE_NAME).read_text()
    logged = json.dumps(records(isolated_config))
    for text in (json.dumps(listed), json.dumps(card), " ".join(flags), stored, logged):
        assert "env-secret" not in text and "header-secret" not in text
    # The run still gets the complete definitions, in a private file.
    config = Path(flags[flags.index("--mcp-config") + 1])
    assert json.loads(config.read_text()) == {"mcpServers": {"mine": PERSONAL, "remote": REMOTE}}
    assert config.stat().st_mode & 0o777 == 0o600
    assert config.parent.stat().st_mode & 0o777 == 0o700


def test_a_personal_server_needs_its_own_approval_and_a_new_one_after_a_change(settings, ask_dir):
    source = define_personal(settings, {"mine": PERSONAL})
    assert mcp_servers.for_run(ask_dir, source) == {}
    assert approve_personal(ask_dir, source) == {"approved": True, "name": "mine"}
    assert mcp_servers.listing(ask_dir, source)[0]["state"] == "approved"
    assert mcp_servers.for_run(ask_dir, source) == {"mine": PERSONAL}
    # Approved once, for every folder; without the file given (an older check), never.
    assert mcp_servers.for_run(ask_dir.parent, source) == {"mine": PERSONAL}
    assert mcp_servers.for_run(ask_dir) == {}
    define_personal(settings, {"mine": dict(PERSONAL, args=["-y", "other-server"])})
    assert mcp_servers.listing(ask_dir, source)[0]["state"] == "changed"
    assert mcp_servers.for_run(ask_dir, source) == {}
    define_personal(settings, {"mine": dict(PERSONAL, env={"TOKEN": "another"})})
    assert mcp_servers.for_run(ask_dir, source) == {}  # a changed value counts as well
    define_personal(settings, {"mine": PERSONAL})
    assert mcp_servers.for_run(ask_dir, source) == {"mine": PERSONAL}
    assert mcp_servers.revoke(ask_dir, "mine", "s1", "personal", source) is True
    assert mcp_servers.for_run(ask_dir, source) == {}
    assert mcp_servers.revoke(ask_dir, "mine", "s1", "personal", source) is False


def test_a_change_between_card_and_answer_approves_nothing(settings, ask_dir):
    source = define_personal(settings, {"mine": PERSONAL})
    card = mcp_servers.request("s1", ask_dir, "mine", "personal", source)
    define_personal(settings, {"mine": dict(PERSONAL, command="sh")})
    done = mcp_servers.answer(approvals.take("s1", card["id"]), True, source)
    assert done["approved"] is False and mcp_servers.for_run(ask_dir, source) == {}
    with pytest.raises(ClaudeError) as caught:
        mcp_servers.request("s1", ask_dir, "missing", "personal", source)
    assert caught.value.code == "mcp_server_not_found"


def test_the_two_scopes_never_stand_in_for_each_other(settings, ask_dir):
    """The same name in the folder and in the personal file: two servers, two approvals."""
    source = define_personal(settings, {"tools": PERSONAL})
    define(ask_dir, {"tools": SERVER})
    assert [(s["scope"], s["state"]) for s in mcp_servers.listing(ask_dir, source)] == [
        ("folder", "not_approved"),
        ("personal", "not_approved"),
    ]
    approve(ask_dir)  # the folder's own
    assert [s["state"] for s in mcp_servers.listing(ask_dir, source)] == [
        "approved", "not_approved",
    ]  # fmt: skip
    assert mcp_servers.for_run(ask_dir, source) == {"tools": SERVER}
    assert mcp_servers.for_run(ask_dir.parent, source) == {}  # not the personal one elsewhere
    assert mcp_servers.revoke(ask_dir, "tools", "s1") is True
    approve_personal(ask_dir, source, "tools")
    assert [s["state"] for s in mcp_servers.listing(ask_dir, source)] == [
        "not_approved", "approved",
    ]  # fmt: skip
    assert mcp_servers.for_run(ask_dir, source) == {"tools": PERSONAL}  # never the folder's
    approve(ask_dir)
    servers = mcp_servers.for_run(ask_dir, source)
    assert servers == {"tools": SERVER}  # both approved: the folder's own wins the name
    assert mcp_servers.scopes_for_run(ask_dir, servers) == {"tools": "folder"}
    # A folder approval's hash never approves a personal definition of that name.
    define_personal(settings, {"tools": SERVER})
    mcp_servers.revoke(ask_dir, "tools", "s1", "personal", source)
    assert mcp_servers.for_run(ask_dir.parent, source) == {}
    # A card of one scope and a card of the other are two cards.
    first = mcp_servers.request("s1", ask_dir, "tools", "folder", source)
    second = mcp_servers.request("s1", ask_dir, "tools", "personal", source)
    assert first["id"] != second["id"] and second["already_waiting"] is False
    assert mcp_servers.request("s1", ask_dir, "tools", "personal", source)["id"] == second["id"]


def test_approvals_belong_to_the_personal_file_they_were_given_for(settings, ask_dir, tmp_path):
    """The scratch file of the guardrail check can never approve or revoke a real server."""
    source = define_personal(settings, {"mine": PERSONAL})
    approve_personal(ask_dir, source)
    scratch = tmp_path / "scratch.json"
    scratch.write_text(json.dumps({"mcpServers": {"mine": PERSONAL}}))
    assert mcp_servers.for_run(ask_dir, scratch) == {}
    assert mcp_servers.revoke(ask_dir, "mine", "s1", "personal", scratch) is False
    assert mcp_servers.for_run(ask_dir, source) == {"mine": PERSONAL}


@pytest.mark.parametrize(
    "spec",
    [
        dict(REMOTE, headersHelper="/usr/local/bin/make-headers"),  # a command the card hides
        {"type": ["stdio"], "command": "npx"},  # a type that is no text
        {"type": {"a": 1}, "url": "https://example.org/mcp"},
        dict(REMOTE, oauth={"clientId": "x"}),
        {"type": "sse", "url": "https://example.org/sse"},
        {"type": "ws", "url": "wss://example.org/ws"},
        {"url": "https://example.org/mcp"},  # no type: Claude Code reads it as stdio
        {"type": "http", "url": "file:///etc/passwd"},
        {"type": "stdio", "command": "npx", "args": "not-a-list"},
        {"type": "stdio", "command": "npx", "env": {"TOKEN": 5}},
        {"type": "stdio", "command": ""},
        {"type": "stdio", "command": "npx", "url": "https://example.org"},
    ],
)
def test_only_plain_stdio_and_http_definitions_can_be_approved(settings, ask_dir, spec):
    assert mcp_servers.plain(PERSONAL) and mcp_servers.plain(REMOTE)
    assert mcp_servers.plain({"command": "npx", "timeout": 600000})
    assert not mcp_servers.plain(spec)
    source = define_personal(settings, {"odd": spec})
    assert mcp_servers.listing(ask_dir, source)[0]["state"] == "unsupported"
    with pytest.raises(ClaudeError) as caught:
        mcp_servers.request("s1", ask_dir, "odd", "personal", source)
    assert caught.value.code == "mcp_server_unsupported" and caught.value.status == 409
    # Even an approval that exists (the definition was plain once) passes nothing on.
    data = {mcp_servers._personal_key(source): {"odd": {"hash": mcp_servers.definition_hash(spec)}}}
    (capable.config_dir() / mcp_servers.FILE_NAME).write_text(json.dumps(data))
    assert mcp_servers.for_run(ask_dir, source) == {}


def test_run_files_not_used_for_a_day_are_removed(settings, ask_dir):
    first = mcp_servers.run_config_file({"a": PERSONAL})
    assert mcp_servers.run_config_file({"a": PERSONAL}) == first  # one file per content
    old = 1_000_000_000
    os.utime(first, (old, old))
    second = mcp_servers.run_config_file({"b": PERSONAL})
    assert second.is_file() and not first.exists()


def test_api_for_personal_servers(settings, ask_dir, auth, write_session, isolated_config):
    marker(capable.SUITE_VERSION)
    source = define_personal(settings, {"mine": PERSONAL, "tools": REMOTE})
    define(ask_dir, {"tools": SERVER})
    session_id = write_session(ask_dir, [user_line(ask_dir, "first")])
    with TestClient(create_app(settings)) as client:
        base = f"/sessions/{session_id}/mcp-servers"
        listed = client.get(base, headers=auth).json()["servers"]
        assert [(s["name"], s["scope"]) for s in listed] == [
            ("tools", "folder"), ("mine", "personal"), ("tools", "personal"),
        ]  # fmt: skip
        assert "secret" not in json.dumps(listed)

        def approve_card(card):
            url = f"/sessions/{session_id}/interactions/{card['id']}"
            return client.post(url, headers=auth, json={"approve": True}).json()

        # Without a scope: the folder's own server of that name, else the personal one.
        own = client.post(f"{base}/tools/approval", headers=auth).json()
        assert own["scope"] == "folder" and own["server"]["command"] == "npx"
        mine = client.post(f"{base}/mine/approval", headers=auth).json()
        assert mine["scope"] == "personal" and "secret" not in json.dumps(mine)
        remote = client.post(f"{base}/tools/approval?scope=personal", headers=auth).json()
        assert remote["scope"] == "personal" and remote["server"]["type"] == "http"
        assert client.post(f"{base}/tools/approval?scope=other", headers=auth).status_code == 400
        assert client.post(f"{base}/mine/approval?scope=folder", headers=auth).status_code == 404
        assert approve_card(mine)["approved"] is True
        assert approve_card(remote)["approved"] is True
        states = [s["state"] for s in client.get(base, headers=auth).json()["servers"]]
        assert states == ["not_approved", "approved", "approved"]

        messages = f"/sessions/{session_id}/messages"
        ran = client.post(messages, headers=auth, json={"prompt": "x", "mode": "capable"}).json()
        assert ran["mcp_servers"] == [
            {"name": "mine", "scope": "personal", "outside_sandbox": True},
            {"name": "tools", "scope": "personal", "outside_sandbox": True},
        ]
        args = calls(ask_dir)[-1]["args"]
        config = Path(args[args.index("--mcp-config") + 1])
        assert json.loads(config.read_text())["mcpServers"] == {"mine": PERSONAL, "tools": REMOTE}
        assert "secret" not in " ".join(args) and "--strict-mcp-config" in args
        allow = json.loads(args[args.index("--settings") + 1])["permissions"]["allow"]
        assert "mcp__mine__*" in allow and "mcp__tools__*" in allow
        read_only = client.post(messages, headers=auth, json={"prompt": "y"}).json()
        assert "mcp_servers" not in read_only
        assert "--mcp-config" not in calls(ask_dir)[-1]["args"]

        # Once the folder's own "tools" is approved too, it is the one a run gets.
        assert approve_card(own)["approved"] is True
        ran = client.post(messages, headers=auth, json={"prompt": "z", "mode": "capable"}).json()
        assert {"name": "tools", "scope": "folder", "outside_sandbox": True} in ran["mcp_servers"]
        # Revoking without a scope takes the folder's approval first, then the personal one.
        assert client.delete(f"{base}/tools", headers=auth).json() == {"revoked": True}
        assert mcp_servers.for_run(ask_dir, source) == {"mine": PERSONAL, "tools": REMOTE}
        assert client.delete(f"{base}/tools", headers=auth).json() == {"revoked": True}
        assert client.delete(f"{base}/tools", headers=auth).json() == {"revoked": False}
        assert client.delete(f"{base}/mine?scope=folder", headers=auth).json() == {"revoked": False}
        assert client.delete(f"{base}/mine?scope=other", headers=auth).status_code == 400
        assert client.delete(f"{base}/mine?scope=personal", headers=auth).json() == {
            "revoked": True
        }
        again = client.post(messages, headers=auth, json={"prompt": "w", "mode": "capable"})
        assert "mcp_servers" not in again.json()
    used = [r for r in records(isolated_config) if r["tool"] == "mcp_servers_used"]
    assert used[0]["detail"]["scopes"] == {"mine": "personal", "tools": "personal"}
    assert "secret" not in json.dumps(records(isolated_config))


def test_an_older_check_passes_no_personal_server(settings, ask_dir, auth, write_session):
    marker(capable.PERSONAL_SUITE_VERSIONS["cli"] - 1)
    source = define_personal(settings, {"mine": PERSONAL})
    approve_personal(ask_dir, source)
    session_id = write_session(ask_dir, [user_line(ask_dir, "first")])
    with TestClient(create_app(settings)) as client:
        response = client.post(
            f"/sessions/{session_id}/messages", headers=auth,
            json={"prompt": "x", "mode": "capable"},
        )  # fmt: skip
    assert response.status_code == 200 and "mcp_servers" not in response.json()
    assert "--mcp-config" not in calls(ask_dir)[-1]["args"]


def test_the_check_has_probes_for_personal_servers(settings):
    real = settings.personal_mcp_file
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    suite.probe_personal_mcp_servers()
    assert [(p.name, p.passed) for p in suite.probes] == [
        ("mcp: an unapproved personal server is never passed to a run", True),
        ("mcp: an approved personal server is passed, and only that one (sanity)", True),
        ("mcp: a changed personal server is never passed to a run", True),
        ("mcp: the values of a personal server's variables are neither shown nor put on "
         "the command line", True),
    ]  # fmt: skip
    source = suite.settings.personal_mcp_file
    assert source != real and not real.exists()  # the owner's own file is never written
    assert mcp_servers.for_run(suite.run_dir, source) == {}  # its approval was removed again
    assert json.loads((capable.config_dir() / mcp_servers.FILE_NAME).read_text()) == {}
    suite.clean_outside()
    assert not source.exists() and not list((capable.config_dir() / "mcp-run").iterdir())


def test_the_personal_server_probes_catch_a_failure(settings, monkeypatch):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    everything = lambda folder, source=None: {  # noqa: E731
        name: spec for name, spec in mcp_servers.personal_definitions(source).items()
    }
    monkeypatch.setattr(mcp_servers, "for_run", everything)  # as if approval did not matter
    suite.probe_personal_mcp_servers()
    failed = [p.name for p in suite.probes if not p.passed]
    assert failed == [
        "mcp: an unapproved personal server is never passed to a run",
        "mcp: a changed personal server is never passed to a run",
    ]
    suite.clean_outside()


def test_the_personal_server_probes_catch_secrets_on_the_command_line(settings, monkeypatch):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    # The definitions on the command line, as before #162.
    monkeypatch.setattr(mcp_servers, "run_config_file", mcp_servers.run_config)
    suite.probe_personal_mcp_servers()
    assert [p.name for p in suite.probes if not p.passed] == [
        "mcp: the values of a personal server's variables are neither shown nor put on "
        "the command line"
    ]
    suite.clean_outside()


# ------------------------------------------- a folder's servers: plain definitions only (#171)


def test_a_folder_server_with_a_field_the_card_does_not_show_cannot_be_approved(
    settings, ask_dir, auth, write_session
):
    helper = dict(REMOTE, headersHelper="./make-headers.sh")  # a program Claude Code would run
    stream = {"type": "sse", "url": "https://example.org/sse"}
    define(ask_dir, {"tools": SERVER, "helper": helper, "stream": stream})
    listed = {s["name"]: s["state"] for s in mcp_servers.listing(ask_dir)}
    assert listed == {"helper": "unsupported", "stream": "unsupported", "tools": "not_approved"}
    with pytest.raises(ClaudeError) as caught:
        mcp_servers.request("s1", ask_dir, "helper")
    assert caught.value.code == "mcp_server_unsupported" and caught.value.status == 409
    # An approval given by an older bridge, which showed no such field, passes nothing on.
    stored = {str(ask_dir.resolve()): {"helper": {"hash": mcp_servers.definition_hash(helper)}}}
    (capable.config_dir() / mcp_servers.FILE_NAME).write_text(json.dumps(stored))
    assert mcp_servers.for_run(ask_dir) == {}
    # A card made for a plain definition does not approve one that got such a field since.
    card = mcp_servers.request("s1", ask_dir, "tools")
    define(ask_dir, {"tools": dict(SERVER, headersHelper="x")})
    assert mcp_servers.answer(approvals.take("s1", card["id"]), True)["approved"] is False
    session_id = write_session(ask_dir, [user_line(ask_dir, "first")])
    with TestClient(create_app(settings)) as client:
        answer = client.post(f"/sessions/{session_id}/mcp-servers/tools/approval", headers=auth)
    assert answer.status_code == 409 and answer.json()["error"] == "mcp_server_unsupported"
