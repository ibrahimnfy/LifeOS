"""Session history in pages. All transcripts here are synthetic."""

import hashlib
import json
import uuid

import pytest

from conftest import user_line
from lifeos_bridge import history


def snapshot(folder):
    return {
        str(p): hashlib.sha256(p.read_bytes()).hexdigest() + str(p.stat().st_mtime_ns)
        for p in sorted(folder.rglob("*"))
        if p.is_file()
    }


def owner(cwd, text, **extra):
    fields = {"uuid": str(uuid.uuid4()), "timestamp": "2027-01-15T08:00:00.000Z"}
    return user_line(cwd, text, **{"isSidechain": False, **fields, **extra})


def assistant(*blocks, **extra):
    return {
        "type": "assistant",
        "isSidechain": False,
        "message": {"role": "assistant", "content": list(blocks)},
        "timestamp": "2027-01-15T08:00:05.000Z",
        **extra,
    }


def text(value):
    return {"type": "text", "text": value}


def tool_use(name):
    return {"type": "tool_use", "id": "t1", "name": name, "input": {"file_path": "/secret/path"}}


def tool_result(cwd, output="tool output"):
    blocks = [{"type": "tool_result", "tool_use_id": "t1", "content": output}]
    return user_line(cwd, blocks, isSidechain=False, uuid=str(uuid.uuid4()))


def conversation(cwd, count):
    lines = []
    for index in range(count):
        lines += [owner(cwd, f"question {index}"), assistant(text(f"answer {index}"))]
    return lines


def get_history(client, auth, session_id, **params):
    response = client.get(f"/sessions/{session_id}/history", headers=auth, params=params)
    assert response.status_code == 200, response.text
    return response.json()


def users(body):
    return [exchange["user"] for exchange in body["exchanges"]]


def test_requires_token(client):
    assert client.get(f"/sessions/{uuid.uuid4()}/history").status_code == 401


def test_unknown_session_is_404(client, auth):
    for session_id in [str(uuid.uuid4()), "not-an-id"]:
        response = client.get(f"/sessions/{session_id}/history", headers=auth)
        assert response.status_code == 404
        assert response.json()["error"] == "session_not_found"


def test_session_outside_allowed_folders_is_404(client, auth, tmp_path, write_session):
    outside = tmp_path / "elsewhere"
    outside.mkdir()
    session_id = write_session(outside, conversation(outside, 2))
    response = client.get(f"/sessions/{session_id}/history", headers=auth)
    assert response.status_code == 404
    assert "question" not in response.text


def test_one_exchange_has_the_documented_shape(client, auth, ask_dir, write_session):
    first = owner(ask_dir, "What is in this folder?")
    session_id = write_session(ask_dir, [first, assistant(text("Two files."))])
    assert get_history(client, auth, session_id) == {
        "exchanges": [
            {
                "id": first["uuid"],
                "at": "2027-01-15T08:00:00.000Z",
                "user": "What is in this folder?",
                "assistant": "Two files.",
                "tools": [],
                "complete": True,
                "truncated": False,
            }
        ],
        "older": None,
        "total_exchanges_known": None,
    }


def test_pages_go_back_with_cursors_oldest_first(client, auth, ask_dir, write_session):
    session_id = write_session(ask_dir, conversation(ask_dir, 10))
    page = get_history(client, auth, session_id)
    assert users(page) == [f"question {i}" for i in (6, 7, 8, 9)]
    assert [e["assistant"] for e in page["exchanges"]] == [f"answer {i}" for i in (6, 7, 8, 9)]
    seen = []
    while page["older"]:
        seen = users(page) + seen
        page = get_history(client, auth, session_id, before=page["older"])
    seen = users(page) + seen
    assert seen == [f"question {i}" for i in range(10)]
    assert users(page) == ["question 0", "question 1"]


def test_older_is_null_when_the_page_reaches_the_start(client, auth, ask_dir, write_session):
    session_id = write_session(ask_dir, conversation(ask_dir, 4))
    page = get_history(client, auth, session_id)
    assert len(page["exchanges"]) == 4 and page["older"] is None
    page = get_history(client, auth, session_id, limit=3)
    assert users(page) == ["question 1", "question 2", "question 3"]
    last = get_history(client, auth, session_id, limit=3, before=page["older"])
    assert users(last) == ["question 0"] and last["older"] is None


def test_limit_is_clamped(client, auth, ask_dir, write_session):
    session_id = write_session(ask_dir, conversation(ask_dir, 25))
    assert len(get_history(client, auth, session_id, limit=0)["exchanges"]) == 1
    assert len(get_history(client, auth, session_id, limit=-5)["exchanges"]) == 1
    assert len(get_history(client, auth, session_id, limit=500)["exchanges"]) == 20
    assert len(get_history(client, auth, session_id)["exchanges"]) == 4


