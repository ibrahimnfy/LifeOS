"""The test suite must never touch the owner's real home or private config folder."""

from pathlib import Path

from conftest import REAL_HOME
from lifeos_bridge import capable
from lifeos_bridge.config import config_dir


def test_isolated_by_default(isolated_config):
    assert config_dir() == isolated_config
    assert Path.home() != REAL_HOME


def test_isolation_survives_an_undo(monkeypatch):
    # A test once called monkeypatch.undo() and then wrote a marker into the real folder.
    monkeypatch.undo()
    for path in (config_dir(), capable.marker_path("sdk"), Path.home()):
        assert path != REAL_HOME and REAL_HOME / ".config" not in path.parents
        assert not path.is_relative_to(REAL_HOME / ".config" / "lifeos")
