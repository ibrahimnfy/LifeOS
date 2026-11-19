import json
import os
import re
import sys
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge.app import create_app
from lifeos_bridge.config import Settings

TEST_TOKEN = "test-token-" + "x" * 32


@pytest.fixture(autouse=True)
def isolated_config(tmp_path, monkeypatch):
    """Tests never read the real ~/.config/lifeos or real LIFEOS_* variables."""
    import os

    for name in list(os.environ):
        if name.startswith("LIFEOS_"):
            monkeypatch.delenv(name)
    config = tmp_path / "config"
    config.mkdir(mode=0o700)
    monkeypatch.setenv("LIFEOS_CONFIG_DIR", str(config))
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    return config


@pytest.fixture
def ask_dir(tmp_path):
    folder = tmp_path / "home" / "brain"
    folder.mkdir(parents=True)
    return folder


@pytest.fixture
def fake_claude(tmp_path):
    """A launcher for tests/fake_claude.py that works without PATH lookups."""
    launcher = tmp_path / "claude"
    script = Path(__file__).with_name("fake_claude.py")
    launcher.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{script}" "$@"\n')
    launcher.chmod(0o755)
    return launcher


@pytest.fixture
def settings(ask_dir, fake_claude):
    return Settings(token=TEST_TOKEN, ask_dir=ask_dir, claude_bin=str(fake_claude), ask_timeout=10)


@pytest.fixture
def calls(ask_dir):
    """Returns the recorded invocations of the fake claude."""

    def read():
        log = ask_dir / ".fake-claude-calls.jsonl"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    return read


@pytest.fixture
def client(settings):
    return TestClient(create_app(settings))


@pytest.fixture
def auth():
    return {"Authorization": f"Bearer {TEST_TOKEN}"}


def user_line(cwd, text, **extra):
    return {"type": "user", "cwd": str(cwd), "message": {"role": "user", "content": text}, **extra}


@pytest.fixture
def write_session(tmp_path):
    """Create a synthetic transcript the way Claude Code would store it."""

    def write(cwd: Path, lines: list, *, mtime: int = 1_800_000_000, project: str | None = None):
        session_id = str(uuid.uuid4())
        name = project or re.sub(r"[^A-Za-z0-9]", "-", str(cwd))
        folder = tmp_path / "home" / ".claude" / "projects" / name
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f"{session_id}.jsonl"
        path.write_text(
            "".join((line if isinstance(line, str) else json.dumps(line)) + "\n" for line in lines)
        )
        os.utime(path, (mtime, mtime))
        return session_id

    return write
