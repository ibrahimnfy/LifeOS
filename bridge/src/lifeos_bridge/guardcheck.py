"""The guardrail check runs by itself (#107, #140, lean since #181).

Which check, when:

- **No valid marker** (never verified, or a check failed): the **full suite**, at
  once. The capable mode is off until it passes.
- **Claude Code or the Agent SDK was updated, or the core set changed** (the
  marker's versions or core version differ from what is installed): the **core
  set** (``verify.CORE``) on the cheapest capable model (routing job
  ``guardrail_check``). The capable mode stays on meanwhile: the last verified
  state holds.
- **Monthly**: the full suite again, when the last full pass is older than
  ``FULL_EVERY`` or came from an older suite, at a quiet time (``QUIET_HOURS``
  local time, nothing running), and at most one automatic full check per
  ``FULL_EVERY``. The owner can always run it by hand
  (``scripts/verify-guardrails --full``).

Outcomes of one check (the check is told an id and writes it into its results,
so older results never count):

- *passed*: the check wrote the marker.
- *failed*: a guardrail probe failed. The check itself removed the marker: the
  only way the capable mode goes off (``capable.switch_off``). Final until the
  versions or the suites change; the owner is told.
- *not run*: anything else (a probe whose scenario did not happen, a crash, the
  time limit, the bridge stopping, another check holding the lock, the check's
  own environment failing). Never failed: the mode stays as it was. Retried
  ``MAX_ATTEMPTS`` times, ``RETRY_SECONDS`` apart, then *incomplete* (the owner
  is told); a not-run or incomplete check runs again after the next start.

Checks run one at a time, never while an update is being applied (``held``) or
while the program folder's code differs from the running code. Notifications go
through ``notifications.send`` with the fixed texts below: no versions, paths or
results in them. Plan usage: ``PLAN_RUNS`` and docs/bridge.md.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import signal
import sys
import time
import uuid
from collections.abc import Awaitable, Callable
from datetime import datetime
from pathlib import Path

from . import capable, notifications, program, store
from .claude import ClaudeError
from .config import Settings, config_dir

STATE_FILE = "guardrail-check.json"
AUTOCHECK_VAR = "LIFEOS_GUARD_AUTOCHECK"
INTERVAL = 15 * 60
RETRY_SECONDS = 30 * 60
MAX_ATTEMPTS = 3
CHECK_TIMEOUT = 3 * 3600
FULL_EVERY = 30 * 24 * 3600
QUIET_HOURS = (3, 6)  # local time: from 03:00 to before 06:00
# Short model runs of one check (retries of skipped steps not counted). The full suite
# runs here and again in the service environment.
PLAN_RUNS = {"core": 9, "full": 26}

STARTED = (
    "LifeOS: guardrail check started",
    "The capable mode is off until the guardrail check has passed. It runs by itself and "
    "uses the Claude plan.",
)
PASSED = (
    "LifeOS: guardrail check passed",
    "Every guardrail probe passed. The capable mode is on.",
)
FAILED = (
    "LifeOS: guardrail check failed",
    "A guardrail probe failed, so the capable mode is off. The results are in the private "
    "settings folder.",
)
INCOMPLETE = (
    "LifeOS: guardrail check incomplete",
    "The guardrail check could not complete after several attempts. The capable mode stays "
    "as it was; the check runs again after the next start of the bridge.",
)

# Starts ``python -m lifeos_bridge.verify --core|--full --check-id ID``; returns its status.
Launcher = Callable[[str, str], Awaitable[int]]


def results_path() -> Path:
    from .verify import RESULTS_FILE  # local: verify is large and needs this module

    return config_dir() / RESULTS_FILE


def probe_failed(check_id: str) -> bool:
    """True only when the results of exactly this check show a failed guardrail probe."""
    from .verify import guardrail_failures  # local: verify is large and needs this module

    try:
        report = json.loads(results_path().read_text(encoding="utf-8"))
        if report.get("check_id") != check_id:
            return False  # older results: this check did not get as far as writing its own
        return bool(guardrail_failures(report.get("probes", {})))
    except (OSError, ValueError, AttributeError, TypeError):
        return False


async def run_verify(kind: str, check_id: str) -> int:
    """The real check, as its own process in the bridge folder (as scripts/verify-guardrails)."""
    bridge_dir = Path(__file__).resolve().parents[2]
    process = await asyncio.create_subprocess_exec(
        sys.executable, "-m", "lifeos_bridge.verify", f"--{kind}", "--check-id", check_id,
        cwd=bridge_dir,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
        start_new_session=True,
    )  # fmt: skip
    try:
        return await asyncio.wait_for(process.wait(), CHECK_TIMEOUT)
    except (TimeoutError, asyncio.CancelledError) as stopped:
        # Never leave a check behind; its service run is stopped by the next check.
        with contextlib.suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        await process.wait()
        if isinstance(stopped, asyncio.CancelledError):
            raise
        return 124


def _full_pass_age(marker: dict, now: float) -> float:
    """Seconds since the marker's last full pass; very large when unknown."""
    text = marker.get("full_date") or (
        marker.get("date") if marker.get("check") != "core" else None
    )
    try:
        return now - datetime.fromisoformat(str(text)).timestamp()
    except ValueError:
        return float("inf")


