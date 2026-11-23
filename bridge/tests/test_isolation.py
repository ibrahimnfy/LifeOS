"""The test suite must never touch the owner's real home or private config folder."""

import os
import subprocess
import sys
import threading
from pathlib import Path

import pytest

from conftest import REAL_HOME
from lifeos_bridge import capable, devices
from lifeos_bridge.config import config_dir


def test_isolated_by_default(isolated_config):
    assert config_dir() == isolated_config
    assert Path.home() != REAL_HOME


def test_isolation_survives_an_undo(monkeypatch):
    # A test once called monkeypatch.undo() and then wrote a marker into the real folder.
    monkeypatch.undo()
    for path in (config_dir(), capable.marker_path(), Path.home()):
        assert path != REAL_HOME and REAL_HOME / ".config" not in path.parents
        assert not path.is_relative_to(REAL_HOME / ".config" / "lifeos")


def test_the_real_values_are_gone_from_the_process_itself(monkeypatch):
    # Not a fixture that is undone at the end: threads that outlive a failed test, or the
    # whole session, still see the temporary folders.
    monkeypatch.undo()
    seen = {}
    thread = threading.Thread(target=lambda: seen.update(home=Path.home(), config=config_dir()))
    thread.start()
    thread.join()
    assert REAL_HOME not in (seen["home"], *seen["home"].parents)
    assert REAL_HOME not in seen["config"].parents
    assert not [name for name in os.environ if name.startswith("LIFEOS_RELAY")]


def test_the_real_settings_folder_is_refused_even_when_asked_for(monkeypatch):
    real = REAL_HOME / ".config" / "lifeos"
    monkeypatch.setenv("LIFEOS_CONFIG_DIR", str(real))
    with pytest.raises(RuntimeError):
        config_dir()
    with pytest.raises(RuntimeError):
        devices.begin_pairing("Test phone", {})
    monkeypatch.delenv("LIFEOS_CONFIG_DIR")
    monkeypatch.setenv("HOME", str(REAL_HOME))
    with pytest.raises(RuntimeError):
        config_dir()


def test_a_thread_that_outlives_a_failed_session_writes_nothing_real(tmp_path):
    """The leak of 2026-11-21, replayed: a test fails, its thread saves after the session."""
    (tmp_path / "conftest.py").write_text(Path(__file__).with_name("conftest.py").read_text())
    (tmp_path / "test_leak.py").write_text(
        "import threading, time\n"
        "from lifeos_bridge import devices\n"
        "def late():\n"
        "    time.sleep(1.0)\n"
        "    devices.begin_pairing('Test phone', {})\n"
        "def test_fails_and_leaves_a_thread():\n"
        "    threading.Thread(target=late).start()\n"
        "    assert False\n"
    )
    outer = tmp_path / "outer-config"
    outer.mkdir(mode=0o700)
    env = dict(os.environ, LIFEOS_CONFIG_DIR=str(outer), HOME=str(tmp_path / "outer-home"))
    done = subprocess.run(  # noqa: S603
        [sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider", str(tmp_path)],
        env=env, capture_output=True, text=True, timeout=120, cwd=tmp_path,
    )  # fmt: skip
    assert "1 failed" in done.stdout
    assert list(outer.iterdir()) == []  # nothing reached what the outer process calls real
