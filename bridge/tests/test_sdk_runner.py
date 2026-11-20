"""The Agent SDK runner (#104), with the stand-in claude in SDK mode. Nothing real is started."""

import asyncio
import dataclasses
import json
import os
import threading
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import claude, runcontrol, sdk_runner
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

POISON = {
    "ANTHROPIC_API_KEY": "sk-poison",
    "ANTHROPIC_AUTH_TOKEN": "poison",
    "CLAUDE_CODE_OAUTH_TOKEN": "poison",
    "CLAUDECODE": "1",
    "LIFEOS_BRIDGE_TOKEN": "poison",
    "AWS_SECRET_ACCESS_KEY": "poison",
}


@pytest.fixture
def sdk_settings(settings, monkeypatch):
    monkeypatch.setattr(sdk_runner, "GRACE", 0.3)
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    return dataclasses.replace(settings, runner="sdk")


def log(ask_dir):
    path = ask_dir / ".fake-claude-calls.jsonl"
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def launches(ask_dir):
    return [entry for entry in log(ask_dir) if entry.get("sdk")]


def sent(ask_dir):
    return [(entry["pid"], entry["sdk_prompt"]) for entry in log(ask_dir) if "sdk_prompt" in entry]


async def ask(pool, settings, prompt, session_id, *, resume=False, **choice):
    return await sdk_runner.run(
        pool, settings, prompt, session_id=session_id, resume=resume, **choice
    )


def new_id():
    return str(uuid.uuid4())


# ------------------------------------------------------------- binary and environment


def test_launches_the_installed_cli_with_only_the_allow_listed_environment(
    sdk_settings, fake_claude, ask_dir, monkeypatch
):
    for name, value in POISON.items():
        monkeypatch.setenv(name, value)
    started = []
    real_exec = asyncio.create_subprocess_exec

    async def spy(program, *args, **kwargs):
        started.append((program, kwargs["env"]))
        return await real_exec(program, *args, **kwargs)

    monkeypatch.setattr(sdk_runner.asyncio, "create_subprocess_exec", spy)

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        result = await ask(pool, sdk_settings, "hello", new_id())
        await pool.close_all()
        return result

    assert asyncio.run(scenario()).answer == "echo: hello"
    # Exactly the installed command named in the settings, as an absolute path.
    [(program, env)] = started
    assert program == str(fake_claude)
    assert sdk_runner.package_dir() not in fake_claude.resolve().parents
    # The child saw the allow-list plus the SDK's protocol markers, nothing else.
    [entry] = launches(ask_dir)
    allowed = set(claude.ENV_ALLOW_LIST) | set(sdk_runner.SDK_ENV) | {"PWD", "SHLVL", "_"}
    assert set(entry["env"]) <= allowed
    for name in POISON:
        assert name not in env and name not in entry["env"]
    assert entry["env"]["CLAUDE_CODE_ENTRYPOINT"] == "sdk-py"


def test_a_command_name_is_looked_up_in_path(sdk_settings, fake_claude, monkeypatch):
    monkeypatch.setenv("PATH", f"{fake_claude.parent}{os.pathsep}{os.environ['PATH']}")
    named = dataclasses.replace(sdk_settings, claude_bin="claude")
    assert sdk_runner.resolve_cli(named) == str(fake_claude)


def test_the_copy_bundled_with_the_sdk_is_refused(sdk_settings):
    bundled = sdk_runner.package_dir() / "_bundled" / "claude"
    if not bundled.exists():
        pytest.skip("this SDK build has no bundled CLI")
    with pytest.raises(ClaudeError) as caught:
        sdk_runner.resolve_cli(dataclasses.replace(sdk_settings, claude_bin=str(bundled)))
    assert caught.value.code == "claude_not_found" and "bundled" in caught.value.message


def test_missing_cli_is_a_clean_error(sdk_settings):
    with pytest.raises(ClaudeError) as caught:
        sdk_runner.resolve_cli(dataclasses.replace(sdk_settings, claude_bin="no-such-claude-x"))
    assert caught.value.code == "claude_not_found"


