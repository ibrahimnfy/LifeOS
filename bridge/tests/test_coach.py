"""The coach: daily plan, the open question, settings. Synthetic content, a controllable clock."""

import asyncio
import dataclasses
import json
import subprocess
import time
from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import coach, memory, reminders
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

REPORT, CALL, CHORE, READ = "t-20270110-01", "t-20270112-01", "t-20270113-01", "t-20270111-01"
TASKS = (
    "# Tasks\n\n## Open\n"
    f"- [ ] Write the report  (due: 2027-01-20) (added: 2027-01-10) ^{REPORT}\n"
    f"- [ ] Call the office  (added: 2027-01-12) ^{CALL}\n"
    f"- [ ] Old chore  (due: 2027-01-14) (added: 2027-01-13) ^{CHORE}\n"
    f"- [ ] Read the chapter  (added: 2027-01-11) ^{READ}\n"
)
RULE_ORDER = [CHORE, REPORT, READ, CALL]  # overdue, due, then by date added
PLAN = {
    "messages": [
        {"id": "m1", "kind": "focus", "text": "Start with the report.", "why": "Due soon.",
         "task": REPORT, "unknown": 1},
        {"id": "m2", "kind": "observation", "text": "Two tasks have no due date."},
        {"id": "m3", "kind": "question", "question": "waiting", "task": CALL,
         "text": "What is the call waiting for?"},
        {"id": "m4", "kind": "encouragement", "text": "The chore is small.", "task": CHORE},
    ],
    "notification": "m1",
    "top_three": [
        {"task": REPORT, "reason": "Due in five days."},
        {"task": CHORE, "reason": "Overdue."},
        {"task": CALL, "reason": "Waiting longest."},
    ],
    "order": [REPORT, CHORE, CALL],
    "extra": "ignored",
}  # fmt: skip
FILING = {
    "facts": [
        {"file": "projects/example-project.md", "text": "The office answers only on Mondays."},
        {"file": "profile.md", "text": "Prefers calls in the morning."},
    ],
    "task_fields": {"next": "Find the number (office)", "blocked-by": "Monday", "effort": "small"},
}


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


class Clock:
    def __init__(self, start: str):
        self.now = datetime.fromisoformat(start)

    def __call__(self):
        return self.now

    def advance(self, **delta):
        self.now += timedelta(**delta)

    def at(self, moment: str):
        self.now = datetime.fromisoformat(moment)


