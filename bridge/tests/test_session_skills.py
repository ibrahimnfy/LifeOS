"""A capable session may use its folder's skills and commands (#91). Stand-ins only."""

import asyncio
import dataclasses
import json
import os

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import capable, claude, guardcheck, runcontrol, sdk_runner, skills, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

VERSION = "fake-claude 1.0"


def marker(runner="cli", suite=capable.SUITE_VERSION):
    data = {"runner": runner, "claude_version": VERSION, "sdk_version": capable.sdk_version(),
            "all_passed": True, "scope": "all_allowed_folders"}  # fmt: skip
    if suite is not None:
        data["suite_version"] = suite
    capable.marker_path(runner).write_text(json.dumps(capable.sign_marker(data)))


def folder_with_skills(folder):
    skill = folder / ".claude" / "skills" / "tidy"
    skill.mkdir(parents=True)
    (skill / "SKILL.md").write_text("---\nname: tidy\ndescription: Tidy up.\n---\nTidy.\n")
    (skill / "helper.sh").write_text("echo hi\n")
    commands_dir = folder / ".claude" / "commands" / "git"
    commands_dir.mkdir(parents=True)
    (commands_dir / "log.md").write_text("Show the log.\n")
    (folder / ".claude" / "settings.json").write_text('{"hooks": {"PreToolUse": []}}')
    (folder / ".mcp.json").write_text("{}")
    return folder


def option(args, name):
    return args[args.index(name) + 1] if name in args else None


# ------------------------------------------------------------------- flags and policy


def test_only_capable_runs_with_skills_get_the_folder_plugin(settings, tmp_path):
    plugin = tmp_path / "plugin"
    capable_policy = capable.build_policy(settings, settings.ask_dir, skills_dir=plugin)
    with_skills = claude.run_flags(settings, session_id="s", resume=False, policy=capable_policy)
    assert option(with_skills, "--plugin-dir") == str(plugin)
    assert "--disable-slash-commands" not in with_skills
    assert option(with_skills, "--tools").split(",")[-1] == "Skill"
    assert "--restricted" in with_skills and "--strict-mcp-config" in with_skills
    assert "--setting-sources" not in with_skills  # no settings file source is ever loaded
    settings_json = json.loads(option(with_skills, "--settings"))
    assert settings_json["disableSkillShellExecution"] is True
    assert settings_json["disableBundledSkills"] is True

    plain = capable.build_policy(settings, settings.ask_dir)
    without = claude.run_flags(settings, session_id="s", resume=False, policy=plain)
    assert "--disable-slash-commands" in without and "--plugin-dir" not in without
    assert "disableSkillShellExecution" not in json.loads(option(without, "--settings"))

    read_only = claude.READ_ONLY
    assert read_only.skills_dir is None and read_only.personal_skills_dir is None
    flags = claude.run_flags(settings, session_id="s", resume=False)
    assert "--disable-slash-commands" in flags and "--plugin-dir" not in flags
    # Even a read-only policy carrying a plugin by mistake gets no skills.
    stray = dataclasses.replace(read_only, skills_dir=plugin, personal_skills_dir=plugin)
    flags = claude.run_flags(settings, session_id="s", resume=False, policy=stray)
    assert "--disable-slash-commands" in flags and "--plugin-dir" not in flags


# ------------------------------------------------------------------- the snapshot


def test_the_snapshot_holds_only_the_folders_skills_and_commands(settings, tmp_path):
    folder = folder_with_skills(tmp_path / "home" / "projects" / "alpha")
    outside = tmp_path / "secret.md"
    outside.write_text("secret")
    os.symlink(outside, folder / ".claude" / "commands" / "linked.md")
    plugin = skills.snapshot(folder, "session-1")
    assert plugin.is_relative_to(capable.config_dir())
    files = sorted(p.relative_to(plugin).as_posix() for p in plugin.rglob("*") if p.is_file())
    assert files == [
        ".claude-plugin/plugin.json",
        "commands/git/log.md",
        "skills/tidy/SKILL.md",
        "skills/tidy/helper.sh",
    ]
    # The skill's frontmatter is written by the bridge (see test_personal_skills).
    assert (plugin / "skills" / "tidy" / "SKILL.md").read_text() == (
        '---\nname: "tidy"\ndescription: "Tidy up."\n---\nTidy.\n'
    )
    assert json.loads((plugin / ".claude-plugin" / "plugin.json").read_text())["name"] == "folder"
    assert skills.snapshot(folder, "session-1") == plugin  # unchanged: the same copy
    (folder / ".claude" / "skills" / "tidy" / "SKILL.md").write_text("changed")
    newer = skills.snapshot(folder, "session-1")
    assert newer != plugin and not plugin.exists()  # the older copy is gone


