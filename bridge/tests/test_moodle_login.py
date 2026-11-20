# ruff: noqa: E501
"""Moodle sign-in helper, against a stand-in site. No network, no real addresses."""

import base64
import hashlib
import json
import threading
from datetime import date
from pathlib import Path

import pytest

from lifeos_bridge import moodle_login as ml

REAL_RUN, REAL_SPAWN = ml._run, ml._spawn  # the autouse fixture below replaces both

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


class Desktop:
    """Stands in for xdg-open, xdg-mime and the clipboard tools; records every call."""

    def __init__(self):
        self.calls = []
        self.default = ""  # what `xdg-mime query default` answers
        self.tools = {"xdg-open", "xdg-mime"}
        self.on_open = None
        self.fail_default = False

    def which(self, name):
        return f"/fake/bin/{name}" if name in self.tools else None

    def run(self, args, text=None):
        self.calls.append(("run", [Path(args[0]).name, *args[1:]], text))
        if args[1:2] == ["query"]:
            return self.default + "\n"
        if args[1:2] == ["default"]:
            if self.fail_default:
                return None
            self.default = args[2]
        return ""

    def spawn(self, args):
        self.calls.append(("spawn", [Path(args[0]).name, *args[1:]], None))
        if self.on_open:
            self.on_open()
        return True

    def named(self, tool):
        return [call for call in self.calls if call[1][0] == tool]


@pytest.fixture(autouse=True)
def desktop(monkeypatch, tmp_path):
    """No test here can start a real desktop command, wait long, or leave the temp home."""
    fake = Desktop()
    monkeypatch.setattr(ml, "_run", fake.run)
    monkeypatch.setattr(ml, "_spawn", fake.spawn)
    monkeypatch.setattr(ml.shutil, "which", fake.which)
    monkeypatch.setattr(ml, "POLL_SECONDS", 0.01)
    monkeypatch.setattr(ml, "WAIT_SECONDS", 0.05)
    monkeypatch.delenv("XDG_DATA_HOME", raising=False)
    monkeypatch.delenv("XDG_CONFIG_HOME", raising=False)
    assert ml.applications_dir().is_relative_to(tmp_path)
    assert all(path.is_relative_to(tmp_path) for path in ml._mimeapps_files())
    return fake


def entry_file():
    return ml.applications_dir() / ml.DESKTOP_FILE


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
    (env_file.parent / ml.WATCH_FILE).write_text("{}")
    ml.store_token("new-token", today=date(2027, 1, 15))
    text = env_file.read_text()
    assert text == (
        f"LIFEOS_BRIDGE_TOKEN=abc\nMOODLE_URL={SITE}/\n# keep me\n"
        "MOODLE_TOKEN=new-token\nMOODLE_TOKEN_SAVED=2027-01-15\n"
    )
    assert env_file.stat().st_mode & 0o777 == 0o600
    assert not (env_file.parent / ml.WATCH_FILE).exists()  # the new token is checked at once
    ml.store_value("MOODLE_LOGIN_ISSUER", "3")
    ml.store_value("MOODLE_LOGIN_ISSUER", "none")
    assert env_file.read_text() == text + "MOODLE_LOGIN_ISSUER=none\n"
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


# ---- browser, address handler, callback (issue 99) ---------------------------


def test_the_link_is_opened_in_the_browser_and_printed_on_one_line(
    env_file, site, desktop, monkeypatch, capsys
):
    monkeypatch.setattr("builtins.input", lambda _: "1")
    monkeypatch.setattr(ml.getpass, "getpass", lambda _: redirect(passport=passport_of(desktop)))
    assert ml.main(["--no-handler"]) == 0
    opened = desktop.named("xdg-open")
    assert len(opened) == 1 and opened[0][0] == "spawn"
    link = opened[0][1][1]
    assert link.startswith(f"{SITE}/admin/tool/mobile/launch.php?") and "oauthsso=3" in link
    out = capsys.readouterr().out
    assert "only needed if the browser did not open" in out
    assert link in out.splitlines()  # alone on its line: nothing to select by hand
    assert desktop.named("xdg-mime") == [] and not entry_file().exists()
    assert "LifeOS Moodle sign-in" not in out and "is also on the clipboard" not in out


