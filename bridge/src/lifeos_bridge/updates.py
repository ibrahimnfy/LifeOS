"""Applying an update that a chat in the LifeOS program folder produced (#180).

A capable run in the program folder changes the bridge's and the app's code
inside the sandbox. Running that code is outside the sandbox: the tests, the
app build and the restarted bridge. So the run can only *ask*, with a line
of its own in a fresh answer to the owner's message::

    [[apply-update]]

The bridge takes the line out and turns it into an approval card (kind
``apply_update``, ``approvals``) naming the commit the program folder is at.
Only the owner's yes starts the update, and only for that commit; a later
request replaces a waiting card. After the yes the bridge:

1. waits until no run is active (both runners, live SDK sessions with a run
   in progress, queued messages, background jobs, requests in progress);
   from then on it starts no run until it restarts;
2. checks that the program folder is still at the approved commit and has no
   uncommitted changes to tracked files;
3. runs the bridge's tests and lint with the bridge's own interpreter (no
   package is installed or updated);
4. builds the app with the existing release flow and registers it when its
   version code is newer than the registered release (the phones get the
   usual update push);
5. checks the commit again and that nothing runs, then restarts its own user
   service through the service manager (``systemctl --user --no-block
   restart``: the restart is a job of the service manager, so it completes
   after this process is stopped). Not running as that service: nothing is
   restarted and the owner is told to restart it.

A failing step applies nothing more and is reported with its name. The
state is kept in ``update-state.json`` in the private settings folder; at
the next start a ``restarting`` state means the update runs now, any earlier
state means it was interrupted. Requests, answers and outcomes are in the
audit log.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import re
import subprocess
import sys
import time
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path

from . import approvals, audit, notifications, program, release, store
from .claude import ClaudeError
from .config import ENV_FILE_NAME, ConfigError, Settings, config_dir, read_env_file

KIND = "apply_update"
MARKER = re.compile(r"^[ \t]*\[\[apply-update\]\][ \t]*$", re.MULTILINE)
STATE_FILE = "update-state.json"
LOG_FILE = "update-log.txt"
LOG_KEEP = 20_000  # characters of each step's output kept in the log
WAIT_LIMIT = 12 * 3600  # runs that do not end within this make the update fail ("wait")
POLL_SECONDS = 5.0
STEP_TIMEOUT = 1800
RESTART_GRACE = 120.0  # still running this long after the restart was asked: it failed
SERVICE_VAR = "LIFEOS_SERVICE_NAME"
DEFAULT_SERVICE = "lifeos-bridge.service"
# Settings of the app build (in ~/.config/lifeos/env): the service has only PATH.
BUILD_VARS = {
    "LIFEOS_JAVA_HOME": "JAVA_HOME",
    "LIFEOS_ANDROID_HOME": "ANDROID_HOME",
    "LIFEOS_GRADLE_USER_HOME": "GRADLE_USER_HOME",
    "LIFEOS_ANDROID_USER_HOME": "ANDROID_USER_HOME",
}

WAITING, CHECKING, TESTING, BUILDING, RESTARTING = (
    "waiting", "checking", "testing", "building", "restarting",
)  # fmt: skip
APPLIED, FAILED, INTERRUPTED = "applied", "failed", "interrupted"
IN_PROGRESS = frozenset({WAITING, CHECKING, TESTING, BUILDING, RESTARTING})

INSTRUCTIONS = (
    "This conversation works in the LifeOS program folder: the code of the bridge that runs "
    "you and of the phone app. Never restart the bridge or its service, never install or "
    "reinstall the app, and never run the release command yourself. When your changes are "
    "committed and the owner wants them applied, write a line of its own: [[apply-update]]. "
    "The owner gets an approval card; after a yes the bridge waits until no run is active, "
    "runs the bridge's tests and lint, builds the app update, restarts itself and tells the "
    "owner the outcome. Here you can run the bridge's tests with "
    "bridge/.venv/bin/python -m pytest -q -p no:cacheprovider; nothing can be installed, "
    "and the app is built only by the bridge."
)

REASONS = {
    "wait": "runs did not end within 12 hours",
    "tests": "the bridge's tests failed",
    "lint": "the lint check failed",
    "build": "the app build failed",
    "register": "the app update could not be registered",
    "restart": "the bridge could not restart itself",
    "error": "an unexpected error",
}


class StepFailed(Exception):
    def __init__(self, step: str, reason: str | None = None) -> None:
        super().__init__(step)
        self.step = step
        self.reason = reason or REASONS.get(step, REASONS["error"])


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


# ---- state ------------------------------------------------------------------------------


def state_path() -> Path:
    return config_dir() / STATE_FILE


def read_state() -> dict:
    return store.read_json(state_path(), {})


def _save(**changes: object) -> dict:
    data = read_state() | changes | {"updated_at": _now()}
    store.write_json(state_path(), data)
    return data


def in_progress() -> bool:
    return read_state().get("phase") in IN_PROGRESS


def public_state() -> dict:
    """``GET /app/update``: what the app shows. Commits and fixed texts only, no path."""
    data = read_state()
    app = data.get("app") if isinstance(data.get("app"), dict) else None
    return {
        "state": data.get("phase") or "none",
        "step": data.get("step"),
        "reason": data.get("reason"),
        "commit": data.get("commit"),
        "running_commit": program.running_commit(),
        "app_version": (app or {}).get("version_name") if (app or {}).get("registered") else None,
        "restart_needed": data.get("restart_needed") is True,
        "updated_at": data.get("updated_at"),
    }


def _record(action: str, data: dict, **extra: object) -> None:
    """The commit and the session only: never a prompt, never output of a step."""
    audit.record(
        action,
        {"session": data.get("session_id"), "commit": data.get("commit"), **extra},
    )


def _notify(title: str, message: str) -> None:
    notifications.send(title, message, event="notice")


# ---- the request, as a card ----------------------------------------------------------------


def _refused(code: str, message: str) -> dict:
    return {"kind": KIND, "state": "refused", "error": message, "error_code": code}


def from_answer(
    settings: Settings, answer: str, session_id: str, cwd: Path
) -> tuple[str, list[dict]]:
    """Take the request line out of a fresh answer of a capable run; at most one card."""
    if not MARKER.search(answer):
        return answer, []
    text = MARKER.sub("", answer).strip("\n")
    folder = program.program_dir(settings.allowed_roots)
    if folder is None or cwd.resolve() != folder:
        return text, [
            _refused(
                "not_program_folder",
                "an update is applied only from a chat in the LifeOS program folder",
            )
        ]
    if in_progress():
        return text, [_refused("update_in_progress", "an update is being applied already")]
    commit = program.head_commit(folder)
    if commit is None:
        return text, [_refused("no_commit", "the program folder's commit could not be read")]
    with approvals.LOCK:
        # One request waits at a time: a newer one replaces it.
        for earlier in list(approvals.load().values()):
            if earlier.get("kind") == KIND:
                with contextlib.suppress(ClaudeError):
                    approvals.take(str(earlier.get("session_id")), str(earlier.get("id")))
                with contextlib.suppress(audit.AuditUnavailable):
                    _record("update_superseded", earlier)
        entry = approvals.add(
            KIND, session_id, str(folder),
            {"state": "ready", "commit": commit, "running_commit": program.running_commit() or ""},
        )  # fmt: skip
        try:
            _record("update_requested", entry)
        except audit.AuditUnavailable:
            approvals.take(session_id, entry["id"])
            return text, [_refused("audit_unavailable", "the audit log could not be written")]
    return text, [approvals.public(entry)]


def answer(entry: dict, approve: bool) -> dict:
    """The owner's answer (``approvals.settle``). A yes starts nothing here: the caller
    starts the :class:`Applier` when the answer says ``"approved": true``."""
    if not approve:
        with contextlib.suppress(audit.AuditUnavailable):
            _record("update_refused", entry, why="owner")
        return {"approved": False}
    if in_progress():
        return {"approved": False, "reason": "update_in_progress"}
    commit = program.head_commit(Path(str(entry.get("folder"))))
    if commit != entry.get("commit"):
        # Another commit since the card: that one was never shown. The run asks again.
        with contextlib.suppress(audit.AuditUnavailable):
            _record("update_refused", entry, why="changed", now=commit)
        return {"approved": False, "reason": "changed"}
    try:
        _record("update_approved", entry)  # before anything starts
    except audit.AuditUnavailable as exc:
        raise ClaudeError(
            "audit_unavailable", 503, "the audit log could not be written; nothing was started"
        ) from exc
    store.write_json(
        state_path(),
        {
            "phase": WAITING,
            "commit": commit,
            "session_id": entry.get("session_id"),
            "folder": entry.get("folder"),
            "approved_at": _now(),
            "updated_at": _now(),
        },
    )
    return {"approved": True, "state": WAITING, "commit": commit}


def expire(entry: dict) -> None:
    with contextlib.suppress(audit.AuditUnavailable):
        _record("update_expired", entry)


# ---- the steps ---------------------------------------------------------------------------


def _log(step: str, text: str) -> None:
    """The end of a step's output, for the owner at the computer (private, mode 600)."""
    path = config_dir() / LOG_FILE
    with contextlib.suppress(OSError):
        descriptor = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(descriptor, "a", encoding="utf-8") as handle:
            handle.write(f"\n==== {_now()} {step}\n{text[-LOG_KEEP:]}\n")


