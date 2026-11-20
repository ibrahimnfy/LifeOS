"""The guardrail check reruns by itself after every update (#107, #140).

Whenever the installed Claude Code or Agent SDK version differs from the one
a runner's guardrail check passed for, the capable mode of that runner is
off at once: ``capable.check_available`` compares the versions before every
capable run, so nothing here has to happen first for the mode to be off.
This module then starts the check again by itself, tells the owner, and the
mode comes back only when the check has passed completely (the check writes
the marker; nothing here ever does).

- **When:** at bridge start and every ``INTERVAL`` seconds while it runs.
- **Which runners:** ``sdk`` always while it has no valid marker, whether or
  not it is the selected runner, so one bridge restart completes its check
  (#140); ``cli`` when it was checked on this computer before. Checks run
  one after the other (they share a scratch folder), ``cli`` first.
- **Which versions:** both, for both runners (decision #84).
- **Outcomes** of one check (the check is told an id and writes it into its
  results, so older results never count):
  - *passed*: a full pass; the marker matches.
  - *failed*: this check's results show a probe that failed. Final for this
    version pair: the owner is told and the mode stays off.
  - *not run*: anything else: the bridge stopped, a crash, the time limit,
    another check holding the lock, only failures of the check itself.
    Never recorded as failed. Retried ``MAX_ATTEMPTS`` times,
    ``RETRY_SECONDS`` apart, then *incomplete*; at the next bridge start a
    not-run or incomplete check runs again from the start.
- **Left-overs** of an interrupted check (its service-environment run, its
  scratch folder) are cleaned up by the check itself before it starts
  (``verify.main``), under a lock that lets only one check run at a time.
- **Notifications** go through ``notifications.send`` with the fixed texts
  below: no versions, paths or results in them.
- **Plan usage:** see ``PLAN_RUNS`` and docs/bridge.md.
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
from pathlib import Path

from . import capable, notifications, store
from .claude import ClaudeError
from .config import Settings, config_dir

STATE_FILE = "guardrail-check.json"
AUTOCHECK_VAR = "LIFEOS_GUARD_AUTOCHECK"
INTERVAL = 15 * 60
RETRY_SECONDS = 30 * 60
MAX_ATTEMPTS = 3
CHECK_TIMEOUT = 3 * 3600
ORDER = ("cli", "sdk")
# Short model runs of one check, per environment (terminal, then service).
# Probes that need no model (invalid settings, nested folders) are not counted.
PLAN_RUNS = {"cli": 8, "sdk": 12}
# Failures that say the check could not run, not that a guardrail failed.
INFRASTRUCTURE = ("service environment: suite ran", "sandbox: probe script ran")

STARTED = (
    "LifeOS: guardrail check started",
    "Claude Code or the Agent SDK was updated, or a runner was not checked yet. The capable "
    "mode is off while the guardrail check runs by itself; it uses the Claude plan.",
)
PASSED = (
    "LifeOS: guardrail check passed",
    "Every guardrail probe passed. The capable mode is on again.",
)
FAILED = (
    "LifeOS: guardrail check failed",
    "A guardrail probe failed. The capable mode stays off; the results are in the private "
    "settings folder.",
)
INCOMPLETE = (
    "LifeOS: guardrail check incomplete",
    "The guardrail check could not complete after several attempts. The capable mode stays "
    "off; it runs again at the next start of the bridge.",
)

# Starts ``python -m lifeos_bridge.verify --runner R --check-id ID``; returns its exit status.
Launcher = Callable[[str, str], Awaitable[int]]


def results_path(runner: str) -> Path:
    name = "guardrail-results.json" if runner == "cli" else "guardrail-results-sdk.json"
    return config_dir() / name


def enrolled(settings: Settings) -> list[str]:
    checked_before = results_path("cli").exists() or capable.marker_path("cli").exists()
    return [r for r in ORDER if r == "sdk" or checked_before]


async def version_key(settings: Settings, runner: str) -> str:
    """The versions a runner's check depends on, as one text: both, for both runners."""
    return await capable.claude_version(settings) + " | sdk " + capable.installed_sdk_version()


def probe_failed(runner: str, check_id: str) -> bool:
    """True only when the results of exactly this check show a failed guardrail probe."""
    try:
        report = json.loads(results_path(runner).read_text(encoding="utf-8"))
        if report.get("check_id") != check_id:
            return False  # older results: this check did not get as far as writing its own
        failed = [
            str(probe.get("name"))
            for probes in report.get("probes", {}).values()
            for probe in probes
            if not probe.get("passed")
        ]
    except (OSError, ValueError, AttributeError):
        return False
    return any(name not in INFRASTRUCTURE for name in failed)


