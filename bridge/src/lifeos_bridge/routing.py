"""Which model each of the bridge's model jobs uses, and the plan usage the runs report.

Every model run the bridge starts belongs to one **job**. The routing setting
``LIFEOS_MODEL_ROUTING`` names model, effort and advisor per job; jobs it
leaves out use the defaults below. ``LIFEOS_BACKGROUND_MODEL`` (the older
single setting) still works: when set, it is the model of every background
job that the routing setting does not name, with no advisor, exactly as before.

The advisor is Claude Code's documented ``--advisor <model>`` flag
(docs/research/claude-headless.md): the run's own model does the work and
may consult the stronger advisor model at hard steps.

Usage comes from the runs themselves: the final ``result`` message of
``claude -p`` carries ``usage``, ``total_cost_usd`` (an estimate made by
Claude Code, not billing data) and ``modelUsage``. It is added up per local
day and job in ``~/.config/lifeos/usage.json``; no prompt or answer is kept.

Saving notes and tasks, the course sync and notifications use no model and
are not jobs.
"""

from __future__ import annotations

import contextlib
import re
import threading
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from . import store
from .config import config_dir

ROUTING_VAR = "LIFEOS_MODEL_ROUTING"
USAGE_FILE = "usage.json"
KEEP_DAYS = 62
MAX_SESSIONS = 300  # the last reported cost per session, to count a resumed run's growth only
EFFORTS = ("low", "medium", "high", "xhigh", "max")
MODEL_NAME = re.compile(r"^[a-z0-9][a-z0-9.\-\[\]]{0,79}$")
NO_MODEL = ("saving notes and tasks", "course sync", "notifications")
TOKENS = ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")


@dataclass(frozen=True)
class Job:
    label: str
    model: str | None  # None: the owner's default model (chat sessions)
    effort: str | None  # None: the default effort
    advisor: str | None = None
    background: bool = True  # LIFEOS_BACKGROUND_MODEL applies when the routing does not name it


JOBS: Mapping[str, Job] = {
    "chat": Job("Chat sessions (default; each session can choose its own)", None, None,
                background=False),
    "coach_plan": Job("Coach: morning run and plan refresh", "opus", "high"),
    "coach_chat": Job("Coach: the conversation, its compaction, and filing answers", "opus",
                      "high"),
    "consolidation": Job("Nightly consolidation proposals", "sonnet", None, "opus"),
    "course_resources": Job("Course resource research from syllabi", "sonnet", None, "opus"),
    "summaries": Job("Summaries", "sonnet", None, "opus"),
    "context_map": Job("Context map of the allowed folders", "sonnet", None, "opus"),
    # Interactive and short: the background model setting does not apply (#185).
    "command": Job("Command bar: a typed or spoken request turned into app actions", "haiku",
                   "low", background=False),
    # #181: the cheapest capable model; never moved by LIFEOS_BACKGROUND_MODEL, so a
    # change of the probe model is always a choice made for this job.
    "guardrail_check": Job("Guardrail check (core set after updates, monthly full suite)",
                           "haiku", None, background=False),
}  # fmt: skip


@dataclass(frozen=True)
class Route:
    job: str
    model: str | None
    effort: str | None
    advisor: str | None
    source: str  # "default", "routing" or "background_model"


class RoutingError(ValueError):
    """The routing setting cannot be read."""


def parse(text: str | None) -> dict[str, tuple[str | None, str | None, str | None, bool]]:
    """``job=model[/effort][+advisor]`` entries separated by commas or spaces.

    Example: ``coach_plan=opus/high, context_map=sonnet+opus, summaries=haiku+none``.
    Returns job → (model, effort, advisor, advisor given).
    """
    found: dict[str, tuple[str | None, str | None, str | None, bool]] = {}
    for entry in re.split(r"[,\s]+", text or ""):
        if not entry:
            continue
        job, separator, spec = entry.partition("=")
        if not separator or job not in JOBS:
            known = ", ".join(JOBS)
            raise RoutingError(f"{ROUTING_VAR}: {entry!r} must be job=model; jobs: {known}")
        spec, plus, advisor = spec.partition("+")
        model, slash, effort = spec.partition("/")
        if not MODEL_NAME.match(model):
            raise RoutingError(f"{ROUTING_VAR}: {model!r} is not a model name or alias")
        if slash and effort not in EFFORTS:
            raise RoutingError(f"{ROUTING_VAR}: effort must be one of {', '.join(EFFORTS)}")
        if plus and advisor != "none" and not MODEL_NAME.match(advisor):
            raise RoutingError(f"{ROUTING_VAR}: {advisor!r} is not an advisor model or `none`")
        chosen_advisor = None if advisor == "none" else advisor or None
        found[job] = (model, effort or None, chosen_advisor, bool(plus))
    return found


