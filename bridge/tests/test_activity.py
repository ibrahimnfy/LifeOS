"""Phone activity uploads. Synthetic data only."""

import subprocess

import pytest


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def brain(ask_dir):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("rules\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


USAGE = {
    "date": "2027-01-15",
    "screen_minutes": 192,
    "unlocks": 41,
    "apps": [{"label": "Messages", "minutes": 47}, {"label": "Browser | (x)", "minutes": 58}],
}


def test_routes_require_the_token(client):
    assert client.post("/activity/usage", json={}).status_code == 401
    assert client.post("/activity/sleep", json={}).status_code == 401
    assert client.get("/activity").status_code == 401


def test_usage_and_sleep_are_one_line_per_day_and_replace_on_reupload(client, auth, brain):
    assert client.post("/activity/usage", headers=auth, json=USAGE).status_code == 201
    sleep = {"date": "2027-01-15", "bed": "23:40", "wake": "07:05", "estimated": True}
    saved = client.post("/activity/sleep", headers=auth, json=sleep).json()
    assert saved["minutes"] == 445  # went to bed the evening before
    client.post("/activity/usage", headers=auth, json={**USAGE, "screen_minutes": 200})
    text = (brain / "activity" / "2027-01.md").read_text()
    assert text.count("2027-01-15 | screen") == 1 and "screen 3h20m | unlocks 41" in text
    assert "apps: Browser x 58m; Messages 47m" in text
    assert "- 2027-01-15 | sleep 23:40-07:05 (7h25m) | estimated" in text
    assert git(brain, "status", "--short") == ""
    assert git(brain, "log", "-1", "--format=%s").strip() == "Record phone screen time"

    days = client.get(
        "/activity", headers=auth, params={"start": "2027-01-01", "end": "2027-01-31"}
    ).json()["days"]
    assert days == [
        {
            "date": "2027-01-15",
            "screen": {
                "minutes": 200,
                "unlocks": 41,
                "apps": [
                    {"label": "Browser x", "minutes": 58},
                    {"label": "Messages", "minutes": 47},
                ],
            },
            "sleep": {"bed": "23:40", "wake": "07:05", "minutes": 445, "estimated": True},
        }
    ]


def test_reading_spans_months_and_ignores_other_lines(client, auth, brain):
    for day in ("2026-12-31", "2027-01-01", "2027-02-01"):
        client.post("/activity/usage", headers=auth, json={**USAGE, "date": day})
    (brain / "activity" / "2027-01.md").write_text(
        (brain / "activity" / "2027-01.md").read_text() + "- a note written by hand\n"
    )
    days = client.get(
        "/activity", headers=auth, params={"start": "2026-12-31", "end": "2027-01-31"}
    ).json()["days"]
    assert [d["date"] for d in days] == ["2026-12-31", "2027-01-01"]


def test_bad_uploads(client, auth, brain):
    bad_usage = [
        {},
        {**USAGE, "date": "soon"},
        {**USAGE, "screen_minutes": 2000},
        {**USAGE, "unlocks": -1},
        {**USAGE, "apps": "x"},
        {**USAGE, "screen_minutes": True},
    ]
    for body in bad_usage:
        assert client.post("/activity/usage", headers=auth, json=body).status_code == 400, body
    for body in [{"date": "2027-01-15", "bed": "25:00", "wake": "07:00"}, {"date": "2027-01-15"}]:
        assert client.post("/activity/sleep", headers=auth, json=body).status_code == 400
    assert client.get("/activity", headers=auth, params={"start": "2020-01-01"}).status_code == 400


def test_nothing_is_written_without_a_set_up_memory(client, auth, ask_dir):
    assert client.post("/activity/usage", headers=auth, json=USAGE).status_code == 503
    assert not (ask_dir / "activity").exists()