def test_flags_are_those_of_claude_p_with_the_bridge_as_permission_host(sdk_settings, fake_claude):
    choice = {"context": "MEMORY", "model": "sonnet", "effort": "low"}
    sdk = sdk_runner.build_command(sdk_settings, session_id="s", resume=True, **choice)
    cli = claude.build_command(sdk_settings, session_id="s", resume=True, stream=True, **choice)
    assert sdk[0] == str(fake_claude)
    assert sdk[-2:] == ["--input-format", "stream-json"]
    assert "-p" not in sdk
    # The one difference (#105): the bridge is the permission host. Default mode,
    # where a prompt reaches the bridge's callback (which denies all but the
    # owner's answers), instead of dontAsk; and the question tool.
    host = ["--permission-mode", "default", "--permission-prompt-tool", "stdio"]
    assert sdk[1:5] == cli[2:6]  # output format
    assert sdk[5:9] == host and cli[6:10] == ["--permission-mode", "dontAsk",
                                              "--permission-prompts", "none"]  # fmt: skip
    cli_rest, sdk_rest = cli[10:], sdk[9:-2]
    tools_at = cli_rest.index("--tools") + 1
    assert sdk_rest[tools_at] == cli_rest[tools_at] + ",AskUserQuestion"
    assert sdk_rest[:tools_at] + sdk_rest[tools_at + 1 :] == (
        cli_rest[:tools_at] + cli_rest[tools_at + 1 :]
    )
    for flag in ("--restricted", "--strict-mcp-config", "--disable-slash-commands"):
        assert flag in sdk
    assert sdk[sdk.index("--tools") + 1] == "Read,Glob,Grep,AskUserQuestion"
    assert "--permission-prompts" not in sdk
    assert sdk[sdk.index("--append-system-prompt") + 1] == "MEMORY"
    for absent in ("--settings", "--mcp-config", "--system-prompt", "--add-dir", "--allowedTools"):
        assert absent not in sdk


# ------------------------------------------------------------------- live sessions