@pytest.fixture
def brain(ask_dir):
    """A memory folder the way the bridge expects it: under git, no remote."""
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    (ask_dir / "tasks.md").write_text(TASKS)
    (ask_dir / "profile.md").write_text("# Profile\n")
    (ask_dir / "projects").mkdir()
    (ask_dir / "projects" / "example-project.md").write_text(
        "# Example project\n\n## Facts\n- [owner 2027-01-01] An older fact.\n\n## Links\n"
    )
    (ask_dir / ".gitignore").write_text(".fake-claude-calls.jsonl\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


@pytest.fixture
def clock(monkeypatch):
    """Friday 15 January 2027, 07:00; the routes use it too."""
    clock = Clock("2027-01-15T07:00:00+00:00")
    monkeypatch.setattr(coach, "now_local", clock)
    return clock


@pytest.fixture
def proposes(tmp_path):
    """Sets what the stand-in claude answers next."""

    def write(answer):
        text = (
            answer if isinstance(answer, str) else "Here:\n```json\n" + json.dumps(answer) + "\n```"
        )
        (tmp_path / "home" / ".fake-claude-answer").write_text(text)

    return write


@pytest.fixture
def notified(tmp_path):
    """A stand-in notify script that records its arguments."""
    log = tmp_path / "notify.log"
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf "%s|%s|%s\\n" "$1" "$2" "$3" >> "{log}"\n')
    script.chmod(0o755)
    return script, lambda: log.read_text().splitlines() if log.exists() else []


@pytest.fixture
def planned(settings, brain, clock, proposes):
    """A stored plan prepared at 07:00 from PLAN."""
    proposes(PLAN)
    asyncio.run(coach.prepare_plan(settings))
    return brain


def ids(tasks):
    return [task["id"] for task in tasks]


def commits(folder):
    return git(folder, "log", "--format=%s").splitlines()


def test_routes_require_the_token(client):
    for method, path in [("get", "/coach/plan"), ("post", "/coach/plan/refresh"),
                         ("get", "/coach/question"), ("post", "/coach/question/answer"),
                         ("post", "/coach/question/skip"), ("get", "/coach/settings"),
                         ("put", "/coach/settings")]:  # fmt: skip
        assert getattr(client, method)(path).status_code == 401


# --------------------------------------------------------------------------- the plan


def test_without_a_plan_the_tasks_come_in_rule_order(client, auth, brain, clock, calls):
    body = client.get("/coach/plan", headers=auth).json()
    assert (body["prepared_at"], body["age_seconds"], body["stale"]) == (None, None, True)
    assert body["messages"] == [] and body["notification"] is None
    assert ids(body["order"]) == RULE_ORDER
    assert [(e["task"]["id"], e["reason"]) for e in body["top_three"]] == [
        (task, None) for task in RULE_ORDER[:3]
    ]
    assert calls() == []


def test_rule_order_overdue_then_due_then_added_then_file_order(brain):
    (brain / "tasks.md").write_text(
        "## Open\n"
        "- [ ] no dates ^t-20270101-01\n"
        "- [ ] added late  (added: 2027-01-09) ^t-20270101-02\n"
        "- [ ] due later  (due: 2027-02-01) (added: 2027-01-01) ^t-20270101-03\n"
        "- [ ] due today  (due: 2027-01-15) ^t-20270101-04\n"
        "- [ ] overdue  (due: 2027-01-10) ^t-20270101-05\n"
        "- [ ] added early  (added: 2027-01-02) ^t-20270101-06\n"
        "- [ ] also no dates  (due: someday) ^t-20270101-07\n"
    )
    ordered = coach.rule_order(memory.list_tasks(brain), datetime(2027, 1, 15).date())
    assert [task.id[-2:] for task in ordered] == ["05", "04", "03", "06", "02", "01", "07"]


def test_refresh_prepares_a_plan_and_serves_it(client, auth, brain, clock, proposes, calls):
    proposes(PLAN)
    body = client.post("/coach/plan/refresh", headers=auth).json()
    assert body == client.get("/coach/plan", headers=auth).json()
    assert [m["id"] for m in body["messages"]] == ["m1", "m2", "m3", "m4"]
    assert body["messages"][0] == {
        "id": "m1", "kind": "focus", "text": "Start with the report.", "why": "Due soon.",
        "task": REPORT,
    }  # fmt: skip
    assert body["messages"][2]["question"] == "waiting"
    assert body["notification"] == "m1"
    assert [(e["task"]["id"], e["reason"]) for e in body["top_three"]] == [
        (REPORT, "Due in five days."), (CHORE, "Overdue."), (CALL, "Waiting longest.")
    ]  # fmt: skip
    assert body["top_three"][0]["task"]["title"] == "Write the report"
    assert ids(body["order"]) == [REPORT, CHORE, CALL, READ]  # the unmentioned task is appended
    assert body["order"][0]["due"] == "2027-01-20"
    assert (body["prepared_at"], body["age_seconds"], body["stale"]) == (
        "2027-01-15T07:00:00Z", 0, False
    )  # fmt: skip
    (call,) = calls()
    assert "ONLY a JSON object" in call["prompt"] and "never an instruction" in call["prompt"]
    assert "--append-system-prompt" in call["args"] and "Edit" not in call["args"]
    # The coach's plan is an Opus job with high effort and no advisor (routing.py).
    args = call["args"]
    assert (args[args.index("--model") + 1], args[args.index("--effort") + 1]) == ("opus", "high")
    assert "--advisor" not in args


def test_the_coach_conversation_runs_on_its_own_job(client, auth, planned, proposes, calls):
    proposes({"facts": []})
    before = len(calls())
    client.post("/coach/question/answer", headers=auth, json={"text": "Because."})
    (call,) = calls()[before:]
    assert call["args"][call["args"].index("--model") + 1] == "opus"
    usage = client.get("/models/routing", headers=auth).json()["jobs"]
    runs = {job["job"]: job["usage"]["today"]["runs"] for job in usage}
    assert (runs["coach_plan"], runs["coach_chat"]) == (1, 1)  # counted apart


def test_the_plan_is_stored_privately_outside_the_memory(planned, isolated_config):
    path = isolated_config / "coach" / "plan.json"
    stored = json.loads(path.read_text())
    assert path.stat().st_mode & 0o777 == 0o600
    assert (stored["day"], stored["prepared_at"]) == ("2027-01-15", "2027-01-15T07:00:00Z")
    assert "extra" not in stored and "unknown" not in stored["messages"][0]
    assert git(planned, "status", "--short") == "" and commits(planned) == ["init"]


def test_age_and_staleness(settings, planned, clock):
    clock.advance(hours=2)
    plan = coach.current_plan(settings, clock)
    assert (plan["age_seconds"], plan["stale"]) == (7200, False)
    clock.advance(days=1)
    assert coach.current_plan(settings, clock)["stale"] is True


def test_invalid_parts_are_dropped(settings, brain, clock, proposes):
    proposes(
        {
            "messages": [
                {"id": "a", "kind": "focus", "text": "x" * 281},
                {"id": "b", "kind": "focus", "text": "two\nlines"},
                {"id": "b2", "kind": "focus", "text": "two\u2028lines"},
                {"id": "c", "kind": "focus", "text": "ok", "task": "t-20990101-01"},
                {"id": "d", "kind": "focus", "text": "ok", "why": "y" * 201},
                {"id": "e", "kind": "advert", "text": "ok"},
                {"id": "f", "kind": "question", "question": "why", "text": "ok", "task": CALL},
                {"id": "g", "kind": "question", "question": "hard", "text": "no task"},
                {"id": "", "kind": "focus", "text": "ok"},
                {"kind": "focus", "text": "ok"},
                "not an object",
                {"id": "h", "kind": "focus", "text": "ok", "task": [CALL]},
                {"id": "ok", "kind": "observation", "text": " fine "},
                {"id": "ok", "kind": "observation", "text": "same id again"},
                {"id": "q1", "kind": "question", "question": "hard", "text": "one", "task": CALL},
                {"id": "q2", "kind": "question", "question": "easy", "text": "two", "task": READ},
            ],
            "notification": "a",
            "top_three": [
                {"task": "t-20990101-01", "reason": "unknown task"},
                {"task": CALL, "reason": "r" * 201},
                {"task": CALL, "reason": "twice"},
                {"task": READ, "reason": "fine"},
                "junk",
            ],
            "order": [READ, "t-20990101-01", READ, 5, CALL],
        }
    )
    asyncio.run(coach.prepare_plan(settings))
    plan = coach.current_plan(settings)
    assert [(m["id"], m["text"]) for m in plan["messages"]] == [("ok", "fine"), ("q1", "one")]
    assert plan["notification"] == "ok"
    assert [(e["task"]["id"], e["reason"]) for e in plan["top_three"]] == [
        (CALL, None), (READ, "fine"), (CHORE, None)
    ]  # fmt: skip
    assert ids(plan["order"]) == [READ, CALL, CHORE, REPORT]


def test_at_most_eight_messages(settings, brain, clock, proposes):
    proposes({"messages": [{"id": f"m{i}", "kind": "focus", "text": "ok"} for i in range(12)]})
    asyncio.run(coach.prepare_plan(settings))
    assert len(coach.current_plan(settings)["messages"]) == 8


@pytest.mark.parametrize(
    "answer",
    [
        {"messages": [{"id": "a", "kind": "focus", "text": "x" * 281}], "order": [READ]},
        {"messages": "none", "top_three": []},
        "I could not prepare anything today.",
        "[1, 2]",
    ],
)
def test_an_unusable_result_keeps_the_previous_plan(client, auth, planned, clock, proposes, answer):
    before = client.get("/coach/plan", headers=auth).json()
    clock.advance(minutes=30)
    proposes(answer)
    response = client.post("/coach/plan/refresh", headers=auth)
    assert response.status_code == 502
    assert response.json()["error"] in ("bad_plan", "bad_output")
    after = client.get("/coach/plan", headers=auth).json()
    assert after == before | {"age_seconds": 1800}


def test_ticking_a_task_updates_the_plan_without_a_run(client, auth, planned, calls):
    assert len(calls()) == 1
    memory.complete_task(planned, REPORT)
    body = client.get("/coach/plan", headers=auth).json()
    assert [m["id"] for m in body["messages"]] == ["m2", "m3", "m4"]
    assert body["notification"] is None  # its message was about the finished task
    assert [(e["task"]["id"], e["reason"]) for e in body["top_three"]] == [
        (CHORE, "Overdue."), (CALL, "Waiting longest."), (READ, None)
    ]  # fmt: skip
    assert ids(body["order"]) == [CHORE, CALL, READ]
    memory.complete_task(planned, CALL)
    assert client.get("/coach/question", headers=auth).json() == {"question": None}
    added = memory.add_task(planned, {"title": "Added later", "due": "2027-01-01"})
    assert ids(client.get("/coach/plan", headers=auth).json()["order"]) == [CHORE, added.id, READ]
    assert len(calls()) == 1


def test_refresh_is_limited_to_one_run_in_ten_minutes(client, auth, planned, clock, calls):
    clock.advance(minutes=9)
    refused = client.post("/coach/plan/refresh", headers=auth)
    assert refused.status_code == 429 and refused.json()["error"] == "too_soon"
    assert refused.json()["message"]
    clock.advance(minutes=1)
    assert client.post("/coach/plan/refresh", headers=auth).status_code == 200
    assert len(calls()) == 2


def test_only_one_run_at_a_time(settings, brain, clock, proposes, calls):
    proposes(PLAN)

    async def two():
        return await asyncio.gather(
            coach.prepare_plan(settings), coach.prepare_plan(settings), return_exceptions=True
        )

    first, second = asyncio.run(two())
    assert isinstance(first, dict) and isinstance(second, ClaudeError) and second.code == "busy"
    assert len(calls()) == 1
    assert not coach._RUNNING


# --------------------------------------------------------------------------- morning run


def morning(settings, clock):
    return asyncio.run(coach.morning_run(settings, clock))


def test_morning_run_once_a_day_from_half_past_six(settings, brain, proposes, calls):
    proposes(PLAN)
    clock = Clock("2027-01-15T06:29:00+00:00")
    assert not coach.due_for_morning_run(clock) and morning(settings, clock) is False
    assert calls() == []
    clock.advance(minutes=1)
    assert coach.due_for_morning_run(clock) and morning(settings, clock) is True
    clock.advance(minutes=1)
    assert not coach.due_for_morning_run(clock) and morning(settings, clock) is False
    clock.at("2027-01-15T23:59:00+00:00")
    assert morning(settings, clock) is False
    clock.at("2027-01-16T06:00:00+00:00")
    assert morning(settings, clock) is False
    clock.at("2027-01-16T06:30:00+00:00")
    assert morning(settings, clock) is True
    assert len(calls()) == 2
    assert coach.current_plan(settings, clock)["prepared_at"] == "2027-01-16T06:30:00Z"


def test_a_bridge_started_in_the_afternoon_catches_up_once(settings, brain, proposes, calls):
    proposes(PLAN)
    clock = Clock("2027-01-15T15:00:00+00:00")
    assert morning(settings, clock) is True
    clock.advance(hours=3)
    assert morning(settings, clock) is False
    assert len(calls()) == 1


def test_a_failed_morning_run_is_retried_after_an_hour(settings, brain, proposes, calls):
    proposes("nothing usable")
    clock = Clock("2027-01-15T06:30:00+00:00")
    assert morning(settings, clock) is False and len(calls()) == 1
    proposes(PLAN)
    clock.advance(minutes=59)
    assert not coach.due_for_morning_run(clock) and morning(settings, clock) is False
    assert len(calls()) == 1
    clock.advance(minutes=1)
    assert morning(settings, clock) is True and len(calls()) == 2


def test_no_morning_run_without_a_memory(settings, ask_dir, isolated_config, proposes, calls):
    proposes(PLAN)
    clock = Clock("2027-01-15T09:00:00+00:00")
    assert morning(settings, clock) is False
    assert calls() == [] and not (isolated_config / "coach" / "state.json").exists()


def test_no_run_when_the_memory_has_a_remote(client, auth, brain, clock, proposes, calls):
    proposes(PLAN)
    git(brain, "remote", "add", "origin", "https://example.org/x.git")
    response = client.post("/coach/plan/refresh", headers=auth)
    assert response.status_code == 503 and response.json()["error"] == "memory_unavailable"
    assert morning(dataclasses.replace(client.app.state.settings), clock) is False
    assert calls() == []


def test_the_running_bridge_prepares_the_plan_and_announces_it(
    settings, brain, proposes, notified, monkeypatch
):
    script, lines = notified
    proposes(PLAN)
    monkeypatch.setattr(reminders, "CHECK_INTERVAL_SECONDS", 0.05)
    monkeypatch.setattr(coach, "now_local", Clock("2027-01-15T09:00:00+00:00"))
    with TestClient(create_app(dataclasses.replace(settings, notify_script=script))) as client:
        deadline = time.monotonic() + 10
        while not lines() and time.monotonic() < deadline:
            client.get("/health")
            time.sleep(0.02)
        for _ in range(5):  # further checks send nothing more
            client.get("/health")
            time.sleep(0.02)
    assert lines() == ["LifeOS: coach|Your daily note is ready.|"]
    assert coach.load_plan()["day"] == "2027-01-15"


# --------------------------------------------------------------------------- notification


def test_daily_notification_once_after_quiet_hours_with_a_fixed_text(
    settings, brain, proposes, notified, calls
):
    script, lines = notified
    settings = dataclasses.replace(settings, notify_script=script)
    proposes(PLAN)
    clock = Clock("2027-01-15T06:30:00+00:00")
    assert coach.send_daily_notification(settings, clock) is False  # no plan yet
    assert morning(settings, clock) is True
    assert coach.send_daily_notification(settings, clock) is False  # quiet until 08:00
    clock.at("2027-01-15T07:59:00+00:00")
    assert coach.send_daily_notification(settings, clock) is False and lines() == []
    clock.at("2027-01-15T08:00:00+00:00")
    assert coach.send_daily_notification(settings, clock) is True
    assert coach.send_daily_notification(settings, clock) is False
    assert lines() == ["LifeOS: coach|Your daily note is ready.|"]
    assert coach.load_plan()["notification_sent_at"] == "2027-01-15T08:00:00Z"
    # A refreshed plan on the same day does not notify again.
    clock.advance(hours=1)
    asyncio.run(coach.refresh(settings, clock))
    assert coach.send_daily_notification(settings, clock) is False
    assert len(lines()) == 1 and len(calls()) == 2


def test_a_plan_prepared_in_the_evening_is_not_announced_at_night(
    settings, brain, proposes, notified
):
    script, lines = notified
    settings = dataclasses.replace(settings, notify_script=script)
    proposes(PLAN)
    clock = Clock("2027-01-15T22:30:00+00:00")
    assert morning(settings, clock) is True
    assert coach.send_daily_notification(settings, clock) is False
    clock.at("2027-01-16T08:30:00+00:00")  # yesterday's plan: nothing is sent for it any more
    assert coach.send_daily_notification(settings, clock) is False
    assert lines() == []


def test_frequency_off_sends_nothing(client, auth, settings, planned, clock, notified):
    script, lines = notified
    settings = dataclasses.replace(settings, notify_script=script)
    client.put("/coach/settings", headers=auth, json={"frequency": "off"})
    clock.at("2027-01-15T12:00:00+00:00")
    assert coach.send_daily_notification(settings, clock) is False and lines() == []
    client.put("/coach/settings", headers=auth, json={"frequency": "3"})
    assert coach.send_daily_notification(settings, clock) is True and len(lines()) == 1


@pytest.fixture
def hints(monkeypatch):
    """The coach pushes, as (event, ref) pairs."""
    from lifeos_bridge import push

    sent = []
    monkeypatch.setattr(push, "send_hint", lambda event, ref="", **kw: sent.append((event, ref)))
    return sent


def test_more_nudges_from_the_same_batch_within_the_limits(
    client, auth, settings, planned, clock, notified, hints, calls
):
    script, lines = notified
    settings = dataclasses.replace(settings, notify_script=script)
    runs = len(calls())
    assert (
        client.put("/coach/settings", headers=auth, json={"frequency": "normal"}).json()[
            "frequency"
        ]
        == "3"
    )
    clock.at("2027-01-15T08:00:00+00:00")
    assert coach.send_nudges(settings, clock) is False  # the daily notification comes first
    assert coach.send_daily_notification(settings, clock) is True
    assert coach.send_nudges(settings, clock) is False  # two hours apart
    clock.at("2027-01-15T09:59:00+00:00")
    assert coach.send_nudges(settings, clock) is False
    clock.at("2027-01-15T10:00:00+00:00")
    assert coach.send_nudges(settings, clock) is True
    clock.at("2027-01-15T12:00:00+00:00")
    assert coach.send_nudges(settings, clock) is True
    clock.at("2027-01-15T16:00:00+00:00")
    assert coach.send_nudges(settings, clock) is False  # three a day at most
    assert hints == [("coach", "m1"), ("coach", "m2"), ("coach", "m3")]
    assert (
        lines()
        == ["LifeOS: coach|Your daily note is ready.|"]
        + ["LifeOS: coach|A note from your coach.|"] * 2
    )  # fixed texts only
    assert len(calls()) == runs  # no model run for a nudge


def test_nudges_respect_quiet_hours_done_tasks_and_the_default(
    client, auth, settings, planned, clock, hints
):
    clock.at("2027-01-15T08:00:00+00:00")
    coach.send_daily_notification(settings, clock)
    clock.at("2027-01-15T12:00:00+00:00")
    assert coach.send_nudges(settings, clock) is False  # default: one a day, the daily one
    client.put("/coach/settings", headers=auth, json={"frequency": "3", "quiet_start": "13:00"})
    assert coach.send_nudges(settings, clock) is True  # m2
    clock.at("2027-01-15T14:00:00+00:00")
    assert coach.send_nudges(settings, clock) is False  # in quiet hours
    client.put("/coach/settings", headers=auth, json={"quiet_start": "22:00"})
    client.post(f"/tasks/{CALL}/complete", headers=auth)  # m3 is about a task now done
    assert coach.send_nudges(settings, clock) is True  # so m4 goes instead
    clock.at("2027-01-16T09:00:00+00:00")
    assert coach.send_nudges(settings, clock) is False  # yesterday's batch is over
    assert hints == [("coach", "m1"), ("coach", "m2"), ("coach", "m4")]
    client.put("/coach/settings", headers=auth, json={"frequency": "off"})
    assert client.get("/coach/settings", headers=auth).json()["frequency"] == "off"
    bad = client.put("/coach/settings", headers=auth, json={"frequency": "often"})
    assert bad.status_code == 400


def test_quiet_hours_follow_the_settings(client, auth, settings, planned, clock, notified):
    script, lines = notified
    settings = dataclasses.replace(settings, notify_script=script)
    client.put("/coach/settings", headers=auth, json={"quiet_start": "06:00", "quiet_end": "07:30"})
    assert coach.send_daily_notification(settings, clock) is False  # 07:00
    clock.advance(minutes=30)
    assert coach.send_daily_notification(settings, clock) is True and len(lines()) == 1


def test_quiet_hours_across_midnight_and_none():
    night = {"quiet_start": "22:00", "quiet_end": "08:00"}
    quiet = [coach.in_quiet_hours(datetime(2027, 1, 1, h, m).time(), night)
             for h, m in [(21, 59), (22, 0), (3, 0), (7, 59), (8, 0)]]  # fmt: skip
    assert quiet == [False, True, True, True, False]
    same = {"quiet_start": "09:00", "quiet_end": "09:00"}
    assert not coach.in_quiet_hours(datetime(2027, 1, 1, 9, 0).time(), same)


# --------------------------------------------------------------------------- settings


def test_settings_defaults_and_changes(client, auth, isolated_config):
    defaults = {"frequency": "1", "quiet_start": "22:00", "quiet_end": "08:00", "questions": True}
    assert client.get("/coach/settings", headers=auth).json() == defaults
    changed = client.put(
        "/coach/settings", headers=auth, json={"frequency": "3", "quiet_end": "07:15"}
    ).json()
    assert changed == defaults | {"frequency": "3", "quiet_end": "07:15"}
    assert client.get("/coach/settings", headers=auth).json() == changed
    path = isolated_config / "coach" / "settings.json"
    assert json.loads(path.read_text())["nudges_per_day"] == 3
    assert path.stat().st_mode & 0o777 == 0o600
    off = client.put("/coach/settings", headers=auth, json={"frequency": "off"}).json()
    assert off["frequency"] == "off" and json.loads(path.read_text())["nudges_per_day"] == 0


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"frequency": "2"},
        {"frequency": 1},
        {"quiet_start": "24:00"},
        {"quiet_end": "8:00"},
        {"quiet_end": 8},
        {"questions": "yes"},
        {"volume": "loud"},
    ],
)
def test_bad_settings_are_refused(client, auth, body):
    assert client.put("/coach/settings", headers=auth, json=body).status_code == 400
    assert client.get("/coach/settings", headers=auth).json()["frequency"] == "1"


