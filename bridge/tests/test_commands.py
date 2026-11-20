"""Listing a session folder's skills and commands. All folders here are synthetic."""

import uuid

from conftest import user_line
from lifeos_bridge import commands

REASON = "skills and commands are not switched on for this session"


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)
    return path


def skill(folder, name, content):
    return write(folder / ".claude" / "skills" / name / "SKILL.md", content)


def command(folder, name, content):
    return write(folder / ".claude" / "commands" / f"{name}.md", content)


def session_in(folder, write_session):
    return write_session(folder, [user_line(folder, "hello")])


def get_commands(client, auth, session_id):
    response = client.get(f"/sessions/{session_id}/commands", headers=auth)
    assert response.status_code == 200, response.text
    return response.json()


def names(folder):
    return [(entry["name"], entry["kind"]) for entry in commands.list_commands(folder)]


def test_requires_token(client):
    assert client.get(f"/sessions/{uuid.uuid4()}/commands").status_code == 401


def test_unknown_session_is_404(client, auth):
    response = client.get(f"/sessions/{uuid.uuid4()}/commands", headers=auth)
    assert response.status_code == 404
    assert response.json()["error"] == "session_not_found"


def test_session_outside_allowed_folders_is_404(client, auth, tmp_path, write_session):
    outside = tmp_path / "elsewhere"
    outside.mkdir()
    skill(outside, "hidden", "---\ndescription: must not be listed\n---\n")
    response = client.get(f"/sessions/{session_in(outside, write_session)}/commands", headers=auth)
    assert response.status_code == 404
    assert "must not be listed" not in response.text


def test_folder_without_claude_directory_lists_nothing(client, auth, ask_dir, write_session):
    body = get_commands(client, auth, session_in(ask_dir, write_session))
    assert body == {"enabled": False, "reason": REASON, "commands": []}


def test_lists_skill_command_and_nested_command(client, auth, ask_dir, write_session):
    skill(ask_dir, "summarise", "---\nname: summarise\ndescription: Summarise notes\n---\nBody\n")
    command(ask_dir, "review", "---\ndescription: Review a file\nargument-hint: <file>\n---\nDo\n")
    command(ask_dir, "git/log/short", "Show the short log\n\nMore text that stays private.\n")
    body = get_commands(client, auth, session_in(ask_dir, write_session))
    assert body["enabled"] is False and body["reason"] == REASON
    assert body["commands"] == [
        {
            "name": "git:log:short",
            "kind": "command",
            "description": "Show the short log",
            "argument_hint": None,
            "warnings": [],
        },
        {
            "name": "review",
            "kind": "command",
            "description": "Review a file",
            "argument_hint": "<file>",
            "warnings": [],
        },
        {
            "name": "summarise",
            "kind": "skill",
            "description": "Summarise notes",
            "argument_hint": None,
            "warnings": [],
        },
    ]


def test_only_the_documented_files_count(ask_dir):
    skill(ask_dir, "good", "A skill\n")
    write(ask_dir / ".claude" / "skills" / "no-skill-file" / "README.md", "not a skill\n")
    write(ask_dir / ".claude" / "skills" / "loose.md", "not a skill\n")
    write(ask_dir / ".claude" / "commands" / "notes.txt", "not a command\n")
    write(ask_dir / ".claude" / "agents" / "helper.md", "not listed\n")
    write(ask_dir / ".claude" / "settings.json", "{}")
    assert names(ask_dir) == [("good", "skill")]


def test_frontmatter_is_parsed_tolerantly():
    fields, body = commands.split_frontmatter(
        "---\n"
        'description: "Quoted: with a colon"\n'
        "Argument-Hint: '[issue]'\n"
        "allowed-tools:\n"
        "  - Read\n"
        "  - Grep\n"
        "this line means nothing\n"
        ": neither does this\n"
        "---\n"
        "The body\n"
    )
    assert fields["description"] == "Quoted: with a colon"
    assert fields["argument-hint"] == "[issue]"
    assert fields["allowed-tools"] == "- Read - Grep"
    assert body == "The body\n".rstrip("\n")


def test_folded_description_becomes_one_line(ask_dir):
    skill(ask_dir, "folded", "---\ndescription: >\n  First part\n  second part\n---\nBody\n")
    (entry,) = commands.list_commands(ask_dir)
    assert entry["description"] == "First part second part"


def test_description_falls_back_to_first_text_line_and_is_capped(ask_dir):
    command(ask_dir, "heading", "\n\n# Tidy   the\tfolder\n\nSecond line\n")
    command(ask_dir, "long", "word " * 100)
    command(ask_dir, "no-description", "---\nmodel: some-model\n---\n\nFrom the body\n")
    heading, long, no_description = commands.list_commands(ask_dir)
    assert heading["description"] == "Tidy the folder"
    assert len(long["description"]) <= 200 and long["description"].endswith("…")
    assert no_description["description"] == "From the body"


def test_malformed_files_are_still_listed(ask_dir):
    command(ask_dir, "unclosed", "---\ndescription: Never closed\nargument-hint: [x]\n!`date`\n")
    command(ask_dir, "empty", "")
    command(ask_dir, "only-dashes", "---\n")
    write(ask_dir / ".claude" / "commands" / "binary.md", "").write_bytes(b"\xff\xfe\x00\x01\n")
    entries = {entry["name"]: entry for entry in commands.list_commands(ask_dir)}
    assert set(entries) == {"unclosed", "empty", "only-dashes", "binary"}
    assert entries["unclosed"]["description"] == "Never closed"
    assert entries["unclosed"]["argument_hint"] == "[x]"
    assert entries["unclosed"]["warnings"] == ["shell_lines"]
    assert entries["empty"]["description"] == "" and entries["empty"]["warnings"] == []
    assert isinstance(entries["binary"]["description"], str)


