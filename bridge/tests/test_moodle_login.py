# ruff: noqa: E501
"""Moodle sign-in helper, against a stand-in site. No network, no real addresses."""

import base64
import hashlib
import json

import pytest

from lifeos_bridge import moodle_login as ml

SITE = "https://moodle.example.invalid"
CONFIG = {
    "wwwroot": SITE,
    "enablewebservices": 1,
    "enablemobilewebservice": 1,
    "launchurl": f"{SITE}/admin/tool/mobile/launch.php",
    "identityproviders": [
        {"name": "Institution login", "url": f"{SITE}/auth/oauth2/login.php?id=3&wantsurl=%2F"},
        {"name": "Other login", "url": f"{SITE}/auth/other/index.php"},
    ],
}


def redirect(token="tok-123", passport="pp", root=SITE, private="priv-456"):  # noqa: S107
    site_id = hashlib.md5((root + passport).encode()).hexdigest()  # noqa: S324
    payload = ":::".join([site_id, token, private])
    return "moodlemobile://token=" + base64.b64encode(payload.encode()).decode()


@pytest.fixture
def env_file(isolated_config):
    path = isolated_config / "env"
    path.write_text(f"LIFEOS_BRIDGE_TOKEN=abc\nMOODLE_URL={SITE}/\nMOODLE_TOKEN=old\n# keep me\n")
    path.chmod(0o600)
    return path


@pytest.fixture
def site(monkeypatch):
    """Answers the two calls the helper makes; records what was sent."""
    sent = []

    def post(url, data, content_type):
        sent.append((url, data))
        if "service-nologin.php" in url:
            return [{"error": False, "data": CONFIG}]
        fields = dict(pair.split("=") for pair in data.decode().split("&"))
        if fields["wstoken"] != "tok-123":
            return {"exception": "moodle_exception", "errorcode": "invalidtoken"}
        names = [*ml.NEEDED_FUNCTIONS[:-1], "something_else"]
        return {"release": "4.1.9 (Build: 1)", "downloadfiles": 1, "username": "someone",
                "functions": [{"name": n} for n in names]}  # fmt: skip

    monkeypatch.setattr(ml, "_post", post)
    return sent


def test_providers_and_link():
    found = ml.providers(CONFIG)
    assert found == [
        {"name": "Institution login", "issuer": "3"},
        {"name": "Other login", "issuer": None},
    ]
    link = ml.launch_link(CONFIG, "pp", "3")
    assert link.startswith(f"{SITE}/admin/tool/mobile/launch.php?")
    assert "service=moodle_mobile_app" in link and "passport=pp" in link
    assert "urlscheme=moodlemobile" in link and "oauthsso=3" in link
    assert "oauthsso" not in ml.launch_link(CONFIG, "pp", None)
    with pytest.raises(ml.LoginError):
        ml.launch_link({**CONFIG, "enablemobilewebservice": 0}, "pp", None)


def test_the_pasted_value_is_verified():
    assert ml.token_from_redirect(redirect(), SITE, "pp") == "tok-123"
    assert ml.token_from_redirect(f"  '{redirect()}'\n", SITE, "pp") == "tok-123"
    http_root = redirect(root=SITE.replace("https://", "http://"))
    assert ml.token_from_redirect(http_root, SITE, "pp") == "tok-123"
    for bad in [redirect(passport="another"), redirect(root="https://evil.example.invalid"),
                "moodlemobile://token=%%%", "something else", "moodlemobile://token=" + "QQ=="]:  # fmt: skip
        with pytest.raises(ml.LoginError):
            ml.token_from_redirect(bad, SITE, "pp")


def test_token_is_stored_privately_and_other_lines_are_kept(env_file):
    ml.store_token("new-token")
    text = env_file.read_text()
    assert (
        text == f"LIFEOS_BRIDGE_TOKEN=abc\nMOODLE_URL={SITE}/\n# keep me\nMOODLE_TOKEN=new-token\n"
    )
    assert env_file.stat().st_mode & 0o777 == 0o600


def test_full_run_never_prints_the_token(env_file, site, monkeypatch, capsys):
    monkeypatch.setattr("builtins.input", lambda _: "1")
    monkeypatch.setattr(ml.secrets, "token_hex", lambda _: "pp")
    monkeypatch.setattr(ml.getpass, "getpass", lambda _: redirect())
    assert ml.main([]) == 0
    out = capsys.readouterr()
    assert "oauthsso=3" in out.out and "The token works. Moodle 4.1.9" in out.out
    assert "core_calendar_get_action_events_by_timesort" in out.out  # reported as missing
    for secret in ("tok-123", "priv-456", "someone"):
        assert secret not in out.out + out.err
    assert "MOODLE_TOKEN=tok-123" in env_file.read_text()
    assert "priv-456" not in env_file.read_text()


def test_wrong_paste_stores_nothing(env_file, site, monkeypatch, capsys):
    monkeypatch.setattr("builtins.input", lambda _: "0")
    monkeypatch.setattr(ml.getpass, "getpass", lambda _: redirect(passport="stale"))
    assert ml.main([]) == 1
    assert "MOODLE_TOKEN=old" in env_file.read_text()
    assert "does not belong" in capsys.readouterr().err


def test_check_reports_an_expired_token_without_printing_it(env_file, site, capsys):
    assert ml.main(["--check"]) == 1
    err = capsys.readouterr().err
    assert "expired or was revoked" in err and "old" not in err.replace("stored", "")
    ml.store_token("tok-123")
    assert ml.main(["--check"]) == 0


def test_missing_address_and_plain_http_are_refused(isolated_config, capsys):
    assert ml.main([]) == 1
    assert "MOODLE_URL" in capsys.readouterr().err
    with pytest.raises(ml.LoginError):
        ml._post("http://moodle.example.invalid/x", b"", "text/plain")


def test_error_messages_never_contain_the_address(env_file, monkeypatch, capsys):
    def fail(url, data, content_type):
        raise ml.LoginError("the Moodle site could not be reached (URLError)")

    monkeypatch.setattr(ml, "_post", fail)
    assert ml.main(["--check"]) == 1
    assert "example.invalid" not in json.dumps(capsys.readouterr().err)
