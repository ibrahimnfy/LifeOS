"""Model routing per job, and the plan usage the runs report."""

import dataclasses
import json
from datetime import date

import pytest

from lifeos_bridge import claude, notifications, routing
from lifeos_bridge.config import ConfigError, load_settings

TOKEN = "t" * 40


def args_of(call):
    return call["args"]


def value(args, flag):
    return args[args.index(flag) + 1] if flag in args else None


def test_defaults_per_job(settings):
    chosen = {job: routing.route(settings, job) for job in routing.JOBS}
    assert (chosen["coach_plan"].model, chosen["coach_plan"].effort) == ("opus", "high")
    assert (chosen["coach_chat"].model, chosen["coach_chat"].effort) == ("opus", "high")
    for job in ("consolidation", "course_resources", "summaries", "context_map"):
        assert (chosen[job].model, chosen[job].advisor) == ("sonnet", "opus"), job
    assert (chosen["chat"].model, chosen["chat"].effort) == (None, None)  # the owner's default
    assert all(route.source == "default" for route in chosen.values())


def test_the_routing_setting_and_the_older_background_model(settings):
    parsed = routing.parse("coach_plan=sonnet/medium, context_map=haiku+none summaries=opus")
    assert parsed == {
        "coach_plan": ("sonnet", "medium", None, False),
        "context_map": ("haiku", None, None, True),
        "summaries": ("opus", None, None, False),
    }
    routed = dataclasses.replace(settings, model_routing=parsed, background_model="haiku")
    assert routing.route(routed, "coach_plan") == routing.Route(
        "coach_plan", "sonnet", "medium", None, "routing"
    )
    assert routing.route(routed, "context_map").advisor is None  # "+none" switches it off
    assert routing.route(routed, "summaries").advisor == "opus"  # not named: the default
    # Jobs the routing does not name follow LIFEOS_BACKGROUND_MODEL as before, without advisor.
    assert routing.route(routed, "coach_chat") == routing.Route(
        "coach_chat", "haiku", "high", None, "background_model"
    )
    assert routing.route(routed, "chat").model is None  # never moved by the background model


@pytest.mark.parametrize(
    "text",
    ["nojob=opus", "coach_plan", "coach_plan=OPUS!", "coach_plan=opus/turbo", "chat=opus+B@D"],
)
def test_bad_routing_settings_are_refused(text, isolated_config):
    with pytest.raises(routing.RoutingError):
        routing.parse(text)
    with pytest.raises(ConfigError):
        load_settings({"LIFEOS_BRIDGE_TOKEN": TOKEN, "LIFEOS_MODEL_ROUTING": text})


def test_settings_read_the_routing(isolated_config):
    loaded = load_settings(
        {"LIFEOS_BRIDGE_TOKEN": TOKEN, "LIFEOS_MODEL_ROUTING": "summaries=claude-sonnet-5-5"}
    )
    assert routing.route(loaded, "summaries").model == "claude-sonnet-5-5"
    assert load_settings({"LIFEOS_BRIDGE_TOKEN": TOKEN}).model_routing == {}


def test_the_advisor_flag_reaches_the_command(settings):
    command = claude.build_command(
        settings, session_id="s", resume=False, stream=False, **{
            k: v for k, v in routing.run_options(settings, "summaries").items() if k != "job"
        }
    )  # fmt: skip
    assert (value(command, "--model"), value(command, "--advisor")) == ("sonnet", "opus")
    plain = claude.build_command(settings, session_id="s", resume=False, stream=False)
    assert "--advisor" not in plain


@pytest.fixture
def owner(settings):
    """A bridge with the defaults load_settings gives: Opus with high effort for chats."""
    from fastapi.testclient import TestClient

    from lifeos_bridge.app import create_app

    configured = dataclasses.replace(settings, default_model="opus", default_effort="high")
    return TestClient(create_app(configured))


def test_a_refused_advisor_runs_the_job_without_it(settings, calls, tmp_path):
    import asyncio
    import uuid

    (tmp_path / "home" / ".fake-claude-no-advisor").write_text("")
    result = asyncio.run(
        claude.run(settings, "summarise", session_id=str(uuid.uuid4()), resume=False,
                   **routing.run_options(settings, "summaries"))
    )  # fmt: skip
    assert result.answer == "echo: summarise"
    first, second = (args_of(call) for call in calls())
    assert value(first, "--advisor") == "opus" and "--advisor" not in second
    assert value(second, "--model") == "sonnet"


def test_chat_sessions_use_the_default_model_and_count_as_chat(owner, auth, calls):
    client = owner
    answer = client.post("/ask", headers=auth, json={"prompt": "hello"})
    assert answer.status_code == 200
    args = args_of(calls()[-1])
    assert value(args, "--model") == "opus" and value(args, "--effort") == "high"
    assert "--advisor" not in args
    jobs = {job["job"]: job for job in client.get("/models/routing", headers=auth).json()["jobs"]}
    today = jobs["chat"]["usage"]["today"]
    assert today == {"runs": 1, "input_tokens": 100, "output_tokens": 20,
                     "cache_creation_input_tokens": 5, "cache_read_input_tokens": 50,
                     "cost_usd": 0.01, "models": ["model-opus"]}  # fmt: skip
    assert jobs["coach_plan"]["usage"]["today"]["runs"] == 0


