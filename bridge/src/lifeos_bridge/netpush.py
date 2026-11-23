"""An approved ``git push`` with network access, for exactly that one command (#178).

Claude Code's sandbox gives the commands of a capable run no network, also
after the owner approved one of them, and it offers no way to open the
network for one command only that keeps the rest of the sandbox
(docs/permission-policy.md, section 9.12). So the bridge itself runs an
approved push, in a confinement of its own, and hands the result back to
the run. Nothing else of the run ever gets network.

The run's repository is untrusted: the run could have written its hooks
and its configuration (``core.sshCommand``, ``core.hooksPath``, credential
helpers, ``url.*.insteadOf``, aliases, includes, ...). Git therefore never
runs with network or credentials inside it:

1. **Before the card** (``prepare``): in a confinement without network that
   sees only the repository's ``.git`` folder, read-only, git reads the
   branch, the destination address and the commit, and writes a bundle of
   that branch. Its results are data and are checked here: an ``https``
   address without user information, to a host name; the commit is shown.
2. **After the owner's yes** (``run``): a fresh repository that the bridge
   creates and configures (no hooks, fixed settings, no system or global
   configuration) takes the commit from the bundle, checks it is the one on
   the card, and pushes exactly it to exactly the address on the card, in a
   confinement with network that sees nothing but that fresh repository.
   The credentials, if the owner's own git configuration has any for that
   host, are fetched by the bridge for exactly that address and given to
   that one process.

Only a plain ``git push [remote] [branch | src:dst]`` with a few harmless
options qualifies; anything else keeps today's behaviour (approved, run in
the sandbox, no network).
"""

from __future__ import annotations

import ipaddress
import os
import re
import secrets
import shutil
import socket
import subprocess
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from .config import config_dir

# Address schemes a push may use. Tests add "file" for a bare repository on disk.
SCHEMES: tuple[str, ...] = ("https",)
WORK_DIR_NAME = "push-work"  # in the private settings folder: no run can reach it
PUSH_REF = "refs/heads/lifeos-push"
OUTPUT_LIMIT = 4000
STEP_TIMEOUT = 60
PUSH_TIMEOUT = 180
BWRAP_PATH = "/usr/bin:/bin"

# Set only by the guardrail check's own process: the feature is off while the check runs
# (the marker is removed first), and the check must still exercise it.
_forced = False


def force_for_check() -> None:
    global _forced
    _forced = True


def available(runner: str = "sdk") -> bool:
    """On only on the SDK runner, after a check whose probes covered it, with bubblewrap."""
    from . import capable  # local: capable imports a lot

    if runner != "sdk" or bwrap() is None:
        return False
    return _forced or capable.network_push_probed(runner)


def bwrap() -> str | None:
    return shutil.which("bwrap", path=BWRAP_PATH)


# -- the command ------------------------------------------------------------------------
SAFE_TEXT = re.compile(r"[A-Za-z0-9 ._/:@+=,-]+")
FLAGS = {
    "-f": "force",
    "--force": "force",
    "-q": "quiet",
    "--quiet": "quiet",
    "-v": "verbose",
    "--verbose": "verbose",
    "--no-verify": "",  # hooks never run anyway
}
REMOTE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
BRANCH = re.compile(r"(?!-)(?!.*\.\.)(?!.*//)(?!.*/\.)(?!.*\.lock$)[A-Za-z0-9._/-]{1,200}(?<![./])")


@dataclass(frozen=True)
class Request:
    """A ``git push`` command in the one form that may get network."""

    remote: str | None  # a remote's name or an https address; None: the branch's remote
    source: str | None  # local branch; None: the current branch
    target: str | None  # remote branch; None: the same name
    force: bool = False
    quiet: bool = False
    verbose: bool = False


def _branch(name: str) -> str | None:
    name = name.removeprefix("refs/heads/")
    return name if BRANCH.fullmatch(name) and not name.startswith("refs/") else None


def parse(command: str) -> Request | None:
    """The request, when ``command`` is exactly one plain ``git push``; else ``None``.

    No shell syntax at all (no quoting, variables, redirection, chaining), no
    git options before ``push`` (``-c``, ``-C``, ...), no environment prefix,
    and only the options in ``FLAGS``.
    """
    text = command.strip()
    if not SAFE_TEXT.fullmatch(text):
        return None
    words = text.split()
    if words[:2] != ["git", "push"]:
        return None
    flags: dict[str, bool] = {}
    rest = []
    for word in words[2:]:
        if word.startswith("-"):
            if word not in FLAGS:
                return None
            if FLAGS[word]:
                flags[FLAGS[word]] = True
        else:
            rest.append(word)
    if len(rest) > 2:
        return None
    remote = source = target = None
    if rest:
        remote = rest[0]
        if not (REMOTE_NAME.fullmatch(remote) or remote.startswith("https://")):
            return None
    if len(rest) == 2:
        spec = rest[1]
        if spec.startswith("+"):
            flags["force"], spec = True, spec[1:]
        left, colon, right = spec.partition(":")
        source = None if left == "HEAD" else _branch(left)
        if (left != "HEAD" and source is None) or (colon and _branch(right) is None):
            return None
        target = _branch(right) if colon else None  # None: the source's own name
    return Request(remote, source, target, **flags)


