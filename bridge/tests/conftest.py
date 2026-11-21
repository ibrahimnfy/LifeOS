"""Shared fixtures. The first lines fence the whole test process off from the real home."""

import atexit
import os
import pwd
import shutil
import tempfile
from pathlib import Path

# Before anything of the bridge is imported, and never undone: the whole process, with
# every thread that outlives a test or the session, sees a temporary home and settings
# folder. A fixture that restored the real values at the end of the session once let
# relay threads of failed tests save their test pairings into the real device store.
REAL_HOME = Path(pwd.getpwuid(os.getuid()).pw_dir)
_PROCESS_BASE = Path(tempfile.mkdtemp(prefix="lifeos-tests-"))
atexit.register(shutil.rmtree, _PROCESS_BASE, ignore_errors=True)
(_PROCESS_BASE / "home").mkdir()
(_PROCESS_BASE / "config").mkdir(mode=0o700)
for _name in [name for name in os.environ if name.startswith("LIFEOS_")]:
    del os.environ[_name]
os.environ.update(
    HOME=str(_PROCESS_BASE / "home"),
    LIFEOS_CONFIG_DIR=str(_PROCESS_BASE / "config"),
    LIFEOS_NOTIFY_SCRIPT="off",
    LIFEOS_NOTIFY_PUSH="off",
    LIFEOS_GUARD_AUTOCHECK="off",
)

import json  # noqa: E402
import re  # noqa: E402
import sys  # noqa: E402
import uuid  # noqa: E402

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from lifeos_bridge.app import create_app  # noqa: E402
from lifeos_bridge.config import Settings  # noqa: E402

TEST_TOKEN = "test-token-" + "x" * 32

# A new conversation without a folder is a blank chat in a folder of its own (#163).
# Most tests are about how a run is started, not where, and read the stand-in CLI's
# call log from the memory folder: their first messages name that folder. Tests marked
# ``blank_chats`` send exactly what they wrote.
_first_messages_in_memory = True
_plain_post = TestClient.post


def _post(self, url, **kwargs):
    body = kwargs.get("json")
    if (
        _first_messages_in_memory
        and url == "/ask"
        and isinstance(body, dict)
        and "folder" not in body
        and "session_id" not in body
    ):
        kwargs["json"] = {**body, "folder": str(self.app.state.settings.ask_dir)}
    return _plain_post(self, url, **kwargs)


TestClient.post = _post


@pytest.fixture(autouse=True)
def first_messages(request):
    global _first_messages_in_memory
    _first_messages_in_memory = request.node.get_closest_marker("blank_chats") is None
    yield
    _first_messages_in_memory = True


@pytest.fixture(autouse=True)
def isolated_config(tmp_path, monkeypatch):
    """Tests never read the real ~/.config/lifeos or real LIFEOS_* variables, and never notify."""
    for name in list(os.environ):
        if name.startswith("LIFEOS_"):
            monkeypatch.delenv(name)
    config = tmp_path / "config"
    config.mkdir(mode=0o700)
    monkeypatch.setenv("LIFEOS_CONFIG_DIR", str(config))
    # Tests must never notify the owner: no real notification script, ever.
    monkeypatch.setenv("LIFEOS_NOTIFY_SCRIPT", "off")
    monkeypatch.setenv("LIFEOS_NOTIFY_PUSH", "off")
    # Tests never start a real guardrail check by themselves (test_guardcheck opts in).
    monkeypatch.setenv("LIFEOS_GUARD_AUTOCHECK", "off")
    # ...and never stop a real user service (what an interrupted check would leave).
    from lifeos_bridge import verify

    monkeypatch.setattr(verify, "stop_leftover_service", lambda: None)
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


def added_dirs(args):
    """The folders a run was given with ``--add-dir``, in order."""
    return [args[i + 1] for i, arg in enumerate(args) if arg == "--add-dir"]


def index_dir(ask_dir):
    """The folder of the conversation index every run may read (#164)."""
    return str(ask_dir.parent / "LifeOS-Chats" / ".bridge")


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
