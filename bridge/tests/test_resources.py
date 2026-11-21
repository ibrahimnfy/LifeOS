# ruff: noqa: F811  (fixtures imported from the course-sync tests are used as parameters)
"""Course resources from the syllabus: a stand-in run, a stand-in web, no network."""

import asyncio
import http.client
import io
import json
import shutil
import socket
import urllib.error
import urllib.request
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import claude, resources, routing
from lifeos_bridge import moodle_sync as ms
from lifeos_bridge.app import create_app
from lifeos_bridge.claude import RunResult
from test_moodle_sync import (  # noqa: F401  (fixtures)
    FOLDER_A,
    NOW,
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
# The first entry of an EPUB: "mimetype", stored (method 0), no extra field.
EPUB = (
    b"PK\x03\x04\x14\x00\x00\x00\x00\x00" + bytes(16) + b"\x08\x00\x00\x00"
    + b"mimetype" + b"application/epub+zip" + b"PK\x03\x04 the rest of the book"
)  # fmt: skip
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
    """Stands in for the web: what each address answers, and which were asked.

    A page is (content type, body) or (content type, body, reported length or None).
    """

    def __init__(self):
        self.asked = []
        self.read = 0  # bytes the bridge read from bodies
        self.pages = {
            "https://openstax.org/details/example.pdf": ("application/pdf", PDF),
            "https://arxiv.org/abs/0000.00000": ("text/html", b"<html>abstract</html>"),
            "https://docs.vendor.example.invalid/manual.pdf": ("application/pdf", PDF),
        }

    def open(self, address):
        self.asked.append(address)
        if address not in self.pages:
            raise urllib.error.URLError("no such page")
        kind, body, *reported = self.pages[address]
        length = reported[0] if reported else len(body)
        web = self

        class Body(io.BytesIO):
            def read(self, size=-1):
                chunk = super().read(size)
                web.read += len(chunk)
                return chunk

        response = Body(body)
        response.headers = {"Content-Type": kind}
        if length is not None:
            response.headers["Content-Length"] = str(length)
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
def named(configured):
    """The owner has answered #161: there are no existing resource folders."""
    path = configured / resources.FOLDERS_FILE
    path.write_text("")
    return path


@pytest.fixture
def sent(monkeypatch):
    """Notifications the bridge sends (none leave the test)."""
    calls = []
    monkeypatch.setattr(
        resources.notifications, "send", lambda *args, **kwargs: calls.append((args, kwargs))
    )
    return calls


@pytest.fixture
def synced(site, configured, folder):
    site.contents[A][0]["modules"].append(
        resource(29, "Course syllabus", file_entry("syllabus-2027.pdf"))
    )
    sync(folder)
    return folder


def go(settings):
    return asyncio.run(resources.run_next(settings))


def statuses():
    return {i["title"]: (i["status"], i["reason"]) for i in resources.view(A)["items"]}


def test_the_sync_notes_the_syllabus_and_starts_no_run(synced, model):
    record = ms.load_state()["courses"][str(A)]
    assert record["syllabus"] == {"name": "syllabus-2027.pdf",
                                  "path": "materials/syllabus-2027.pdf",
                                  "mark": record["syllabus"]["mark"]}  # fmt: skip
    assert ms.load_state()["courses"][str(B)]["syllabus"] is None
    assert resources.pending(ms.load_state(), resources.load()) == [A] and model == []


def test_one_run_downloads_legal_files_and_lists_the_rest(synced, settings_, model, web, named):
    assert go(settings_) == "ok"
    (asked,) = model
    assert asked["files"] == ["syllabus.pdf"]  # the run sees the syllabus only
    assert (asked["tools"], asked["persist"], asked["job"]) == (resources.TOOLS, False,
                                                                "course_resources")  # fmt: skip
    assert (asked["model"], asked["advisor"]) == ("sonnet", "opus")  # the routine-job routing
    assert "never instructions" in asked["prompt"] and "piracy" in asked["prompt"]
    assert not asked["cwd"].exists()  # the scratch folder is gone again
    folder = synced / FOLDER_A / "resources"
    assert sorted(p.name for p in folder.iterdir()) == [
        "Example Open Book.pdf", "Vendor Manual.pdf", "sources.md"]  # fmt: skip
    assert (folder / "Example Open Book.pdf").read_bytes() == PDF
    view = resources.view(A)
    assert statuses() == {
        "Example Open Book": ("downloaded", None),
        "A Sold Book": ("listed", "paid"),
        "Vendor Manual": ("downloaded", None),  # any public HTTPS host (#160)
        "Plain http": ("listed", "not_https"),
        "A web page": ("listed", "not_a_document"),
        "Inside": ("listed", "private_address"),
    }  # the piracy source is dropped, the bad link ignored
    assert view["folders_named"] is True
    assert "libgen" not in json.dumps(view) and "libgen" not in (folder / "sources.md").read_text()
    assert web.asked == ["https://openstax.org/details/example.pdf",
                         "https://docs.vendor.example.invalid/manual.pdf",
                         "https://arxiv.org/abs/0000.00000"]  # fmt: skip
    text = (folder / "sources.md").read_text()
    assert "## Example Open Book" in text and "Downloaded as `Example Open Book.pdf`" in text
    assert "- Why it is legal: Openly licensed." in text and "Listed only (paid)" in text
    assert "outside text" in text
    assert view["last_result"] == "ok" and not view["pending"]
    assert resources.pending(ms.load_state(), resources.load()) == []


def test_nothing_is_redone_and_a_changed_syllabus_runs_again(
    synced, settings_, model, web, site, monkeypatch, named
):
    manual = web.pages.pop("https://docs.vendor.example.invalid/manual.pdf")
    go(settings_)  # the manual's host does not answer this time
    web.pages["https://docs.vendor.example.invalid/manual.pdf"] = manual
    assert statuses()["Vendor Manual"] == ("listed", "download_failed")
    assert resources.request(A) == "too_soon"  # asked again within ten minutes
    monkeypatch.setattr(resources, "now_utc", lambda: later(1))  # an hour after the run
    assert resources.request(A) == "queued"
    web.asked.clear()
    go(settings_)
    # Only the item whose download failed is tried again; downloaded and finally
    # listed ones are not redone.
    assert web.asked == ["https://docs.vendor.example.invalid/manual.pdf"] and len(model) == 2
    assert statuses()["Vendor Manual"] == ("downloaded", None) and len(statuses()) == 6
    # The syllabus changes on the site: the next sync notes it and a run follows.
    site.contents[A][0]["modules"][-1]["contents"][0].update(
        filesize=2000, timemodified=later(2).timestamp()
    )
    sync(synced, when=later(7))
    assert resources.pending(ms.load_state(), resources.load()) == [A]


def test_nothing_is_downloaded_until_the_folders_are_named(
    synced, settings_, model, web, configured
):
    assert go(settings_) == "ok"
    assert web.asked == []  # no address was even contacted
    view = resources.view(A)
    assert statuses()["Example Open Book"] == ("listed", "folders_not_named")
    assert statuses()["Vendor Manual"] == ("listed", "folders_not_named")
    assert statuses()["A Sold Book"] == ("listed", "paid")  # the legal rules come first
    assert view["folders_named"] is False and not view["pending"]
    text = (synced / FOLDER_A / "resources" / "sources.md").read_text()
    assert "Listed only (folders not named)" in text
    assert not resources.due()  # waiting for the owner, not looping
    # The owner answers #161 with no folders: one more run, without the model.
    (configured / resources.FOLDERS_FILE).write_text("# none\n\n")
    assert resources.pending(ms.load_state(), resources.load()) == [A]
    assert go(settings_) == "ok" and len(model) == 1
    assert statuses()["Example Open Book"] == ("downloaded", None)
    assert statuses()["Vendor Manual"] == ("downloaded", None)
    assert resources.pending(ms.load_state(), resources.load()) == []


def test_a_failed_resume_does_not_loop(synced, settings_, model, web, configured, monkeypatch):
    go(settings_)
    (configured / resources.FOLDERS_FILE).write_text("")
    monkeypatch.setattr(ms, "download_dir", lambda: None)  # the course folder went away
    assert go(settings_) == "folder_missing"
    assert resources.pending(ms.load_state(), resources.load()) == [] and len(model) == 1


def test_the_folders_file(configured):
    assert resources.named_folders() is None  # missing: not named yet
    (configured / resources.FOLDERS_FILE).write_bytes(b"\xff\xfe not text")
    assert resources.named_folders() is None  # unreadable: not named either
    (configured / resources.FOLDERS_FILE).write_text("/srv/books\n  relative/path\n# /x\n~/b\n")
    folders = resources.named_folders()
    assert folders[0].as_posix() == "/srv/books" and len(folders) == 2  # relative is ignored
    assert folders[1].is_absolute()
    (configured / resources.FOLDERS_FILE).write_text("")
    assert resources.named_folders() == []  # empty: there are none


# ---- what the owner already has ---------------------------------------------------------


def test_the_matching_rule():
    assert resources.normalised("Calculus, Vol. 1 (2nd éd.)") == "calculus vol 1 2nd ed"
    assert resources.normalised("İŞLETİM Sistemleri_ders-notu") == "isletim sistemleri ders notu"
    local = resources.LocalCopies()
    local.add("Introduction_to-Algorithms 3rd ed.PDF", 100)
    local.add("Linear Algebra.epub", 200)
    local.add("intro-signals-notes.pdf", None)
    local.add("download.pdf", None)
    assert local.has_name("introduction to algorithms", "https://a.example.invalid/x")
    assert local.has_name("Linear Algebra", "https://a.example.invalid/x")  # equal
    # Inside one another only with three words or more on the shorter side.
    assert not local.has_name("Linear Algebra Done Right", "https://a.example.invalid/x")
    assert not local.has_name("Algebra", "https://a.example.invalid/x")
    # The address's own file name, when it says something.
    assert local.has_name("Signals", "https://b.example.invalid/intro-signals-notes.pdf")
    assert not local.has_name("Something", "https://b.example.invalid/files/download.pdf")
    assert not local.has_name("Something", "https://b.example.invalid/12345678.pdf")
    # A size alone counts only for files of some size.
    local.add("other.pdf", 5 * 1024 * 1024)
    assert local.has_size(5 * 1024 * 1024) and not local.has_size(100)


def test_only_names_and_sizes_of_pdf_and_epub_files_are_read(tmp_path):
    books = tmp_path / "books"
    (books / "deep" / "er").mkdir(parents=True)
    (books / "Signals and Systems.PDF").write_bytes(b"x" * 10)
    (books / "deep" / "er" / "Compilers.epub").write_bytes(b"y" * 20)
    (books / "notes.txt").write_text("not a book")
    (books / ".hidden").mkdir()
    (books / ".hidden" / "Secret Book.pdf").write_bytes(b"z")
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "Linked Book.pdf").write_bytes(b"w")
    (books / "link").symlink_to(outside, target_is_directory=True)
    before = sorted((p.as_posix(), p.stat().st_mtime_ns) for p in books.rglob("*"))
    found = resources.local_copies([books, tmp_path / "missing"])
    assert found.names == {"signals and systems", "compilers"}  # no link followed, no hidden
    assert found.sizes == {10, 20}
    assert sorted((p.as_posix(), p.stat().st_mtime_ns) for p in books.rglob("*")) == before


