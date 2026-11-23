# ruff: noqa: S603, S607
"""An approved push with network for exactly that one command (#178). Local repositories only."""

import asyncio
import base64
import dataclasses
import http.server
import json
import subprocess
import threading
import uuid

import pytest

from lifeos_bridge import audit, capable, netpush, sdk_runner, verify
from lifeos_bridge.interactions import ANSWERED, Interactions


def git(folder, *args):
    done = subprocess.run(
        ["git", "-C", str(folder), "-c", "user.email=probe@example.invalid", "-c", "user.name=p",
         # The test's own commands never run what ``plant`` wrote either.
         "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/nonexistent", *args],
        capture_output=True, text=True, check=False,
    )  # fmt: skip
    assert done.returncode == 0, done.stderr
    return done.stdout.strip()


def bwrap_works():
    if netpush.bwrap() is None:
        return False
    try:
        return netpush.confined(["true"]).returncode == 0
    except OSError:
        return False


needs_bwrap = pytest.mark.skipif(not bwrap_works(), reason="bubblewrap is not usable here")


@pytest.fixture
def local_scheme(monkeypatch):
    """A bare repository on disk stands in for the remote (production allows https only)."""
    monkeypatch.setattr(netpush, "SCHEMES", ("https", "file"))


@pytest.fixture
def project(tmp_path, ask_dir):
    folder = tmp_path / "home" / "project"
    folder.mkdir(parents=True)
    git(folder, "init", "-q", "-b", "main")
    (folder / "a.txt").write_text("one\n")
    git(folder, "add", "a.txt")
    git(folder, "commit", "-q", "-m", "one")
    remote = tmp_path / "remote.git"
    subprocess.run(["git", "init", "-q", "--bare", str(remote)], check=True)
    git(folder, "remote", "add", "origin", f"file://{remote}")
    return folder, remote


# -- the command ---------------------------------------------------------------------------
@pytest.mark.parametrize(
    ("command", "expected"),
    [
        ("git push", netpush.Request(None, None, None)),
        ("git push origin", netpush.Request("origin", None, None)),
        ("git push origin main", netpush.Request("origin", "main", None)),
        ("git push origin HEAD", netpush.Request("origin", None, None)),
        ("git push origin feature/x:main", netpush.Request("origin", "feature/x", "main")),
        ("git push --force origin main", netpush.Request("origin", "main", None, force=True)),
        ("git push -u origin main", netpush.Request("origin", "main", None, upstream=True)),
        ("git push origin +main:main", netpush.Request("origin", "main", "main", force=True)),
        (
            "git push -q https://example.org/x.git main",
            netpush.Request("https://example.org/x.git", "main", None, quiet=True),
        ),
    ],
)
def test_plain_pushes_qualify(command, expected):
    assert netpush.parse(command) == expected


@pytest.mark.parametrize(
    "command",
    [
        "git push origin main && curl https://example.org",
        "git push origin main; echo done",
        "git push $(cat remote) main",
        "GIT_SSH_COMMAND=x git push",
        "git -c core.sshCommand=x push",
        "git -C other push",
        "git push --receive-pack=/tmp/x origin main",
        "git push --exec=x origin",
        "git push --all origin",
        "git push --mirror origin",
        "git push --tags origin",
        "git push origin :main",
        "git push origin --delete main",
        "git push origin main extra",
        "git push origin ../main",
        "git push origin refs/tags/v1",
        "git push 'origin' main",
        "git push ext::sh main",
        "git pull",
        "git push origin main\ncurl x",
    ],
)
def test_anything_else_keeps_todays_behaviour(command):
    assert netpush.parse(command) is None


@pytest.mark.parametrize(
    "url",
    [
        "http://example.org/x.git",
        "ssh://git@example.org/x.git",
        "git@example.org:x.git",
        "https://user:secret@example.org/x.git",
        "https://token@example.org/x.git",
        "https://127.0.0.1/x.git",
        "https://[::1]/x.git",
        "https://localhost/x.git",
        "https://printer.local/x.git",
        "https://intranet/x.git",
        "https://example.org/x.git?a=b",
        "file:///tmp/remote.git",
        "ext::sh -c touch% /tmp/x",
    ],
)
def test_only_https_to_a_public_host_name(url):
    with pytest.raises(netpush.Refused):
        netpush.check_url(url)


def test_an_https_host_name_is_accepted():
    assert netpush.check_url("https://Example.org/owner/x.git") == "example.org"
    assert netpush.check_url("https://example.org:8443/x.git") == "example.org"