def test_a_damaged_settings_file_falls_back_to_defaults(isolated_config):
    (isolated_config / "coach").mkdir()
    (isolated_config / "coach" / "settings.json").write_text(
        '{"nudges_per_day": 9, "quiet_start": "soon", "questions": "no"}'
    )
    assert coach.load_settings() == coach.DEFAULT_SETTINGS


def test_questions_off_hides_the_question(client, auth, planned):
    assert client.get("/coach/question", headers=auth).json()["question"]["id"] == "m3"
    client.put("/coach/settings", headers=auth, json={"questions": False})
    assert client.get("/coach/question", headers=auth).json() == {"question": None}
    assert "m3" not in [m["id"] for m in client.get("/coach/plan", headers=auth).json()["messages"]]
    assert client.post("/coach/question/skip", headers=auth).status_code == 404
    client.put("/coach/settings", headers=auth, json={"questions": True})
    assert client.get("/coach/question", headers=auth).json()["question"]["id"] == "m3"


# --------------------------------------------------------------------------- the question


def test_answer_is_logged_and_what_it_says_is_filed(client, auth, planned, proposes, calls):
    question = client.get("/coach/question", headers=auth).json()["question"]
    assert question == {
        "id": "m3", "kind": "question", "question": "waiting", "task": CALL,
        "text": "What is the call waiting for?",
    }  # fmt: skip
    proposes(FILING)
    answer = "They only answer on Mondays.\n\n  And I prefer mornings. "
    response = client.post("/coach/question/answer", headers=auth, json={"text": answer})
    assert response.status_code == 200
    assert response.json() == {
        "saved": True,
        "filed": {
            "facts": ["projects/example-project.md", "profile.md"],
            "task_fields": ["blocked-by", "effort", "next"],
        },
    }
    log = (planned / "coach" / "log.md").read_text()
    assert log.startswith("# Coach log\n")
    assert log.endswith(
        "\n## 2027-01-15 07:00\nQ: What is the call waiting for?\n"
        "A: They only answer on Mondays.\n   And I prefer mornings.\n"
    )
    assert commits(planned)[:2] == ["File what the coach learned", "Record a coach answer"]
    assert git(planned, "status", "--short") == ""
    assert git(planned, "log", "-1", "--format=%an") == "lifeos-bridge\n"
    assert (planned / "projects" / "example-project.md").read_text() == (
        "# Example project\n\n## Facts\n- [owner 2027-01-01] An older fact.\n"
        "- [owner 2027-01-15] The office answers only on Mondays.\n\n## Links\n"
    )
    assert (planned / "profile.md").read_text() == (
        "# Profile\n\n## Facts\n- [owner 2027-01-15] Prefers calls in the morning.\n"
    )
    task = next(t for t in memory.list_tasks(planned) if t.id == CALL)
    assert task.title == "Call the office"
    assert task.fields == {
        "added": "2027-01-12", "next": "Find the number [office]", "blocked-by": "Monday",
        "effort": "small",
    }  # fmt: skip
    assert len(memory.list_tasks(planned)) == 4
    # The question is closed without another run.
    assert client.get("/coach/question", headers=auth).json() == {"question": None}
    assert "m3" not in [m["id"] for m in client.get("/coach/plan", headers=auth).json()["messages"]]
    assert (
        client.post("/coach/question/answer", headers=auth, json={"text": "x"}).status_code == 404
    )
    assert len(calls()) == 2
    prompt = calls()[1]["prompt"]
    assert "They only answer on Mondays." in prompt and "projects/example-project.md" in prompt


