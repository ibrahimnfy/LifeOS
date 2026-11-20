"""Firebase settings handling. No network; the files are synthetic."""

import json

import pytest

from lifeos_bridge import google, relaycheck


def write_settings(config, **project_info):
    folder = config / "firebase"
    folder.mkdir(mode=0o700)
    info = {"project_id": "example-project", "firebase_url": "https://example.invalid/"}
    info.update(project_info)
    (folder / "google-services.json").write_text(
        json.dumps({"project_info": info, "client": [{"api_key": [{"current_key": "k"}]}]})
    )
    return folder


def test_project_settings_are_read(isolated_config):
    write_settings(isolated_config)
    assert google.read_project() == {
        "project_id": "example-project",
        "database_url": "https://example.invalid",
        "api_key": "k",
    }


def test_missing_or_incomplete_settings_give_a_clear_error(isolated_config):
    with pytest.raises(google.FirebaseConfigError):
        google.read_project()
    folder = write_settings(isolated_config, firebase_url="http://example.invalid")
    with pytest.raises(google.FirebaseConfigError):
        google.read_project()
    (folder / "google-services.json").write_text("{}")
    with pytest.raises(google.FirebaseConfigError):
        google.read_project()


def test_key_file_must_be_private_and_valid(isolated_config):
    folder = write_settings(isolated_config)
    with pytest.raises(google.FirebaseConfigError):
        google.access_token(google.DATABASE_SCOPES)  # missing
    key = folder / "service-account.json"
    key.write_text("{}")
    key.chmod(0o644)
    with pytest.raises(google.FirebaseConfigError, match="other users"):
        google.access_token(google.DATABASE_SCOPES)
    key.chmod(0o600)
    with pytest.raises(google.FirebaseConfigError, match="unusable"):
        google.access_token(google.DATABASE_SCOPES)


def test_the_transport_refuses_plain_http():
    with pytest.raises(ValueError, match="https"):
        google.UrllibRequest()("http://example.invalid/")


def test_relay_check_without_settings_exits_with_a_configuration_error(isolated_config, capsys):
    assert relaycheck.main(["--idle", "0"]) == 2
    assert "settings file" in capsys.readouterr().err
