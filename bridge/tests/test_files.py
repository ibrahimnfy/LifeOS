"""File offers and downloads. Synthetic files only."""

import dataclasses
import hashlib
import json

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import files
from lifeos_bridge.app import create_app


@pytest.fixture
def world(settings, ask_dir, tmp_path):
    (ask_dir / "notes").mkdir()
    (ask_dir / "notes" / "slides.pdf").write_bytes(b"%PDF-1.4 " + b"x" * 5000)
    (ask_dir / ".secret").write_text("hidden")
    (ask_dir / ".git").mkdir()
    (ask_dir / ".git" / "config").write_text("hidden too")
    outside = tmp_path / "home" / "outside.txt"
    outside.write_text("not allowed")
    (ask_dir / "link.txt").symlink_to(outside)
    (ask_dir / "big.bin").write_bytes(b"0" * (2 * 1024 * 1024))
    small = dataclasses.replace(settings, max_file_mb=1)
    return TestClient(create_app(small)), ask_dir, tmp_path / "home"


def ask(client, auth, home, answer):
    (home / ".fake-claude-answer").write_text(answer)
    response = client.post("/ask", headers=auth, json={"prompt": "send it"})
    assert response.status_code == 200
    return response.json()


def audit(isolated_config):
    lines = [
        json.loads(line)
        for file in (isolated_config / "audit").glob("*.jsonl")
        for line in file.read_text().splitlines()
    ]
    return [(e["tool"], e["detail"]) for e in lines]


def test_download_requires_the_token(client):
    assert client.get("/files/x", params={"session_id": "s"}).status_code == 401


def test_an_offer_becomes_a_card_and_nothing_is_sent_before_the_request(
    world, auth, isolated_config
):
    client, brain, home = world
    body = ask(
        client, auth, home, "Here are the slides.\n\n[[file: notes/slides.pdf]]\n\nAnything else?"
    )
    assert body["answer"] == "Here are the slides.\n\nAnything else?"
    (card,) = body["files"]
    assert (
        card["name"] == "slides.pdf" and card["type"] == "application/pdf" and card["size"] == 5009
    )
    assert set(card) == {"offer", "name", "type", "size"}  # no path
    assert audit(isolated_config) == [("FileOffer", {"name": "slides.pdf", "size": 5009})]

    response = client.get(
        f"/files/{card['offer']}", headers=auth, params={"session_id": body["session_id"]}
    )
    assert response.status_code == 200
    assert response.content == (brain / "notes" / "slides.pdf").read_bytes()
    assert response.headers["content-length"] == "5009"
    assert audit(isolated_config)[-1] == ("FileDownload", {"name": "slides.pdf", "size": 5009})


def test_files_that_may_not_be_sent_are_refused_with_a_reason(world, auth, isolated_config):
    client, _brain, home = world
    answer = "\n".join(
        f"[[file: {path}]]"
        for path in [
            ".secret",
            ".git/config",
            "link.txt",
            "../outside.txt",
            str(home / "outside.txt"),
            "big.bin",
            "notes",
            "missing.pdf",
            "/etc/hostname",
        ]
    )
    body = ask(client, auth, home, answer)
    assert body["files"] == []
    reasons = {item["name"]: item["reason"] for item in body["files_refused"]}
    assert reasons[".secret"] == "hidden files and folders are not sent"
    assert reasons["config"] == "hidden files and folders are not sent"
    assert reasons["link.txt"] == "outside the allowed folders"  # the link is resolved first
    assert reasons["outside.txt"] == "outside the allowed folders"
    assert reasons["big.bin"] == "larger than the limit of 1 MB"
    assert (
        reasons["notes"] == "not a regular file" and reasons["missing.pdf"] == "not a regular file"
    )
    assert reasons["hostname"] == "outside the allowed folders"
    assert audit(isolated_config) == []


def test_offers_are_bound_to_the_session_and_expire(world, auth, monkeypatch):
    client, _brain, home = world
    body = ask(client, auth, home, "[[file: notes/slides.pdf]]")
    offer = body["files"][0]["offer"]
    wrong = client.get(f"/files/{offer}", headers=auth, params={"session_id": "another"})
    assert wrong.status_code == 404
    assert (
        client.get(
            "/files/unknown", headers=auth, params={"session_id": body["session_id"]}
        ).status_code
        == 404
    )
    monkeypatch.setattr(files.time, "time", lambda: 10**10)
    late = client.get(f"/files/{offer}", headers=auth, params={"session_id": body["session_id"]})
    assert late.status_code == 404


def test_a_file_that_changed_or_vanished_is_not_sent(world, auth):
    client, brain, home = world
    body = ask(client, auth, home, "[[file: notes/slides.pdf]]")
    offer, session = body["files"][0]["offer"], body["session_id"]
    (brain / "notes" / "slides.pdf").write_bytes(b"different size")
    assert (
        client.get(f"/files/{offer}", headers=auth, params={"session_id": session}).status_code
        == 409
    )
    (brain / "notes" / "slides.pdf").unlink()
    assert (
        client.get(f"/files/{offer}", headers=auth, params={"session_id": session}).status_code
        == 409
    )


def test_text_that_only_mentions_the_marker_is_left_alone(world, auth):
    client, _brain, home = world
    body = ask(client, auth, home, "Write [[file: path]] on its own line to offer a file.")
    assert body["files"] == [] and "files_refused" not in body
    assert "[[file: path]]" in body["answer"]


def test_too_many_offers_in_one_answer(world, auth, monkeypatch):
    client, _brain, home = world
    monkeypatch.setattr(files, "MAX_OFFERS_PER_ANSWER", 1)
    body = ask(client, auth, home, "[[file: notes/slides.pdf]]\n[[file: notes/slides.pdf]]")
    assert (
        len(body["files"]) == 1
        and body["files_refused"][0]["reason"] == "too many files in one answer"
    )


def test_chunks_and_digest_for_the_relay(world, auth):
    client, _brain, home = world
    body = ask(client, auth, home, "[[file: notes/slides.pdf]]")
    offer = client.app.state.offers.take(body["files"][0]["offer"], body["session_id"])
    pieces = list(files.chunks(offer, 2000))
    assert [len(p) for p in pieces] == [2000, 2000, 1009]
    assert files.digest(offer) == hashlib.sha256(b"".join(pieces)).hexdigest()


def test_streamed_answers_carry_the_cards_in_the_final_event(world, auth):
    client, _brain, home = world
    (home / ".fake-claude-answer").write_text("[[file: notes/slides.pdf]]")
    body = {"prompt": "x", "stream": True, "folder": str(_brain)}
    with client.stream("POST", "/ask", headers=auth, json=body) as response:
        text = response.read().decode()
    done = json.loads(text.split("event: done\ndata: ")[1].split("\n")[0])
    assert done["files"][0]["name"] == "slides.pdf" and done["answer"] == ""


def test_every_run_gets_the_standing_instructions(world, auth, ask_dir):
    client, _brain, home = world
    ask(client, auth, home, "ok")
    args = json.loads((ask_dir / ".fake-claude-calls.jsonl").read_text().splitlines()[-1])["args"]
    text = args[args.index("--append-system-prompt") + 1]
    assert "[[file: path]]" in text and "lettered list" in text