def test_invalid_filing_proposals_are_ignored_but_the_answer_is_kept(
    client, auth, planned, proposes, tmp_path
):
    (tmp_path / "home" / "outside.md").write_text("# Outside\n")
    (planned / "courses").mkdir()
    (planned / "courses" / "linked.md").symlink_to(tmp_path / "home" / "outside.md")
    proposes(
        {
            "facts": [
                {"file": "projects/missing.md", "text": "no such file"},
                {"file": "tasks.md", "text": "not a topic file"},
                {"file": "../outside.md", "text": "outside"},
                {"file": "projects/../profile.md", "text": "not a plain name"},
                {"file": "courses/linked.md", "text": "a link leading out"},
                {"file": "profile.md", "text": "two\nlines"},
                {"file": "profile.md", "text": "two\u2028lines"},
                {"file": "profile.md", "text": "x" * 241},
                {"file": ["profile.md"], "text": "not text"},
                "junk",
            ],
            "task_fields": {
                "next": "a\x0cb",
                "blocked-by": f"something ^{REPORT}",
                "effort": "huge",
                "due": "2099-01-01",
            },
        }
    )
    response = client.post("/coach/question/answer", headers=auth, json={"text": "Because."})
    assert response.json() == {"saved": True, "filed": {"facts": [], "task_fields": []}}
    assert commits(planned)[0] == "Record a coach answer"
    assert "A: Because.\n" in (planned / "coach" / "log.md").read_text()
    assert (planned / "tasks.md").read_text() == TASKS
    assert (planned / "profile.md").read_text() == "# Profile\n"
    assert (tmp_path / "home" / "outside.md").read_text() == "# Outside\n"


