"""Warn the owner before, and when, the Moodle sign-in stops working.

The bridge's watcher calls :func:`check` every few seconds; it contacts the
site at most once per day (after a failed attempt: once per hour) and keeps
its state in ``moodle-watch.json`` in the private config folder.

- The site refuses the token as invalid: one notification per day.
- The token is older than :data:`WARN_AFTER_DAYS`: one notification per
  three days. **This is an estimate.** The site does not tell a client when
  a token expires; 12 weeks is Moodle's default lifetime, the site may use
  another one, and the site may have handed out a token it had issued
  earlier, so the token can be older than the day it was saved here. A token
  stored before that day was recorded has no known age and gets no such
  warning.
- The site cannot be reached, or answers with another error: no warning.

Notification texts are fixed: no address, no names. ``scripts/moodle-login``
deletes the state after a new sign-in, so the new token is checked at once.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

from . import moodle_login, notifications, store
from .config import ENV_FILE_NAME, ConfigError, config_dir, read_env_file

TOKEN_LIFETIME_DAYS = 84  # Moodle's default "user created token duration": 12 weeks
WARN_AFTER_DAYS = TOKEN_LIFETIME_DAYS - 7  # 77: an estimate, see the module text
CHECK_EVERY = timedelta(days=1)
RETRY_AFTER = timedelta(hours=1)
EXPIRED_WARNING_EVERY = timedelta(days=1)
EXPIRING_WARNING_EVERY = timedelta(days=3)

TITLE = "LifeOS: Moodle sign-in needed"
EXPIRED_TEXT = "The Moodle sign-in has expired. Run scripts/moodle-login."
EXPIRING_TEXT = "The Moodle sign-in will probably expire within a week. Run scripts/moodle-login."

Clock = Callable[[], datetime]


def now_utc() -> datetime:
    return datetime.now(UTC)


def state_path() -> Path:
    return config_dir() / moodle_login.WATCH_FILE


def _settings() -> dict[str, str]:
    try:
        return read_env_file(config_dir() / ENV_FILE_NAME)
    except (ConfigError, OSError):
        return {}


def _iso(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _parse(value: object) -> datetime | None:
    try:
        moment = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return moment if moment.tzinfo else None


def _since(state: dict, key: str, now: datetime) -> timedelta | None:
    moment = _parse(state.get(key))
    return None if moment is None else now - moment


def age_days(values: dict[str, str], now: datetime) -> int | None:
    """Days since the token was saved, by the local calendar; None when unknown."""
    try:
        saved = date.fromisoformat(values.get(moodle_login.SAVED_KEY, "").strip())
    except ValueError:
        return None
    return max(0, (now.astimezone().date() - saved).days)


def _warn(state: dict, now: datetime, every: timedelta, text: str, script: Path | None) -> None:
    """Notify unless a warning went out less than ``every`` ago. State is saved first."""
    since = _since(state, "last_warning", now)
    if since is not None and timedelta(0) <= since < every:
        return
    state["last_warning"] = _iso(now)
    store.write_json(state_path(), state)
    notifications.send(TITLE, text, "", script, event="signin")


def check(clock: Clock = now_utc, notify_script: Path | None = None) -> str:
    """One step of the watch. Returns what happened, for tests and logs.

    ``not_configured``, ``not_due``, ``ok``, ``expiring``, ``expired`` or
    ``unreachable``. The time of the attempt is stored *before* the site is
    contacted, so a failure can never make the watcher ask again and again.
    """
    values = _settings()
    url = values.get("MOODLE_URL", "").strip().rstrip("/")
    token = values.get("MOODLE_TOKEN", "").strip()
    if not url or not token:
        return "not_configured"
    now = clock()
    state = store.read_json(state_path(), {})
    since = _since(state, "last_check", now)
    wait = RETRY_AFTER if state.get("failed") else CHECK_EVERY
    if since is not None and timedelta(0) <= since < wait:
        return "not_due"
    state.update(last_check=_iso(now), failed=True)
    store.write_json(state_path(), state)
    try:
        moodle_login.check_token(url, token)
    except moodle_login.TokenRejected as refusal:
        if not refusal.expired:
            return "unreachable"
        state.update(failed=False, result="expired")
        store.write_json(state_path(), state)
        _warn(state, now, EXPIRED_WARNING_EVERY, EXPIRED_TEXT, notify_script)
        return "expired"
    except moodle_login.LoginError:
        return "unreachable"
    state.update(failed=False, result="ok")
    store.write_json(state_path(), state)
    age = age_days(values, now)
    if age is not None and age >= WARN_AFTER_DAYS:
        _warn(state, now, EXPIRING_WARNING_EVERY, EXPIRING_TEXT, notify_script)
        return "expiring"
    return "ok"


def status(clock: Clock = now_utc) -> dict:
    """What ``GET /moodle/status`` returns. Reads stored state only; no address, no names."""
    values = _settings()
    configured = bool(
        values.get("MOODLE_URL", "").strip() and values.get("MOODLE_TOKEN", "").strip()
    )
    state = store.read_json(state_path(), {})
    age = age_days(values, clock()) if configured else None
    if not configured:
        result = "not_configured"
    elif state.get("result") == "expired":
        result = "expired"
    elif age is not None and age >= WARN_AFTER_DAYS:
        result = "expiring"
    elif state.get("result") == "ok":
        result = "ok"
    else:
        result = "unknown"
    return {
        "configured": configured,
        "token_saved": (values.get(moodle_login.SAVED_KEY) or None) if age is not None else None,
        "age_days": age,
        "state": result,
        "last_check": state.get("last_check") if configured else None,
    }