class GuardCheck:
    def __init__(
        self,
        settings: Settings,
        launcher: Launcher = run_verify,
        notify: Callable[[str, str], None] | None = None,
    ) -> None:
        self.settings = settings
        self.launcher = launcher
        self.notify = notify or (
            lambda title, text: notifications.send(title, text, event="notice")
        )
        self.task: asyncio.Task | None = None
        # True while an approved update is being applied (#180): no check starts then.
        self.held: Callable[[], bool] = lambda: False
        # True while anything runs (runs, queues, jobs): the monthly full check waits.
        self.busy: Callable[[], bool] = lambda: False
        self.hour: Callable[[float], int] = lambda now: time.localtime(now).tm_hour

    @staticmethod
    def _path() -> Path:
        return config_dir() / STATE_FILE

    def state(self) -> dict:
        data = store.read_json(self._path(), {})
        return data if isinstance(data, dict) else {}

    def _save(self, **changes: object) -> None:
        store.write_json(self._path(), self.state() | changes)

    def recover(self) -> None:
        """At start: whatever an earlier bridge left unfinished is "not run" and runs again."""
        capable.remove_stale_files()
        entry = self.state().get("current")
        if isinstance(entry, dict) and entry.get("result") in ("running", "not_run",
                                                               "incomplete"):  # fmt: skip
            self._save(current=entry | {"result": "not_run", "attempts": 0, "next_try": 0})

    @staticmethod
    def key(claude_version: str) -> str:
        """What a check's outcome depends on: both versions and both probe suites."""
        return (
            f"{claude_version} | sdk {capable.installed_sdk_version()}"
            f" | core {capable.CORE_VERSION} | suite {capable.SUITE_VERSION}"
        )

    def _quiet(self, now: float) -> bool:
        low, high = QUIET_HOURS
        return low <= self.hour(now) < high and not self.busy()

    async def due(self, now: float) -> tuple[str, dict] | None:
        """The check that should start now (``"core"`` or ``"full"``) with its state entry."""
        try:
            installed = await capable.claude_version(self.settings)
        except ClaudeError:
            return None  # the version cannot be read: try again later
        key = self.key(installed)
        marker = capable.read_marker()
        state = self.state()
        current = state.get("current") if isinstance(state.get("current"), dict) else {}
        if current.get("key") == key and current.get("result") == "failed":
            return None  # a failure is final until the versions or the suites change
        # A monthly check that started and did not finish keeps its slot for its retries.
        in_slot = (
            current.get("kind") == "monthly"
            and current.get("key") == key
            and current.get("result") in ("", "running", "not_run")
        )
        if marker is None:
            kind = "full"  # never verified, or a check failed
        elif capable.core_outdated(installed, capable.installed_sdk_version()):
            kind = "core"
        elif (_full_pass_age(marker, now) >= FULL_EVERY or capable.suite_outdated()) and (
            in_slot or now - float(state.get("full_tried") or 0) >= FULL_EVERY
        ):
            kind = "monthly"
        else:
            return None
        entry = current
        if (
            entry.get("key") != key
            or entry.get("kind") != kind
            or (kind == "monthly" and not in_slot)
        ):
            entry = {"kind": kind, "key": key, "attempts": 0, "result": "", "next_try": 0}
        if entry.get("result") in ("failed", "incomplete", "passed"):
            return None  # final until the key changes or the bridge restarts
        if entry["attempts"] >= MAX_ATTEMPTS or now < entry.get("next_try", 0):
            return None
        if kind == "monthly" and not self._quiet(now):
            return None
        return kind, entry

    async def tick(self, now: float | None = None) -> None:
        now = time.time() if now is None else now
        if os.environ.get(AUTOCHECK_VAR, "").strip().lower() == "off":
            return  # no automatic check (by hand: scripts/verify-guardrails)
        if self.settings.runner != "sdk":
            return  # claude -p keeps read-only runs only: no capable mode to check (#181)
        if program.code_changed() or self.held():
            # The check runs the bridge's code from the program folder in a process of its
            # own. A run there may have changed it (#180): the check waits until the bridge
            # starts again (after an update), then runs with that code.
            return
        if self.task is not None and not self.task.done():
            return  # one check at a time
        found = await self.due(now)
        if found is not None:
            self.task = asyncio.create_task(self._check(*found))

    async def _check(self, kind: str, entry: dict) -> None:
        was_on = capable.read_marker() is not None
        if not was_on and not entry.get("notified"):
            self.notify(*STARTED)  # only when the mode is off: otherwise nothing changes
        if kind == "monthly" and entry["attempts"] == 0:
            self._save(full_tried=time.time())
        check_id = uuid.uuid4().hex
        entry = entry | {"attempts": entry["attempts"] + 1, "result": "running", "notified": True}
        self._save(current=entry)
        try:
            status = await self.launcher("core" if kind == "core" else "full", check_id)
        except asyncio.CancelledError:
            # The bridge is stopping: not run, and not counted; it reruns at the next start.
            self._save(current=entry | {"result": "not_run", "attempts": entry["attempts"] - 1})
            raise
        except Exception:  # the check could not be started
            status = -1
        from .verify import FAILED as CHECK_FAILED  # local: verify is large

        if status == 0 and capable.read_marker() is not None:
            self._save(current=entry | {"result": "passed"})
            if not was_on:
                self.notify(*PASSED)
        elif status == CHECK_FAILED and probe_failed(check_id):
            self._save(current=entry | {"result": "failed"})
            self.notify(*FAILED)
        elif entry["attempts"] >= MAX_ATTEMPTS:
            self._save(current=entry | {"result": "incomplete"})
            self.notify(*INCOMPLETE)
        else:  # not run: inconclusive, crash, time limit, lock busy, its own environment
            self._save(
                current=entry | {"result": "not_run", "next_try": time.time() + RETRY_SECONDS}
            )

    async def run_forever(self) -> None:
        self.recover()
        while True:
            with contextlib.suppress(Exception):  # the watcher must survive anything
                await self.tick()
            await asyncio.sleep(INTERVAL)

    async def close(self) -> None:
        if self.task is not None:
            self.task.cancel()
            with contextlib.suppress(BaseException):
                await self.task