def test_what_the_owner_has_is_not_fetched_again(
    synced, settings_, model, web, configured, tmp_path
):
    books = tmp_path / "books"
    books.mkdir()
    (books / "Example Open Book - 2nd edition.pdf").write_bytes(b"owner's copy")
    big = 200 * 1024
    (books / "renamed by the owner.pdf").write_bytes(b"m" * big)
    (configured / resources.FOLDERS_FILE).write_text(f"{books}\n")
    web.pages["https://docs.vendor.example.invalid/manual.pdf"] = ("application/pdf", PDF, big)
    before = sorted(p.name for p in books.iterdir())
    assert go(settings_) == "ok"
    # By name: no request at all. By the size the server reports: no body read.
    assert "https://openstax.org/details/example.pdf" not in web.asked
    assert "https://docs.vendor.example.invalid/manual.pdf" in web.asked
    assert statuses()["Example Open Book"] == ("listed", "already_present")
    assert statuses()["Vendor Manual"] == ("listed", "already_present")
    assert not (synced / FOLDER_A / "resources" / "Vendor Manual.pdf").exists()
    assert sorted(p.name for p in books.iterdir()) == before  # never written there
    text = (synced / FOLDER_A / "resources" / "sources.md").read_text()
    assert "Listed only (already present)" in text and str(books) not in text
    assert str(books) not in json.dumps(resources.view(A))
    assert "already_present" not in resources.RETRY  # final: the owner has it