def passport_of(desktop):
    link = desktop.named("xdg-open")[0][1][1]
    return link.split("passport=")[1].split("&")[0]


@pytest.mark.parametrize(
    ("tool", "arguments"), [("wl-copy", []), ("xclip", ["-selection", "clipboard"])]
)
def test_the_clipboard_is_offered_when_a_tool_exists(desktop, capsys, tool, arguments):
    desktop.tools.add(tool)
    ml.show_link("https://moodle.example.invalid/x?passport=1")
    assert desktop.named(tool) == [
        ("run", [tool, *arguments], "https://moodle.example.invalid/x?passport=1")
    ]
    assert "also on the clipboard" in capsys.readouterr().out


def test_the_handler_is_registered_only_while_the_block_runs(desktop, tmp_path):
    desktop.default = "other-app.desktop"
    with ml.signin_handler() as registered:
        assert registered is True
        text = entry_file().read_text()
        assert entry_file().is_relative_to(tmp_path)
        assert "Name=LifeOS Moodle sign-in\n" in text and "NoDisplay=true\n" in text
        assert "MimeType=x-scheme-handler/moodlemobile;\n" in text
        exec_line = next(line for line in text.splitlines() if line.startswith("Exec="))
        assert exec_line.endswith("/scripts/moodle-callback %u")
        assert "LIFEOS_CONFIG_DIR=" in exec_line  # the handler writes where this run reads
        assert desktop.default == ml.DESKTOP_FILE
    assert desktop.default == "other-app.desktop"
    assert not entry_file().exists()
    commands = [call[1][1:] for call in desktop.named("xdg-mime")]
    assert commands == [
        ["query", "default", "x-scheme-handler/moodlemobile"],
        ["default", ml.DESKTOP_FILE, "x-scheme-handler/moodlemobile"],
        ["default", "other-app.desktop", "x-scheme-handler/moodlemobile"],
    ]


def test_without_a_previous_default_the_association_is_removed(desktop, monkeypatch):
    config, legacy = ml._mimeapps_files()
    config.parent.mkdir(parents=True)
    kept = "[Default Applications]\ntext/html=browser.desktop\n"
    desktop.default = (
        ml.DESKTOP_FILE
    )  # left behind by a run that was killed: not a previous default
    real_run = desktop.run

    def run(args, text=None):  # what the real xdg-mime does: it writes the lists
        answer = real_run(args, text)
        if args[1:2] == ["default"]:
            config.write_text(kept + f"x-scheme-handler/moodlemobile={args[2]}\n")
            legacy.write_text(f"[Default Applications]\nx-scheme-handler/moodlemobile={args[2]};\n")
        return answer

    monkeypatch.setattr(ml, "_run", run)
    with ml.signin_handler() as registered:
        assert registered and "moodlemobile" in config.read_text()
    assert config.read_text() == kept
    assert not legacy.exists()  # it held nothing but our line
    assert not entry_file().exists()
    assert [call[1][1] for call in desktop.named("xdg-mime")] == ["query", "default"]


def test_the_handler_is_removed_on_failure_and_on_ctrl_c(
    env_file, site, desktop, monkeypatch, capsys
):
    desktop.default = "other-app.desktop"
    monkeypatch.setattr("builtins.input", lambda _: "0")
    seen = []

    def wait(error):
        def waiting():
            seen.append((entry_file().exists(), desktop.default))
            ml.receive_callback(redirect())  # must not survive either
            raise error

        return waiting

    for error in (KeyboardInterrupt(), ml.LoginError("something failed"), RuntimeError("bug")):
        monkeypatch.setattr(ml, "wait_for_callback", wait(error))
        if isinstance(error, RuntimeError):
            with pytest.raises(RuntimeError):
                ml.main([])
        else:
            assert ml.main([]) == 1
        assert not entry_file().exists() and desktop.default == "other-app.desktop"
        assert not ml.callback_path().exists()
    assert seen == [(True, ml.DESKTOP_FILE)] * 3
    assert "Cancelled; nothing was stored." in capsys.readouterr().err
    assert "MOODLE_TOKEN=old" in env_file.read_text()