def test_at_most_three_facts(client, auth, planned, proposes):
    proposes({"facts": [{"file": "profile.md", "text": f"fact {i}"} for i in range(5)]})
    client.post("/coach/question/answer", headers=auth, json={"text": "Several things."})
    assert (planned / "profile.md").read_text().count("- [owner 2027-01-15] fact") == 3


def test_a_failed_filing_run_does_not_fail_the_answer(client, auth, planned, proposes):
    proposes("I cannot say.")
    response = client.post("/coach/question/answer", headers=auth, json={"text": "Because."})
    assert response.status_code == 200 and response.json()["saved"] is True
    assert commits(planned)[0] == "Record a coach answer"
    assert client.get("/coach/question", headers=auth).json() == {"question": None}


def test_bad_answers(client, auth, planned, calls):
    for text in ["", "   ", None, 5, "x" * 4001]:
        response = client.post("/coach/question/answer", headers=auth, json={"text": text})
        assert response.status_code == 400
    assert client.get("/coach/question", headers=auth).json()["question"]["id"] == "m3"
    assert not (planned / "coach").exists() and len(calls()) == 1


def test_no_open_question(client, auth, brain, clock):
    assert client.get("/coach/question", headers=auth).json() == {"question": None}
    assert (
        client.post("/coach/question/answer", headers=auth, json={"text": "x"}).status_code == 404
    )
    assert client.post("/coach/question/skip", headers=auth).status_code == 404