def answer_with(monkeypatch, proposal):
    """The stand-in run proposes ``proposal``."""

    async def run(settings, prompt, **kwargs):
        return RunResult(json.dumps(proposal), "", 1, 1)

    monkeypatch.setattr(claude, "run", run)


def test_the_course_folder_counts_too_and_one_book_is_fetched_once(
    synced, settings_, web, named, monkeypatch
):
    answer_with(monkeypatch, {"items": [
        {"title": "Example Open Book", "url": "https://openstax.org/details/example.pdf",
         "kind": "open_access_book"},
        {"title": "Example Open Book", "url": "https://mirror.example.invalid/example.pdf",
         "kind": "open_access_book"},
        # The course sync already has this one (course files count as present).
        {"title": "Syllabus 2027", "url": "https://uni.example.invalid/syllabus-2027.pdf",
         "kind": "public_material"},
    ]})  # fmt: skip
    assert go(settings_) == "ok"
    items = resources.view(A)["items"]
    assert [(i["status"], i["reason"]) for i in items] == [
        ("downloaded", None),
        ("listed", "already_present"),
        ("listed", "already_present"),
    ]
    assert web.asked == ["https://openstax.org/details/example.pdf"]


# ---- the file's content decides ---------------------------------------------------------


def test_the_type_is_decided_by_the_first_bytes():
    assert resources.document_suffix(PDF) == ".pdf"
    assert resources.document_suffix(EPUB) == ".epub"
    assert resources.document_suffix(b"%PDF") is None  # no "-"
    assert resources.document_suffix(b"<html>%PDF-1.7</html>") is None
    assert resources.document_suffix(b"PK\x03\x04" + bytes(40)) is None  # another zip
    compressed = EPUB[:8] + b"\x08\x00" + EPUB[10:]
    assert resources.document_suffix(compressed) is None  # mimetype must be stored
    other = EPUB.replace(b"application/epub+zip", b"application/x-other!")
    assert resources.document_suffix(other) is None
    assert resources.document_suffix(b"MZ\x90\x00 an executable") is None


