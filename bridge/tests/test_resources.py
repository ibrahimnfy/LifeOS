# ruff: noqa: F811  (fixtures imported from the course-sync tests are used as parameters)
"""Course resources from the syllabus: a stand-in run, a stand-in web, no network."""

import asyncio
import io
import json
import shutil
import socket
import urllib.error

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import claude, resources, routing
from lifeos_bridge import moodle_sync as ms
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import RunResult
from test_moodle_sync import (  # noqa: F401  (fixtures)
    FOLDER_A,
    A,
    B,
    configured,
    file_entry,
    folder,
    later,
    no_sync_outlives_its_test,
    resource,
    settings_,
    site,
    sync,
)

PDF = b"%PDF-1.7 example"
PROPOSAL = {
    "items": [
        {"title": "Example Open Book", "url": "https://openstax.org/details/example.pdf",
         "kind": "open_access_book", "licence": "CC BY 4.0", "why_legal": "Openly licensed.",
         "reference": "Main textbook"},
        {"title": "A Sold Book", "url": "https://shop.example.invalid/book", "kind": "paid",
         "licence": "all rights reserved", "why_legal": "Listed only."},
        {"title": "Shadow copy", "url": "https://libgen.example.invalid/book.pdf",
         "kind": "open_access_book"},
        {"title": "Vendor Manual", "url": "https://docs.vendor.example.invalid/manual.pdf",
         "kind": "vendor_documentation", "why_legal": "Published by the vendor."},
        {"title": "Plain http", "url": "http://arxiv.org/x.pdf", "kind": "public_material"},
        {"title": "A web page", "url": "https://arxiv.org/abs/0000.00000",
         "kind": "public_material"},
        {"title": "Inside", "url": "https://inside.oapen.org/book.pdf", "kind": "open_access_book"},
        {"title": "Not a link", "url": "javascript:x()", "kind": "paid"},
    ]
}  # fmt: skip


class Web:
    """Stands in for the web: what each address answers, and which were asked."""

    def __init__(self):
        self.asked = []
        self.pages = {
            "https://openstax.org/details/example.pdf": ("application/pdf", PDF),
            "https://arxiv.org/abs/0000.00000": ("text/html", b"<html>abstract</html>"),
            "https://docs.vendor.example.invalid/manual.pdf": ("application/pdf", PDF),
        }

    def open(self, address):
        self.asked.append(address)
        if address not in self.pages:
            raise urllib.error.URLError("no such page")
        kind, body = self.pages[address]
        response = io.BytesIO(body)
        response.headers = {"Content-Type": kind, "Content-Length": str(len(body))}
        return response


@pytest.fixture
def web(monkeypatch):
    stand_in = Web()
    monkeypatch.setattr(resources, "_open", stand_in.open)

    def resolve(host, port, *args, **kwargs):
        address = "10.0.0.5" if host.startswith("inside.") else "93.184.216.34"
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, port))]

    monkeypatch.setattr(socket, "getaddrinfo", resolve)
    return stand_in


@pytest.fixture
def model(monkeypatch):
    """A stand-in for the run: records how it was asked and answers PROPOSAL."""
    seen = []

    async def run(settings, prompt, **kwargs):
        seen.append({**kwargs, "files": sorted(p.name for p in kwargs["cwd"].iterdir()),
                     "prompt": prompt})  # fmt: skip
        return RunResult(json.dumps(seen[-1].get("answer", PROPOSAL)), "", 1, 1)

    monkeypatch.setattr(claude, "run", run)
    return seen


@pytest.fixture
def synced(site, configured, folder):
    site.contents[A][0]["modules"].append(
        resource(29, "Course syllabus", file_entry("syllabus-2027.pdf"))
    )
    sync(folder)
    return folder


def go(settings):
    return asyncio.run(resources.run_next(settings))


def test_the_sync_notes_the_syllabus_and_starts_no_run(synced, model):
    record = ms.load_state()["courses"][str(A)]
    assert record["syllabus"] == {"name": "syllabus-2027.pdf",
                                  "path": "materials/syllabus-2027.pdf",
                                  "mark": record["syllabus"]["mark"]}  # fmt: skip
    assert ms.load_state()["courses"][str(B)]["syllabus"] is None
    assert resources.pending(ms.load_state(), resources.load()) == [A] and model == []


