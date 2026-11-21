"""The Projects endpoint (#152): context map + memory project files, no model run."""

import dataclasses
import subprocess

import pytest
from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge.app import create_app


def git(folder, *args):
    command = ["git", "-C", str(folder), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def world(settings, ask_dir, tmp_path, isolated_config, write_session):
    home = tmp_path / "home"
    alpha, beta, courses = (home / "projects" / "alpha", home / "projects" / "beta",
                            home / "courses")  # fmt: skip
    for folder in (alpha, beta, courses):
        folder.mkdir(parents=True)
    git(alpha, "init", "-q")
    (alpha / "README.md").write_text("# Alpha\n")
    git(alpha, "add", "-A")
    git(alpha, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "x")
    env = isolated_config / "env"
    env.write_text(f"MOODLE_DOWNLOAD_DIR={courses}\n")
    env.chmod(0o600)
    (ask_dir / "CLAUDE.md").write_text("# Memory\n")
    (ask_dir / "map").mkdir()
    (ask_dir / "map" / "alpha.md").write_text(
        "---\ntitle: Alpha\ntype: map\nupdated: 2027-01-10\n---\n\n"
        "## Location\n`~/projects/alpha`\n\n"
        "## What it is\nA small example tool.\n\n## State\nWorking prototype; tests pass.\n\n"
        "## Key files\n- none listed\n"
    )
    (ask_dir / "projects").mkdir()
    (ask_dir / "projects" / "alpha-project.md").write_text(
        "---\ntitle: Alpha tool\ntype: project\nupdated: 2027-01-10\nstatus: active\n---\n\n"
        "The example tool the owner builds.\n\n## Facts\n- [owner 2027-01-01] x\n\n"
        "## Links\n- Folder: see [[map/alpha]]\n"
    )
    (ask_dir / "projects" / "thesis.md").write_text(
        "---\ntitle: Thesis\ntype: project\nupdated: 2026-12-01\nstatus: paused\n---\n\nA thesis.\n"
    )
    (ask_dir / "tasks.md").write_text(
        "# Tasks\n\n## Open\n"
        "- [ ] Fix the parser  (project: alpha-project) (added: 2027-01-12) ^t-20270112-01\n"
        "- [ ] Write chapter 2  (project: thesis) (added: 2027-01-12) ^t-20270112-02\n"
        "- [ ] Unrelated  (added: 2027-01-12) ^t-20270112-03\n"
    )
    session = write_session(alpha, [user_line(alpha, "how does the parser work")])
    configured = dataclasses.replace(settings, allowed_paths=(alpha, beta, courses))
    return TestClient(create_app(configured)), alpha, beta, session


def test_projects_from_the_map_and_the_memory(world, auth, calls):
    client, alpha, beta, session = world
    listed = client.get("/projects", headers=auth).json()["projects"]
    assert [p["id"] for p in listed] == ["p-alpha-project", "p-thesis", "f-beta"]
    first = listed[0]
    assert set(first) == {"id", "name", "title", "status", "summary", "state", "folder",
                          "folder_exists", "memory_file", "map_file", "last_activity",
                          "open_task_count", "open_tasks", "sessions"}  # fmt: skip
    assert (first["title"], first["status"], first["folder"]) == (
        "Alpha tool",
        "active",
        str(alpha),
    )
    assert first["summary"] == "The example tool the owner builds."
    assert first["state"] == "Working prototype; tests pass."
    assert (first["memory_file"], first["map_file"]) == (
        "projects/alpha-project.md",
        "map/alpha.md",
    )
    assert first["open_task_count"] == 1 and first["open_tasks"][0]["title"] == "Fix the parser"
    assert first["sessions"][0]["id"] == session
    assert first["last_activity"] == first["sessions"][0]["last_activity"]  # the newest source
    thesis = listed[1]
    assert (thesis["status"], thesis["folder"], thesis["last_activity"]) == (
        "paused", None, "2026-12-01T00:00:00Z"
    )  # fmt: skip
    assert thesis["open_tasks"][0]["title"] == "Write chapter 2"
    beta_entry = listed[2]
    assert (beta_entry["folder"], beta_entry["memory_file"], beta_entry["last_activity"]) == (
        str(beta), None, None
    )  # fmt: skip
    assert all("courses" not in (p["folder"] or "") for p in listed)  # the course folder
    assert calls() == []  # no model run


def test_one_project(world, auth):
    client, *_ = world
    one = client.get("/projects/p-thesis", headers=auth).json()
    assert one["title"] == "Thesis"
    missing = client.get("/projects/p-nothing", headers=auth)
    assert missing.status_code == 404 and missing.json()["error"] == "project_not_found"
    assert client.get("/projects").status_code == 401
