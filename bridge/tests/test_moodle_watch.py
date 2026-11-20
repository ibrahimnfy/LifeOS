"""The daily check of the Moodle sign-in, against a stand-in site and a clock."""

import json
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import moodle_login as ml
from lifeos_bridge import moodle_watch as watch
from lifeos_bridge import reminders
from lifeos_bridge.app import create_app

SITE = "https://moodle.example.invalid"
TOKEN = "tok-secret-123"


class Clock:
    def __init__(self, start="2027-03-01T09:00:00+00:00"):
        self.now = datetime.fromisoformat(start)

    def __call__(self):
        return self.now

    def advance(self, **delta):
        self.now += timedelta(**delta)


class Site:
    """Answers the token check: ``ok``, ``expired``, ``error`` (another refusal) or ``down``."""

    def __init__(self):
        self.mode = "ok"
        self.calls = 0

    def post(self, url, data, content_type):
        self.calls += 1
        assert url.startswith(SITE) and TOKEN.encode() in data
        if self.mode == "down":
            raise ml.LoginError("the Moodle site could not be reached (URLError)")
        if self.mode == "expired":
            return {"exception": "moodle_exception", "errorcode": "invalidtoken", "message": "x"}
        if self.mode == "error":
            return {"exception": "moodle_exception", "errorcode": "sitemaintenance"}
        return {"release": "4.1.9", "functions": [{"name": "a"}], "username": "someone"}


@pytest.fixture
def site(monkeypatch):
    stand_in = Site()
    monkeypatch.setattr(ml, "_post", stand_in.post)
    return stand_in


@pytest.fixture
def notified(tmp_path):
    """A stand-in notification script that records its arguments."""
    log = tmp_path / "notified.jsonl"
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf \'%s|%s|%s\\n\' "$1" "$2" "$3" >> "{log}"\n')
    script.chmod(0o755)
    return script, lambda: log.read_text().splitlines() if log.exists() else []


def configure(config, saved="2027-02-20"):
    lines = [f"MOODLE_URL={SITE}/", f"MOODLE_TOKEN={TOKEN}"]
    if saved:
        lines.append(f"MOODLE_TOKEN_SAVED={saved}")
    path = config / "env"
    path.write_text("\n".join(lines) + "\n")
    path.chmod(0o600)


def test_nothing_happens_until_moodle_is_configured(isolated_config, site, notified):
    script, lines = notified
    assert watch.check(Clock(), script) == "not_configured"
    (isolated_config / "env").write_text(f"MOODLE_URL={SITE}\n")
    (isolated_config / "env").chmod(0o600)
    assert watch.check(Clock(), script) == "not_configured"
    assert site.calls == 0 and lines() == []
    assert not watch.state_path().exists()
    assert watch.status(Clock()) == {
        "configured": False, "token_saved": None, "age_days": None,
        "state": "not_configured", "last_check": None,
    }  # fmt: skip


def test_a_working_token_is_checked_once_per_day(isolated_config, site, notified):
    script, lines = notified
    configure(isolated_config)
    clock = Clock()
    assert watch.status(clock)["state"] == "unknown"
    assert watch.check(clock, script) == "ok"
    for _ in range(5):
        clock.advance(hours=4)
        assert watch.check(clock, script) == "not_due"
    assert site.calls == 1
    clock.advance(hours=4, seconds=1)
    assert watch.check(clock, script) == "ok"
    assert site.calls == 2 and lines() == []
    assert watch.status(clock) == {
        "configured": True, "token_saved": "2027-02-20", "age_days": 10,
        "state": "ok", "last_check": "2027-03-02T09:00:01Z",
    }  # fmt: skip
    assert watch.state_path().stat().st_mode & 0o777 == 0o600


def test_an_old_token_warns_once_per_three_days(isolated_config, site, notified):
    script, lines = notified
    configure(isolated_config, saved="2027-01-01")
    clock = Clock("2027-03-18T09:00:00+00:00")  # day 76
    assert watch.check(clock, script) == "ok" and lines() == []
    assert watch.status(clock)["state"] == "ok"
    for day in range(1, 8):  # days 77 to 83
        clock.advance(days=1)
        assert watch.status(clock)["state"] == "expiring"
        assert watch.check(clock, script) == "expiring"
        assert len(lines()) == 1 + (day - 1) // 3
    assert lines()[0] == (
        "LifeOS: Moodle sign-in needed|"
        "The Moodle sign-in will probably expire within a week. Run scripts/moodle-login.|"
    )
    assert watch.status(clock)["age_days"] == 83


def test_a_rejected_token_warns_once_per_day(isolated_config, site, notified):
    script, lines = notified
    configure(isolated_config)
    clock = Clock()
    site.mode = "expired"
    assert watch.check(clock, script) == "expired"
    assert lines() == [
        "LifeOS: Moodle sign-in needed|The Moodle sign-in has expired. Run scripts/moodle-login.|"
    ]
    clock.advance(hours=23)
    assert watch.check(clock, script) == "not_due"
    assert watch.status(clock)["state"] == "expired"
    clock.advance(hours=1)
    assert watch.check(clock, script) == "expired"
    assert len(lines()) == 2 and site.calls == 2