def test_the_personal_folder_is_never_copied(settings, tmp_path):
    home = tmp_path / "home"
    folder_with_skills(home)  # ~/.claude itself
    plugin = skills.snapshot(home, "s")
    assert sorted(p.name for p in plugin.rglob("*") if p.is_file()) == ["plugin.json"]


def test_messages_starting_with_a_slash_must_name_a_folder_skill(settings, tmp_path):
    folder = folder_with_skills(tmp_path / "f")
    offered = skills.for_run(settings, folder, "s", folder_skills=True, personal=True).names
    assert offered == {"folder": {"tidy", "git:log"}}  # no personal skill exists here
    assert skills.check_prompt("hello /there", offered) == "hello /there"
    assert skills.check_prompt("/tidy now", offered) == "/folder:tidy now"
    assert skills.check_prompt("  /folder:git:log", offered) == "/folder:git:log"
    for bad in ("/config model=x", "/ghost", "/folder:missing", "/compact", "/personal:tidy"):
        with pytest.raises(ClaudeError) as caught:
            skills.check_prompt(bad, offered)
        assert caught.value.code == "command_not_allowed"


# ------------------------------------------------------------------- availability


def test_skills_need_the_check_with_the_skill_probes(settings):
    with pytest.raises(ClaudeError):
        asyncio.run(capable.skills_available(settings))  # no marker at all
    marker(suite=None)  # a check from before the skill probes
    asyncio.run(capable.check_available(settings))  # the capable mode itself stays on
    with pytest.raises(ClaudeError) as caught:
        asyncio.run(capable.skills_available(settings))
    assert caught.value.code == "skills_unavailable"
    assert capable.suite_outdated("cli")
    marker()
    asyncio.run(capable.skills_available(settings))
    assert not capable.suite_outdated("cli")


def test_the_start_up_check_reruns_an_older_suite_by_itself(settings, monkeypatch):
    monkeypatch.delenv("LIFEOS_GUARD_AUTOCHECK", raising=False)
    marker("cli", suite=1)
    marker("sdk", suite=1)
    launched = []

    async def launcher(runner, check_id):
        launched.append(runner)
        marker(runner)
        return 0

    guard = guardcheck.GuardCheck(settings, launcher=launcher, notify=lambda *a: None)

    async def go():
        await guard.tick()
        await guard.task

    asyncio.run(go())
    assert launched == ["cli", "sdk"]
    asyncio.run(guard.tick())
    assert guard.task.done() and launched == ["cli", "sdk"]  # nothing more to do


# ------------------------------------------------------------------- through the API


@pytest.fixture
def world(settings, ask_dir, write_session, monkeypatch):
    monkeypatch.setattr(sdk_runner, "GRACE", 0.3)
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    folder_with_skills(ask_dir)
    session_id = write_session(ask_dir, [user_line(ask_dir, "first")])
    return session_id


def calls(ask_dir):
    path = ask_dir / ".fake-claude-calls.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


@pytest.mark.parametrize("runner", ["cli", "sdk"])
def test_a_capable_session_with_skills_on(settings, ask_dir, auth, world, runner):
    marker(runner)
    session_id = world
    with TestClient(create_app(dataclasses.replace(settings, runner=runner))) as client:
        url = f"/sessions/{session_id}"
        listed = client.get(f"{url}/commands", headers=auth).json()
        assert listed["enabled"] is False and listed["switched_on"] is False
        patched = client.patch(url, headers=auth, json={"skills": True})
        assert patched.status_code == 200 and patched.json()["skills"] is True
        listed = client.get(f"{url}/commands", headers=auth).json()
        assert listed["enabled"] is True and listed["prefix"] == "folder:"
        assert [c["name"] for c in listed["commands"]] == ["git:log", "tidy"]

        refused = client.post(
            f"{url}/messages", headers=auth, json={"prompt": "/config x", "mode": "capable"}
        )
        assert refused.status_code == 400 and refused.json()["error"] == "command_not_allowed"
        ran = client.post(
            f"{url}/messages", headers=auth, json={"prompt": "/tidy please", "mode": "capable"}
        )
        assert ran.status_code == 200
        read_only = client.post(f"{url}/messages", headers=auth, json={"prompt": "/tidy"})
        assert read_only.status_code == 200
    capable_call, read_call = [c for c in calls(ask_dir) if "args" in c][-2:]
    plugin = option(capable_call["args"], "--plugin-dir")
    assert plugin and "--disable-slash-commands" not in capable_call["args"]
    sent = capable_call.get("prompt") or ""
    if runner == "cli":
        assert sent.startswith("/folder:tidy please")
    assert option(read_call["args"], "--plugin-dir") is None
    assert "--disable-slash-commands" in read_call["args"]


