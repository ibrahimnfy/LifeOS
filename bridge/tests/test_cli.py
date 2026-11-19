"""scripts/ask against a real bridge instance (with the fake claude)."""

import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest
import uvicorn

from conftest import TEST_TOKEN
from lifeos_bridge.app import create_app

ASK = Path(__file__).resolve().parents[2] / "scripts" / "ask"


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@pytest.fixture
def bridge(settings, isolated_config):
    port = free_port()
    server = uvicorn.Server(
        uvicorn.Config(create_app(settings), host="127.0.0.1", port=port, log_level="error")
    )
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    while not server.started:
        time.sleep(0.01)
    env_file = isolated_config / "env"
    env_file.write_text(f"LIFEOS_BRIDGE_TOKEN={TEST_TOKEN}\nLIFEOS_BRIDGE_PORT={port}\n")
    env_file.chmod(0o600)
    yield port
    server.should_exit = True
    thread.join(timeout=5)


def ask(*args, stdin=None):
    return subprocess.run(  # noqa: S603
        [sys.executable, str(ASK), *args], capture_output=True, text=True, input=stdin, timeout=30
    )


def test_health(bridge):
    result = ask("--health")
    assert result.returncode == 0
    assert "token accepted" in result.stdout
    assert TEST_TOKEN not in result.stdout + result.stderr


def test_ask_and_continue(bridge, calls):
    first = ask("hello", "there")
    assert first.returncode == 0 and first.stdout == "echo: hello there\n"
    second = ask("-c", "again")
    assert second.returncode == 0 and second.stdout == "echo: again\n"
    recorded = calls()
    assert "--resume" in recorded[1]["args"]


def test_prompt_from_stdin_and_stream(bridge):
    result = ask("--stream", "-", stdin="from stdin")
    assert result.returncode == 0
    assert result.stdout == "echo: from stdin\n"


def test_json_output_and_denied_tools(bridge):
    result = ask("--json", "hello")
    assert '"session_id"' in result.stdout
    denied = ask("FAKE_DENIED")
    assert "[not permitted: Write]" in denied.stderr


def test_sessions_and_resume(bridge, ask_dir, write_session):
    from conftest import user_line

    session_id = write_session(ask_dir, [user_line(ask_dir, "an earlier question")])
    listing = ask("--sessions")
    assert session_id in listing.stdout and "an earlier question" in listing.stdout
    resumed = ask("--resume", session_id, "more")
    assert resumed.returncode == 0 and resumed.stdout == "echo: more\n"


def test_bridge_error_is_reported(bridge):
    result = ask("FAKE_ERROR")
    assert result.returncode == 1
    assert "error_max_turns" in result.stderr


def test_continue_without_history(bridge):
    result = ask("-c", "x")
    assert result.returncode == 2


def test_wrong_token(bridge, isolated_config):
    env_file = isolated_config / "env"
    env_file.write_text(env_file.read_text().replace(TEST_TOKEN, "wrong-" + "y" * 40))
    result = ask("hello")
    assert result.returncode == 4
    assert "rejected the token" in result.stderr


def test_bridge_down(isolated_config):
    env_file = isolated_config / "env"
    env_file.write_text(f"LIFEOS_BRIDGE_TOKEN={TEST_TOKEN}\nLIFEOS_BRIDGE_PORT={free_port()}\n")
    result = ask("hello")
    assert result.returncode == 3
    assert "cannot reach the bridge" in result.stderr
    assert TEST_TOKEN not in result.stdout + result.stderr


def test_missing_token(isolated_config):
    result = ask("hello")
    assert result.returncode == 2
    assert "LIFEOS_BRIDGE_TOKEN" in result.stderr


def test_no_prompt_shows_usage(bridge):
    assert ask().returncode == 2