def test_the_routing_endpoint(owner, auth):
    client = owner
    body = client.get("/models/routing", headers=auth).json()
    assert [job["job"] for job in body["jobs"]] == list(routing.JOBS)
    coach = body["jobs"][1]
    assert set(coach) == {"job", "label", "model", "effort", "advisor", "source", "default",
                          "per_session", "usage"}  # fmt: skip
    assert (coach["model"], coach["effort"], coach["advisor"]) == ("opus", "high", None)
    chat = body["jobs"][0]
    assert (chat["model"], chat["effort"], chat["per_session"]) == ("opus", "high", True)
    assert body["no_model"] == ["saving notes and tasks", "course sync", "notifications"]
    assert set(body["jobs"][2]["usage"]) == {"today", "week"}
    assert client.get("/models/routing").status_code == 401


def test_usage_adds_up_per_day_and_week(isolated_config):
    result = {"type": "result", "usage": {"input_tokens": 10, "output_tokens": 2},
              "total_cost_usd": 0.5, "modelUsage": {"claude-sonnet-5-5": {}}}  # fmt: skip
    monday, wednesday, last_week = date(2027, 1, 11), date(2027, 1, 13), date(2027, 1, 8)
    routing.record("summaries", result, monday)
    routing.record("summaries", result, wednesday)
    routing.record("summaries", result, wednesday)
    routing.record("summaries", result, last_week)
    routing.record("summaries", {"type": "something else"}, wednesday)
    settings = load_settings({"LIFEOS_BRIDGE_TOKEN": TOKEN})
    jobs = {j["job"]: j for j in routing.overview(settings, wednesday)["jobs"]}
    usage = jobs["summaries"]["usage"]
    assert (usage["today"]["runs"], usage["today"]["input_tokens"]) == (2, 20)
    assert (usage["week"]["runs"], usage["week"]["cost_usd"]) == (3, 1.5)
    assert usage["week"]["models"] == ["claude-sonnet-5-5"]
    stored = json.loads((isolated_config / routing.USAGE_FILE).read_text())
    assert "prompt" not in json.dumps(stored)
    routing.record("summaries", result, date(2027, 6, 1))  # old days are dropped
    kept = json.loads((isolated_config / routing.USAGE_FILE).read_text())["days"]
    assert sorted(kept) == ["2027-06-01"]


def test_a_resumed_session_adds_only_its_growth_in_cost(isolated_config):
    day = date(2027, 1, 13)

    def run(cost, resumed):
        result = {"type": "result", "session_id": "s-1", "total_cost_usd": cost, "usage": {}}
        routing.record("chat", result, day, resumed=resumed)

    run(0.2, False)
    run(0.5, True)  # cumulative: this run cost 0.3
    run(0.5, False)  # another new session reusing nothing: counted in full
    # A session from before the counting began: its first resume is only a baseline.
    older = {"type": "result", "session_id": "s-old", "total_cost_usd": 9.0, "usage": {}}
    routing.record("chat", older, day, resumed=True)
    routing.record("chat", {**older, "total_cost_usd": 9.25}, day, resumed=True)
    settings = load_settings({"LIFEOS_BRIDGE_TOKEN": TOKEN})
    today = routing.overview(settings, day)["jobs"][0]["usage"]["today"]
    assert (today["runs"], today["cost_usd"]) == (5, 1.25)


def test_routing_chat_changes_the_default_of_sessions(settings, auth, calls):
    from fastapi.testclient import TestClient

    from lifeos_bridge.app import create_app

    routed = dataclasses.replace(
        settings, default_model="opus", default_effort="high",
        model_routing=routing.parse("chat=sonnet/low"),
    )  # fmt: skip
    client = TestClient(create_app(routed))
    assert client.post("/ask", headers=auth, json={"prompt": "hi"}).status_code == 200
    args = args_of(calls()[-1])
    assert (value(args, "--model"), value(args, "--effort")) == ("sonnet", "low")
    models = client.get("/models", headers=auth).json()
    assert (models["default_model"], models["default_effort"]) == ("sonnet", "low")
    # A model the session chose itself still wins.
    chosen = client.post("/ask", headers=auth, json={"prompt": "hi", "model": "haiku"})
    assert chosen.status_code == 200 and value(args_of(calls()[-1]), "--model") == "haiku"


def test_the_fixed_probe_model_overrides_every_job(settings):
    fixed = dataclasses.replace(settings, claude_model="haiku")
    options = routing.run_options(fixed, "summaries")
    assert (options["model"], options["advisor"]) == ("haiku", None)
    jobs = {j["job"]: j for j in routing.overview(fixed)["jobs"]}
    assert (jobs["coach_plan"]["model"], jobs["summaries"]["advisor"]) == ("haiku", None)