async def run_verify(runner: str, check_id: str) -> int:
    """The real check, as its own process in the bridge folder (as scripts/verify-guardrails)."""
    bridge_dir = Path(__file__).resolve().parents[2]
    process = await asyncio.create_subprocess_exec(
        sys.executable, "-m", "lifeos_bridge.verify", "--runner", runner, "--check-id", check_id,
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

    @staticmethod
    def _path() -> Path:
        return config_dir() / STATE_FILE

    def state(self) -> dict:
        data = store.read_json(self._path(), {})
        return data if isinstance(data, dict) else {}

    def _save(self, runner: str, entry: dict) -> None:
        data = self.state()
        data[runner] = entry
        store.write_json(self._path(), data)

    def recover(self) -> None:
        """At start: whatever an earlier bridge left unfinished is "not run" and runs again."""
        for runner in capable.MARKER_FILE_NAMES:
            capable.set_check_running(runner, False)
        for runner, entry in self.state().items():
            if isinstance(entry, dict) and entry.get("result") in ("running", "not_run",
                                                                   "incomplete"):  # fmt: skip
                self._save(runner, entry | {"result": "not_run", "attempts": 0, "next_try": 0})

    async def due(self, now: float) -> list[tuple[str, dict]]:
        """The runners whose check should start now, with their state entries."""
        found = []
        for runner in enrolled(self.settings):
            try:
                key = await version_key(self.settings, runner)
            except ClaudeError:
                continue  # the version cannot be read: the mode is off anyway; try again later
            entry = self.state().get(runner, {})
            if entry.get("versions") != key:
                entry = {"versions": key, "attempts": 0, "result": "", "next_try": 0}
            try:
                await capable.check_available(self.settings, None, runner=runner)
                if entry.get("result") != "passed":
                    self._save(runner, entry | {"result": "passed"})
                continue  # verified for exactly these versions
            except ClaudeError:
                pass
            if entry.get("result") in ("failed", "incomplete", "passed"):
                continue  # final until the versions change or the bridge restarts
            if entry["attempts"] >= MAX_ATTEMPTS or now < entry.get("next_try", 0):
                continue
            found.append((runner, entry))
        return found

    async def tick(self, now: float | None = None) -> None:
        now = time.time() if now is None else now
        if os.environ.get(AUTOCHECK_VAR, "").strip().lower() == "off":
            return  # no automatic rerun (the mode still goes off after an update)
        if self.task is not None and not self.task.done():
            return  # one check at a time
        due = await self.due(now)
        if due:
            self.task = asyncio.create_task(self._run_all(due))

    async def _run_all(self, due: list[tuple[str, dict]]) -> None:
        for runner, entry in due:
            await self._check(runner, entry)

    async def _check(self, runner: str, entry: dict) -> None:
        if not entry.get("notified"):
            self.notify(*STARTED)
        check_id = uuid.uuid4().hex
        entry = entry | {"attempts": entry["attempts"] + 1, "result": "running", "notified": True}
        self._save(runner, entry)
        capable.set_check_running(runner, True)
        try:
            status = await self.launcher(runner, check_id)
        except asyncio.CancelledError:
            # The bridge is stopping: not run, and not counted; it reruns at the next start.
            self._save(runner, entry | {"result": "not_run", "attempts": entry["attempts"] - 1})
            raise
        except Exception:  # the check could not be started
            status = -1
        finally:
            capable.set_check_running(runner, False)
        passed = False
        if status == 0:
            with contextlib.suppress(ClaudeError):
                await capable.check_available(self.settings, None, runner=runner)
                passed = True
        if passed:
            self._save(runner, entry | {"result": "passed"})
            self.notify(*PASSED)
        elif status == 1 and probe_failed(runner, check_id):
            self._save(runner, entry | {"result": "failed"})
            self.notify(*FAILED)
        elif entry["attempts"] >= MAX_ATTEMPTS:
            self._save(runner, entry | {"result": "incomplete"})
            self.notify(*INCOMPLETE)
        else:  # not run: crash, time limit, lock busy, the check's own environment failed
            retry = time.time() + RETRY_SECONDS
            self._save(runner, entry | {"result": "not_run", "next_try": retry})

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
