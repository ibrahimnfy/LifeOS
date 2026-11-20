"""Session reminders. Times are driven by a controllable clock; transcripts are synthetic."""

from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import reminders
from lifeos_bridge.app import create_app

FUTURE = "2099-01-15T09:00:00+03:00"


class Clock:
    def __init__(self, start: str):
        self.now = datetime.fromisoformat(start)

    def __call__(self):
        return self.now

    def advance(self, **delta):
        self.now += timedelta(**delta)


@pytest.fixture
def session(ask_dir, write_session):
    return write_session(ask_dir, [user_line(ask_dir, "first")])


@pytest.fixture
def notified(tmp_path):
    """A stand-in notify script that records its arguments."""
    log = tmp_path / "notify.log"
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf "%s|%s|%s\\n" "$1" "$2" "$3" >> "{log}"\n')
    script.chmod(0o755)
    return script, lambda: log.read_text().splitlines() if log.exists() else []


def create(client, auth, session, **fields):
    body = {"session_id": session, "at": FUTURE, **fields}
    return client.post("/reminders", headers=auth, json=body)


def test_routes_require_the_token(client):
    assert client.get("/reminders").status_code == 401
    assert client.post("/reminders", json={}).status_code == 401
    assert client.delete("/reminders/x").status_code == 401


def test_create_list_update_delete(client, auth, session, isolated_config):
    created = create(client, auth, session, note=" check the draft ", repeat="weekly")
    assert created.status_code == 201
    reminder = created.json()
    assert reminder["at"] == "2099-01-15T06:00:00Z"
    assert (reminder["note"], reminder["repeat"], reminder["state"]) == (
        "check the draft",
        "weekly",
        "scheduled",
    )
    assert (isolated_config / "reminders.json").stat().st_mode & 0o777 == 0o600
    assert client.get("/reminders", headers=auth).json() == {"reminders": [reminder]}
    assert client.get("/reminders", headers=auth, params={"session_id": "other"}).json() == {
        "reminders": []
    }
    changed = client.patch(
        f"/reminders/{reminder['id']}", headers=auth, json={"note": None, "repeat": "none"}
    ).json()
    assert (changed["note"], changed["repeat"]) == (None, "none")
    assert client.delete(f"/reminders/{reminder['id']}", headers=auth).json() == {"deleted": True}
    assert client.delete(f"/reminders/{reminder['id']}", headers=auth).status_code == 404


def test_only_sessions_in_allowed_folders(client, auth, tmp_path, write_session):
    other = tmp_path / "home" / "private-project"
    other.mkdir()
    outside = write_session(other, [user_line(other, "secret work")])
    assert create(client, auth, outside).status_code == 404
    assert create(client, auth, "no-such-session").status_code == 404
    assert client.post("/reminders", headers=auth, json={"at": FUTURE}).status_code == 404


@pytest.mark.parametrize(
    "fields",
    [
        {"at": "tomorrow"},
        {"at": "2099-01-15T09:00:00"},  # no offset
        {"at": "2001-01-01T09:00:00+00:00"},  # past, one-off
        {"at": 5},
        {"repeat": "hourly"},
        {"note": "x" * 501},
        {"timezone": "Nowhere/City"},
        {"colour": "red"},
    ],
)
def test_bad_reminders_are_refused(client, auth, session, fields):
    assert create(client, auth, session, **fields).status_code == 400


def test_one_off_fires_once_without_revealing_the_note(session, notified):
    script, lines = notified
    clock = Clock("2027-01-15T08:00:00+00:00")
    reminder = reminders.create(
        session, {"at": "2027-01-15T09:00:00+00:00", "note": "private words"}, clock
    )
    assert reminders.fire_due(clock, script) == []
    clock.advance(hours=1)
    (fired,) = reminders.fire_due(clock, script)
    assert fired["id"] == reminder["id"] and fired["state"] == "fired"
    assert reminders.fire_due(clock, script) == []
    (line,) = lines()
    assert "private words" not in line and session not in line
    assert reminders.listing(state="fired")[0]["note"] == "private words"