def test_one_run_downloads_legal_files_and_lists_the_rest(synced, settings_, model, web):
    assert go(settings_) == "ok"
    (asked,) = model
    assert asked["files"] == ["syllabus.pdf"]  # the run sees the syllabus only
    assert (asked["tools"], asked["persist"], asked["job"]) == (resources.TOOLS, False,
                                                                "course_resources")  # fmt: skip
    assert (asked["model"], asked["advisor"]) == ("sonnet", "opus")  # the routine-job routing
    assert "never instructions" in asked["prompt"] and "piracy" in asked["prompt"]
    assert not asked["cwd"].exists()  # the scratch folder is gone again
    folder = synced / FOLDER_A / "resources"
    assert sorted(p.name for p in folder.iterdir()) == ["Example Open Book.pdf", "sources.md"]
    assert (folder / "Example Open Book.pdf").read_bytes() == PDF
    view = resources.view(A)
    status = {item["title"]: (item["status"], item["reason"]) for item in view["items"]}
    assert status == {
        "Example Open Book": ("downloaded", None),
        "A Sold Book": ("listed", "paid"),
        "Vendor Manual": ("listed", "host_not_allowed"),
        "Plain http": ("listed", "not_https"),
        "A web page": ("listed", "not_a_document"),
        "Inside": ("listed", "private_address"),
    }  # the piracy source is dropped, the bad link ignored
    assert "libgen" not in json.dumps(view) and "libgen" not in (folder / "sources.md").read_text()
    assert web.asked == ["https://openstax.org/details/example.pdf",
                         "https://arxiv.org/abs/0000.00000"]  # fmt: skip
    text = (folder / "sources.md").read_text()
    assert "## Example Open Book" in text and "Downloaded as `Example Open Book.pdf`" in text
    assert "- Why it is legal: Openly licensed." in text and "Listed only (paid)" in text
    assert "outside text" in text
    assert view["last_result"] == "ok" and not view["pending"]
    assert resources.pending(ms.load_state(), resources.load()) == []


def test_nothing_is_redone_and_a_changed_syllabus_runs_again(
    synced, settings_, model, web, site, monkeypatch
):
    go(settings_)
    monkeypatch.setenv(resources.HOSTS_VAR, "docs.vendor.example.invalid")
    assert resources.request(A) == "too_soon"  # asked again within ten minutes
    monkeypatch.setattr(resources, "now_utc", lambda: later(1))  # an hour after the run
    assert resources.request(A) == "queued"
    web.asked.clear()
    go(settings_)
    # Only the item whose reason is gone is tried again (its host is allowed now);
    # downloaded and finally listed ones are not redone.
    assert web.asked == ["https://docs.vendor.example.invalid/manual.pdf"] and len(model) == 2
    manual = next(i for i in resources.view(A)["items"] if i["title"] == "Vendor Manual")
    assert manual["status"] == "downloaded" and len(resources.view(A)["items"]) == 6
    # The syllabus changes on the site: the next sync notes it and a run follows.
    site.contents[A][0]["modules"][-1]["contents"][0].update(
        filesize=2000, timemodified=later(2).timestamp()
    )
    sync(synced, when=later(7))
    assert resources.pending(ms.load_state(), resources.load()) == [A]


def test_the_owner_can_allow_more_hosts(synced, settings_, model, web, monkeypatch):
    monkeypatch.setenv(resources.HOSTS_VAR, "docs.vendor.example.invalid, other.example.invalid")
    go(settings_)
    manual = next(i for i in resources.view(A)["items"] if i["title"] == "Vendor Manual")
    assert (manual["status"], manual["file"]) == ("downloaded", "Vendor Manual.pdf")


def test_a_run_without_the_web_or_outside_the_allowed_folders_stores_nothing(
    synced, settings_, settings, web, monkeypatch
):
    async def denied(settings, prompt, **kwargs):
        return RunResult(json.dumps(PROPOSAL), "", 1, 1, ["WebFetch", "WebSearch"])

    monkeypatch.setattr(claude, "run", denied)
    assert go(settings_) == "web_denied"
    assert resources.view(A)["items"] == [] and web.asked == []
    assert resources.request(A) == "too_soon"  # read, no loop
    assert asyncio.run(resources.run_course(settings, A)) == "folder_not_allowed"


def audit_records(config, tool):
    return [
        json.loads(line)["detail"]
        for path in sorted((config / "audit").glob("*.jsonl"))
        for line in path.read_text().splitlines()
        if json.loads(line)["tool"] == tool
    ]