def test_warnings(ask_dir):
    command(ask_dir, "inline-shell", "---\ndescription: d\n---\nStatus: !`git status`\n")
    command(ask_dir, "block-shell", "Text\n\n```!\ngit status\n```\n")
    command(ask_dir, "plain-code", "Text with `code` and a block:\n\n```sh\nls\n```\nWow!\n")
    command(ask_dir, "tools", "---\nallowed-tools: Bash(git status:*)\n---\nBody\n")
    skill(ask_dir, "with-script", "---\ndescription: d\nallowed-tools: Read\n---\n!`date`\n")
    write(ask_dir / ".claude" / "skills" / "with-script" / "scripts" / "run.sh", "echo hi\n")
    skill(ask_dir, "markdown-only", "A skill\n")
    write(ask_dir / ".claude" / "skills" / "markdown-only" / "reference.md", "More\n")
    warnings = {entry["name"]: entry["warnings"] for entry in commands.list_commands(ask_dir)}
    assert warnings == {
        "inline-shell": ["shell_lines"],
        "block-shell": ["shell_lines"],
        "plain-code": [],
        "tools": ["requests_tools"],
        "with-script": ["shell_lines", "requests_tools", "has_scripts"],
        "markdown-only": [],
    }


def test_content_beyond_the_description_is_not_returned(client, auth, ask_dir, write_session):
    command(ask_dir, "private", "---\ndescription: Short\n---\nINNER-INSTRUCTIONS !`run-this`\n")
    response = client.get(f"/sessions/{session_in(ask_dir, write_session)}/commands", headers=auth)
    assert "INNER-INSTRUCTIONS" not in response.text and "run-this" not in response.text


def test_symlinks_leaving_the_folder_are_skipped(ask_dir, tmp_path):
    outside = tmp_path / "outside"
    write(outside / "secret.md", "---\ndescription: outside secret\n---\n")
    write(outside / "skill" / "SKILL.md", "---\ndescription: outside skill\n---\n")
    command(ask_dir, "inside", "Inside\n")
    base = ask_dir / ".claude"
    (base / "commands" / "leak.md").symlink_to(outside / "secret.md")
    (base / "commands" / "linked-folder").symlink_to(outside, target_is_directory=True)
    (base / "skills").mkdir()
    (base / "skills" / "leak").symlink_to(outside / "skill", target_is_directory=True)
    write(base / "skills" / "half" / "x", "")
    (base / "skills" / "half" / "SKILL.md").symlink_to(outside / "skill" / "SKILL.md")
    (base / "commands" / "alias.md").symlink_to(base / "commands" / "inside.md")
    assert names(ask_dir) == [("alias", "command"), ("inside", "command")]
    assert "outside" not in str(commands.list_commands(ask_dir))


def test_symlinked_claude_directory_lists_nothing(ask_dir, tmp_path):
    elsewhere = tmp_path / "elsewhere"
    skill(elsewhere, "foreign", "A foreign skill\n")
    command(elsewhere, "foreign", "A foreign command\n")
    (ask_dir / ".claude").symlink_to(elsewhere / ".claude", target_is_directory=True)
    assert commands.list_commands(ask_dir) == []


def test_home_folder_skills_are_not_listed(client, auth, ask_dir, tmp_path, write_session):
    home = tmp_path / "home"
    skill(home, "x", "---\ndescription: personal skill\n---\n")
    command(home, "personal", "personal command\n")
    skill(ask_dir, "project", "Project skill\n")
    body = get_commands(client, auth, session_in(ask_dir, write_session))
    assert [entry["name"] for entry in body["commands"]] == ["project"]
    assert "personal" not in str(body)


def test_names_must_be_plain(ask_dir):
    for name in ["ok-1", "Also.ok_2", "has space", "-leading", "ünicode", "x" * 65, "y" * 64]:
        command(ask_dir, name, "A command\n")
    skill(ask_dir, "bad name", "A skill\n")
    command(ask_dir, "deep/" + "z" * 62, "Too long once joined\n")
    assert names(ask_dir) == [("Also.ok_2", "command"), ("ok-1", "command"), ("y" * 64, "command")]


def test_at_most_200_entries(ask_dir):
    for index in range(205):
        command(ask_dir, f"c{index:03d}", "A command\n")
    entries = commands.list_commands(ask_dir)
    assert len(entries) == 200
    assert [entries[0]["name"], entries[-1]["name"]] == ["c000", "c199"]


def test_only_the_first_64_kb_of_a_file_are_read(ask_dir):
    padding = "x: " + "p" * 70_000
    command(ask_dir, "big", f"---\n{padding}\ndescription: beyond the limit\n---\n!`late`\n")
    command(ask_dir, "big-body", "First line\n" + "filler\n" * 12_000 + "!`late`\n")
    big, big_body = commands.list_commands(ask_dir)
    assert big["description"] == "" and big["warnings"] == []
    assert big_body["description"] == "First line" and big_body["warnings"] == []


def test_same_name_as_skill_and_command_lists_both(ask_dir):
    skill(ask_dir, "twin", "The skill\n")
    command(ask_dir, "twin", "The command\n")
    assert names(ask_dir) == [("twin", "command"), ("twin", "skill")]


def test_home_folder_as_session_folder_lists_nothing(tmp_path):
    home = tmp_path / "home"
    skill(home, "x", "A personal skill\n")
    assert commands.list_commands(home) == []