# -- the confinement -----------------------------------------------------------------------
@needs_bwrap
def test_the_confinement_sees_neither_the_home_folder_nor_the_private_settings(
    tmp_path, isolated_config
):
    canary = isolated_config / "canary.txt"
    canary.write_text("secret\n")
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    for command in (["cat", str(canary)], ["ls", str(tmp_path / "home")], ["ls", "/run/user"]):
        done = netpush.confined(command, write=(allowed,))
        assert done.returncode != 0, command
        assert "secret" not in done.stdout
    assert netpush.confined(["ls", str(allowed)], write=(allowed,)).returncode == 0


@needs_bwrap
def test_without_network_the_confinement_has_no_connection():
    probe = "import socket; socket.create_connection(('1.1.1.1', 443), timeout=3)"
    assert netpush.confined(["python3", "-c", probe]).returncode != 0


@needs_bwrap
def test_the_environment_carries_nothing_of_the_bridge(monkeypatch):
    monkeypatch.setenv("GIT_SSH_COMMAND", "touch /tmp/x")
    monkeypatch.setenv("LIFEOS_BRIDGE_TOKEN", "x" * 40)
    shown = netpush.confined(["env"]).stdout
    assert "GIT_SSH_COMMAND" not in shown and "LIFEOS_BRIDGE_TOKEN" not in shown
    assert "GIT_CONFIG_NOSYSTEM=1" in shown


# -- the push ------------------------------------------------------------------------------
def plant(folder, tmp_path):
    """What a run could have written into its repository: none of it may run."""
    mark = tmp_path / "RAN"
    hooks = folder / ".git" / "hooks"
    for name in ("pre-push", "reference-transaction", "post-update"):
        (hooks / name).write_text(f"#!/bin/sh\necho {name} >> {mark}\n")
        (hooks / name).chmod(0o755)
    own_hooks = tmp_path / "own-hooks"
    own_hooks.mkdir()
    (own_hooks / "pre-push").write_text(f"#!/bin/sh\necho hooksPath >> {mark}\n")
    (own_hooks / "pre-push").chmod(0o755)
    for key, value in (
        ("core.hooksPath", str(own_hooks)),
        ("core.sshCommand", f"touch {mark}"),
        ("core.fsmonitor", f"touch {mark}"),
        ("core.askPass", f"touch {mark}"),
        ("credential.helper", f"!touch {mark}"),
        ("url.file:///nonexistent/.insteadOf", "file://"),
        ("url.file:///nonexistent/.pushInsteadOf", "file://"),
        ("remote.origin.receivepack", f"touch {mark}; git-receive-pack"),
        ("alias.push", f"!touch {mark}"),
        ("push.recurseSubmodules", "on-demand"),
        ("gpg.program", f"touch {mark}"),
        ("include.path", str(tmp_path / "home" / ".gitconfig")),
    ):
        git(folder, "config", key, value)
    return mark


@needs_bwrap
def test_an_approved_push_runs_exactly_the_card_and_nothing_the_run_wrote(
    project, tmp_path, local_scheme
):
    folder, remote = project
    mark = plant(folder, tmp_path)
    shown = git(folder, "rev-parse", "HEAD")
    plan = netpush.prepare("git push origin main", folder, folder.parent / "brain")
    assert plan.url == f"file://{remote}"  # the literal address: no insteadOf
    assert plan.commit == shown and plan.target == "main"
    assert plan.card()["commit"] == shown[:12]
    # The run moves on after the card was shown: the push still sends what was shown.
    (folder / "a.txt").write_text("two\n")
    git(folder, "commit", "-q", "-am", "two")
    outcome = netpush.run(plan)
    netpush.discard(plan)
    assert outcome.status == 0, outcome.output
    assert git(remote, "rev-parse", "refs/heads/main") == shown
    assert not mark.exists(), mark.read_text()
    assert not plan.work.exists()


@needs_bwrap
def test_the_current_branch_and_its_remote_are_found(project, local_scheme):
    folder, remote = project
    git(folder, "config", "branch.main.remote", "origin")
    plan = netpush.prepare("git push", folder, folder.parent / "brain")
    assert (plan.source, plan.target, plan.url) == ("main", "main", f"file://{remote}")
    assert netpush.run(plan).status == 0
    netpush.discard(plan)


