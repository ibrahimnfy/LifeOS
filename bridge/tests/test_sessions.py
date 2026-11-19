"""Session listing. All transcripts here are synthetic."""

import dataclasses
import json
import uuid
from pathlib import Path

from fastapi.testclient import TestClient

from conftest import user_line
from lifeos_bridge import sessions
from lifeos_bridge.app import create_app
from lifeos_bridge.config import load_settings, read_allowed_paths


def get_sessions(client, auth, **params):
    response = client.get("/sessions", headers=auth, params=params)
    assert response.status_code == 200
    return response.json()


def test_requires_token(client):
    assert client.get("/sessions").status_code == 401


def test_empty_when_nothing_is_stored(client, auth):
    assert get_sessions(client, auth) == {"sessions": [], "total": 0}


def test_lists_title_folder_and_last_activity_newest_first(client, auth, ask_dir, write_session):
    old = write_session(ask_dir, [user_line(ask_dir, "first question")], mtime=1_800_000_000)
    new = write_session(ask_dir, [user_line(ask_dir, "second question")], mtime=1_800_000_500)
    body = get_sessions(client, auth)
    assert body["total"] == 2
    assert [s["id"] for s in body["sessions"]] == [new, old]
    assert body["sessions"][0] == {
        "id": new,
        "title": "second question",
        "folder": str(ask_dir),
        "last_activity": "2027-01-15T08:08:20Z",
        "in_use": False,
    }


def test_limit(client, auth, ask_dir, write_session):
    for index in range(3):
        write_session(ask_dir, [user_line(ask_dir, f"q{index}")], mtime=1_800_000_000 + index)
    body = get_sessions(client, auth, limit=2)
    assert len(body["sessions"]) == 2 and body["total"] == 3
    assert [s["title"] for s in body["sessions"]] == ["q2", "q1"]


def test_sessions_outside_allowed_roots_are_not_listed(
    client, auth, ask_dir, tmp_path, write_session
):
    other = tmp_path / "home" / "private-project"
    other.mkdir()
    write_session(other, [user_line(other, "secret work")])
    # A different folder whose encoded name collides with a sub-folder of the root:
    lookalike = Path(str(ask_dir) + "-private")
    lookalike.mkdir()
    write_session(lookalike, [user_line(lookalike, "also secret")])
    # A transcript stored under the root's project name but recorded elsewhere:
    write_session(other, [user_line(other, "moved")], project=sessions.encode_folder(ask_dir))
    assert get_sessions(client, auth)["sessions"] == []


def test_sub_folders_of_a_root_are_listed(client, auth, ask_dir, write_session):
    sub = ask_dir / "notes"
    sub.mkdir()
    session_id = write_session(sub, [user_line(sub, "in a sub-folder")])
    (listed,) = get_sessions(client, auth)["sessions"]
    assert listed["id"] == session_id and listed["folder"] == str(sub)


def test_allowed_paths_file_adds_roots(settings, auth, tmp_path, write_session):
    project = tmp_path / "home" / "project"
    project.mkdir()
    write_session(project, [user_line(project, "project work")])
    client = TestClient(create_app(dataclasses.replace(settings, allowed_paths=(project,))))
    assert [s["title"] for s in get_sessions(client, auth)["sessions"]] == ["project work"]


def test_malformed_files_are_skipped(client, auth, ask_dir, write_session):
    write_session(ask_dir, ["not json at all", "{broken"])
    write_session(ask_dir, [{"type": "user", "message": {"content": "no folder recorded"}}])
    write_session(ask_dir, [])
    good = write_session(ask_dir, ["garbage", [1, 2], user_line(ask_dir, "fine")])
    folder = sessions.projects_dir() / sessions.encode_folder(ask_dir)
    (folder / "not-a-session-id.jsonl").write_text(json.dumps(user_line(ask_dir, "x")))
    (folder / "memory").mkdir()
    assert [s["id"] for s in get_sessions(client, auth)["sessions"]] == [good]


def test_title_preference_and_cleanup(client, auth, ask_dir, write_session):
    write_session(
        ask_dir,
        [
            user_line(ask_dir, "<command-name>/clear</command-name>"),
            user_line(ask_dir, "side task", isSidechain=True),
            user_line(ask_dir, [{"type": "text", "text": "  typed   on\ntwo lines  "}]),
        ],
        mtime=1,
    )
    write_session(
        ask_dir,
        [
            user_line(ask_dir, "first prompt"),
            {"type": "ai-title", "aiTitle": "Generated title"},
            {"type": "custom-title", "customTitle": "My own title"},
        ],
        mtime=2,
    )
    write_session(ask_dir, [user_line(ask_dir, "x" * 300)], mtime=3)
    write_session(ask_dir, [{"type": "system", "cwd": str(ask_dir)}], mtime=4)
    titles = [s["title"] for s in get_sessions(client, auth)["sessions"]]
    assert titles[0] == "(untitled)"
    assert len(titles[1]) == 80 and titles[1].endswith("…")
    assert titles[2] == "My own title"
    assert titles[3] == "typed on two lines"


def test_large_files_are_read_from_both_ends_only(ask_dir, write_session):
    filler = json.dumps({"type": "api-request-blob", "data": "z" * 1000})
    session_id = write_session(
        ask_dir,
        [
            user_line(ask_dir, "start"),
            *[filler] * 700,
            {"type": "ai-title", "aiTitle": "At the end"},
        ],
    )
    info = sessions.find_session(session_id, (ask_dir,))
    assert info.title == "At the end" and info.folder == ask_dir


def test_find_session_rejects_bad_ids_and_foreign_sessions(ask_dir, tmp_path, write_session):
    other = tmp_path / "home" / "elsewhere"
    other.mkdir()
    foreign = write_session(other, [user_line(other, "x")])
    assert sessions.find_session(foreign, (ask_dir,)) is None
    assert sessions.find_session("../../etc/passwd", (ask_dir,)) is None
    assert sessions.find_session(str(uuid.uuid4()), (ask_dir,)) is None


def test_in_use_flag_reflects_running_prompts(settings, auth, ask_dir, write_session):
    session_id = write_session(ask_dir, [user_line(ask_dir, "busy one")])
    app = create_app(settings)
    app.state.slots.active.add(session_id)
    (listed,) = get_sessions(TestClient(app), auth)["sessions"]
    assert listed["in_use"] is True


def test_read_allowed_paths(tmp_path):
    file = tmp_path / "allowed_paths"
    file.write_text("# comment\n\n/srv/projects/one\n~/two\nrelative/path\n/\n")
    assert read_allowed_paths(file) == (Path("/srv/projects/one"), Path.home() / "two")
    assert read_allowed_paths(tmp_path / "missing") == ()


def test_load_settings_reads_allowed_paths_and_applies_the_limits(isolated_config, tmp_path):
    env = isolated_config / "env"
    env.write_text("LIFEOS_BRIDGE_TOKEN=" + "t" * 40 + "\n")
    env.chmod(0o600)
    good = tmp_path / "home" / "project"
    good.mkdir(parents=True)
    (isolated_config / "allowed_paths").write_text(f"/srv/projects/one\n{good}\n~\n~/.ssh\n")
    settings = load_settings()
    assert settings.allowed_paths_file == isolated_config / "allowed_paths"
    # Outside home, home itself and hidden folders never become roots.
    assert settings.allowed_roots == (settings.ask_dir.resolve(), good)
