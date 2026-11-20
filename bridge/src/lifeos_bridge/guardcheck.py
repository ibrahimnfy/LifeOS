"""The guardrail check reruns by itself after every update (#107).

Whenever the installed Claude Code or Agent SDK version differs from the one
a runner's guardrail check passed for, the capable mode of that runner is
off at once: ``capable.check_available`` compares the versions before every
capable run, so nothing here has to happen first for the mode to be off.
This module then starts the check again by itself, tells the owner, and the
mode comes back only when the check has passed completely (the check writes
the marker; nothing here ever does).

- **When:** at bridge start and every ``INTERVAL`` seconds while it runs.
- **Which runners:** those that were checked on this computer before (their
  results file exists), and ``sdk`` only while it is the selected runner.
  A runner that was never checked is never switched on by itself.
- **Which versions:** both, for both runners (decision #84: a change of
  either switches the capable mode off).
- **Once per version pair**, with retries only for a check that could not
  complete (crash, time limit, environment missing): at most
  ``MAX_ATTEMPTS`` attempts, ``RETRY_SECONDS`` apart. A probe that fails is
  final for that pair: the owner is told and the mode stays off.
- **Notifications** go through ``notifications.send`` with the fixed texts
  below: no versions, paths or results in them.
- **Plan usage:** one check is the probe suite of docs/permission-policy.md
  (about ten short runs on the small model) in the terminal and again in the
  service environment, so about twenty short runs per runner and update.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import signal
import sys
import time
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
# Failures that say the check could not run, not that a guardrail failed.
INFRASTRUCTURE = ("service environment: suite ran", "sandbox: probe script ran")

STARTED = (
    "LifeOS: guardrail check started",
    "Claude Code or the Agent SDK was updated. The capable mode is off while the guardrail "
    "check runs by itself; it uses the Claude plan.",
)
PASSED = (
    "LifeOS: guardrail check passed",
    "Every guardrail probe passed after the update. The capable mode is on again.",
)
FAILED = (
    "LifeOS: guardrail check failed",
    "A guardrail probe failed after an update. The capable mode stays off; the results are "
    "in the private settings folder.",
)
INCOMPLETE = (
    "LifeOS: guardrail check incomplete",
    "The guardrail check could not complete after several attempts. The capable mode stays off.",
)

# Starts ``python -m lifeos_bridge.verify --runner R`` and returns its exit status.
Launcher = Callable[[str], Awaitable[int]]


def results_path(runner: str) -> Path:
    name = "guardrail-results.json" if runner == "cli" else "guardrail-results-sdk.json"
    return config_dir() / name


def enrolled(settings: Settings) -> list[str]:
    runners = ["cli"] + (["sdk"] if settings.runner == "sdk" else [])
    return [r for r in runners if results_path(r).exists() or capable.marker_path(r).exists()]


async def version_key(settings: Settings, runner: str) -> str:
    """The versions a runner's check depends on, as one text: both, for both runners."""
    return await capable.claude_version(settings) + " | sdk " + capable.installed_sdk_version()


def only_infrastructure(runner: str) -> bool:
    """True when the last results show only failures of the check itself."""
    try:
        report = json.loads(results_path(runner).read_text(encoding="utf-8"))
        failed = [
            str(probe.get("name"))
            for probes in report.get("probes", {}).values()
            for probe in probes
            if not probe.get("passed")
        ]
    except (OSError, ValueError, AttributeError):
        return True
    return all(name in INFRASTRUCTURE for name in failed)


async def run_verify(runner: str) -> int:
    """The real check, as its own process in the bridge folder (as scripts/verify-guardrails)."""
    bridge_dir = Path(__file__).resolve().parents[2]
    process = await asyncio.create_subprocess_exec(
        sys.executable, "-m", "lifeos_bridge.verify", "--runner", runner,
        cwd=bridge_dir,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
        start_new_session=True,
    )  # fmt: skip
    try:
        return await asyncio.wait_for(process.wait(), CHECK_TIMEOUT)
    except (TimeoutError, asyncio.CancelledError) as stopped:
        # Never leave a check behind (a second one would share its scratch folder).
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
        self.tasks: dict[str, asyncio.Task] = {}

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
        """At start: a check marked running belongs to an earlier bridge; it ended with it."""
        for runner in capable.MARKER_FILE_NAMES:
            capable.set_check_running(runner, False)

    async def tick(self, now: float | None = None) -> None:
        now = time.time() if now is None else now
        if os.environ.get(AUTOCHECK_VAR, "").strip().lower() == "off":
            return  # no automatic rerun (the mode still goes off after an update)
        for runner in enrolled(self.settings):
            if runner in self.tasks and not self.tasks[runner].done():
                continue
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
                continue  # final for this version pair (passed: the marker went away by hand)
            if entry["attempts"] >= MAX_ATTEMPTS or now < entry.get("next_try", 0):
                continue
            if entry["attempts"] == 0:
                self.notify(*STARTED)
            entry = entry | {"attempts": entry["attempts"] + 1, "result": "running"}
            self._save(runner, entry)
            self.tasks[runner] = asyncio.create_task(self._check(runner, entry))

    async def _check(self, runner: str, entry: dict) -> None:
        capable.set_check_running(runner, True)
        try:
            status = await self.launcher(runner)
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
        elif status == 1 and not only_infrastructure(runner):
            self._save(runner, entry | {"result": "failed"})
            self.notify(*FAILED)
        elif entry["attempts"] >= MAX_ATTEMPTS:
            self._save(runner, entry | {"result": "incomplete"})
            self.notify(*INCOMPLETE)
        else:
            self._save(runner, entry | {"result": "", "next_try": time.time() + RETRY_SECONDS})

    async def run_forever(self) -> None:
        self.recover()
        while True:
            with contextlib.suppress(Exception):  # the watcher must survive anything
                await self.tick()
            await asyncio.sleep(INTERVAL)

    async def close(self) -> None:
        for task in self.tasks.values():
            task.cancel()
            with contextlib.suppress(BaseException):
                await task