def own_unit(cgroup: Path = Path("/proc/self/cgroup")) -> str | None:
    """The systemd unit this process runs in, when it is a service."""
    try:
        lines = cgroup.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    for line in lines:
        last = line.rpartition("/")[2]
        if last.endswith(".service"):
            return last
    return None


def _settings(environ: dict[str, str]) -> dict[str, str]:
    """``LIFEOS_*`` values of ``~/.config/lifeos/env``; real variables win, as everywhere."""
    values = read_env_file(config_dir() / ENV_FILE_NAME)
    values.update({k: v for k, v in environ.items() if k.startswith("LIFEOS_")})
    return values


def service_name(environ: dict[str, str] | None = None) -> str:
    try:
        values = _settings(dict(os.environ if environ is None else environ))
    except ConfigError:
        values = {}
    return values.get(SERVICE_VAR, "").strip() or DEFAULT_SERVICE


def build_environment(environ: dict[str, str] | None = None) -> dict[str, str]:
    """The bridge's environment plus the build settings of ``~/.config/lifeos/env``."""
    environ = dict(os.environ if environ is None else environ)
    values = _settings(environ)
    for name, target in BUILD_VARS.items():
        if values.get(name):
            environ[target] = values[name]
    return environ


class Steps:
    """The real steps, each blocking (run in a thread). Tests never run them (conftest)."""

    def _run(self, step: str, command: list[str], cwd: Path, env: dict[str, str]) -> None:
        try:
            done = subprocess.run(  # noqa: S603
                command, cwd=cwd, env=env, capture_output=True, text=True,
                timeout=STEP_TIMEOUT, check=False, stdin=subprocess.DEVNULL,
                # The service runs with umask 077; the tests expect a terminal's 022
                # (some check the modes of files they create in their own scratch folders).
                umask=0o022,
            )  # fmt: skip
        except (OSError, subprocess.SubprocessError) as exc:
            _log(step, repr(exc))
            raise StepFailed(step) from exc
        _log(step, (done.stdout or "") + (done.stderr or ""))
        if done.returncode != 0:
            raise StepFailed(step)

    @staticmethod
    def _plain_env() -> dict[str, str]:
        keep = ("PATH", "HOME", "LANG", "LC_ALL", "USER", "TMPDIR")
        return {name: os.environ[name] for name in keep if name in os.environ}

    @staticmethod
    def _python(folder: Path) -> str:
        own = folder / "bridge" / ".venv" / "bin" / "python"
        return str(own) if own.exists() else sys.executable

    def check(self, folder: Path, commit: str) -> None:
        if program.head_commit(folder) != commit:
            raise StepFailed("check", "the program folder is at another commit than approved")
        env = self._plain_env() | {"GIT_OPTIONAL_LOCKS": "0"}
        try:
            done = subprocess.run(  # noqa: S603
                ["git", "-C", str(folder), "status", "--porcelain", "--untracked-files=no"],  # noqa: S607
                capture_output=True, text=True, timeout=60, check=False, env=env,
                stdin=subprocess.DEVNULL,
            )  # fmt: skip
        except (OSError, subprocess.SubprocessError) as exc:
            raise StepFailed("check", "git could not be run in the program folder") from exc
        if done.returncode != 0:
            raise StepFailed("check", "git could not read the program folder")
        if done.stdout.strip():
            raise StepFailed("check", "the program folder has uncommitted changes")

    def tests(self, folder: Path) -> None:
        command = [self._python(folder), "-m", "pytest", "-q", "-p", "no:cacheprovider"]
        self._run("tests", command, folder / "bridge", self._plain_env())

    def lint(self, folder: Path) -> None:
        python = self._python(folder)
        self._run("lint", [python, "-m", "ruff", "check", "."], folder / "bridge",
                  self._plain_env())  # fmt: skip
        self._run("lint", [python, "-m", "ruff", "format", "--check", "."], folder / "bridge",
                  self._plain_env())  # fmt: skip

    def build(self) -> dict:
        try:
            release.build(env=build_environment(), args=("--no-daemon",))
            apk, code, name = release.from_build_output(release.build_output())
        except (release.ReleaseError, ConfigError, OSError, subprocess.SubprocessError) as exc:
            _log("build", str(exc))
            raise StepFailed("build") from exc
        current = release.current() or {}
        if code <= int(current.get("version_code") or 0):
            # Nothing newer to offer (no commit since the registered release).
            return {"registered": False, "version_code": code, "version_name": name}
        try:
            registered = release.register(apk, code, name)
        except (release.ReleaseError, OSError) as exc:
            _log("register", str(exc))
            raise StepFailed("register") from exc
        return {"registered": True, "version_code": code, "version_name": name,
                "phones_reached": registered.get("phones_reached")}  # fmt: skip

    def restart(self) -> str:
        """``"restarting"`` once the service manager has the job; ``"manual"`` when this
        process is not the bridge's user service (then nothing is restarted)."""
        wanted = service_name()
        if own_unit() != wanted:
            return "manual"
        try:
            done = subprocess.run(  # noqa: S603
                ["systemctl", "--user", "--no-block", "restart", wanted],  # noqa: S607
                capture_output=True, text=True, timeout=30, check=False,
                stdin=subprocess.DEVNULL,
            )  # fmt: skip
        except (OSError, subprocess.SubprocessError) as exc:
            raise StepFailed("restart") from exc
        if done.returncode != 0:
            _log("restart", done.stderr or "")
            raise StepFailed("restart")
        return "restarting"


