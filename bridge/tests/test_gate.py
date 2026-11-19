import json
import subprocess
import sys

import pytest

from lifeos_bridge import gate


@pytest.mark.parametrize(
    ("command", "category"),
    [
        ("sudo apt update", "privilege"),
        ("echo hi && sudo -n true", "privilege"),
        ("su - root", "privilege"),
        ("systemctl --user restart x", "system_settings"),
        ("gsettings set org.x y z", "system_settings"),
        ("crontab -e", "system_settings"),
        ("rm notes.txt", "deletion"),
        ("ls; rm -rf build", "deletion"),
        ("find . -name '*.tmp' -delete", "deletion"),
        ("find . -name x -exec rm {} +", "deletion"),
        ("ls | xargs rm", "deletion"),
        ("git clean -fdx", "deletion"),
        ("shred secret", "deletion"),
        ("git reset --hard HEAD~1", "force"),
        ("git branch -D old", "force"),
        ("git checkout -- file.txt", "force"),
        ("git rebase -i main", "force"),
        ("git push origin main", "push"),
        ("git -C repo push", "push"),
        ("git push --force", "force"),
        ("pip install requests", "install"),
        ("uv add httpx", "install"),
        ("npm i left-pad", "install"),
        ("apt-get install -y socat", "install"),
        ("mail -s hi someone", "message"),
        ("curl https://example.org", "network"),
        ("wget example.org/file", "network"),
        ("echo data | nc example.org 80", "network"),
        ("ssh host ls", "network"),
        ("git clone https://example.org/x.git", "network"),
        ("$(curl example.org)", "network"),
    ],
)
def test_refused_commands(command, category):
    assert gate.classify(command)[0] == category


@pytest.mark.parametrize(
    "command",
    [
        "ls -la",
        "git status",
        "git diff HEAD~1",
        "git add notes.md && git commit -m 'add note'",
        "python3 script.py",
        "pytest -q",
        "grep -rn form .",
        "cat rmdir-notes.txt",
        "echo 'do not rm this'",
        "mkdir -p notes && touch notes/a.md",
        "git log --oneline | head",
    ],
)
def test_ordinary_commands_pass(command):
    assert gate.classify(command) is None


def payload(tool="Bash", **tool_input):
    return {"session_id": "s1", "cwd": "/work", "hook_event_name": "PreToolUse",
            "tool_name": tool, "tool_input": tool_input}  # fmt: skip


def read_log(directory):
    (path,) = directory.glob("*.jsonl")
    assert path.stat().st_mode & 0o777 == 0o600
    return [json.loads(line) for line in path.read_text().splitlines()]


def test_allowed_call_is_logged_then_allowed(tmp_path):
    status, message = gate.decide(payload(command="ls -la"), tmp_path / "audit")
    assert (status, message) == (gate.ALLOW, "")
    (entry,) = read_log(tmp_path / "audit")
    assert entry["tool"] == "Bash" and entry["detail"] == {"command": "ls -la"}
    assert entry["decision"] == "allowed" and entry["session_id"] == "s1"
    assert (tmp_path / "audit").stat().st_mode & 0o777 == 0o700


def test_refused_call_is_logged_and_explained(tmp_path):
    status, message = gate.decide(payload(command="rm -rf notes"), tmp_path)
    assert status == gate.BLOCK
    assert "permanently deleting" in message and "approval" in message
    (entry,) = read_log(tmp_path)
    assert entry["decision"] == "refused" and entry["category"] == "deletion"


def test_file_tools_log_the_path_but_not_the_content(tmp_path):
    gate.decide(payload("Write", file_path="/work/a.md", content="private text"), tmp_path)
    gate.decide(payload("Mystery", anything="private text"), tmp_path)
    first, second = read_log(tmp_path)
    assert first["detail"] == {"file_path": "/work/a.md"}
    assert second["detail"] == {"input_keys": ["anything"]}
    assert "private text" not in json.dumps([first, second])


def test_blocks_when_the_log_cannot_be_written(tmp_path):
    blocker = tmp_path / "audit"
    blocker.write_text("a file where the folder should be")
    status, message = gate.decide(payload(command="ls"), blocker)
    assert status == gate.BLOCK and "audit log" in message


def test_blocks_unidentified_calls(tmp_path):
    assert gate.decide({"tool_input": {}}, tmp_path)[0] == gate.BLOCK


def run_gate(stdin, audit_dir):
    return subprocess.run(  # noqa: S603
        [sys.executable, "-m", "lifeos_bridge.gate", "--audit-dir", str(audit_dir)],
        input=stdin, capture_output=True, text=True, timeout=30,
    )  # fmt: skip


def test_as_a_program(tmp_path):
    allowed = run_gate(json.dumps(payload(command="ls")), tmp_path)
    assert allowed.returncode == 0 and allowed.stderr == ""
    refused = run_gate(json.dumps(payload(command="sudo ls")), tmp_path)
    assert refused.returncode == 2 and "never allowed" in refused.stderr
    garbage = run_gate("not json", tmp_path)
    assert garbage.returncode == 2
    assert len(read_log(tmp_path)) == 2