def route(settings, job: str, *, with_app: bool = True) -> Route:
    """Model, effort and advisor of one job under ``settings``.

    The owner's choices from the app (``PUT /models/routing``) come first; under
    them, ``LIFEOS_MODEL_ROUTING``, then ``LIFEOS_BACKGROUND_MODEL``, then the
    defaults of :data:`JOBS`. ``with_app=False`` gives the default underneath.
    """
    base = _route_from_settings(settings, job)
    chosen = app_choices().get(job) if with_app else None
    if not chosen:
        return base
    advisor = chosen.get("advisor", base.advisor)
    return Route(
        job,
        chosen.get("model", base.model),
        chosen.get("effort", base.effort),
        None if advisor == "none" else advisor,
        "app",
    )


def _route_from_settings(settings, job: str) -> Route:
    defaults = JOBS[job]
    named = settings.model_routing.get(job)
    if named is not None:
        model, effort, advisor, advisor_given = named
        return Route(job, model, effort or defaults.effort,
                     advisor if advisor_given else defaults.advisor, "routing")  # fmt: skip
    if defaults.background and settings.background_model:
        return Route(job, settings.background_model, defaults.effort, None, "background_model")
    return Route(job, defaults.model, defaults.effort, defaults.advisor, "default")


# ---- the owner's choices from the app ------------------------------------------------------

CHOICES_FILE = "model-routing.json"
CHOICE_FIELDS = ("model", "effort", "advisor")
_CHOICES = threading.Lock()


def app_choices() -> dict[str, dict]:
    """Job → the fields the owner chose in the app (only those; read on every use)."""
    data = store.read_json(config_dir() / CHOICES_FILE, {})
    jobs = data.get("jobs") if isinstance(data.get("jobs"), dict) else {}
    found: dict[str, dict] = {}
    for job, chosen in jobs.items():
        if job in JOBS and isinstance(chosen, dict):
            kept = {k: v for k, v in chosen.items() if k in CHOICE_FIELDS and isinstance(v, str)}
            if kept:
                found[job] = kept
    return found


def _bad(message: str):
    from .claude import ClaudeError  # local import: claude imports this module

    return ClaudeError("bad_request", 400, message)


def _model_name(settings, value: object, name: str, extra: tuple[str, ...] = ()) -> str:
    known = (*settings.models, *extra)
    if isinstance(value, str) and (
        value in known or (value.startswith("claude-") and MODEL_NAME.match(value))
    ):
        return value
    raise _bad(f"`{name}` must be one of: {', '.join(known)} (or a full claude-… name)")


def change(settings, data: dict, efforts: tuple[str, ...]) -> dict:
    """Apply one change of ``PUT /models/routing``; returns the new overview.

    Body: ``{"job", "model"?, "effort"?, "advisor"?}`` or ``{"job", "reset": true}``.
    A field left out stays as it is; ``null`` returns it to the default underneath;
    ``"advisor": "none"`` switches the advisor off. Written to the audit log first:
    when the record cannot be written, nothing changes.
    """
    from . import audit
    from .claude import ClaudeError

    job = data.get("job")
    if job not in JOBS:
        raise _bad(f"`job` must be one of: {', '.join(JOBS)}")
    unknown = set(data) - {"job", "reset", *CHOICE_FIELDS}
    reset = data.get("reset") is True
    if unknown or (not reset and not set(data) & set(CHOICE_FIELDS)):
        raise _bad("give `model`, `effort` or `advisor` (or `reset`: true) for the job")
    with _CHOICES:
        everything = app_choices()
        before = dict(everything.get(job) or {})
        after = {} if reset else dict(before)
        for field in () if reset else CHOICE_FIELDS:
            if field not in data:
                continue
            value = data[field]
            if value is None:
                after.pop(field, None)
            elif field == "model":
                after["model"] = _model_name(settings, value, "model")
            elif field == "effort":
                if value not in efforts:
                    raise _bad(f"`effort` must be one of: {', '.join(efforts)}")
                after["effort"] = value
            else:
                after["advisor"] = _model_name(settings, value, "advisor", ("none",))
        try:
            audit.record("ModelRouting", {"job": job, "before": before, "after": after})
        except audit.AuditUnavailable as exc:
            raise ClaudeError(
                "audit_unavailable", 503, "the audit log cannot be written; nothing was changed"
            ) from exc
        if after:
            everything[job] = after
        else:
            everything.pop(job, None)
        store.write_json(config_dir() / CHOICES_FILE, {"jobs": everything})
    return {**overview(settings), "changed": job}


def run_options(settings, job: str) -> dict:
    """Keyword arguments for :func:`claude.run` / :func:`claude.stream` of a job's run.

    ``LIFEOS_CLAUDE_MODEL`` (the guardrail probes' fixed model) overrides every job.
    """
    chosen = route(settings, job)
    if settings.claude_model:
        return {"model": settings.claude_model, "effort": chosen.effort, "advisor": None,
                "job": job}  # fmt: skip
    return {"model": chosen.model, "effort": chosen.effort, "advisor": chosen.advisor, "job": job}