def test_answers_are_refused_when_the_memory_has_a_remote(client, auth, planned, calls):
    git(planned, "remote", "add", "origin", "https://example.org/x.git")
    response = client.post("/coach/question/answer", headers=auth, json={"text": "Because."})
    assert response.status_code == 503 and response.json()["error"] == "memory_unavailable"
    assert not (planned / "coach").exists() and len(calls()) == 1
    assert client.get("/coach/question", headers=auth).json()["question"]["id"] == "m3"


def test_an_answered_question_is_not_asked_again(client, auth, settings, planned, clock, proposes):
    proposes({})
    client.post("/coach/question/answer", headers=auth, json={"text": "Because."})
    clock.advance(days=30)
    proposes(PLAN)
    asyncio.run(coach.prepare_plan(settings))
    assert coach.open_question(settings) is None
    other = PLAN | {"messages": [PLAN["messages"][0], PLAN["messages"][2] | {"question": "next"}]}
    clock.advance(hours=1)
    proposes(other)
    asyncio.run(coach.prepare_plan(settings))
    assert coach.open_question(settings)["question"] == "next"


def question_about(task, ident="q"):
    return {
        "messages": [
            {"id": "m", "kind": "observation", "text": "A note."},
            {"id": ident, "kind": "question", "question": "still", "text": "Still?", "task": task},
        ]
    }