@pytest.mark.parametrize(("kind", "body", "title", "result", "name"), [
    ("text/html", PDF, "A Book", ("downloaded", None), "A Book.pdf"),
    ("application/octet-stream", EPUB, "An Epub", ("downloaded", None), "An Epub.epub"),
    ("application/pdf", b"<html>not really</html>", "Fake", ("listed", "not_a_document"), None),
    ("application/epub+zip", b"PK\x03\x04" + bytes(60), "Zip", ("listed", "not_a_document"), None),
    ("application/pdf", PDF, "setup.exe", ("downloaded", None), "setup.pdf"),
])  # fmt: skip
def test_names_and_content_types_never_decide(
    synced, settings_, web, named, monkeypatch, kind, body, title, result, name
):
    address = "https://files.example.invalid/x"
    web.pages = {address: (kind, body)}
    answer_with(monkeypatch, {"items": [{"title": title, "url": address,
                                         "kind": "public_material"}]})  # fmt: skip
    go(settings_)
    (item,) = resources.view(A)["items"]
    assert (item["status"], item["reason"]) == result and item["file"] == name
    folder = synced / FOLDER_A / "resources"
    assert sorted(p.name for p in folder.iterdir()) == sorted(filter(None, [name, "sources.md"]))
    if name:
        assert (folder / name).read_bytes() == body
        assert (folder / name).stat().st_mode & 0o777 == 0o600  # plain, never executable