def test_one_process_serves_every_message_of_a_session(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        first = await ask(pool, sdk_settings, "one", session_id)
        second = await ask(pool, sdk_settings, "two", session_id, resume=True)
        await pool.close_all()
        return first, second

    first, second = asyncio.run(scenario())
    assert (first.answer, second.answer) == ("echo: one", "echo: two")
    assert first.session_id == second.session_id == session_id
    [started] = launches(ask_dir)
    assert started["args"][started["args"].index("--session-id") + 1] == session_id
    assert len({pid for pid, _ in sent(ask_dir)}) == 1


def test_idle_sessions_close_and_the_next_message_resumes(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        await ask(pool, sdk_settings, "one", session_id)
        live = pool.live[session_id]
        await pool.reap(now=time.monotonic() + sdk_settings.sdk_idle_seconds - 5)
        assert session_id in pool.live  # not yet
        await pool.reap(now=time.monotonic() + sdk_settings.sdk_idle_seconds + 1)
        assert session_id not in pool.live
        assert live.transport.process.returncode is not None
        again = await ask(pool, sdk_settings, "two", session_id, resume=True)
        await pool.close_all()
        return again

    assert asyncio.run(scenario()).answer == "echo: two"
    first, second = launches(ask_dir)
    assert "--session-id" in first["args"]
    assert second["args"][second["args"].index("--resume") + 1] == session_id
    transcript = claude.project_dir(ask_dir) / f"{session_id}.jsonl"
    assert len(transcript.read_text().splitlines()) == 2  # nothing lost


def test_at_most_n_live_and_the_longest_idle_goes_first(sdk_settings, ask_dir):
    small = dataclasses.replace(sdk_settings, sdk_max_live=2)
    a, b, c = new_id(), new_id(), new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(small)
        await ask(pool, small, "a", a)
        await ask(pool, small, "b", b)
        await ask(pool, small, "a again", a, resume=True)  # b is now the longest idle
        await ask(pool, small, "c", c)
        live = set(pool.live)
        await pool.close_all()
        return live

    assert asyncio.run(scenario()) == {a, c}


def test_a_fourth_session_waits_when_none_is_idle(sdk_settings):
    small = dataclasses.replace(sdk_settings, sdk_max_live=1)

    async def scenario():
        pool = sdk_runner.SdkPool(small)
        first = asyncio.create_task(ask(pool, small, "FAKE_SLEEP", new_id()))
        while not (pool.live and next(iter(pool.live.values())).busy):
            await asyncio.sleep(0.01)
        with pytest.raises(ClaudeError) as caught:
            await ask(pool, small, "other", new_id())
        await next(iter(pool.live.values())).stop(0.3)
        with pytest.raises(ClaudeError):
            await first
        await pool.close_all()
        return caught.value

    error = asyncio.run(scenario())
    assert (error.code, error.status) == ("busy", 429)


def test_other_model_or_memory_restarts_with_resume(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        await ask(pool, sdk_settings, "one", session_id, model="opus")
        await ask(pool, sdk_settings, "two", session_id, resume=True, model="opus")
        await ask(pool, sdk_settings, "three", session_id, resume=True, model="haiku")
        await ask(pool, sdk_settings, "four", session_id, resume=True, model="haiku", context="new")
        await pool.close_all()

    asyncio.run(scenario())
    started = launches(ask_dir)
    assert len(started) == 3
    assert [s["args"][s["args"].index("--model") + 1] for s in started] == [
        "opus",
        "haiku",
        "haiku",
    ]
    assert all("--resume" in s["args"] for s in started[1:])


def test_a_transcript_changed_elsewhere_is_resumed_not_overwritten(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        await ask(pool, sdk_settings, "one", session_id)
        transcript = claude.project_dir(ask_dir) / f"{session_id}.jsonl"
        with transcript.open("a") as handle:  # the owner continued it in the terminal
            handle.write(json.dumps(user_line(ask_dir, "from the terminal")) + "\n")
        await ask(pool, sdk_settings, "two", session_id, resume=True)
        await pool.close_all()

    asyncio.run(scenario())
    first, second = launches(ask_dir)
    assert "--resume" in second["args"] and first["pid"] != second["pid"]


def test_interrupt_ends_the_turn_and_keeps_the_process(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        running = asyncio.create_task(ask(pool, sdk_settings, "FAKE_SLEEP", session_id))
        while not (session_id in pool.live and pool.live[session_id].busy):
            await asyncio.sleep(0.01)
        await pool.live[session_id].stop(1.0)
        with pytest.raises(ClaudeError) as caught:
            await running
        after = await ask(pool, sdk_settings, "next", session_id, resume=True)
        await pool.close_all()
        return caught.value, after

    error, after = asyncio.run(scenario())
    assert error.code == "error_during_execution"
    assert after.answer == "echo: next"
    assert len(launches(ask_dir)) == 1  # the same process answered after the interrupt
    assert any(entry.get("interrupt") for entry in log(ask_dir))


def test_a_stop_for_an_ended_turn_never_reaches_the_next_one(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        live = await pool.session_for(
            session_id, sdk_runner.Launch(sdk_settings.ask_dir, None, None, None), resume=False
        )
        old = await live.send("one")
        assert await old.wait(3)
        current = await live.send("FAKE_SLEEP")
        await live.stop(0.3, old)  # late: the turn it was meant for is over
        assert live.busy and not current.over.is_set()
        await live.stop(1.0)
        assert current.over.is_set() and not live.dead
        await pool.close_all()

    asyncio.run(scenario())
    assert sum(1 for entry in log(ask_dir) if entry.get("interrupt")) == 1


def test_a_turn_that_ignores_the_interrupt_closes_the_process(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        running = asyncio.create_task(ask(pool, sdk_settings, "FAKE_HANG", session_id))
        while not (session_id in pool.live and pool.live[session_id].busy):
            await asyncio.sleep(0.01)
        live = pool.live[session_id]
        await live.stop(0.3)
        with pytest.raises(ClaudeError):
            await running
        assert live.dead and live.transport.process.returncode is not None
        after = await ask(pool, sdk_settings, "next", session_id, resume=True)
        await pool.close_all()
        return after

    assert asyncio.run(scenario()).answer == "echo: next"
    assert "--resume" in launches(ask_dir)[1]["args"]


def test_timeout_interrupts_the_turn(sdk_settings, ask_dir):
    quick = dataclasses.replace(sdk_settings, ask_timeout=1)

    async def scenario():
        pool = sdk_runner.SdkPool(quick)
        with pytest.raises(ClaudeError) as caught:
            await ask(pool, quick, "FAKE_SLEEP", new_id())
        await pool.close_all()
        return caught.value

    assert asyncio.run(scenario()).code == "timeout"
    assert any(entry.get("interrupt") for entry in log(ask_dir))


def test_an_abandoned_stream_ends_its_turn_in_the_background(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)

        async def consume():
            async for _ in sdk_runner.stream(
                pool, sdk_settings, "FAKE_SLEEP", session_id=session_id, resume=False
            ):
                pass

        reader = asyncio.create_task(consume())
        while not (session_id in pool.live and pool.live[session_id].busy):
            await asyncio.sleep(0.01)
        reader.cancel()  # the phone went away
        with pytest.raises(asyncio.CancelledError):
            await reader
        live = pool.live[session_id]
        assert await live.wait_turn_over(3)
        after = await ask(pool, sdk_settings, "next", session_id, resume=True)
        await pool.close_all()
        return after

    assert asyncio.run(scenario()).answer == "echo: next"
    assert len(launches(ask_dir)) == 1


def test_a_process_that_dies_is_reported_and_resumed_next_time(sdk_settings, ask_dir):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        await ask(pool, sdk_settings, "one", session_id)
        with pytest.raises(ClaudeError) as caught:
            await ask(pool, sdk_settings, "FAKE_EXIT", session_id, resume=True)
        after = await ask(pool, sdk_settings, "two", session_id, resume=True)
        await pool.close_all()
        return caught.value, after

    error, after = asyncio.run(scenario())
    assert error.code == "claude_failed"
    assert after.answer == "echo: two"
    assert "--resume" in launches(ask_dir)[-1]["args"]


def test_resuming_an_unknown_session_is_not_found(sdk_settings):
    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        with pytest.raises(ClaudeError) as caught:
            await ask(pool, sdk_settings, "x", new_id(), resume=True)
        assert pool.live == {}
        return caught.value

    error = asyncio.run(scenario())
    assert (error.code, error.status) == ("session_not_found", 404)


def test_error_results_and_denials_keep_their_meaning(sdk_settings):
    session_id = new_id()

    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        with pytest.raises(ClaudeError) as caught:
            await ask(pool, sdk_settings, "FAKE_ERROR", session_id)
        denied = await ask(pool, sdk_settings, "FAKE_DENIED", session_id, resume=True)
        await pool.close_all()
        return caught.value, denied

    error, denied = asyncio.run(scenario())
    assert error.code == "error_max_turns" and "turn limit" in error.message
    assert denied.denied_tools == ["Write"]


def test_shutdown_closes_every_process(sdk_settings):
    async def scenario():
        pool = sdk_runner.SdkPool(sdk_settings)
        await ask(pool, sdk_settings, "a", new_id())
        await ask(pool, sdk_settings, "b", new_id())
        sessions = list(pool.live.values())
        await pool.close_all()
        return sessions

    for live in asyncio.run(scenario()):
        assert live.dead and live.transport.process.returncode is not None


# ----------------------------------------------------------------- through the API


@pytest.fixture
def sdk_app(sdk_settings):
    with TestClient(create_app(sdk_settings)) as client:
        yield client
        state = client.app.state
        deadline = time.time() + 10
        while (state.drains or state.slots.active) and time.time() < deadline:
            time.sleep(0.02)


def test_api_answers_keep_their_shape(sdk_app, auth, ask_dir):
    first = sdk_app.post("/ask", headers=auth, json={"prompt": "hello"})
    assert first.status_code == 200
    body = first.json()
    assert body["answer"] == "echo: hello" and body["files"] == []
    assert set(body) >= {"answer", "session_id", "duration_ms", "num_turns", "denied_tools"}
    follow = sdk_app.post(
        "/ask", headers=auth, json={"prompt": "again", "session_id": body["session_id"]}
    )
    assert follow.json()["answer"] == "echo: again"
    assert len(launches(ask_dir)) == 1


def test_api_streaming_events(sdk_app, auth):
    response = sdk_app.post("/ask", headers=auth, json={"prompt": "hi", "stream": True})
    events = [line[7:] for line in response.text.splitlines() if line.startswith("event: ")]
    assert events[0] == "session" and events[-1] == "done"
    assert events.count("delta") == 2
    done = json.loads(response.text.strip().splitlines()[-1][6:])
    assert done["answer"] == "echo: hi"


def test_api_capable_mode_is_off_on_the_sdk_runner(sdk_app, auth, ask_dir):
    response = sdk_app.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"})
    assert response.status_code == 503
    assert response.json()["error"] == "capable_mode_unavailable"
    assert launches(ask_dir) == []


def test_api_own_process_is_not_a_second_writer_but_the_terminal_is(sdk_app, auth, ask_dir):
    session_id = sdk_app.post("/ask", headers=auth, json={"prompt": "one"}).json()["session_id"]
    own_pid = launches(ask_dir)[0]["pid"]
    agents = ask_dir.parent / ".fake-claude-agents.json"
    agents.write_text(json.dumps([{"pid": own_pid, "sessionId": session_id}]))
    again = sdk_app.post("/ask", headers=auth, json={"prompt": "two", "session_id": session_id})
    assert again.status_code == 200

    agents.write_text(json.dumps([{"pid": 1, "sessionId": session_id}]))  # open in a terminal
    refused = sdk_app.post("/ask", headers=auth, json={"prompt": "3", "session_id": session_id})
    assert refused.status_code == 409 and refused.json()["error"] == "session_in_use"
    assert session_id not in sdk_app.app.state.sdk_pool.live  # ours is closed, not kept


def test_api_cancel_and_send_now_use_the_interrupt(sdk_app, auth, ask_dir):
    session_id = sdk_app.post("/ask", headers=auth, json={"prompt": "one"}).json()["session_id"]
    holder = {}

    def slow():
        holder["response"] = sdk_app.post(
            "/ask", headers=auth, json={"prompt": "FAKE_SLEEP", "session_id": session_id}
        )

    thread = threading.Thread(target=slow)
    thread.start()
    deadline = time.time() + 10
    pool = sdk_app.app.state.sdk_pool
    while not (session_id in pool.live and pool.live[session_id].busy):
        assert time.time() < deadline
        time.sleep(0.02)
    forced = sdk_app.post(
        "/ask", headers=auth, json={"prompt": "urgent", "session_id": session_id, "when": "now"}
    )
    assert forced.status_code == 202 and forced.json()["interrupted_current_run"] is True
    thread.join(10)
    assert holder["response"].status_code == 409
    assert holder["response"].json()["error"] == "cancelled"
    deadline = time.time() + 10
    while [p for _, p in sent(ask_dir)][-1:] != ["urgent"]:
        assert time.time() < deadline
        time.sleep(0.02)
    assert any(entry.get("interrupt") for entry in log(ask_dir))
    assert len(launches(ask_dir)) == 1  # interrupted, not restarted
