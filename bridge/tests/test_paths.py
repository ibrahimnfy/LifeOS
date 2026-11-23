from pathlib import Path

import pytest

from lifeos_bridge import paths
from lifeos_bridge.paths import refusal_reason


@pytest.fixture
def home(tmp_path):
    folder = tmp_path / "home"
    folder.mkdir(exist_ok=True)
    return folder


def test_ordinary_folder_inside_home_is_fine(home):
    project = home / "projects" / "alpha"
    project.mkdir(parents=True)
    assert refusal_reason(project) is None
    assert refusal_reason(Path("~/projects/alpha")) is None


def test_refusals(home, tmp_path, isolated_config):
    (home / ".ssh").mkdir()
    (home / ".config" / "app").mkdir(parents=True)
    (home / "projects" / ".secrets").mkdir(parents=True)
    (home / "file.txt").write_text("x")
    outside = tmp_path / "outside"
    outside.mkdir()
    assert "home folder itself" in refusal_reason(home)
    assert "home folder itself" in refusal_reason(Path("~"))
    assert "hidden" in refusal_reason(home / ".ssh")
    assert "hidden" in refusal_reason(home / ".config" / "app")
    assert "hidden" in refusal_reason(home / "projects" / ".secrets")
    assert "inside the home" in refusal_reason(outside)
    assert "inside the home" in refusal_reason(Path("/etc"))
    assert "inside the home" in refusal_reason(Path("/"))
    assert "does not exist" in refusal_reason(home / "missing")
    assert "not a folder" in refusal_reason(home / "file.txt")
    assert "absolute" in refusal_reason(Path("relative/path"))


def test_symlinks_are_resolved_before_checking(home, tmp_path):
    (home / ".ssh").mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    (home / "innocent").symlink_to(home / ".ssh")
    (home / "elsewhere").symlink_to(outside)
    (home / "up").symlink_to(home)
    assert "hidden" in refusal_reason(home / "innocent")
    assert "inside the home" in refusal_reason(home / "elsewhere")
    assert "home folder itself" in refusal_reason(home / "up")


def test_the_program_folder_itself_only_and_the_settings_folder_never(
    home, monkeypatch, isolated_config
):
    repo = home / "lifeos"
    (repo / "bridge").mkdir(parents=True)
    monkeypatch.setattr(paths, "repo_dir", lambda: repo)
    # #180: the program folder itself may be allowed; its own rules come with it.
    assert refusal_reason(repo) is None
    assert "program folder itself" in refusal_reason(repo / "bridge")
    assert paths.program_dir([home / "other", repo]) == repo
    assert paths.program_dir([repo / "bridge"]) is None
    parent = home / "code"
    (parent / "lifeos").mkdir(parents=True)
    monkeypatch.setattr(paths, "repo_dir", lambda: parent / "lifeos")
    assert "program folder itself" in refusal_reason(parent)
    visible_config = home / "settings"
    visible_config.mkdir()
    monkeypatch.setenv("LIFEOS_CONFIG_DIR", str(visible_config))
    assert "private settings" in refusal_reason(visible_config)


def test_repo_dir_points_at_this_checkout():
    assert (paths.repo_dir() / "CLAUDE.md").is_file()