# ---- the size limit ---------------------------------------------------------------------


def test_the_size_limit_setting(configured, monkeypatch):
    assert resources.file_limit() == 200 * resources.MB
    (configured / "env").write_text((configured / "env").read_text()
                                    + "LIFEOS_RESOURCE_MAX_FILE_MB=50\n")  # fmt: skip
    assert resources.file_limit() == 50 * resources.MB
    monkeypatch.setenv(resources.FILE_MB_VAR, "99999")
    assert resources.file_limit() == 2000 * resources.MB
    monkeypatch.setenv(resources.FILE_MB_VAR, "lots")
    assert resources.file_limit() == 200 * resources.MB


@pytest.mark.parametrize("reported", [None, 10, "declared"])
def test_the_size_limit_holds_while_streaming(
    synced, settings_, model, web, named, monkeypatch, reported
):
    monkeypatch.setenv(resources.FILE_MB_VAR, "1")
    body = PDF + b"x" * resources.MB  # one byte over the limit, and some
    length = len(body) if reported == "declared" else reported
    web.pages["https://openstax.org/details/example.pdf"] = ("application/pdf", body, length)
    go(settings_)
    assert statuses()["Example Open Book"] == ("listed", "too_large")
    folder = synced / FOLDER_A / "resources"
    assert not [p for p in folder.iterdir() if p.name.startswith("Example")]  # no .part left
    if reported == "declared":  # refused before the body: only the other files were read
        assert web.read == len(PDF) + len(b"<html>abstract</html>")  # the manual, the page
    assert "too_large" in resources.RETRY


# ---- audit, addresses -------------------------------------------------------------------


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
    synced, settings_, model, web, configured, named
):
    go(settings_)
    downloads = audit_records(configured, "ResourceDownload")
    assert downloads == [
        {"course_id": A, "host": "openstax.org", "name": "Example Open Book.pdf",
         "size": len(PDF), "result": "downloaded"},
        {"course_id": A, "host": "docs.vendor.example.invalid", "name": "Vendor Manual.pdf",
         "size": len(PDF), "result": "downloaded"},
        {"course_id": A, "host": "arxiv.org", "name": None, "size": None,
         "result": "not_a_document"},
    ]  # fmt: skip
    (run,) = audit_records(configured, "ResourcesRun")
    assert run == {"course_id": A, "result": "ok", "refused_piracy": 1, "proposed": 7, "new": 7,
                   "retried": 0, "downloaded": 2, "listed": 4}  # fmt: skip
    log = "".join(p.read_text() for p in (configured / "audit").glob("*.jsonl"))
    for text in ("Openly licensed", "Main textbook", "abstract", "https://", "libgen"):
        assert text not in log, text


def test_nothing_is_kept_without_its_audit_record(synced, settings_, model, web, configured, named):
    shutil.rmtree(configured / "audit", ignore_errors=True)
    (configured / "audit").write_text("not a folder")
    assert go(settings_) == "ok"
    assert statuses()["Example Open Book"] == ("listed", "audit_unavailable")
    assert not (synced / FOLDER_A / "resources" / "Example Open Book.pdf").exists()
    assert "audit_unavailable" in resources.RETRY  # tried again at the next run


