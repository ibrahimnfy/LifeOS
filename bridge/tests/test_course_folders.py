"""The owner's read-only course folders (#179): the list, the policy, the runs, resources.

Stand-in claude only; the real CLI was checked by the course-folder probes of the
guardrail suite (verify.probe_course_folders) and by hand (docs/permission-policy.md 9.13).
"""

import dataclasses
import json

import pytest
from fastapi.testclient import TestClient

from conftest import added_dirs
from lifeos_bridge import allowed, capable, course_folders, resources
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import ClaudeError


@pytest.fixture
def home(ask_dir):
    return ask_dir.parent


@pytest.fixture
def settings(settings, isolated_config):
    return dataclasses.replace(settings, allowed_paths_file=isolated_config / "allowed_paths")


@pytest.fixture
def sync_root(home, isolated_config):
    """The course folder of the Moodle sync, with one course the sync knows (id 5)."""
    root = home / "courses"
    (root / "ex-101-5" / "materials").mkdir(parents=True)
    (isolated_config / "env").write_text(f"MOODLE_DOWNLOAD_DIR={root}\n")
    (isolated_config / "env").chmod(0o600)
    state = {"courses": {
        "5": {"id": 5, "folder": "ex-101-5", "shortname": "EX 101", "name": "Example 101",
              "files": {}, "sections": [{"name": "General", "files": []}]},
        "7": {"id": 7, "folder": "ex-102-7", "shortname": "EX 102", "name": "Example 102"},
    }}  # fmt: skip
    (isolated_config / "moodle-sync.json").write_text(json.dumps(state))
    return root


@pytest.fixture
def own(home, isolated_config):
    """The owner's own folders of course 5 and 7, listed in ``course_folders``."""
    first, second = home / "Desktop" / "EX101 notes", home / "Desktop" / "EX102"
    for folder in (first, second):
        folder.mkdir(parents=True)
        (folder / "lecture.pdf").write_text("pdf")
    (folder / ".claude").mkdir()
    (isolated_config / course_folders.FILE_NAME).write_text(
        f"# course id<TAB>folder\n5\t{first}\n7\t{second}\n"
    )
    return {"5": first, "7": second}


def write_list(config, text):
    (config / course_folders.FILE_NAME).write_text(text)


# ------------------------------------------------------------------ the list


def test_lines_are_read_with_the_fixed_limits(home, isolated_config, ask_dir, settings):
    good = home / "Desktop" / "Course"
    good.mkdir(parents=True)
    hidden = home / ".ssh"
    hidden.mkdir()
    link = home / "linked"
    link.symlink_to(good)
    write_list(
        isolated_config,
        "\n".join([
            "# a comment",
            f"5\t{good}",
            f"5\t{good}",  # a repeat counts once
            f"6\t{hidden}",  # hidden: never
            "7\t/etc",  # outside the home folder: never
            f"8\t{home}",  # the home folder itself: never
            f"9\t{ask_dir}",  # the memory folder: never
            f"x\t{good}",  # not a course id
            f"{good}",  # no id
            "10\trelative/path",
            f"11\t{home / 'missing'}",
            f"12\t{link}",  # a link: its target
        ]),
    )  # fmt: skip
    assert course_folders.listed(settings) == {5: [good], 12: [good]}
    assert course_folders.all_folders(settings) == [good]
    assert course_folders.listed() == {
        5: [good],
        9: [ask_dir],
        12: [good],
    }  # no settings: home rules only


def test_a_missing_or_unreadable_list_means_none(isolated_config):
    assert course_folders.listed() == {}
    (isolated_config / course_folders.FILE_NAME).write_bytes(b"\xff\xfe")
    assert course_folders.listed() == {}


def test_a_run_folder_belongs_to_its_course_through_the_sync_records(sync_root, own, settings):
    course = sync_root / "ex-101-5"
    assert course_folders.course_of(course) == 5
    assert course_folders.course_of(course / "materials") == 5  # sub-folders count
    assert course_folders.course_of(sync_root) is None  # the root belongs to no single course
    assert course_folders.course_of(sync_root / "unknown-9") is None
    assert course_folders.for_folder(settings, course / "materials") == [own["5"]]
    assert course_folders.for_folder(settings, sync_root) == []
    assert course_folders.is_sync_folder(course) and not course_folders.is_sync_folder(sync_root)
    # the name alone means nothing: only the sync's own records
    (sync_root / "ex-101-6").mkdir()
    assert course_folders.course_of(sync_root / "ex-101-6") is None