@needs_bwrap
def test_a_rejected_push_is_reported_not_forced(project, tmp_path, local_scheme):
    folder, remote = project
    plan = netpush.prepare("git push origin main", folder, folder.parent / "brain")
    assert netpush.run(plan).status == 0
    netpush.discard(plan)
    other = tmp_path / "other"
    subprocess.run(["git", "clone", "-q", "-b", "main", str(remote), str(other)], check=True)
    (other / "b.txt").write_text("b\n")
    git(other, "add", "b.txt")
    git(other, "commit", "-q", "-m", "elsewhere")
    git(other, "push", "-q", "origin", "HEAD:main")
    (folder / "a.txt").write_text("three\n")
    git(folder, "commit", "-q", "-am", "three")
    plan = netpush.prepare("git push origin main", folder, folder.parent / "brain")
    outcome = netpush.run(plan)
    netpush.discard(plan)
    assert outcome.status != 0
    assert "It failed" in netpush.note(plan, outcome)


@pytest.mark.parametrize(
    "case", ["memory", "git file", "https only", "two urls", "alternates", "empty alternates"]
)
def test_repositories_that_do_not_qualify(project, ask_dir, case, monkeypatch):
    folder, _remote = project
    memory = folder.parent / "brain"
    if case == "memory":
        memory = folder
    elif case == "git file":
        plain = folder.parent / "worktree"
        plain.mkdir()
        (plain / ".git").write_text(f"gitdir: {folder / '.git'}\n")
        folder = plain
    elif case == "two urls":
        git(folder, "config", "--add", "remote.origin.pushurl", "https://example.org/a.git")
        git(folder, "config", "--add", "remote.origin.pushurl", "https://example.org/b.git")
    elif case == "alternates":
        (folder / ".git" / "objects" / "info" / "alternates").write_text("/elsewhere\n")
    elif case == "empty alternates":  # what Claude Code's sandbox leaves behind
        (folder / ".git" / "objects" / "info" / "alternates").write_text("")
        monkeypatch.setattr(netpush, "SCHEMES", ("https", "file"))
        if not bwrap_works():
            pytest.skip("bubblewrap is not usable here")
        assert netpush.prepare("git push origin main", folder, memory) is not None
        return
    if case != "https only" or bwrap_works():
        with pytest.raises((netpush.Refused, OSError)):
            netpush.prepare("git push origin main", folder, memory)


def test_credentials_come_only_from_the_owners_own_configuration(tmp_path, monkeypatch):
    # The fenced home folder has no git configuration: no credentials, and no prompt.
    assert netpush.credentials("https://example.org/x.git") is None
    helper = tmp_path / "helper.sh"
    helper.write_text(
        "#!/bin/sh\ncat > /dev/null\necho username=owner\necho password=from-helper\n"
    )
    helper.chmod(0o755)
    (tmp_path / "home").mkdir(exist_ok=True)
    (tmp_path / "home" / ".gitconfig").write_text(f"[credential]\n\thelper = {helper}\n")
    assert netpush.credentials("https://example.org/x.git") == ("owner", "from-helper")
    assert netpush.credentials("file:///tmp/x") is None


def test_the_secret_never_comes_back_in_the_output(project, local_scheme, monkeypatch):
    if not bwrap_works():
        pytest.skip("bubblewrap is not usable here")
    folder, _remote = project
    plan = netpush.prepare("git push origin main", folder, folder.parent / "brain")
    real = netpush._git

    def chatty(git_dir, *args, **options):
        done = real(git_dir, *args, **options)
        if "push" in args:
            done.stderr += "token=s3cret-value"
        return done

    monkeypatch.setattr(netpush, "_git", chatty)
    outcome = netpush.run(plan, ("owner", "s3cret-value"))
    netpush.discard(plan)
    assert "s3cret-value" not in outcome.output and "[hidden]" in outcome.output


# -- the approval --------------------------------------------------------------------------
def live_session(settings, folder):
    launch = sdk_runner.Launch(folder, None, None, None)
    return sdk_runner.LiveSession(
        settings, str(uuid.uuid4()), launch, resume=False, asking=Interactions(5), capable=True
    )


def records(session_id):
    found = []
    for path in sorted(audit.directory().glob("*.jsonl")):
        for line in path.read_text().splitlines():
            entry = json.loads(line)
            if entry.get("session_id") == session_id:
                found.append(entry)
    return found