def test_a_skipped_question_is_not_asked_for_that_task_for_two_weeks(
    client, auth, settings, planned, clock, proposes, calls
):
    skipped = client.post("/coach/question/skip", headers=auth).json()
    assert skipped == {"skipped": True, "questions_paused_until": None}
    assert client.get("/coach/question", headers=auth).json() == {"question": None}
    assert len(calls()) == 1  # no run
    assert commits(planned) == ["init"]
    clock.advance(days=13)
    proposes(question_about(CALL))
    asyncio.run(coach.prepare_plan(settings))
    assert coach.open_question(settings) is None  # same task, any kind
    clock.advance(hours=1)
    proposes(question_about(READ))
    asyncio.run(coach.prepare_plan(settings))
    assert coach.open_question(settings)["task"] == READ
    clock.advance(days=1)
    proposes(question_about(CALL))
    asyncio.run(coach.prepare_plan(settings))
    assert coach.open_question(settings)["task"] == CALL


def test_three_skips_in_a_row_pause_all_questions_for_a_week(
    client, auth, settings, planned, clock, proposes
):
    def ask(task):
        clock.advance(hours=1)
        proposes(question_about(task))
        asyncio.run(coach.prepare_plan(settings))
        return coach.open_question(settings)

    assert (
        client.post("/coach/question/skip", headers=auth).json()["questions_paused_until"] is None
    )
    assert ask(READ)["task"] == READ
    assert coach.skip_question(settings)["questions_paused_until"] is None
    assert ask(CHORE)["task"] == CHORE
    paused = coach.skip_question(settings)["questions_paused_until"]
    assert paused == "2027-01-22T09:00:00Z"
    assert ask(REPORT) is None
    clock.at("2027-01-22T07:30:00+00:00")
    assert ask(REPORT) is None  # 08:30: still paused
    clock.at("2027-01-22T08:00:00+00:00")
    assert ask(REPORT)["task"] == REPORT  # 09:00: the week is over