def test_rules_for_addresses_and_redirects(web):
    assert resources.refusal("https://openstax.org/x") is None
    assert resources.refusal("https://any-public-host.example.invalid/x") is None
    assert resources.refusal("http://openstax.org/x") == "not_https"
    assert resources.refusal("https://sci-hub.example.invalid/x") == "piracy"
    assert resources.refusal("https://annas-archive.example.invalid/x") == "piracy"
    assert resources.refusal("https://127.0.0.1/x") == "private_address"
    assert resources.refusal("https://[::ffff:127.0.0.1]/x") == "private_address"
    assert resources.refusal("https://localhost/x") == "private_address"
    assert resources.refusal("https://inside.oapen.org/x") == "private_address"
    handler = resources._CheckedRedirects()
    first = urllib.request.Request("https://openstax.org/x")
    for target in ("http://openstax.org/x", "https://libgen.example.invalid/x",
                   "https://10.0.0.1/x", "https://inside.example.invalid/x",
                   "https://[::ffff:10.0.0.1]/x", "https://localhost/x"):  # fmt: skip
        with pytest.raises(urllib.error.URLError):
            handler.redirect_request(first, None, 302, "", {}, target)
    # Any other public HTTPS host may be a hop now (#160).
    hop = handler.redirect_request(first, None, 302, "", {}, "https://elsewhere.example.invalid/b")
    assert hop.full_url == "https://elsewhere.example.invalid/b"


@pytest.mark.parametrize(("peer", "refused"), [
    ("93.184.216.34", False), ("10.0.0.5", True), ("127.0.0.1", True),
    ("::ffff:192.168.1.2", True), ("fe80::1%2", True),
])  # fmt: skip
def test_the_address_connected_to_is_checked_again(monkeypatch, peer, refused):
    """A name that resolved to a public address for the check may answer from a private one."""

    class Socket:
        closed = False

        def getpeername(self):
            return (peer, 443)

        def close(self):
            Socket.closed = True

    def connect(self):
        self.sock = Socket()

    monkeypatch.setattr(http.client.HTTPSConnection, "connect", connect)
    connection = resources._CheckedConnection("books.example.invalid")
    if refused:
        with pytest.raises(OSError, match="private_address"):
            connection.connect()
        assert Socket.closed
    else:
        connection.connect()
        assert not Socket.closed


def test_the_opener_only_speaks_https(monkeypatch):
    def no_network(*args, **kwargs):
        raise AssertionError("no connection in tests")

    monkeypatch.setattr(resources._CheckedHTTPS, "https_open", no_network)
    for address in ("http://openstax.org/x", "ftp://openstax.org/x", "data:,x"):
        with pytest.raises(urllib.error.URLError):
            resources._open(address)  # no handler for anything but HTTPS


# ---- the daily summary ------------------------------------------------------------------


def test_one_summary_a_day_with_counts_only(synced, settings_, model, web, named, sent):
    assert not resources.send_summary(clock=lambda: NOW)  # nothing downloaded yet
    go(settings_)  # two files
    assert resources.send_summary(clock=lambda: NOW)
    ((args, kwargs),) = sent
    assert args[:3] == (resources.SUMMARY_TITLE, "2 course resources were downloaded.", "")
    assert kwargs == {"event": "courses", "opens": "courses", "counts": {"resources": 2}}
    assert "Example" not in json.dumps([args, kwargs]) and "example" not in args[0] + args[1]
    # Another download the same day waits for the next day.
    state = resources.load()
    state["summary"]["waiting"] = 1
    resources.store.write_json(resources.state_path(), state)
    assert not resources.send_summary(clock=lambda: NOW + timedelta(minutes=1))
    assert resources.send_summary(clock=lambda: NOW + timedelta(days=1))
    assert sent[-1][0][1] == "1 course resource was downloaded." and len(sent) == 2
    assert not resources.send_summary(clock=lambda: NOW + timedelta(days=2))  # nothing new


def test_no_summary_while_a_run_is_going(synced, settings_, model, web, named, sent):
    go(settings_)
    with resources._LOCK:
        assert not resources.send_summary(clock=lambda: NOW)
    assert resources.send_summary(clock=lambda: NOW) and len(sent) == 1


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


def test_endpoints(synced, settings_, auth, model, web, named):
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