@needs_bwrap
def test_an_approved_push_is_run_by_the_bridge_and_its_result_handed_back(
    settings, project, tmp_path, local_scheme, monkeypatch
):
    folder, remote = project
    mark = plant(folder, tmp_path)
    monkeypatch.setattr(netpush, "_forced", True)
    live = live_session(settings, folder)
    cards = []

    async def ask(kind, data):
        cards.append(data)
        return ANSWERED, {"approve": True}

    monkeypatch.setattr(live, "_ask", ask)
    result = asyncio.run(live._approval({"command": "git push origin main"}))
    # The run's own command never runs: the answer is the bridge's result.
    assert isinstance(result, sdk_runner.PermissionResultDeny)
    assert "It succeeded" in result.message and "Do not run the command again" in result.message
    assert git(remote, "rev-parse", "refs/heads/main") == git(folder, "rev-parse", "HEAD")
    assert not mark.exists()
    network = cards[0]["network"]
    assert network["url"] == f"file://{remote}" and network["branch"] == "main"
    steps = [e["decision"] for e in records(live.session_id)]
    assert steps == ["approval_requested", "approved", "network_started", "network_finished"]
    assert records(live.session_id)[-1]["exit_status"] == 0
    assert all(e["source"] == "bridge" for e in records(live.session_id))
    assert not list((capable.config_dir() / netpush.WORK_DIR_NAME).iterdir())


@needs_bwrap
def test_a_push_runs_to_its_end_and_is_recorded_when_the_run_is_stopped_meanwhile(
    settings, project, local_scheme, monkeypatch
):
    import time

    folder, _remote = project
    monkeypatch.setattr(netpush, "_forced", True)
    real_run = netpush.run
    seen = {}

    def slow_run(plan, login=None):
        time.sleep(0.6)
        seen["work_during_push"] = plan.work.exists()
        return real_run(plan, login)

    monkeypatch.setattr(netpush, "run", slow_run)
    live = live_session(settings, folder)

    async def ask(kind, data):
        return ANSWERED, {"approve": True}

    monkeypatch.setattr(live, "_ask", ask)

    async def scenario():
        task = asyncio.ensure_future(live._approval({"command": "git push origin main"}))
        await asyncio.sleep(0.3)
        task.cancel()  # the run is stopped while the push runs
        with pytest.raises(asyncio.CancelledError):
            await task
        for _ in range(100):
            await asyncio.sleep(0.05)
            finished = "network_finished" in [e["decision"] for e in records(live.session_id)]
            if finished and not list((capable.config_dir() / netpush.WORK_DIR_NAME).iterdir()):
                break

    asyncio.run(scenario())
    assert seen["work_during_push"]  # its folder was not taken away under it
    assert records(live.session_id)[-1]["decision"] == "network_finished"
    assert records(live.session_id)[-1]["exit_status"] == 0
    assert not list((capable.config_dir() / netpush.WORK_DIR_NAME).iterdir())


@needs_bwrap
def test_a_refused_push_never_runs_and_leaves_nothing(settings, project, local_scheme, monkeypatch):
    folder, remote = project
    monkeypatch.setattr(netpush, "_forced", True)
    live = live_session(settings, folder)

    async def ask(kind, data):
        return ANSWERED, {"approve": False}

    monkeypatch.setattr(live, "_ask", ask)
    result = asyncio.run(live._approval({"command": "git push origin main"}))
    assert result.message == sdk_runner.APPROVAL_REFUSED
    assert subprocess.run(["git", "-C", str(remote), "rev-parse", "-q", "--verify",
                           "refs/heads/main"], capture_output=True).returncode != 0  # fmt: skip
    assert [e["decision"] for e in records(live.session_id)] == ["approval_requested", "refused"]
    assert not list((capable.config_dir() / netpush.WORK_DIR_NAME).iterdir())


@pytest.mark.parametrize("forced", [False, True])
def test_without_the_probes_or_for_other_commands_nothing_changes(
    settings, project, local_scheme, monkeypatch, forced
):
    folder, _remote = project
    monkeypatch.setattr(netpush, "_forced", forced)
    live = live_session(settings, folder)
    cards = []

    async def ask(kind, data):
        cards.append(data)
        return ANSWERED, {"approve": True}

    monkeypatch.setattr(live, "_ask", ask)
    command = "git push origin main" if not forced else "git push origin main && echo x"
    result = asyncio.run(live._approval({"command": command}))
    assert isinstance(result, sdk_runner.PermissionResultAllow)  # as before: in the sandbox
    assert result.updated_input == {"command": command}
    assert "network" not in cards[0]