# ---- usage -------------------------------------------------------------------------------

_LOCK = threading.Lock()


def _number(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0.0
    return float(value)


def from_result(payload: object) -> dict | None:
    """The usage a run reported in its final ``result`` message, or None."""
    if not isinstance(payload, dict) or payload.get("type") != "result":
        return None
    usage = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
    models = payload.get("modelUsage") if isinstance(payload.get("modelUsage"), dict) else {}
    return {
        **{name: int(_number(usage.get(name))) for name in TOKENS},
        "cost_usd": _number(payload.get("total_cost_usd")),
        "models": sorted(str(name)[:80] for name in models)[:10],
    }


def record(job: str, payload: object, today: date | None = None, *, resumed: bool = False) -> None:
    """Add one run's reported usage to its job and day. Never fails the run.

    A resumed run reports the conversation's cumulative ``total_cost_usd``
    (documented), so only the growth since the session's last run is added.
    """
    found = from_result(payload)
    if found is None:
        return
    day = (today or datetime.now().astimezone().date()).isoformat()
    session = str(payload.get("session_id") or "")[:64] if isinstance(payload, dict) else ""
    with _LOCK, contextlib.suppress(OSError, ValueError, TypeError):
        path = config_dir() / USAGE_FILE
        data = store.read_json(path, {})
        sessions = data.get("sessions") if isinstance(data.get("sessions"), dict) else {}
        if session:
            known = session in sessions
            previous = _number(sessions.pop(session, 0.0)) if resumed else 0.0
            sessions[session] = found["cost_usd"]  # newest last; the oldest are dropped
            data["sessions"] = dict(list(sessions.items())[-MAX_SESSIONS:])
            if resumed and not known:
                # A session from before the counting began: its total so far is only
                # the baseline (counting it would book weeks of use to today).
                found["cost_usd"] = 0.0
            elif found["cost_usd"] >= previous:
                found["cost_usd"] -= previous
        days = data.get("days") if isinstance(data.get("days"), dict) else {}
        entry = days.setdefault(day, {}).setdefault(job, {"runs": 0, "cost_usd": 0.0, "models": []})
        entry["runs"] = int(entry.get("runs") or 0) + 1
        for name in TOKENS:
            entry[name] = int(entry.get(name) or 0) + found[name]
        entry["cost_usd"] = round(_number(entry.get("cost_usd")) + found["cost_usd"], 6)
        entry["models"] = sorted({*entry.get("models", []), *found["models"]})[:10]
        cutoff = (date.fromisoformat(day) - timedelta(days=KEEP_DAYS)).isoformat()
        data["days"] = {d: v for d, v in days.items() if d >= cutoff}
        store.write_json(path, data)


def _total(days: dict, job: str, first: date, last: date) -> dict:
    total = {"runs": 0, **dict.fromkeys(TOKENS, 0), "cost_usd": 0.0, "models": []}
    models: set[str] = set()
    for day, jobs in days.items():
        try:
            when = date.fromisoformat(day)
        except ValueError:
            continue
        entry = jobs.get(job) if isinstance(jobs, dict) else None
        if not isinstance(entry, dict) or not first <= when <= last:
            continue
        total["runs"] += int(entry.get("runs") or 0)
        for name in TOKENS:
            total[name] += int(entry.get(name) or 0)
        total["cost_usd"] += _number(entry.get("cost_usd"))
        models.update(str(m) for m in entry.get("models") or [])
    total["cost_usd"] = round(total["cost_usd"], 6)
    total["models"] = sorted(models)
    return total


def overview(settings, today: date | None = None) -> dict:
    """What ``GET /models/routing`` returns: every job, its model, and its usage."""
    today = today or datetime.now().astimezone().date()
    monday = today - timedelta(days=today.weekday())
    days = store.read_json(config_dir() / USAGE_FILE, {}).get("days") or {}
    jobs = []
    for name, job in JOBS.items():
        chosen = route(settings, name)
        under = route(settings, name, with_app=False)
        model = settings.claude_model or chosen.model or settings.default_model
        jobs.append(
            {
                "job": name,
                "label": job.label,
                "model": model,
                "effort": chosen.effort or settings.default_effort,
                "advisor": None if settings.claude_model else chosen.advisor,
                "source": chosen.source,
                # What the job uses without the owner's choice in the app.
                "default": {
                    "model": settings.claude_model or under.model or settings.default_model,
                    "effort": under.effort or settings.default_effort,
                    "advisor": None if settings.claude_model else under.advisor,
                    "source": under.source,
                },
                "per_session": name == "chat",
                "usage": {
                    "today": _total(days, name, today, today),
                    "week": _total(days, name, monday, today),
                },
            }
        )
    return {
        "jobs": jobs,
        "no_model": list(NO_MODEL),
        "today": today.isoformat(),
        "week_from": monday.isoformat(),
        "note": "Usage as the runs report it; cost_usd is Claude Code's own estimate.",
    }
