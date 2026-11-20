from pathlib import Path

import pytest

from lifeos_bridge.config import (
    ConfigError,
    Settings,
    load_settings,
    parse_env_text,
    validate_host,
)

TOKEN = "t" * 40


def write_env(config_dir: Path, text: str, mode: int = 0o600) -> Path:
    path = config_dir / "env"
    path.write_text(text)
    path.chmod(mode)
    return path


def test_parse_env_text_handles_shell_style_lines():
    text = """
    # comment
    PLAIN=value
    export EXPORTED=yes
    QUOTED="with spaces"
    SINGLE='single'
    URL=https://example.org/path?a=b
    EMPTY=
    not a variable line
    """
    assert parse_env_text(text) == {
        "PLAIN": "value",
        "EXPORTED": "yes",
        "QUOTED": "with spaces",
        "SINGLE": "single",
        "URL": "https://example.org/path?a=b",
        "EMPTY": "",
    }


def test_example_file_parses_and_sets_nothing_secret():
    example = Path(__file__).resolve().parents[2] / "config" / "env.example"
    values = parse_env_text(example.read_text())
    assert not values.get("LIFEOS_BRIDGE_TOKEN")


def test_load_settings_from_file(isolated_config):
    write_env(isolated_config, f"LIFEOS_BRIDGE_TOKEN={TOKEN}\nLIFEOS_BRIDGE_PORT=9000\n")
    settings = load_settings()
    assert settings.token == TOKEN
    assert settings.host == "127.0.0.1"
    assert settings.port == 9000


def test_environment_overrides_file(isolated_config, monkeypatch):
    write_env(isolated_config, f"LIFEOS_BRIDGE_TOKEN={TOKEN}\nLIFEOS_BRIDGE_PORT=9000\n")
    monkeypatch.setenv("LIFEOS_BRIDGE_PORT", "9001")
    assert load_settings().port == 9001


def test_missing_token_refuses_to_start(isolated_config):
    write_env(isolated_config, "LIFEOS_BRIDGE_PORT=9000\n")
    with pytest.raises(ConfigError, match="LIFEOS_BRIDGE_TOKEN"):
        load_settings()


def test_missing_file_refuses_to_start():
    with pytest.raises(ConfigError, match="LIFEOS_BRIDGE_TOKEN"):
        load_settings()


def test_short_token_is_refused(isolated_config):
    write_env(isolated_config, "LIFEOS_BRIDGE_TOKEN=short\n")
    with pytest.raises(ConfigError, match="at least"):
        load_settings()


def test_env_file_readable_by_others_is_refused(isolated_config):
    write_env(isolated_config, f"LIFEOS_BRIDGE_TOKEN={TOKEN}\n", mode=0o644)
    with pytest.raises(ConfigError, match="chmod 600"):
        load_settings()


@pytest.mark.parametrize("host", ["127.0.0.1", "::1", "localhost", "127.0.0.2"])
def test_allowed_hosts(host):
    assert validate_host(host) == host


@pytest.mark.parametrize(
    "host",
    [
        "0.0.0.0",  # noqa: S104
        "::",
        "192.168.1.10",
        "8.8.8.8",
        "100.100.1.2",
        "fd7a:115c:a1e0::1",
        "example.org",
        "",
    ],
)
def test_refused_hosts(host):
    with pytest.raises(ConfigError):
        validate_host(host)


@pytest.mark.parametrize("port", ["80", "70000", "abc"])
def test_invalid_port(isolated_config, port):
    write_env(isolated_config, f"LIFEOS_BRIDGE_TOKEN={TOKEN}\nLIFEOS_BRIDGE_PORT={port}\n")
    with pytest.raises(ConfigError, match="LIFEOS_BRIDGE_PORT"):
        load_settings()


def test_settings_repr_hides_token():
    assert TOKEN not in repr(Settings(token=TOKEN))


def test_runner_and_sdk_pool_settings(isolated_config, monkeypatch):
    write_env(isolated_config, f"LIFEOS_BRIDGE_TOKEN={TOKEN}\n")
    settings = load_settings()
    assert (settings.runner, settings.sdk_idle_seconds, settings.sdk_max_live) == ("cli", 900, 3)
    monkeypatch.setenv("LIFEOS_RUNNER", "SDK")
    monkeypatch.setenv("LIFEOS_SDK_IDLE_MINUTES", "5")
    monkeypatch.setenv("LIFEOS_SDK_MAX_LIVE", "2")
    settings = load_settings()
    assert (settings.runner, settings.sdk_idle_seconds, settings.sdk_max_live) == ("sdk", 300, 2)
    monkeypatch.setenv("LIFEOS_RUNNER", "api")
    with pytest.raises(ConfigError):
        load_settings()
    monkeypatch.setenv("LIFEOS_RUNNER", "cli")
    monkeypatch.setenv("LIFEOS_SDK_MAX_LIVE", "0")
    with pytest.raises(ConfigError):
        load_settings()