def test_unknown_cursor_is_400(client, auth, ask_dir, write_session):
    session_id = write_session(ask_dir, conversation(ask_dir, 3))
    # p0 is the first owner message: a valid cursor with nothing before it.
    assert get_history(client, auth, session_id, before="p0")["exchanges"] == []
    for cursor in ["nonsense", "p", "p-1", "p3", "p999999999", str(uuid.uuid4())]:
        response = client.get(
            f"/sessions/{session_id}/history", headers=auth, params={"before": cursor}
        )
        assert response.status_code == 400, cursor
        assert response.json()["error"] == "bad_request"


def test_cursor_must_point_at_an_owner_message(client, auth, ask_dir, write_session):
    lines = conversation(ask_dir, 2)
    session_id = write_session(ask_dir, lines)
    answer_offset = len(json.dumps(lines[0])) + 1  # start of the first assistant line
    response = client.get(
        f"/sessions/{session_id}/history", headers=auth, params={"before": f"p{answer_offset}"}
    )
    assert response.status_code == 400


def test_tools_are_counted_by_name_and_never_replayed(client, auth, ask_dir, write_session):
    lines = [
        owner(ask_dir, "Summarise the notes"),
        assistant({"type": "thinking", "thinking": "private reasoning"}),
        assistant(text("Looking."), tool_use("Read")),
        tool_result(ask_dir, "file body one"),
        assistant(tool_use("Read")),
        tool_result(ask_dir, "file body two"),
        assistant(tool_use("Grep"), tool_use("Read")),
        tool_result(ask_dir),
        assistant(text("Here is the summary.")),
    ]
    session_id = write_session(ask_dir, lines)
    response = client.get(f"/sessions/{session_id}/history", headers=auth)
    (exchange,) = response.json()["exchanges"]
    assert exchange["user"] == "Summarise the notes"
    assert exchange["assistant"] == "Looking.\n\nHere is the summary."
    assert exchange["tools"] == [{"name": "Read", "count": 3}, {"name": "Grep", "count": 1}]
    assert exchange["complete"] is True
    for hidden in ["private reasoning", "file body", "tool output", "/secret/path"]:
        assert hidden not in response.text


def test_sidechain_meta_and_injected_entries_are_ignored(client, auth, ask_dir, write_session):
    lines = [
        owner(ask_dir, "real question"),
        owner(ask_dir, "subagent prompt", isSidechain=True),
        assistant(text("subagent answer"), tool_use("Bash"), isSidechain=True),
        owner(ask_dir, "meta note", isMeta=True),
        owner(ask_dir, "summary of earlier talk", isCompactSummary=True),
        owner(ask_dir, "<system-reminder>injected</system-reminder>"),
        owner(ask_dir, "<command-name>/clear</command-name>"),
        owner(ask_dir, [text("<system-reminder>injected</system-reminder>")]),
        owner(ask_dir, "   "),
        assistant(text("real answer")),
        owner(ask_dir, [text("<system-reminder>injected</system-reminder>"), text("second")]),
        owner(ask_dir, "<b>markup</b> typed by the owner"),
    ]
    body = get_history(client, auth, write_session(ask_dir, lines))
    assert users(body) == ["real question", "second", "<b>markup</b> typed by the owner"]
    assert body["exchanges"][0]["assistant"] == "real answer"
    assert body["exchanges"][0]["tools"] == []


def test_garbage_lines_are_skipped(client, auth, ask_dir, write_session):
    lines = [
        owner(ask_dir, "first"),
        "this is not json",
        '{"type": "user", "message": ',
        "[1, 2, 3]",
        '"just a string"',
        "",
        {"type": "user", "message": None},
        {"type": "user", "message": {"content": 7}},
        {"type": "assistant", "message": {"content": [None, 3, {"type": "text", "text": 9}]}},
        {"type": "assistant", "message": {"content": [{"type": "tool_use", "name": None}]}},
        {"type": "assistant"},
        {"type": "from-a-future-version", "payload": {"x": 1}},
        assistant(text("answer")),
        {"uuid": 5, "timestamp": 7, **user_line(ask_dir, "second")},
    ]
    body = get_history(client, auth, write_session(ask_dir, lines))
    assert users(body) == ["first", "second"]
    first, second = body["exchanges"]
    assert first["assistant"] == "answer"
    assert first["tools"] == [{"name": "unknown", "count": 1}]
    assert second["at"] is None and second["id"].startswith("line-")


def test_unfinished_last_exchange_is_not_complete(client, auth, ask_dir, write_session):
    lines = [
        *conversation(ask_dir, 1),
        owner(ask_dir, "still running"),
        assistant(text("Working on it."), tool_use("Read")),
        tool_result(ask_dir),
    ]
    body = get_history(client, auth, write_session(ask_dir, lines))
    assert [e["complete"] for e in body["exchanges"]] == [True, False]
    assert body["exchanges"][1]["assistant"] == "Working on it."

    unanswered = write_session(ask_dir, [owner(ask_dir, "no answer yet")])
    (exchange,) = get_history(client, auth, unanswered)["exchanges"]
    assert (exchange["assistant"], exchange["complete"]) == ("", False)


