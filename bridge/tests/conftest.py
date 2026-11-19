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
def settings():
    return Settings(token=TEST_TOKEN)


@pytest.fixture
def client(settings):
    return TestClient(create_app(settings))


@pytest.fixture
def auth():
    return {"Authorization": f"Bearer {TEST_TOKEN}"}
