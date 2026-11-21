"""Skill installs with quarantine and approval (#92). A stand-in source; nothing is downloaded."""

import io
import json
import tarfile
import time
import zipfile

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import approvals, audit, capable, claude, skill_installs, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError

SKILL = b"---\nname: tidy\ndescription: Tidy the notes.\nallowed-tools: Bash\n---\nRun !`ls`\n"


def zipped(files: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in files.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def tarred(files: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, content in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(content)
            archive.addfile(info, io.BytesIO(content))
    return buffer.getvalue()


def records(config):
    folder = config / "audit"
    return [json.loads(line) for f in sorted(folder.glob("*.jsonl")) for line in f.open()]


def propose(folder, url, session_id="s1"):
    text, cards = skill_installs.from_answer(
        f"Here it is.\n[[skill-install: {url}]]\n", session_id, folder
    )
    return text, cards


# ------------------------------------------------------------------- proposals


def test_a_proposal_line_becomes_a_card_and_an_audit_record(ask_dir, isolated_config):
    text, [card] = propose(ask_dir, "https://example.org/tidy/SKILL.md")
    assert text == "Here it is."
    assert card["kind"] == "skill_install" and card["state"] == "downloading"
    assert card["source"] == "https://example.org/tidy/SKILL.md"
    [entry] = records(isolated_config)
    assert entry["tool"] == "skill_install_proposed" and entry["session_id"] == ""
    assert entry["detail"]["session"] == "s1"
    assert approvals.pending("s1")[0]["id"] == card["id"]


@pytest.mark.parametrize("url", ["http://example.org/SKILL.md", "file:///etc/passwd", "ftp://x/y"])
def test_only_https_sources(ask_dir, url):
    _, [card] = propose(ask_dir, url)
    assert card["state"] == "refused" and approvals.pending("s1") == []


def test_no_line_no_change(ask_dir):
    assert skill_installs.from_answer("plain answer", "s1", ask_dir) == ("plain answer", [])


# ------------------------------------------------------------------- quarantine


def ready(ask_dir, url, data):
    _, [card] = propose(ask_dir, url)
    return skill_installs.prepare(card["id"], fetcher=lambda _url: data)


def test_a_single_skill_file_is_quarantined_and_described(ask_dir, isolated_config):
    entry = ready(ask_dir, "https://example.org/tidy/SKILL.md", SKILL)
    assert entry["state"] == "ready" and entry["name"] == "tidy"
    assert entry["target"] == str(ask_dir / ".claude" / "skills" / "tidy")
    assert entry["files"] == [{"path": "SKILL.md", "size": len(SKILL)}]
    assert entry["description"] == "Tidy the notes."
    assert set(entry["warnings"]) == {"shell_lines", "requests_tools"}
    quarantine = isolated_config / "skill-quarantine" / entry["id"]
    assert (quarantine / "SKILL.md").read_bytes() == SKILL
    assert not (ask_dir / ".claude").exists()  # nothing reaches the folder before approval
    public = approvals.pending("s1")[0]
    assert "quarantine" not in public and public["state"] == "ready"


def test_archives_with_one_folder_around_them(ask_dir):
    files = {"tidy/SKILL.md": SKILL, "tidy/run.sh": b"echo hi\n"}
    for url, data in (("https://x.org/tidy.zip", zipped(files)),
                      ("https://x.org/tidy.tar.gz", tarred(files))):  # fmt: skip
        entry = ready(ask_dir, url, data)
        assert [f["path"] for f in entry["files"]] == ["SKILL.md", "run.sh"]
        assert "has_scripts" in entry["warnings"]
        approvals.take("s1", entry["id"])


@pytest.mark.parametrize(
    ("url", "data"),
    [
        ("https://x.org/a.zip", zipped({"../evil/SKILL.md": SKILL})),
        ("https://x.org/a.zip", zipped({"/etc/SKILL.md": SKILL})),
        ("https://x.org/a.zip", zipped({"README.md": b"no skill"})),
        ("https://x.org/a.zip", b"not a zip"),
        ("https://x.org/SKILL.md", b"---\nname: ../up\n---\n"),
    ],
)
def test_unsafe_or_broken_sources_fail_and_leave_nothing(ask_dir, isolated_config, url, data):
    entry = ready(ask_dir, url, data)
    assert entry["state"] == "failed" and entry["error"]
    assert not (isolated_config / "skill-quarantine" / entry["id"]).exists()


def test_a_skill_that_exists_is_not_replaced(ask_dir):
    (ask_dir / ".claude" / "skills" / "tidy").mkdir(parents=True)
    entry = ready(ask_dir, "https://example.org/tidy/SKILL.md", SKILL)
    assert entry["state"] == "failed" and "already installed" in entry["error"]


def test_a_download_error_is_shown_not_raised(ask_dir):
    _, [card] = propose(ask_dir, "https://example.org/SKILL.md")

    def broken(_url):
        raise OSError("network down")

    entry = skill_installs.prepare(card["id"], fetcher=broken)
    assert entry["state"] == "failed" and entry["error"] == "the download failed"


# ------------------------------------------------------------------- the owner's answer


def test_only_an_approval_installs(ask_dir, isolated_config):
    entry = ready(ask_dir, "https://x.org/tidy.zip", zipped({"SKILL.md": SKILL, "run.sh": b"x"}))
    done = skill_installs.answer(approvals.take("s1", entry["id"]), True)
    target = ask_dir / ".claude" / "skills" / "tidy"
    assert done == {"installed": True, "name": "tidy", "target": str(target)}
    assert (target / "SKILL.md").read_bytes() == SKILL
    assert (target / "run.sh").stat().st_mode & 0o777 == 0o644  # never executable
    assert not (isolated_config / "skill-quarantine" / entry["id"]).exists()
    steps = [r["tool"] for r in records(isolated_config)]
    assert steps == ["skill_install_proposed", "skill_install_ready", "skill_install_approved"]


def test_a_refusal_or_expiry_removes_the_quarantine(ask_dir, isolated_config):
    entry = ready(ask_dir, "https://example.org/tidy/SKILL.md", SKILL)
    assert skill_installs.answer(approvals.take("s1", entry["id"]), False) == {"installed": False}
    assert not (ask_dir / ".claude").exists()
    assert not (isolated_config / "skill-quarantine" / entry["id"]).exists()
    late = ready(ask_dir, "https://example.org/tidy/SKILL.md", SKILL)
    [expired] = approvals.take_expired(now=time.time() + approvals.TTL_SECONDS + 1)
    skill_installs.expire(expired)
    assert not (isolated_config / "skill-quarantine" / late["id"]).exists()
    assert records(isolated_config)[-1]["tool"] == "skill_install_expired"
    assert approvals.pending("s1") == []


def test_no_install_without_its_audit_record(ask_dir, monkeypatch):
    entry = ready(ask_dir, "https://example.org/tidy/SKILL.md", SKILL)

    def unavailable(*_a, **_k):
        raise audit.AuditUnavailable("x")

    monkeypatch.setattr(audit, "record", unavailable)
    with pytest.raises(ClaudeError) as caught:
        skill_installs.answer(approvals.take("s1", entry["id"]), True)
    assert caught.value.code == "audit_unavailable"
    assert not (ask_dir / ".claude" / "skills" / "tidy").exists()


def test_install_records_never_count_as_a_runs_tool_calls(ask_dir, isolated_config):
    ready(ask_dir, "https://example.org/tidy/SKILL.md", SKILL)
    watch = capable.AuditWatch("s1")
    result = {"type": "tool_result", "is_error": False}
    with pytest.raises(ClaudeError):
        watch({"type": "user", "message": {"content": [result]}})


# ------------------------------------------------------------------- through the API


def capable_marker():
    data = {"runner": "cli", "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "all_allowed_folders",
            "suite_version": capable.SUITE_VERSION, "skills_verified": True}  # fmt: skip
    capable.marker_path("cli").write_text(json.dumps(capable.sign_marker(data)))


def test_api_round_trip(settings, ask_dir, auth, monkeypatch):
    capable_marker()
    monkeypatch.setattr(skill_installs, "fetch", lambda _url: SKILL)
    (ask_dir.parent / ".fake-claude-answer").write_text(
        "Done.\n[[skill-install: https://example.org/tidy/SKILL.md]]\n"
    )
    with TestClient(create_app(settings)) as client:
        reply = client.post("/ask", headers=auth, json={"prompt": "x", "mode": "capable"}).json()
        assert reply["answer"] == "Done."
        [card] = reply["skill_installs"]
        session_id = reply["session_id"]
        deadline = time.time() + 10
        while True:
            pending = client.get(f"/sessions/{session_id}/interactions", headers=auth).json()
            [item] = pending["pending"]
            if item["state"] == "ready":
                break
            assert time.time() < deadline
            time.sleep(0.02)
        assert item["kind"] == "skill_install" and item["id"] == card["id"]
        url = f"/sessions/{session_id}/interactions/{card['id']}"
        assert client.post(url, headers=auth, json={"approve": "yes"}).status_code == 400
        done = client.post(url, headers=auth, json={"approve": True}).json()
        assert done["answered"] is True and done["installed"] is True
        assert client.post(url, headers=auth, json={"approve": True}).status_code == 404
        # A read-only run cannot propose an install: the line is left as text.
        plain = client.post("/ask", headers=auth, json={"prompt": "y"}).json()
        assert "skill_installs" not in plain
    assert (ask_dir / ".claude" / "skills" / "tidy" / "SKILL.md").exists()


# ------------------------------------------------------------------- guardrails


def test_capable_runs_cannot_write_the_folders_claude_files(settings):
    policy = capable.build_policy(settings, settings.ask_dir)
    deny = json.loads(policy.settings_json)["permissions"]["deny"]
    root = "/" + str(settings.ask_dir.resolve())
    assert f"Edit({root}/.claude/**)" in deny and f"Edit({root}/.mcp.json)" in deny
    flags = claude.run_flags(settings, session_id="s", resume=False, policy=policy)
    assert "--settings" in flags


def test_the_check_has_a_probe_for_it(settings, monkeypatch):
    for writes, expected in ((False, True), (True, False)):
        suite = verify.Suite(settings)
        suite.run_dir.mkdir(exist_ok=True)

        def run(*_a, writes=writes, suite=suite, **_k):
            if writes:
                (suite.run_dir / ".claude" / "skills" / "added-by-shell").mkdir(parents=True)
            return verify.Run(0, [], [], "", [], "", "s")

        monkeypatch.setattr(suite, "capable_run", run)
        suite.probe_no_self_install()
        assert suite.probes[-1].passed is expected
        suite.retire()