# -- the confinement --------------------------------------------------------------------
def _environment(extra: dict[str, str] | None = None) -> dict[str, str]:
    """Inside the confinement: no setting of the owner's or the run's reaches git."""
    return {
        "PATH": BWRAP_PATH,
        "HOME": "/tmp",  # noqa: S108 - the confinement's own, empty /tmp
        "LANG": "C.UTF-8",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_ATTR_NOSYSTEM": "1",
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_ASKPASS": "/bin/false",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_OPTIONAL_LOCKS": "0",
        **(extra or {}),
    }


def confined(
    argv: list[str],
    *,
    read: tuple[Path, ...] = (),
    write: tuple[Path, ...] = (),
    network: bool = False,
    env: dict[str, str] | None = None,
    timeout: float = STEP_TIMEOUT,
) -> subprocess.CompletedProcess:
    """Run ``argv`` in a bubblewrap confinement that sees only the system and these paths.

    The home folder, the private settings folder, ``/run`` (session bus, agent
    sockets) and every other folder are absent; ``/tmp`` is empty. Without
    ``network`` there is no network namespace connection at all.
    """
    tool = bwrap()
    if tool is None:
        raise OSError("bubblewrap is not installed")
    command = [
        tool, "--unshare-all", *(["--share-net"] if network else []),
        "--die-with-parent", "--new-session", "--clearenv", "--cap-drop", "ALL",
        "--ro-bind", "/usr", "/usr", "--ro-bind", "/etc", "/etc",
        "--symlink", "usr/bin", "/bin", "--symlink", "usr/sbin", "/sbin",
        "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
        "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",  # noqa: S108 - a fresh one
    ]  # fmt: skip
    if network:  # name resolution through the system resolver
        command += ["--ro-bind-try", "/run/systemd/resolve", "/run/systemd/resolve"]
    for path in read:
        command += ["--ro-bind", str(path), str(path)]
    for path in write:
        command += ["--bind", str(path), str(path)]
    for key, value in _environment(env).items():
        command += ["--setenv", key, value]
    command += ["--chdir", "/tmp", "--", *argv]  # noqa: S108 - inside the confinement
    return subprocess.run(  # noqa: S603
        command,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        errors="replace",
        timeout=timeout,
        check=False,
    )


def _git(git_dir: Path, *args: str, **options) -> subprocess.CompletedProcess:
    return confined(["git", f"--git-dir={git_dir}", *args], **options)


# -- before the card ----------------------------------------------------------------------
@dataclass(frozen=True)
class Plan:
    """What the card shows and the push does: exactly this commit to exactly this address."""

    url: str
    host: str
    source: str  # local branch
    target: str  # remote branch
    commit: str
    force: bool
    quiet: bool
    verbose: bool
    work: Path  # the bridge's own folder for this push (bundle, then the fresh repository)

    @property
    def bundle(self) -> Path:
        return self.work / "read" / "push.bundle"

    def card(self) -> dict:
        return {
            "host": self.host,
            "url": self.url,
            "branch": self.target,
            "commit": self.commit[:12],
            "force": self.force,
        }

    def record(self) -> dict:
        """For the audit log: where it goes and what, never credentials."""
        return {
            "host": self.host,
            "branch": self.target,
            "commit": self.commit,
            "force": self.force,
        }


class Refused(Exception):
    """This push cannot get network; the reason is for the run."""


def check_url(url: str) -> str:
    """The host of an address a push may go to; raises ``Refused`` otherwise."""
    if not SAFE_TEXT.fullmatch(url) or " " in url:
        raise Refused("the address contains characters that are not allowed")
    parts = urlsplit(url)
    if parts.scheme not in SCHEMES:
        raise Refused("only https addresses can be pushed to from the phone")
    if parts.scheme == "file":  # tests only (SCHEMES): a bare repository on disk
        return "local"
    if parts.username or parts.password or "@" in parts.netloc:
        raise Refused("the address carries user information")
    if parts.query or parts.fragment:
        raise Refused("the address has a query or fragment")
    host = (parts.hostname or "").lower()
    try:
        ipaddress.ip_address(host.strip("[]"))
        raise Refused("the address names an IP address, not a host name")
    except ValueError:
        pass
    labels = host.split(".")
    if len(labels) < 2 or labels[-1] in ("localhost", "local", "internal", "lan", "home"):
        raise Refused("the address does not name a public host")
    if not all(re.fullmatch(r"[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?", label) for label in labels):
        raise Refused("the host name is not valid")
    return host