def test_skills_switched_on_but_not_verified_refuse_instead_of_running_without(
    settings, auth, world
):
    marker("cli", suite=None)
    session_id = world
    with TestClient(create_app(settings)) as client:
        client.patch(f"/sessions/{session_id}", headers=auth, json={"skills": True})
        listed = client.get(f"/sessions/{session_id}/commands", headers=auth).json()
        assert listed["enabled"] is False and "skill probes" in listed["reason"]
        response = client.post(
            f"/sessions/{session_id}/messages", headers=auth,
            json={"prompt": "x", "mode": "capable"},
        )  # fmt: skip
        assert response.status_code == 503 and response.json()["error"] == "skills_unavailable"
        client.patch(f"/sessions/{session_id}", headers=auth, json={"skills": False})
        response = client.post(
            f"/sessions/{session_id}/messages", headers=auth,
            json={"prompt": "x", "mode": "capable"},
        )  # fmt: skip
        assert response.status_code == 200


def test_the_switch_must_be_a_boolean(client, auth, world):
    response = client.patch(f"/sessions/{world}", headers=auth, json={"skills": "yes"})
    assert response.status_code == 400


# ------------------------------------------------------------------- the probes


def suite_with(settings, monkeypatch, capable_run, read_only_run):
    suite = verify.Suite(settings)
    suite.run_dir.mkdir()
    suite.prepare_skills()
    monkeypatch.setattr(suite, "capable_run", capable_run)
    monkeypatch.setattr(verify, "execute", lambda *a, **k: read_only_run)
    monkeypatch.setattr(verify, "sdk_execute", lambda *a, **k: read_only_run)
    return suite


def good_run(**changes):
    run = verify.Run(0, [{"name": "Skill", "input": {}}], [], "", ["Read", "Bash", "Skill"], "",
                     "s", ["folder:approve-probe", "folder:shell-probe", "compact"])  # fmt: skip
    return dataclasses.replace(run, **changes)


READ_ONLY_RUN = verify.Run(0, [], [], "", ["Read", "Glob", "Grep"], "", "s", [])


def results(suite):
    return {p.name: p.passed for p in suite.probes}


def test_the_skill_probes_pass_when_nothing_happened(settings, monkeypatch):
    suite = suite_with(settings, monkeypatch, lambda *a, **k: good_run(), READ_ONLY_RUN)
    suite.probe_skills()
    assert len(suite.probes) == 5 and all(results(suite).values())
    suite.clean_outside()


def test_the_skill_probes_catch_each_failure(settings, monkeypatch):
    def shell_ran(*a, **k):
        (suite.run_dir / verify.SKILL_SHELL_MARK).write_text("ran")
        return good_run()

    cases = {
        "skills: the folder's skills were offered and used (otherwise the probes prove nothing)":
            lambda *a, **k: good_run(tool_uses=[]),
        "skills: a shell line in a skill does not run": shell_ran,
        "skills: tools a skill approves for itself change nothing":
            lambda *a, **k: good_run(init_tools=["Read", "WebFetch", "Skill"]),
    }  # fmt: skip
    for name, capable_run in cases.items():
        suite = suite_with(settings, monkeypatch, capable_run, READ_ONLY_RUN)
        suite.probe_skills()
        assert results(suite)[name] is False, name
        suite.clean_outside()
        suite.retire()
    leaky = dataclasses.replace(READ_ONLY_RUN, init_commands=["folder:tidy"])
    suite = suite_with(settings, monkeypatch, lambda *a, **k: good_run(), leaky)
    suite.probe_skills()
    assert results(suite)["skills: a read-only run in the same session has no skills"] is False