# ---- the flow ----------------------------------------------------------------------------


class Applier:
    """Applies the approved update in the background; at most one at a time.

    ``blockers`` names what is active now (runs, queues, jobs, requests); an empty
    list means nothing runs. ``holding`` is true from the moment nothing runs until
    the restart (or a failure): the bridge then starts no run and no job.
    """

    def __init__(
        self,
        settings: Settings,
        blockers: Callable[[], list[str]],
        steps: Steps | None = None,
        *,
        poll: float = POLL_SECONDS,
        wait_limit: float = WAIT_LIMIT,
        restart_grace: float = RESTART_GRACE,
    ) -> None:
        self.settings = settings
        self.blockers = blockers
        self.steps = steps if steps is not None else Steps()
        self.poll = poll
        self.wait_limit = wait_limit
        self.restart_grace = restart_grace
        self.holding = False
        self.task: asyncio.Task | None = None

    def start(self) -> None:
        if self.task is None or self.task.done():
            self.task = asyncio.create_task(self.run())

    async def _wait_idle(self) -> None:
        started = time.monotonic()
        while self.blockers():
            if time.monotonic() - started > self.wait_limit:
                raise StepFailed("wait")
            await asyncio.sleep(self.poll)

    async def run(self) -> None:
        state = read_state()
        if state.get("phase") != WAITING or not state.get("commit") or not state.get("folder"):
            return
        commit, folder = str(state["commit"]), Path(str(state["folder"]))
        try:
            await self._wait_idle()
            # No await between the last check and the hold: no run can begin in between.
            self.holding = True
            _save(phase=CHECKING, step="check")
            await asyncio.to_thread(self.steps.check, folder, commit)
            _save(phase=TESTING, step="tests")
            await asyncio.to_thread(self.steps.tests, folder)
            _save(step="lint")
            await asyncio.to_thread(self.steps.lint, folder)
            _save(phase=BUILDING, step="build")
            app = await asyncio.to_thread(self.steps.build)
            _save(app=app, step="check")
            await asyncio.to_thread(self.steps.check, folder, commit)
            # A request that began a run before the hold (a coach refresh, a context map)
            # ends first: never a restart while a run is active.
            await self._wait_idle()
            data = _save(phase=RESTARTING, step="restart", pid=os.getpid())
            with contextlib.suppress(audit.AuditUnavailable):
                _record("update_restarting", data)
            how = await asyncio.to_thread(self.steps.restart)
            if how == "manual":
                self._finish_manual()
                return
            # The service manager stops this process now; the next start reports it.
            await asyncio.sleep(self.restart_grace)
            raise StepFailed("restart")
        except StepFailed as failure:
            self._fail(failure.step, failure.reason)
        except asyncio.CancelledError:
            # The bridge is stopping: the state stays as it is; the next start says
            # "interrupted" (or "applied", when this was the restart).
            raise
        except Exception:
            self._fail(str(read_state().get("step") or "error"), REASONS["error"])

    def _fail(self, step: str, reason: str) -> None:
        self.holding = False
        data = _save(phase=FAILED, step=step, reason=reason)
        with contextlib.suppress(audit.AuditUnavailable):
            _record("update_failed", data, step=step, reason=reason)
        app = data.get("app") if isinstance(data.get("app"), dict) else {}
        if app.get("registered"):
            message = (
                f"The app update was registered, but the bridge was not restarted: the step "
                f'"{step}" failed ({reason}). The bridge keeps running its earlier version.'
            )
        else:
            message = (
                f'The step "{step}" failed ({reason}). Nothing was applied; the bridge keeps '
                "running its earlier version."
            )
        _notify("LifeOS: update not applied", message)

    def _finish_manual(self) -> None:
        self.holding = False
        data = _save(phase=APPLIED, step=None, restart_needed=True)
        with contextlib.suppress(audit.AuditUnavailable):
            _record("update_applied", data, restart="manual")
        _notify(
            "LifeOS: update ready",
            "Tests and the app build passed. The bridge does not run as its service, so it "
            "was not restarted: restart it to run the new version.",
        )


def after_start() -> None:
    """At bridge start: finish the story of an update that was under way."""
    data = read_state()
    phase = data.get("phase")
    if phase == RESTARTING and data.get("pid") != os.getpid():
        data = _save(phase=APPLIED, step=None, applied_at=_now())
        with contextlib.suppress(audit.AuditUnavailable):
            _record("update_applied", data, restart="service")
        app = data.get("app") if isinstance(data.get("app"), dict) else {}
        message = "The tested update runs now."
        if app.get("registered"):
            message += " An app update is ready to install."
        _notify("LifeOS: update applied", message)
    elif phase in IN_PROGRESS and phase != RESTARTING:
        data = _save(phase=INTERRUPTED, step=data.get("step") or phase)
        with contextlib.suppress(audit.AuditUnavailable):
            _record("update_interrupted", data, step=data.get("step"))
        _notify(
            "LifeOS: update interrupted",
            "The bridge stopped while an update was being applied; the update did not restart "
            "it. Ask for the update again in the chat.",
        )


def strip(text: str) -> str:
    """A stored answer without its request line (history: never a card by itself)."""
    return MARKER.sub("", text).strip("\n") if "[[apply-update" in text else text