def _first_line(done: subprocess.CompletedProcess) -> str:
    return (done.stdout or "").strip().splitlines()[0] if (done.stdout or "").strip() else ""


def _repository(folder: Path, memory: Path) -> Path:
    git_dir = folder / ".git"
    if folder == memory or folder.is_relative_to(memory):
        raise Refused("the memory folder is never pushed")
    if git_dir.is_symlink() or not git_dir.is_dir():
        raise Refused("the run folder is not the root of a git repository")
    if (git_dir / "commondir").exists():
        raise Refused("the repository is a linked worktree")
    # Claude Code's sandbox leaves an empty alternates file behind (seen with 2.1.330); one
    # with content would point git at objects elsewhere. (The confinement would not show
    # them anyway.)
    alternates = git_dir / "objects" / "info" / "alternates"
    if alternates.is_symlink() or (alternates.exists() and alternates.stat().st_size > 0):
        raise Refused("the repository borrows objects from elsewhere")
    return git_dir


def prepare(command: str, folder: Path, memory: Path) -> Plan:
    """Read and check what the push would do, before the owner is asked.

    Raises ``Refused`` when the command or the repository does not qualify;
    the caller then keeps today's behaviour (no network for it).
    """
    request = parse(command)
    if request is None:
        raise Refused("only a plain git push of one branch can get network")
    folder, memory = folder.resolve(), memory.resolve()
    git_dir = _repository(folder, memory)
    look = {"read": (git_dir,)}

    source = request.source
    if source is None:
        head = _git(git_dir, "symbolic-ref", "-q", "HEAD", **look)
        source = _branch(_first_line(head)) if head.returncode == 0 else None
        if source is None:
            raise Refused("no current branch")
    remote = request.remote
    if remote is None:
        found = _git(git_dir, "config", "--no-includes", "--get", f"branch.{source}.remote", **look)
        remote = _first_line(found) or "origin"
        if not REMOTE_NAME.fullmatch(remote):
            raise Refused("the branch's remote is not a plain name")
    if remote.startswith(tuple(f"{scheme}://" for scheme in SCHEMES)):
        url = remote
    else:
        url = ""
        for key in ("pushurl", "url"):
            found = _git(
                git_dir, "config", "--no-includes", "--get-all", f"remote.{remote}.{key}", **look
            )
            values = (found.stdout or "").split()
            if len(values) > 1:
                raise Refused(f"the remote has more than one {key}")
            if values:
                url = values[0]
                break
        if not url:
            raise Refused("the remote has no address")
    host = check_url(url)
    found = _git(git_dir, "rev-parse", "--verify", "-q", f"refs/heads/{source}^{{commit}}", **look)
    commit = _first_line(found)
    if found.returncode != 0 or not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", commit):
        raise Refused("the branch has no commit")

    base = config_dir() / WORK_DIR_NAME
    base.mkdir(mode=0o700, parents=True, exist_ok=True)
    work = base / secrets.token_hex(8)
    (work / "read").mkdir(mode=0o700, parents=True)
    plan = Plan(
        url=url, host=host, source=source, target=request.target or source, commit=commit,
        force=request.force, quiet=request.quiet, verbose=request.verbose, work=work,
    )  # fmt: skip
    made = _git(
        git_dir, "bundle", "create", "-q", str(plan.bundle), f"refs/heads/{source}",
        read=(git_dir,), write=(work / "read",),
    )  # fmt: skip
    if made.returncode != 0 or not plan.bundle.is_file():
        discard(plan)
        raise Refused("the branch could not be read")
    return plan


def discard(plan: Plan | None) -> None:
    if plan is not None:
        shutil.rmtree(plan.work, ignore_errors=True)


# -- after the yes ------------------------------------------------------------------------
REPO_CONFIG = """[core]
\trepositoryformatversion = 0
\tbare = true
\thooksPath = /nonexistent-lifeos-hooks
\tfsmonitor = false
[http]
\tsslVerify = true
[gc]
\tauto = 0
"""
HELPER = """#!/bin/sh
[ "$1" = get ] || exit 0
printf 'username=%s\\npassword=%s\\n' "$LIFEOS_PUSH_USERNAME" "$LIFEOS_PUSH_PASSWORD"
"""