def test_an_expiring_warning_does_not_delay_the_expired_one(isolated_config, site, notified):
    script, lines = notified
    configure(isolated_config, saved="2026-12-01")
    clock = Clock()
    assert watch.check(clock, script) == "expiring"
    site.mode = "expired"
    clock.advance(days=1)
    assert watch.check(clock, script) == "expired"
    assert [line.split("|")[1] for line in lines()] == [watch.EXPIRING_TEXT, watch.EXPIRED_TEXT]


@pytest.mark.parametrize("mode", ["down", "error"])
def test_failures_are_not_warnings_and_are_retried_after_an_hour(
    isolated_config, site, notified, mode
):
    script, lines = notified
    configure(isolated_config, saved="2026-12-01")  # old enough to warn, if the site answered
    clock = Clock()
    site.mode = mode
    assert watch.check(clock, script) == "unreachable"
    assert watch.status(clock)["state"] == "expiring"  # by age alone; no result is invented
    clock.advance(minutes=59)
    assert watch.check(clock, script) == "not_due"
    clock.advance(minutes=1)
    assert watch.check(clock, script) == "unreachable"
    assert site.calls == 2 and lines() == []
    site.mode = "ok"
    clock.advance(hours=1)
    assert watch.check(clock, script) == "expiring"
    assert len(lines()) == 1


def test_a_crash_during_the_check_cannot_cause_a_request_storm(isolated_config, site, monkeypatch):
    configure(isolated_config)
    clock = Clock()

    def explode(url, token):
        site.calls += 1
        raise RuntimeError("unexpected")

    monkeypatch.setattr(ml, "check_token", explode)
    with pytest.raises(RuntimeError):
        watch.check(clock)
    clock.advance(seconds=30)
    assert watch.check(clock) == "not_due"
    assert site.calls == 1


def test_a_token_without_a_saved_day_gets_no_age_warning(isolated_config, site, notified):
    script, lines = notified
    configure(isolated_config, saved=None)
    clock = Clock()
    assert watch.check(clock, script) == "ok"
    status = watch.status(clock)
    assert (status["state"], status["age_days"], status["token_saved"]) == ("ok", None, None)
    assert lines() == []


def test_a_new_sign_in_is_checked_at_once(isolated_config, site, notified):
    script, _ = notified
    configure(isolated_config)
    clock = Clock()
    site.mode = "expired"
    assert watch.check(clock, script) == "expired"
    site.mode = "ok"
    ml.store_token(TOKEN, today=clock.now.date())
    assert watch.status(clock)["state"] == "unknown"
    clock.advance(seconds=30)
    assert watch.check(clock, script) == "ok"
    assert watch.status(clock)["state"] == "ok" and watch.status(clock)["age_days"] == 0


def test_nothing_identifying_reaches_notifications_or_state(isolated_config, site, notified):
    script, lines = notified
    configure(isolated_config, saved="2026-12-01")
    clock = Clock()
    watch.check(clock, script)
    site.mode = "expired"
    clock.advance(days=1)
    watch.check(clock, script)
    written = "\n".join(lines()) + watch.state_path().read_text() + json.dumps(watch.status(clock))
    assert len(lines()) == 2
    for secret in (TOKEN, "example.invalid", "someone", "https://"):
        assert secret not in written


def test_a_damaged_state_file_is_treated_as_empty(isolated_config, site):
    configure(isolated_config)
    watch.state_path().write_text("{not json")
    assert watch.check(Clock()) == "ok"
    watch.state_path().write_text('{"last_check": "nonsense", "last_warning": 5}')
    assert watch.check(Clock()) == "ok"


def test_the_bridge_runs_the_check_and_serves_the_status(
    isolated_config, settings, site, notified, auth, monkeypatch
):
    script, lines = notified
    configure(isolated_config, saved="2020-01-01")
    site.mode = "expired"
    monkeypatch.setattr(reminders, "CHECK_INTERVAL_SECONDS", 0.05)
    settings = type(settings)(**{**settings.__dict__, "notify_script": script})
    with TestClient(create_app(settings)) as client:
        assert client.get("/moodle/status").status_code == 401
        for _ in range(100):
            client.get("/health")
            if lines():
                break
        for _ in range(5):
            client.get("/health")
        answer = client.get("/moodle/status", headers=auth)
    assert len(lines()) == 1 and site.calls == 1
    assert answer.status_code == 200
    body = answer.json()
    assert set(body) == {"configured", "token_saved", "age_days", "state", "last_check"}
    assert body["state"] == "expired" and body["configured"] is True
    assert TOKEN not in answer.text and "example.invalid" not in answer.text
    assert datetime.fromisoformat(body["last_check"].replace("Z", "+00:00")).tzinfo == UTC