def test_sign_in_through_the_handler_end_to_end(env_file, site, desktop, monkeypatch, capsys):
    """The browser hands the address to scripts/moodle-callback while `main` waits."""
    monkeypatch.setattr(ml, "WAIT_SECONDS", 20)
    monkeypatch.setattr("builtins.input", lambda _: "1")
    monkeypatch.setattr(ml.secrets, "token_hex", lambda _: "pp")
    monkeypatch.setattr(ml.getpass, "getpass", lambda _: pytest.fail("no paste is needed"))
    ml.receive_callback(redirect(token="stale", passport="earlier"))  # from an earlier attempt
    results = []

    def browser():
        state = (entry_file().exists(), desktop.default, ml.callback_path().exists())
        results.append(state)
        results.append(ml.main(["--callback", redirect()]))

    thread = threading.Thread(target=browser)
    desktop.on_open = thread.start
    assert ml.main([]) == 0
    thread.join(5)
    assert results == [(True, ml.DESKTOP_FILE, False), 0]
    assert "MOODLE_TOKEN=tok-123" in env_file.read_text()
    assert f"MOODLE_TOKEN_SAVED={date.today().isoformat()}" in env_file.read_text()
    assert not ml.callback_path().exists() and not entry_file().exists()
    out = capsys.readouterr()
    assert 'open the link with\n     "LifeOS Moodle sign-in". Allow it.' in out.out
    assert "Registered until this script ends" in out.out
    assert "Waiting for the sign-in in your browser… (Ctrl-C to cancel)" in out.out
    for secret in ("tok-123", "priv-456", "someone", redirect(), redirect().split("=", 1)[1]):
        assert secret not in out.out + out.err
    assert "F12" not in out.out


def test_the_callback_file_is_private_and_other_schemes_are_refused(isolated_config, capsys):
    for bad in ("https://moodle.example.invalid/?token=abc", "moodlemobile:token=abc", "",
                "file:///etc/passwd", "moodlemobile://token=" + "A" * 9000):  # fmt: skip
        assert ml.receive_callback(bad) == 2
        assert ml.main([f"--callback={bad}"]) == 2
    assert not ml.callback_path().exists()
    assert ml.main(["--callback=moodlemobile://token=abc"]) == 0
    assert ml.callback_path().read_text() == "moodlemobile://token=abc"
    assert ml.callback_path().stat().st_mode & 0o777 == 0o600
    assert [p.name for p in isolated_config.iterdir()] == ["moodle-callback"]
    out = capsys.readouterr()
    assert out.out == "" and "abc" not in out.err and "example.invalid" not in out.err
    assert ml._take_callback() == "moodlemobile://token=abc" and not ml.callback_path().exists()


def test_a_callback_of_another_attempt_stores_nothing(env_file, site, desktop, monkeypatch, capsys):
    monkeypatch.setattr("builtins.input", lambda _: "0")
    desktop.on_open = lambda: ml.receive_callback(redirect(passport="another"))
    assert ml.main([]) == 1
    assert "does not belong" in capsys.readouterr().err
    assert "MOODLE_TOKEN=old" in env_file.read_text() and not ml.callback_path().exists()


@pytest.mark.parametrize("how", ["timeout", "enter", "not_registered"])
def test_fallback_to_paste(env_file, site, desktop, monkeypatch, capsys, how):
    monkeypatch.setattr("builtins.input", lambda _: "1")
    monkeypatch.setattr(ml.secrets, "token_hex", lambda _: "pp")
    monkeypatch.setattr(ml.getpass, "getpass", lambda _: redirect())
    if how == "enter":
        monkeypatch.setattr(ml, "WAIT_SECONDS", 20)
        monkeypatch.setattr(ml, "_enter_pressed", lambda seconds: True)
    desktop.fail_default = how == "not_registered"
    assert ml.main([]) == 0
    out = capsys.readouterr().out
    assert "MOODLE_TOKEN=tok-123" in env_file.read_text()
    assert not entry_file().exists() and not ml.callback_path().exists()
    if how == "not_registered":
        assert "could not be registered" in out and "Waiting for the sign-in" not in out
    else:
        assert "Nothing arrived from the browser" in out
    assert "tok-123" not in out