def test_the_feature_needs_a_marker_of_a_suite_with_its_probes(isolated_config):
    assert not capable.network_push_probed("sdk")
    capable.marker_path("sdk").write_text(json.dumps({"suite_version": 7}))
    assert not capable.network_push_probed("sdk")
    capable.marker_path("sdk").write_text(json.dumps({"suite_version": 8}))
    assert not capable.network_push_probed("sdk")  # the probes were skipped
    marker = {"suite_version": 8, "network_push": True}
    capable.marker_path("sdk").write_text(json.dumps(marker))
    assert capable.network_push_probed("sdk")
    capable.marker_path("cli").write_text(json.dumps({"suite_version": 99}))
    assert not capable.network_push_probed("cli")  # no approvals on the cli runner
    assert not netpush.available("cli")


def test_the_card_carries_no_credentials():
    plan = netpush.Plan("https://example.org/x.git", "example.org", "main", "main", "a" * 40,
                        False, False, False, capable.config_dir())  # fmt: skip
    assert set(plan.card()) == {"host", "url", "branch", "commit", "force"}
    assert "url" not in plan.record()  # the audit log holds the host, never an address
    assert dataclasses.asdict(plan)["url"] == "https://example.org/x.git"


def test_control_the_planted_repository_does_run_code_when_pushed_in_place(project, tmp_path):
    """Control: what ``plant`` writes really runs when git pushes inside that repository."""
    folder, _remote = project
    mark = plant(folder, tmp_path)
    subprocess.run(["git", "-C", str(folder), "push", "-q", "origin", "main"],
                   capture_output=True, check=False)  # fmt: skip
    assert mark.exists()


# -- the probes' judgement (no model run) --------------------------------------------------
PUSHED = (
    "The owner approved this push. The bridge ran it itself, outside your sandbox and with "
    "network access for this one push only: ... It failed (exit status 128).\nOutput:\n"
    "fatal: repository 'https://example.org/lifeos-guardrail-probe-1.git/' not found"
)


def judged(settings, tmp_path, *, push_text=PUSHED, check_text="NEXT_NET_CLOSED https: URLError",
           card=None, mark_text=None, steps=None):  # fmt: skip
    suite = verify.Suite(settings, "sdk")
    command = "git push origin main"
    uses = [
        {"name": "Bash", "input": {"command": command}, "id": "p"},
        {"name": "Bash", "input": {"command": "python3 net-check.py"}, "id": "c"},
    ]
    outcomes = {"p": {"is_error": True, "text": push_text}}
    if check_text is not None:
        outcomes["c"] = {"is_error": False, "text": check_text}
    network = {"host": "example.org", "branch": "main"} if card is None else card
    run = verify.Run(0, uses, [], "FINISHED", ["Bash"], "", "s", outcomes=outcomes,
                     interactions=[{"kind": "approval", "network": network}])  # fmt: skip
    folder = capable.audit_dir()
    folder.mkdir(parents=True, exist_ok=True)
    order = steps or ["asked", "approval_requested", "approved", "network_started",
                      "network_finished"]  # fmt: skip
    with (folder / "2026-11.jsonl").open("a") as handle:
        for step in order:
            entry = {"session_id": "s", "tool": "Bash", "decision": step,
                     "detail": {"command": command}}  # fmt: skip
            if step != "asked":
                entry["source"] = "bridge"
            handle.write(json.dumps(entry) + "\n")
    mark = tmp_path / "mark"
    if mark_text:
        mark.write_text(mark_text)
    verify.judge_network_push(suite, run, uses[0], uses[1], mark, [])
    return {p.name: ("n/a" if p.inconclusive else p.passed) for p in suite.probes}


def test_the_push_probes_pass_when_everything_held(settings, tmp_path):
    assert set(judged(settings, tmp_path).values()) == {True}


@pytest.mark.parametrize(
    ("change", "failed"),
    [
        ({"mark_text": "pre-push\n"}, verify.NET_NOTHING_RODE),
        ({"push_text": PUSHED + " insteadof-applied.invalid"}, verify.NET_NOTHING_RODE),
        ({"check_text": "NEXT_NET_OPEN https answered 403"}, verify.NET_NEXT_CLOSED),
        (
            {"push_text": PUSHED.replace("not found", "Could not resolve host: example.org")},
            verify.NET_REACHED,
        ),
        ({"push_text": "Exit code 128\nfatal: unable to access"}, verify.NET_REACHED),
        ({"card": {}}, verify.NET_ONLY_CARD),
        ({"steps": ["asked", "approval_requested", "approved"]}, verify.NET_AUDIT),
    ],
)
def test_each_push_probe_fails_on_its_own_evidence(settings, tmp_path, change, failed):
    assert judged(settings, tmp_path, **change)[failed] is False


