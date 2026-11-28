"""scripts/phone-chats (#201): the sessions Claude Code's own list hides. Synthetic content only."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "phone-chats"
PHONE = "11111111-2222-4333-8444-555555555555"
PRINT = "22222222-2222-4333-8444-555555555555"
TERMINAL = "33333333-2222-4333-8444-555555555555"


def transcript(directory: Path, session_id: str, entrypoint: str, prompt: str, title=None, at=0):
    lines = [
        {"type": "session-owner", "sessionId": session_id},
        {"type": "user", "entrypoint": entrypoint, "cwd": "/somewhere", "sessionId": session_id,
         "message": {"role": "user", "content": prompt}},
        # A later turn from the other side never changes how the session was started.
        {"type": "user", "entrypoint": "cli" if entrypoint != "cli" else "sdk-py",
         "message": {"role": "user", "content": "a later turn"}},
    ]  # fmt: skip
    if title:
        lines.append({"type": "ai-title", "aiTitle": title, "sessionId": session_id})
    path = directory / f"{session_id}.jsonl"
    path.write_text("".join(json.dumps(line) + "\n" for line in lines))
    os.utime(path, (at, at))


def run(home: Path, folder: Path, *args: str, path: str | None = None):
    env = {"HOME": str(home), "PATH": path or os.environ["PATH"]}
    return subprocess.run(  # noqa: S603
        [sys.executable, str(SCRIPT), *args], cwd=folder, env=env, capture_output=True, text=True
    )


def setup(tmp_path: Path) -> tuple[Path, Path]:
    home, folder = tmp_path / "home", tmp_path / "work" / "my project"
    folder.mkdir(parents=True)
    project = home / ".claude" / "projects" / re.sub(r"[^A-Za-z0-9]", "-", str(folder.resolve()))
    project.mkdir(parents=True)
    transcript(project, PHONE, "sdk-py", "ignored", title="Asked from the phone", at=2_000)
    transcript(project, PRINT, "sdk-cli", "A  print-mode\nprompt", at=1_000)
    transcript(project, TERMINAL, "cli", "Typed in a terminal", title="Terminal work", at=3_000)
    (project / "notes.jsonl").write_text("{}\n")  # not a session id: skipped
    return home, folder


def test_only_the_sessions_the_list_hides_are_shown_newest_first(tmp_path):
    home, folder = setup(tmp_path)
    listed = run(home, folder)
    assert listed.returncode == 0
    lines = listed.stdout.splitlines()
    assert lines[0].endswith("Asked from the phone") and lines[0].lstrip().startswith("1 ")
    assert lines[1].strip() == f"claude --resume {PHONE}"
    assert lines[2].endswith("A print-mode prompt") and lines[3].strip().endswith(PRINT)
    assert "Terminal work" not in listed.stdout and TERMINAL not in listed.stdout

    # From elsewhere the line also goes to the folder, quoted for the shell.
    other = run(home, tmp_path, str(folder))
    assert f"cd '{folder.resolve()}' && claude --resume {PHONE}" in other.stdout
    assert "No conversation" in run(home, tmp_path).stdout
    assert run(home, tmp_path, str(tmp_path / "missing")).returncode == 2


def test_resume_starts_claude_with_the_id_in_the_folder(tmp_path):
    home, folder = setup(tmp_path)
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    fake = fake_bin / "claude"
    fake.write_text('#!/bin/sh\necho "$PWD|$*"\n')
    fake.chmod(0o755)
    path = f"{fake_bin}:{os.environ['PATH']}"
    newest = run(home, tmp_path, str(folder), "-r", path=path)
    assert newest.stdout.strip() == f"{folder.resolve()}|--resume {PHONE}"
    second = run(home, folder, "-r", "2", path=path)
    assert second.stdout.strip() == f"{folder.resolve()}|--resume {PRINT}"
    assert run(home, folder, "-r", "3", path=path).returncode == 2