def test_the_sign_in_button_is_remembered(env_file, site, desktop, monkeypatch, capsys):
    asked = []

    def answer(value):
        def ask(_):
            asked.append(value)
            return value

        return ask

    monkeypatch.setattr(ml.secrets, "token_hex", lambda _: "pp")
    monkeypatch.setattr(ml.getpass, "getpass", lambda _: redirect())
    monkeypatch.setattr("builtins.input", answer("1"))
    assert ml.main(["--no-handler"]) == 0
    assert "MOODLE_LOGIN_ISSUER=3\n" in env_file.read_text()
    assert "Institution login" not in env_file.read_text()  # the number only, never the name

    assert ml.main(["--no-handler"]) == 0  # not asked again
    assert asked == ["1"]
    out = capsys.readouterr().out
    assert "chosen last time (--choose asks again)" in out
    assert "oauthsso=3" in desktop.named("xdg-open")[-1][1][1]

    monkeypatch.setattr("builtins.input", answer("0"))
    assert ml.main(["--no-handler", "--choose"]) == 0
    assert asked == ["1", "0"]
    assert "MOODLE_LOGIN_ISSUER=none\n" in env_file.read_text()
    assert "oauthsso" not in desktop.named("xdg-open")[-1][1][1]
    assert ml.main(["--no-handler"]) == 0  # "none" is remembered too
    assert asked == ["1", "0"]

    ml.store_value("MOODLE_LOGIN_ISSUER", "9")  # a button the site no longer offers
    monkeypatch.setattr("builtins.input", answer("1"))
    assert ml.main(["--no-handler"]) == 0
    assert asked == ["1", "0", "1"]


def test_a_failed_sign_in_does_not_remember_the_button(env_file, site, monkeypatch):
    monkeypatch.setattr("builtins.input", lambda _: "1")
    monkeypatch.setattr(ml.getpass, "getpass", lambda _: "something else")
    assert ml.main(["--no-handler"]) == 1
    assert "MOODLE_LOGIN_ISSUER" not in env_file.read_text()
    assert "MOODLE_TOKEN_SAVED" not in env_file.read_text()


def test_rejection_and_network_failure_are_told_apart(env_file, site, monkeypatch):
    with pytest.raises(ml.TokenRejected) as refusal:
        ml.check_token(SITE, "old")
    assert refusal.value.expired
    monkeypatch.setattr(ml, "_post", lambda *a: {"exception": "x", "errorcode": "sitemaintenance"})
    with pytest.raises(ml.TokenRejected) as refusal:
        ml.check_token(SITE, "old")
    assert not refusal.value.expired


def test_exec_arguments_are_plain_when_safe_and_quoted_otherwise(monkeypatch):
    assert (
        ml._quoted("/opt/life-os/scripts/moodle-callback") == "/opt/life-os/scripts/moodle-callback"
    )
    assert ml._quoted("/a folder/50%/$x") == '"/a folder/50%%/\\$x"'
    monkeypatch.delenv("LIFEOS_CONFIG_DIR")
    entry = ml.desktop_entry(Path("/opt/lifeos/scripts/moodle-callback"))
    assert "Exec=/opt/lifeos/scripts/moodle-callback %u\n" in entry


def test_the_real_command_runners_with_a_harmless_command(tmp_path):
    """The seams themselves, with the Python interpreter only: never a desktop tool."""
    import sys
    import time

    python = sys.executable
    assert REAL_RUN([python, "-c", "print('out')"]) == "out\n"
    assert REAL_RUN([python, "-c", "import sys; sys.exit(3)"]) is None
    assert REAL_RUN([str(tmp_path / "missing-tool")]) is None
    target = tmp_path / "received"
    script = f"import sys; open({str(target)!r}, 'w').write(sys.stdin.read())"
    assert REAL_RUN([python, "-c", script], "the text") == ""
    assert target.read_text() == "the text"
    assert REAL_SPAWN([str(tmp_path / "missing-tool")]) is False
    marker = tmp_path / "spawned"
    assert REAL_SPAWN([python, "-c", f"open({str(marker)!r}, 'w').write('x')"]) is True
    for _ in range(200):
        if marker.exists():
            break
        time.sleep(0.02)
    assert marker.exists()