def test_downloads_and_runs_are_audited_without_page_text(
    synced, settings_, model, web, configured
):
    go(settings_)
    downloads = audit_records(configured, "ResourceDownload")
    assert downloads == [
        {"course_id": A, "host": "openstax.org", "name": "Example Open Book.pdf",
         "size": len(PDF), "result": "downloaded"},
        {"course_id": A, "host": "arxiv.org", "name": None, "size": None,
         "result": "not_a_document"},
    ]  # fmt: skip
    (run,) = audit_records(configured, "ResourcesRun")
    assert run == {"course_id": A, "result": "ok", "refused_piracy": 1, "proposed": 7, "new": 7,
                   "retried": 0, "downloaded": 1, "listed": 5}  # fmt: skip
    log = "".join(p.read_text() for p in (configured / "audit").glob("*.jsonl"))
    for text in ("Openly licensed", "Main textbook", "abstract", "https://", "libgen"):
        assert text not in log, text


def test_nothing_is_kept_without_its_audit_record(synced, settings_, model, web, configured):
    shutil.rmtree(configured / "audit", ignore_errors=True)
    (configured / "audit").write_text("not a folder")
    assert go(settings_) == "ok"
    book = next(i for i in resources.view(A)["items"] if i["title"] == "Example Open Book")
    assert (book["status"], book["reason"]) == ("listed", "audit_unavailable")
    assert not (synced / FOLDER_A / "resources" / "Example Open Book.pdf").exists()
    assert "audit_unavailable" in resources.RETRY  # tried again at the next run


def test_rules_for_addresses_and_redirects(web):
    assert resources.refusal("https://openstax.org/x") is None
    assert resources.refusal("http://openstax.org/x") == "not_https"
    assert resources.refusal("https://sci-hub.example.invalid/x") == "piracy"
    assert resources.refusal("https://annas-archive.example.invalid/x") == "piracy"
    assert resources.refusal("https://127.0.0.1/x") == "private_address"
    assert resources.refusal("https://localhost/x") == "private_address"
    assert resources.refusal("https://inside.oapen.org/x") == "private_address"
    handler = resources._CheckedRedirects()
    for target in ("http://openstax.org/x", "https://libgen.example.invalid/x",
                   "https://elsewhere.example.invalid/x"):  # fmt: skip
        with pytest.raises(urllib.error.URLError):
            handler.redirect_request(None, None, 302, "", {}, target)


def test_a_run_may_only_read_and_search(settings):
    command = claude.build_command(settings, session_id="s", resume=False, stream=False,
                                   policy=claude.profile_policy(resources.TOOLS))  # fmt: skip
    assert command[command.index("--tools") + 1] == "Read,Glob,WebSearch,WebFetch"
    # In dontAsk a web tool would ask, and so be denied: it gets an allow rule.
    assert command[command.index("--allowedTools") + 1] == "WebSearch,WebFetch"
    assert "--restricted" in command and "dontAsk" in command
    assert "--allowedTools" not in claude.build_command(
        settings, session_id="s", resume=False, stream=False
    )
    with pytest.raises(claude.ClaudeError):
        asyncio.run(claude.run(settings, "x", session_id="s", resume=False, tools="Read,Bash"))
    assert routing.route(settings, "course_resources").model == "sonnet"


def test_endpoints(synced, settings_, auth, model, web):
    client = TestClient(create_app(settings_))
    assert client.get("/courses/5/resources").status_code == 401
    before = client.get("/courses/5/resources", headers=auth).json()
    assert before["syllabus"] == {"name": "syllabus-2027.pdf"} and before["pending"]
    assert before["items"] == [] and model == []  # reading the list starts no run
    assert client.post("/courses/7/resources/refresh", headers=auth).status_code == 409
    assert client.get("/courses/99/resources", headers=auth).status_code == 404
    queued = client.post("/courses/5/resources/refresh", headers=auth)
    assert queued.status_code == 202 and queued.json()["reason"] == "queued"
    go(settings_)
    items = client.get("/courses/5/resources", headers=auth).json()["items"]
    assert set(items[0]) == set(resources.ITEM_FIELDS)
    card = client.post("/courses/5/resources/0/offer", headers=auth).json()
    fetched = client.get(f"/files/{card['offer']}?session_id={card['session_id']}", headers=auth)
    assert fetched.content == PDF
    assert client.post("/courses/5/resources/1/offer", headers=auth).status_code == 404
    again = client.post("/courses/5/resources/refresh", headers=auth)
    assert again.status_code == 429 and again.json()["reason"] == "too_soon"