@pytest.fixture
def no_runs(monkeypatch):
    async def refuse(*args, **kwargs):
        raise AssertionError("a model run was started")

    monkeypatch.setattr(claude, "_start", refuse)


def test_notifications_never_start_a_run(client, auth, no_runs, tmp_path, calls):
    assert client.post("/push/notify", headers=auth, json={"title": "x"}).status_code == 200
    script = tmp_path / "notify.sh"
    script.write_text("#!/bin/sh\nexit 0\n")
    script.chmod(0o755)
    notifications.send("LifeOS: test", "text", "", script)
    assert client.get("/models/routing", headers=auth).status_code == 200
    assert calls() == []


# ---- the owner's choices from the app (PUT /models/routing) ------------------------------


def put(client, auth, body):
    return client.put("/models/routing", headers=auth, json=body)


def jobs_of(body):
    return {job["job"]: job for job in body["jobs"]}


def audited(config):
    return [
        json.loads(line)["detail"]
        for path in sorted((config / "audit").glob("*.jsonl"))
        for line in path.read_text().splitlines()
        if json.loads(line)["tool"] == "ModelRouting"
    ]


def test_the_app_changes_a_job_without_a_restart(owner, auth, calls, isolated_config):
    client = owner
    changed = put(client, auth, {"job": "chat", "model": "haiku", "effort": "low"})
    assert changed.status_code == 200 and changed.json()["changed"] == "chat"
    chat = jobs_of(changed.json())["chat"]
    assert (chat["model"], chat["effort"], chat["source"]) == ("haiku", "low", "app")
    assert chat["default"] == {"model": "opus", "effort": "high", "advisor": None,
                               "source": "default"}  # fmt: skip
    assert client.post("/ask", headers=auth, json={"prompt": "hi"}).status_code == 200
    args = args_of(calls()[-1])
    assert (value(args, "--model"), value(args, "--effort")) == ("haiku", "low")
    stored = json.loads((isolated_config / routing.CHOICES_FILE).read_text())
    assert stored == {"jobs": {"chat": {"model": "haiku", "effort": "low"}}}
    # null returns a field to the default underneath; reset returns the whole job.
    back = jobs_of(put(client, auth, {"job": "chat", "effort": None}).json())["chat"]
    assert (back["model"], back["effort"]) == ("haiku", "high")
    reset = jobs_of(put(client, auth, {"job": "chat", "reset": True}).json())["chat"]
    assert (reset["model"], reset["source"]) == ("opus", "default")
    assert json.loads((isolated_config / routing.CHOICES_FILE).read_text()) == {"jobs": {}}
    assert [entry["after"] for entry in audited(isolated_config)] == [
        {"model": "haiku", "effort": "low"}, {"model": "haiku"}, {}
    ]  # fmt: skip
    assert audited(isolated_config)[1]["before"] == {"model": "haiku", "effort": "low"}


def test_the_environment_setting_stays_underneath(settings, isolated_config):
    configured = dataclasses.replace(
        settings, model_routing=routing.parse("summaries=haiku/low+none")
    )
    efforts = ("low", "medium", "high")
    routing.change(configured, {"job": "summaries", "model": "opus", "advisor": "sonnet"}, efforts)
    chosen = routing.route(configured, "summaries")
    assert (chosen.model, chosen.effort, chosen.advisor, chosen.source) == (
        "opus", "low", "sonnet", "app"
    )  # fmt: skip
    assert routing.run_options(configured, "summaries")["model"] == "opus"
    routing.change(configured, {"job": "summaries", "advisor": "none"}, efforts)
    assert routing.route(configured, "summaries").advisor is None
    routing.change(configured, {"job": "summaries", "reset": True}, efforts)
    under = routing.route(configured, "summaries")
    assert (under.model, under.advisor, under.source) == ("haiku", None, "routing")


@pytest.mark.parametrize(
    "body",
    [
        {"job": "nothing", "model": "opus"},
        {"model": "opus"},
        {"job": "chat"},
        {"job": "chat", "model": "gpt-x"},
        {"job": "chat", "model": "claude-bad name"},
        {"job": "chat", "effort": "turbo"},
        {"job": "chat", "advisor": "someone"},
        {"job": "chat", "model": "opus", "colour": "red"},
        {"job": "chat", "model": 5},
    ],
)
def test_bad_changes_are_refused(owner, auth, body, isolated_config):
    refused = put(owner, auth, body)
    assert refused.status_code == 400, body
    assert not (isolated_config / routing.CHOICES_FILE).exists()


def test_no_change_without_its_audit_record(owner, auth, isolated_config):
    (isolated_config / "audit").write_text("not a folder")
    refused = put(owner, auth, {"job": "coach_plan", "model": "sonnet"})
    assert refused.status_code == 503 and refused.json()["error"] == "audit_unavailable"
    assert routing.app_choices() == {}
    assert owner.put("/models/routing", json={"job": "chat", "model": "opus"}).status_code == 401
    full = put(owner, auth, {"job": "chat", "model": "claude-sonnet-5-5"})
    assert full.status_code == 503  # a full model name is accepted, the audit still blocks