def credentials(url: str) -> tuple[str, str] | None:
    """The owner's own git credentials for exactly this address, if git has any.

    Runs ``git credential fill`` with the owner's global and system configuration
    only (outside every repository, so nothing the run wrote takes part), never
    prompting. ``None`` when there are none.
    """
    parts = urlsplit(url)
    if parts.scheme != "https":
        return None
    work = config_dir() / WORK_DIR_NAME
    work.mkdir(mode=0o700, parents=True, exist_ok=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    env |= {
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_ASKPASS": "/bin/false",
        "GIT_CEILING_DIRECTORIES": str(work.parent),
    }
    env.pop("SSH_ASKPASS", None)
    query = f"protocol=https\nhost={parts.netloc}\npath={parts.path.lstrip('/')}\n\n"
    try:
        done = subprocess.run(
            ["git", "credential", "fill"],  # noqa: S607
            input=query, cwd=work, env=env, capture_output=True, text=True,
            timeout=STEP_TIMEOUT, check=False,
        )  # fmt: skip
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    found = dict(line.split("=", 1) for line in done.stdout.splitlines() if "=" in line)
    if found.get("host", parts.netloc) != parts.netloc or not found.get("password"):
        return None
    return found.get("username", ""), found["password"]


@dataclass(frozen=True)
class Outcome:
    status: int
    output: str


def run(plan: Plan, login: tuple[str, str] | None = None) -> Outcome:
    """Push exactly ``plan.commit`` to exactly ``plan.url`` from a fresh repository."""
    fresh = plan.work / "push"
    fresh.mkdir(mode=0o700)
    repo = fresh / "repo.git"
    made = confined(["git", "init", "-q", "--bare", "--template=", str(repo)], write=(fresh,))
    if made.returncode != 0:
        return Outcome(made.returncode, "the push repository could not be created")
    (repo / "config").write_text(REPO_CONFIG, encoding="utf-8")
    fetched = _git(
        repo, "fetch", "-q", "--no-tags", "--no-write-fetch-head", str(plan.bundle),
        f"refs/heads/{plan.source}:{PUSH_REF}",
        read=(plan.bundle,), write=(fresh,),
    )  # fmt: skip
    got = _git(repo, "rev-parse", "--verify", "-q", PUSH_REF, write=(fresh,))
    if fetched.returncode != 0 or _first_line(got) != plan.commit:
        return Outcome(1, "the commit on the card could not be prepared; nothing was pushed")
    (repo / "config").write_text(REPO_CONFIG, encoding="utf-8")  # as written, whatever ran
    env = {"GIT_ALLOW_PROTOCOL": ":".join(SCHEMES)}
    read: tuple[Path, ...] = ()
    write: tuple[Path, ...] = (fresh,)
    if login is not None:
        helper = fresh / "credential-helper"
        helper.write_text(HELPER, encoding="utf-8")
        helper.chmod(0o500)
        env |= {"LIFEOS_PUSH_USERNAME": login[0], "LIFEOS_PUSH_PASSWORD": login[1]}
        read = (helper,)
    parts = urlsplit(plan.url)
    if parts.scheme == "file":  # tests only
        write = (*write, Path(parts.path))
    options = [
        *(["-c", f"credential.helper={fresh / 'credential-helper'}"] if login else []),
        "push",
        *(["--force"] if plan.force else []),
        *(["--quiet"] if plan.quiet else []),
        *(["--verbose"] if plan.verbose else []),
        plan.url,
        f"{PUSH_REF}:refs/heads/{plan.target}",
    ]
    try:
        done = _git(repo, *options, read=read, write=write, network=True, env=env,
                    timeout=PUSH_TIMEOUT)  # fmt: skip
    except subprocess.TimeoutExpired:
        return Outcome(124, "the push took too long and was stopped")
    output = ((done.stdout or "") + (done.stderr or "")).strip()
    if login is not None and login[1]:
        output = output.replace(login[1], "[hidden]")
    return Outcome(done.returncode, output[-OUTPUT_LIMIT:])


def note(plan: Plan, outcome: Outcome) -> str:
    """What the run is told: the bridge ran the push; never run it again."""
    what = (
        f"commit {plan.commit[:12]} of the local branch {plan.source} to {plan.url} "
        f"as the branch {plan.target}" + (" (forced)" if plan.force else "")
    )
    state = "It succeeded" if outcome.status == 0 else f"It failed (exit status {outcome.status})"
    return (
        "The owner approved this push. The bridge ran it itself, outside your sandbox and "
        f"with network access for this one push only: {what}. {state}. Do not run the "
        "command again; tell the owner the result. Your repository's remote-tracking branch "
        "was not updated.\nOutput:\n" + (outcome.output or "(none)")
    )


def public_host(host: str) -> bool:
    """Every address the host has right now is a public one (checked just before the push)."""
    if host == "local":
        return True
    try:
        found = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError:
        return False
    addresses = {info[4][0] for info in found}
    return bool(addresses) and all(ipaddress.ip_address(a).is_global for a in addresses)