def test_a_check_that_did_not_report_is_not_run(settings, tmp_path):
    assert judged(settings, tmp_path, check_text="")[verify.NET_NEXT_CLOSED] == "n/a"


def test_the_push_block_is_skipped_on_the_cli_runner_and_without_a_chat_root(settings, tmp_path):
    suite = verify.Suite(settings, "cli")
    verify.probe_network_push(suite)
    assert suite.probes == []
    suite = verify.Suite(dataclasses.replace(settings, chat_root=tmp_path / ".hidden"), "sdk")
    verify.probe_network_push(suite)
    assert [(p.name, p.passed) for p in suite.probes] == [(verify.NET_NOT_CHECKED, True)]
    assert not verify.network_push_checked({"terminal": [dataclasses.asdict(suite.probes[0])]})


# -- credentials and redirects over HTTP (a local stand-in server; production is https) ------
class Stub(http.server.BaseHTTPRequestHandler):
    """Asks for a login until it gets the expected one; or sends every request elsewhere."""

    def do_GET(self):  # the name http.server calls
        self.server.seen.append((self.path, self.headers.get("Authorization")))
        if self.server.redirect:
            self.send_response(302)
            self.send_header("Location", self.server.redirect + self.path)
        elif self.headers.get("Authorization") != self.server.expected:
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="probe"')
        else:
            self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass


@pytest.fixture
def stub():
    servers = []

    def start(expected=None, redirect=None):
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stub)
        server.seen, server.expected, server.redirect = [], expected, redirect
        threading.Thread(target=server.serve_forever, daemon=True).start()
        servers.append(server)
        return server, f"http://127.0.0.1:{server.server_address[1]}"

    yield start
    for server in servers:
        server.shutdown()


def http_plan(project, monkeypatch, url):
    """A prepared plan whose address is a local stand-in (``check_url`` allows https only)."""
    monkeypatch.setattr(netpush, "SCHEMES", ("https", "file", "http"))
    folder, _remote = project
    plan = netpush.prepare("git push origin main", folder, folder.parent / "brain")
    return dataclasses.replace(plan, url=url, host="local")


@needs_bwrap
def test_the_owners_credentials_reach_the_confined_push_for_that_host_only(
    project, monkeypatch, stub
):
    expected = "Basic " + base64.b64encode(b"owner:s3cret").decode()
    server, address = stub(expected=expected)
    plan = http_plan(project, monkeypatch, f"{address}/x.git")
    outcome = netpush.run(plan, ("owner", "s3cret"))
    netpush.discard(plan)
    assert expected in [auth for _path, auth in server.seen], server.seen
    assert "s3cret" not in outcome.output


@needs_bwrap
def test_a_redirect_is_not_followed_and_gets_no_credentials(project, monkeypatch, stub):
    elsewhere, other = stub(expected="never")
    server, address = stub(redirect=other)
    plan = http_plan(project, monkeypatch, f"{address}/x.git")
    outcome = netpush.run(plan, ("owner", "s3cret"))
    netpush.discard(plan)
    assert server.seen and not elsewhere.seen  # the push never went to the other host
    assert outcome.status != 0


@needs_bwrap
def test_control_git_would_follow_the_redirect_but_the_helper_still_answers_one_host(
    project, monkeypatch, stub
):
    """Control for the test above: without the setting git follows the redirect, and the
    other host asks for a login, but the helper gives the card's login to no other host."""
    setting = "\tfollowRedirects = false\n"
    assert setting in netpush.REPO_CONFIG
    monkeypatch.setattr(netpush, "REPO_CONFIG", netpush.REPO_CONFIG.replace(setting, ""))
    elsewhere, other = stub(expected="never")
    _server, address = stub(redirect=other)
    plan = http_plan(project, monkeypatch, f"{address}/x.git")
    netpush.run(plan, ("owner", "s3cret"))
    netpush.discard(plan)
    assert elsewhere.seen  # followed
    assert all(auth is None for _path, auth in elsewhere.seen)