def test_an_answer_resets_the_skip_streak(client, auth, settings, planned, clock, proposes):
    def ask(task):
        clock.advance(hours=1)
        proposes(question_about(task))
        asyncio.run(coach.prepare_plan(settings))

    coach.skip_question(settings)
    ask(READ)
    coach.skip_question(settings)
    ask(CHORE)
    proposes({})
    assert client.post("/coach/question/answer", headers=auth, json={"text": "Yes."}).json()[
        "saved"
    ]
    ask(REPORT)
    assert coach.skip_question(settings)["questions_paused_until"] is None
    assert json.loads((coach.coach_dir() / "state.json").read_text())["skip_streak"] == 1


def test_the_real_clock_works(settings, brain):
    plan = coach.current_plan(settings)
    assert plan["prepared_at"] is None and len(plan["order"]) == 4
    assert coach.due_for_morning_run() in (True, False)
    assert coach.send_daily_notification(settings) is False


def test_add_fact_adds_a_missing_heading():
    assert coach.add_fact("", "- x") == "## Facts\n- x\n"
    assert coach.add_fact("# Title\n\ntext", "- x") == "# Title\n\ntext\n\n## Facts\n- x\n"
    assert (
        coach.add_fact("## Facts\n\n\n## Links\n- l\n", "- x")
        == "## Facts\n- x\n\n\n## Links\n- l\n"
    )