# ------------------------------------------------------------------ the capable policy


def test_a_capable_run_reads_its_course_folder_and_writes_none(sync_root, own, settings):
    settings = dataclasses.replace(settings, allowed_paths=(sync_root,))
    run_dir = sync_root / "ex-101-5"
    policy = capable.build_policy(settings, run_dir)
    granted = json.loads(policy.settings_json)
    filesystem = granted["sandbox"]["filesystem"]
    assert str(own["5"]) in filesystem["allowRead"] and str(own["7"]) not in filesystem["allowRead"]
    # every listed course folder is write-denied, readable or not
    assert {str(own["5"]), str(own["7"])} <= set(filesystem["denyWrite"])
    for folder in own.values():
        assert f"Edit(/{folder}/**)" in granted["permissions"]["deny"]
    assert own["5"] in policy.add_dirs and own["7"] not in policy.add_dirs
    # given explicitly (the bridge passes none until the check proved course folders)
    nothing = capable.build_policy(settings, run_dir, course_dirs=[])
    granted = json.loads(nothing.settings_json)
    assert str(own["5"]) not in granted["sandbox"]["filesystem"]["allowRead"]
    assert str(own["5"]) in granted["sandbox"]["filesystem"]["denyWrite"]
    assert own["5"] not in nothing.add_dirs


def test_a_course_folder_is_never_the_folder_of_a_capable_run(own, home, settings):
    settings = dataclasses.replace(settings, allowed_paths=(home / "Desktop",))
    for run_dir in (own["5"], own["5"] / "sub"):
        run_dir.mkdir(exist_ok=True)
        with pytest.raises(ClaudeError) as refused:
            capable.build_policy(settings, run_dir)
        assert refused.value.code == "folder_not_allowed"
        assert "read-only course folders" in refused.value.message
    # a folder around one is fine; the course folder in it stays write-denied
    policy = capable.build_policy(settings, home / "Desktop")
    assert str(own["5"]) in json.loads(policy.settings_json)["sandbox"]["filesystem"]["denyWrite"]


def test_the_marker_must_have_proved_course_folders(isolated_config):
    def marker(**fields):
        data = {"runner": "sdk", "claude_version": "x", "all_passed": True} | fields
        capable.marker_path().write_text(json.dumps(capable.sign_marker(data)))

    assert not capable.course_folders_available()
    marker(suite_version=7, course_folders=False)
    assert not capable.course_folders_available()
    marker(suite_version=7, course_folders=True)
    assert not capable.course_folders_available()
    marker(suite_version=8, course_folders=True)
    assert capable.course_folders_available()


def test_a_course_folder_cannot_become_an_allowed_folder(own, settings, isolated_config):
    file = isolated_config / "allowed_paths"
    for path in (own["5"], own["5"] / ".." / "EX101 notes"):
        with pytest.raises(ClaudeError) as refused:
            allowed.add(file, str(path))
        assert (
            refused.value.code == "path_not_allowed" and "course folders" in refused.value.message
        )
    inner = own["5"] / "week 1"
    inner.mkdir()
    with pytest.raises(ClaudeError):
        allowed.add(file, str(inner))
    assert not file.exists()
    client = TestClient(create_app(settings))
    reply = client.post(
        "/allowed-paths", headers={"Authorization": f"Bearer {settings.token}"},
        json={"path": str(own["7"])},
    )  # fmt: skip
    assert reply.status_code == 400 and reply.json()["error"] == "path_not_allowed"


# ------------------------------------------------------------------ runs through the bridge


@pytest.fixture
def course_client(settings, sync_root):
    return TestClient(create_app(dataclasses.replace(settings, allowed_paths=(sync_root,))))


def logged(folder):
    """The stand-in CLI's calls made in ``folder`` (its call log lies in its working folder)."""
    log = folder / ".fake-claude-calls.jsonl"
    return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []


def test_a_read_only_course_chat_gets_its_course_folder_to_read(
    course_client, auth, sync_root, own, write_session
):
    from conftest import user_line

    course = sync_root / "ex-101-5"
    session = write_session(course, [user_line(course, "what is due")])
    reply = course_client.post("/ask", headers=auth, json={"prompt": "hi", "session_id": session})
    assert reply.status_code == 200
    args = logged(course)[-1]["args"]
    assert str(own["5"]) in added_dirs(args) and str(own["7"]) not in added_dirs(args)
    context = args[args.index("--append-system-prompt") + 1]
    assert f"readable at {own['5']}" in context and "read-only" in context
    assert "--settings" not in args  # read-only: no write tool at all


def test_a_chat_elsewhere_gets_no_course_folder(course_client, auth, sync_root, own, ask_dir):
    course_client.post("/ask", headers=auth, json={"prompt": "hi", "folder": str(sync_root)})
    course_client.post("/ask", headers=auth, json={"prompt": "hi", "folder": str(ask_dir)})
    made = [*logged(sync_root), *logged(ask_dir)]
    assert len(made) == 2
    for call in made:
        assert not any("EX10" in folder for folder in added_dirs(call["args"]))


def test_a_new_chat_may_start_in_a_course_folder_of_the_sync(course_client, auth, sync_root, own):
    course = sync_root / "ex-101-5"
    reply = course_client.post("/ask", headers=auth, json={"prompt": "hi", "folder": str(course)})
    assert reply.status_code == 200
    # never in the owner's own course folder, nor in a folder the sync does not know
    for folder in (own["5"], sync_root / "ex-101-5" / "materials"):
        refused = course_client.post(
            "/ask", headers=auth, json={"prompt": "hi", "folder": str(folder)}
        )
        assert refused.status_code == 403


# ------------------------------------------------------------------ resources, materials


def test_resources_count_the_course_folders_as_existing_ones(own, isolated_config, home):
    assert resources.named_folders() is None  # the resource folders are still not named
    named = home / "Books"
    named.mkdir()
    (isolated_config / resources.FOLDERS_FILE).write_text(f"{named}\n")
    assert resources.named_folders() == [named, own["5"], own["7"]]
    found = resources.local_copies(resources.named_folders())
    assert "lecture" in found.names


def test_the_course_materials_never_list_the_course_folders(course_client, auth, own, sync_root):
    reply = course_client.get("/courses/5/materials", headers=auth)
    assert reply.status_code == 200
    assert "lecture" not in reply.text and "EX101" not in reply.text


@pytest.mark.parametrize("proved", [True, False])
def test_a_capable_course_chat_reads_its_folder_once_the_check_proved_it(
    settings, auth, sync_root, own, write_session, isolated_config, proved
):
    from conftest import user_line

    marker = {"runner": "sdk", "claude_version": "fake-claude 1.0", "all_passed": True,
              "scope": "all_allowed_folders", "sdk_version": capable.sdk_version(),
              "suite_version": capable.SUITE_VERSION, "course_folders": proved}  # fmt: skip
    capable.marker_path().write_text(json.dumps(capable.sign_marker(marker)))
    course = sync_root / "ex-101-5"
    session = write_session(course, [user_line(course, "what is due")])
    sdk = dataclasses.replace(settings, runner="sdk", allowed_paths=(sync_root,))
    with TestClient(create_app(sdk)) as course_client:
        reply = course_client.post(
            "/ask", headers=auth, json={"prompt": "hi", "session_id": session, "mode": "capable"}
        )
    assert reply.status_code == 200, reply.text
    args = [entry for entry in logged(course) if "args" in entry][-1]["args"]
    granted = json.loads(args[args.index("--settings") + 1])
    assert (str(own["5"]) in added_dirs(args)) is proved
    assert (str(own["5"]) in granted["sandbox"]["filesystem"]["allowRead"]) is proved
    # never writable, proved or not
    assert {str(own["5"]), str(own["7"])} <= set(granted["sandbox"]["filesystem"]["denyWrite"])
