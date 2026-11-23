"""Capable mode refuses folders nested inside another git repository (#139). Stand-ins only."""

import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import capable, runcontrol, sdk_runner, verify
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError


def repo(folder):
    (folder / ".git").mkdir(parents=True)
    (folder / ".git" / "HEAD").write_text("ref: refs/heads/main\n")
    return folder


@pytest.fixture
def projects(tmp_path):
    outer = repo(tmp_path / "home" / "projects" / "outer")
    inner = outer / "inner"
    inner.mkdir()
    own = repo(outer / "own-repo")  # a repository of its own inside the outer one
    return outer, inner, own


def marker(runner):
    data = {"runner": runner, "claude_version": "fake-claude 1.0", "all_passed": True,
            "sdk_version": capable.sdk_version(), "scope": "all_allowed_folders"}  # fmt: skip
    capable.marker_path().write_text(json.dumps(capable.sign_marker(data)))


def test_repository_root_is_found_as_git_finds_it(tmp_path, projects):
    outer, inner, own = projects
    assert capable.repository_root(inner) == outer
    assert capable.repository_root(own) == own
    (tmp_path / "plain" / ".git").mkdir(parents=True)  # not a repository: no HEAD
    assert capable.repository_root(tmp_path / "plain") is None
    worktree = tmp_path / "wt"
    worktree.mkdir()
    (worktree / ".git").write_text("gitdir: /somewhere/.git/worktrees/wt\n")
    assert capable.repository_root(worktree) == worktree


def test_a_folder_inside_another_repository_is_refused_and_the_root_named(settings, projects):
    outer, inner, own = projects
    allowed = dataclasses.replace(settings, allowed_paths=(outer, inner, own))
    with pytest.raises(ClaudeError) as caught:
        capable.build_policy(allowed, inner)
    assert caught.value.code == "folder_inside_repository" and caught.value.status == 403
    assert f"Add {outer} as an allowed folder" in caught.value.message
    capable.build_policy(allowed, outer)  # the repository root itself
    capable.build_policy(allowed, own)  # a repository root of its own


def test_subfolders_of_the_memory_folder_stay_usable(settings):
    repo(settings.ask_dir)
    sub = settings.ask_dir / "projects"
    sub.mkdir()
    capable.build_policy(settings, sub)  # the memory folder is writable in every capable run


def test_the_memory_folder_inside_another_repository_is_refused(settings, tmp_path):
    repo(tmp_path / "home")  # the whole home folder is a repository
    with pytest.raises(ClaudeError) as caught:
        capable.build_policy(settings, settings.ask_dir)
    assert caught.value.code == "folder_inside_repository"


@pytest.mark.parametrize("runner", ["sdk"])  # capable runs exist on the SDK runner only
def test_both_runners_refuse_before_the_run(settings, projects, auth, monkeypatch, runner):
    monkeypatch.setattr(sdk_runner, "GRACE", 0.3)
    monkeypatch.setattr(runcontrol, "GRACE", 0.3)
    outer, inner, _ = projects
    marker(runner)
    allowed = dataclasses.replace(settings, runner=runner, allowed_paths=(inner,))
    with TestClient(create_app(allowed)) as client:
        response = client.post(
            "/ask", headers=auth, json={"prompt": "x", "mode": "capable", "folder": str(inner)}
        )
    assert response.status_code == 403
    assert response.json()["error"] == "folder_inside_repository"
    assert str(outer) in response.json()["message"]
    calls = settings.ask_dir / ".fake-claude-calls.jsonl"
    assert not calls.exists() and not (inner / ".fake-claude-calls.jsonl").exists()


def test_the_check_suite_has_a_probe_for_it(settings):
    suite = verify.Suite(settings)
    suite.run_dir = settings.ask_dir / "guardrail-probe"
    suite.run_dir.mkdir()
    suite.probe_nested_repository()
    assert [(p.name, p.passed) for p in suite.probes] == [
        ("folders: a folder inside another repository is refused", True),
        ("folders: the repository root itself is accepted", True),
    ]