def test_text_announcing_a_tool_call_is_not_complete(client, auth, ask_dir, write_session):
    announcing = assistant(text("Let me look."))
    announcing["message"]["stop_reason"] = "tool_use"
    body = get_history(client, auth, write_session(ask_dir, [owner(ask_dir, "q"), announcing]))
    assert body["exchanges"][0]["complete"] is False


def test_long_texts_are_cut_and_flagged(client, auth, ask_dir, write_session):
    lines = [
        owner(ask_dir, "u" * 70_000),
        assistant(text("short")),
        owner(ask_dir, "short"),
        *[assistant(text("a" * 25_000)) for _ in range(4)],
        owner(ask_dir, "exact"),
        assistant(text("b" * 60_000)),
    ]
    body = get_history(client, auth, write_session(ask_dir, lines))
    long_user, long_answer, exact = body["exchanges"]
    assert len(long_user["user"]) == 60_000 and long_user["truncated"] is True
    assert len(long_answer["assistant"]) == 60_000 and long_answer["truncated"] is True
    assert len(exact["assistant"]) == 60_000 and exact["truncated"] is False


def test_claude_session_storage_is_not_written(client, auth, ask_dir, tmp_path, write_session):
    session_id = write_session(ask_dir, conversation(ask_dir, 6))
    storage = tmp_path / "home" / ".claude"
    before = snapshot(storage)
    page = get_history(client, auth, session_id, limit=2)
    get_history(client, auth, session_id, limit=2, before=page["older"])
    client.get(f"/sessions/{session_id}/history", headers=auth, params={"before": "bad"})
    assert snapshot(storage) == before


@pytest.fixture
def tiny_windows(monkeypatch):
    """Make the reader's windows a few hundred bytes so that tests cross them."""
    monkeypatch.setattr(history, "FIRST_WINDOW_BYTES", 256)
    monkeypatch.setattr(history, "MAX_WINDOW_BYTES", 1024)
    monkeypatch.setattr(history, "HARD_WINDOW_BYTES", 16 * 1024)


def test_paging_is_the_same_with_tiny_windows(client, auth, ask_dir, write_session, tiny_windows):
    session_id = write_session(ask_dir, conversation(ask_dir, 30))
    seen, page = [], get_history(client, auth, session_id, limit=3)
    while True:
        assert len(page["exchanges"]) <= 3
        assert all(
            e["assistant"] == e["user"].replace("question", "answer") for e in page["exchanges"]
        )
        seen = users(page) + seen
        if not page["older"]:
            break
        page = get_history(client, auth, session_id, limit=3, before=page["older"])
    assert seen == [f"question {i}" for i in range(30)]


def test_an_exchange_larger_than_the_window_is_read_whole(
    client, auth, ask_dir, write_session, tiny_windows
):
    lines = [
        *conversation(ask_dir, 2),
        owner(ask_dir, "big one"),
        *[assistant(text(f"part {i} " + "x" * 300)) for i in range(8)],
        assistant(text("the end")),
    ]
    body = get_history(client, auth, write_session(ask_dir, lines), limit=1)
    (exchange,) = body["exchanges"]
    assert exchange["user"] == "big one"
    assert exchange["assistant"].startswith("part 0 ") and exchange["assistant"].endswith("the end")
    assert exchange["complete"] is True
    assert body["older"] is not None


def test_size_guard_still_offers_a_cursor(client, auth, ask_dir, write_session, monkeypatch):
    for name in ("FIRST_WINDOW_BYTES", "MAX_WINDOW_BYTES", "HARD_WINDOW_BYTES"):
        monkeypatch.setattr(history, name, 2048)
    session_id = write_session(ask_dir, conversation(ask_dir, 40))
    page = get_history(client, auth, session_id, limit=20)
    assert 0 < len(page["exchanges"]) < 20  # the guard stopped the reading early
    assert page["older"] is not None
    seen = users(page)
    while page["older"]:
        page = get_history(client, auth, session_id, limit=20, before=page["older"])
        seen = users(page) + seen
    assert seen == [f"question {i}" for i in range(40)]


def test_unreadable_transcript_gives_an_empty_page(tmp_path):
    assert history.read_page(tmp_path / "missing.jsonl") == {
        "exchanges": [],
        "older": None,
        "total_exchanges_known": None,
    }


def test_injected_entries_after_the_answer_keep_it_complete(client, auth, ask_dir, write_session):
    lines = [
        *conversation(ask_dir, 1),
        owner(ask_dir, "<command-name>/clear</command-name>"),
        owner(ask_dir, "<local-command-stdout>done</local-command-stdout>"),
        owner(ask_dir, "<task_notification kind='x'>done</task_notification>"),
        owner(ask_dir, "a note from the program", isMeta=True),
    ]
    (exchange,) = get_history(client, auth, write_session(ask_dir, lines))["exchanges"]
    assert (exchange["user"], exchange["complete"]) == ("question 0", True)