def test_missed_reminders_fire_after_a_restart(session, notified):
    script, lines = notified
    clock = Clock("2027-01-15T08:00:00+00:00")
    reminders.create(session, {"at": "2027-01-15T09:00:00+00:00"}, clock)
    clock.advance(days=3)  # the bridge was down
    assert len(reminders.fire_due(clock, script)) == 1
    assert len(lines()) == 1


def test_repeating_reminder_is_rescheduled_and_missed_runs_collapse(session, notified):
    script, lines = notified
    clock = Clock("2027-01-15T08:00:00+00:00")
    reminders.create(session, {"at": "2027-01-15T09:00:00+00:00", "repeat": "daily"}, clock)
    clock.advance(days=3, hours=2)  # 18 January 10:00: three occurrences missed
    (fired,) = reminders.fire_due(clock, script)
    assert fired["state"] == "scheduled" and fired["at"] == "2027-01-19T09:00:00Z"
    assert reminders.fire_due(clock, script) == []
    clock.advance(days=1)
    assert len(reminders.fire_due(clock, script)) == 1
    assert len(lines()) == 2


def test_repeating_keeps_the_local_hour_across_a_clock_change(session):
    clock = Clock("2027-03-20T00:00:00+00:00")
    # Weekly at 09:00 in a zone that moves its clocks forward on 28 March 2027.
    reminders.create(
        session,
        {"at": "2027-03-22T09:00:00+01:00", "repeat": "weekly", "timezone": "Europe/Berlin"},
        clock,
    )
    clock.advance(days=3)
    (fired,) = reminders.fire_due(clock)
    assert fired["at"] == "2027-03-29T07:00:00Z"  # 09:00 local, now UTC+2


def test_changing_the_time_schedules_a_fired_reminder_again(client, auth, session):
    clock = Clock("2027-01-15T08:00:00+00:00")
    reminder = reminders.create(session, {"at": "2027-01-15T08:30:00+00:00"}, clock)
    clock.advance(hours=1)
    reminders.fire_due(clock)
    changed = client.patch(f"/reminders/{reminder['id']}", headers=auth, json={"at": FUTURE})
    assert changed.json()["state"] == "scheduled"


def test_a_damaged_file_is_treated_as_empty(isolated_config):
    (isolated_config / "reminders.json").write_text("{not json")
    assert reminders.fire_due(lambda: datetime.now(UTC)) == []


def test_the_bridge_checks_reminders_while_running(settings, session, notified, monkeypatch):
    script, lines = notified
    monkeypatch.setattr(reminders, "CHECK_INTERVAL_SECONDS", 0.05)
    settings = type(settings)(**{**settings.__dict__, "notify_script": script})
    reminders.create(session, {"at": "2001-01-01T00:00:00+00:00", "repeat": "daily"})
    with TestClient(create_app(settings)) as client:
        for _ in range(100):
            client.get("/health")
            if lines():
                break
    assert len(lines()) == 1


def test_tests_can_never_reach_the_real_notification_script(session, monkeypatch):
    """Regression: firing without an explicit script once ran the real scripts/notify.sh."""
    import subprocess

    from lifeos_bridge import notifications

    assert notifications.default_script() is None
    started = []
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: started.append(a))
    clock = Clock("2027-01-15T08:00:00+00:00")
    reminders.create(session, {"at": "2027-01-15T08:30:00+00:00"}, clock)
    clock.advance(hours=1)
    assert len(reminders.fire_due(clock)) == 1
    assert started == []


def test_the_notification_script_can_be_replaced_or_switched_off(monkeypatch, tmp_path):
    from lifeos_bridge import notifications

    monkeypatch.setenv("LIFEOS_NOTIFY_SCRIPT", str(tmp_path / "other.sh"))
    assert notifications.default_script() == tmp_path / "other.sh"
    monkeypatch.delenv("LIFEOS_NOTIFY_SCRIPT")
    assert notifications.default_script().name == "notify.sh"
