# ruff: noqa: E501
"""The course sync and the ``/courses`` endpoints, against a stand-in site.

No network, no real folders: two synthetic courses, synthetic files.
"""

import copy
import dataclasses
import io
import json
import logging
import shutil
import subprocess
import time
import urllib.parse
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import memory, moodle, moodle_sync, reminders
from lifeos_bridge import moodle_login as ml
from lifeos_bridge import moodle_sync as ms
from lifeos_bridge.app import create_app

SITE = "https://moodle.example.invalid"
TOKEN = "tok-secret-456"
NOW = datetime(2027, 1, 15, 10, 0, tzinfo=UTC)
TS = int(NOW.timestamp())
DAY = 86400
A, B = 5, 7
FOLDER_A, FOLDER_B = "ex-101-5", "ex-102-7"
COMMIT = "Sync courses from the university platform"


def file_url(name):
    return f"{SITE}/webservice/pluginfile.php/11/mod_resource/content/1/{urllib.parse.quote(name)}?forcedownload=1"


def file_entry(name, size=1000, modified=TS - 10 * DAY):
    return {"type": "file", "filename": name, "filepath": "/", "filesize": size,
            "mimetype": "application/pdf", "timemodified": modified, "fileurl": file_url(name)}  # fmt: skip


def resource(module_id, name, *entries):
    return {
        "id": module_id,
        "name": name,
        "modname": "resource",
        "visible": 1,
        "contents": list(entries),
    }


def chapter_path(chapter):
    return f"/webservice/pluginfile.php/12/mod_book/chapter/{chapter}/index.html"


def book_module(module_id, name, chapters):
    """A book as core_course_get_contents lists it: a table of contents, one index.html per chapter."""
    toc, last = [], None
    for chapter, title, level in chapters:
        item = {
            "title": title,
            "href": f"{chapter}/index.html",
            "level": level,
            "hidden": "0",
            "subitems": [],
        }
        if level and last is not None:
            last["subitems"].append(item)
        else:
            toc.append(item)
            last = item
    contents = [
        {
            "type": "content",
            "filename": "structure",
            "filepath": "/",
            "filesize": 0,
            "fileurl": None,
            "content": json.dumps(toc),
            "timemodified": TS - DAY,
        }
    ]
    for chapter, title, _level in chapters:
        contents.append(
            {
                "type": "file",
                "filename": "index.html",
                "filepath": f"/{chapter}/",
                "filesize": 0,
                "fileurl": SITE + chapter_path(chapter),
                "timemodified": TS - DAY,
                "content": title,
            }
        )
    return {"id": module_id, "name": name, "modname": "book", "visible": 1, "contents": contents}  # fmt: skip


PAGE_HTML = (
    "<h2>Overview</h2><p>Watch <a href='https://video.example.invalid/watch?v=1'>the intro video</a> "
    "and read <a href='https://docs.example.invalid/guide?token=leak&amp;x=1'>the guide</a>.</p>"
    "<ul><li>first point</li><li>second <b>point</b></li></ul>"
    "<iframe title='Lecture 1' src='https://video.example.invalid/embed/2'></iframe>"
    "<script>alert(1)</script><p>--- not a rule</p><a href='javascript:x()'>bad</a>"
)
CHAPTER_ONE = (
    "<html><head><title>Example book</title></head><body><p>Chapter text.</p>"
    "<video src='https://video.example.invalid/clip.mp4'></video></body></html>"
)


def discussion(number, subject, message, created):
    return {"id": 900 + number, "discussion": number, "name": subject, "subject": subject,
            "message": message, "created": created, "modified": created,
            "userfullname": "Some Teacher", "userpictureurl": f"{SITE}/pic"}  # fmt: skip


def grade_item(number, name, grade, itemtype="mod"):
    item = {"id": number, "itemname": name, "itemtype": itemtype, "itemmodule": "assign",
            "gradeformatted": grade, "rangeformatted": "0&ndash;100", "percentageformatted": "-",
            "feedback": ""}  # fmt: skip
    if grade != "-":
        item.update(
            graderaw=float(grade), percentageformatted=f"{grade} %", gradedategraded=TS - DAY
        )
    return item


class Site:
    """Stands in for the web service and for file downloads; all content is synthetic."""

    def __init__(self):
        self.calls, self.asked = [], []
        self.submitted, self.status_asked = {43}, []
        self.down = False
        self.refuse = set()
        self.token = TOKEN
        self.courses = [
            {"id": A, "shortname": "EX 101", "fullname": "Example course A", "visible": 1},
            {"id": B, "shortname": "EX-102", "fullname": "Example course B", "visible": 1},
            {"id": 9, "shortname": "HIDDEN", "fullname": "Hidden example course", "visible": 0},
        ]
        self.contents = {
            A: [{"id": 1, "section": 0, "name": "General", "modules": [
                    resource(21, "Notes", file_entry("notes.pdf")),
                    resource(22, "Slides", file_entry("slides.pdf", 2000)),
                    {"id": 23, "name": "Example video", "modname": "url", "contents": [
                        {"type": "url", "filename": "Example video", "fileurl": "https://video.example.invalid/watch?v=1"}]},
                    {"id": 24, "name": "News", "modname": "forum"},
                    {"id": 27, "name": "A page", "modname": "page", "contents": [file_entry("index.html")]}]},
                {"id": 2, "section": 1, "name": "Week 1", "modules": [
                    {"id": 25, "name": "Reading", "modname": "folder", "contents": [
                        file_entry("reading.pdf", 300),
                        {"type": "url", "filename": "Outside reading", "fileurl": "https://read.example.invalid/x"},
                        {"type": "url", "filename": "Bad link", "fileurl": "javascript:alert(1)"}]},
                    book_module(28, "Example book", [(301, "First chapter", 0), (302, "A sub chapter", 1)])]}],
            B: [{"id": 3, "section": 0, "name": "General", "modules": [
                    resource(31, "Sheet", file_entry("sheet.pdf", 500))]}],
        }  # fmt: skip
        self.forums = [
            {"id": 61, "course": A, "type": "news", "name": "Announcements"},
            {"id": 62, "course": A, "type": "general", "name": "Questions"},
            {"id": 63, "course": B, "type": "news", "name": "Announcements"},
        ]
        self.discussions = {
            61: [discussion(1, "Example welcome", "<p>Hello <b>all</b>.</p><script>x()</script><p># not a heading</p>", TS - 5 * DAY),
                 discussion(2, "Example room change", "Room <i>2</i>", TS - 2 * DAY)],
            63: [],
        }  # fmt: skip
        self.assignments = {
            A: [{"id": 41, "course": A, "name": "Example homework (part 1)", "duedate": TS + 3 * DAY},
                {"id": 42, "course": A, "name": "Old example homework", "duedate": TS - 40 * DAY}],
            B: [{"id": 43, "course": B, "name": "Example report", "duedate": TS + 20 * DAY}],
        }  # fmt: skip
        self.events = [
            {"id": 71, "name": "Example homework is due", "timesort": TS + 3 * DAY,
             "course": {"id": A}, "modulename": "assign", "eventtype": "due"},
            {"id": 72, "name": "Example quiz closes", "timesort": TS + 5 * DAY,
             "course": {"id": B}, "modulename": "quiz", "eventtype": "close"},
        ]  # fmt: skip
        self.grades = {
            A: [grade_item(81, "Example homework", "-"), grade_item(82, "Example quiz", "70.00"),
                grade_item(89, "", "70.00", "course")],
            B: [grade_item(83, "Example report", "-")],
        }  # fmt: skip
        self.overall = [
            {"courseid": A, "grade": "70.00", "rawgrade": "70"},
            {"courseid": B, "grade": "-"},
        ]
        self.bodies = {
            chapter_path(301): CHAPTER_ONE.encode(),
            chapter_path(302): b"<p>Sub chapter text with the <a href='https://read.example.invalid/x'>outside reading</a>.</p>",
        }  # fmt: skip
        self.text_pages = [
            {"id": 501, "coursemodule": 27, "course": A, "name": "A page", "intro": "<p>ignored</p>",
             "content": PAGE_HTML, "revision": 1, "timemodified": TS - DAY},
        ]  # fmt: skip
        self.books = [
            {"id": 601, "coursemodule": 28, "course": A, "name": "Example book", "intro": "",
             "revision": 3, "timemodified": TS - DAY},
        ]  # fmt: skip

    def post(self, url, data, content_type):
        fields = dict(urllib.parse.parse_qsl(data.decode()))
        function = fields["wsfunction"]
        self.calls.append(function)
        if self.down:
            raise ml.LoginError("the Moodle site could not be reached (URLError)")
        if fields["wstoken"] != self.token:
            return {"exception": "moodle_exception", "errorcode": "invalidtoken", "message": "x"}
        if function in self.refuse:
            return {"exception": "x", "errorcode": "nopermission", "message": f"secret at {SITE}"}
        if function == "core_webservice_get_site_info":
            return {"userid": 42, "username": "someone", "release": "4.1.9", "functions": []}
        if function == "core_enrol_get_users_courses":
            return copy.deepcopy(self.courses)
        if function == "core_course_get_contents":
            return copy.deepcopy(self.contents[int(fields["courseid"])])
        if function == "mod_assign_get_assignments":
            return {
                "courses": [
                    {"id": c, "assignments": items} for c, items in self.assignments.items()
                ]
            }
        if function == "core_calendar_get_action_events_by_timesort":
            assert int(fields["timesortto"]) - int(fields["timesortfrom"]) == ms.DEADLINE_DAYS * DAY
            return {"events": [] if "aftereventid" in fields else copy.deepcopy(self.events)}
        if function == "gradereport_overview_get_course_grades":
            assert fields["userid"] == "42"
            return {"grades": self.overall, "warnings": []}
        if function == "gradereport_user_get_grade_items":
            assert fields["userid"] == "42"
            return {"usergrades": [{"courseid": int(fields["courseid"]), "userid": 42,
                                    "userfullname": "Some One",
                                    "gradeitems": self.grades[int(fields["courseid"])]}]}  # fmt: skip
        if function == "mod_forum_get_forums_by_courses":
            assert fields["courseids[0]"] == str(A)
            return self.forums
        if function == "mod_forum_get_forum_discussions":
            assert (fields["sortorder"], fields["page"], fields["perpage"]) == ("3", "0", "100")
            return {"discussions": self.discussions[int(fields["forumid"])], "warnings": []}
        if function == "mod_page_get_pages_by_courses":
            assert fields["courseids[0]"] == str(A)
            return {"pages": copy.deepcopy(self.text_pages), "warnings": []}
        if function == "mod_book_get_books_by_courses":
            return {"books": copy.deepcopy(self.books), "warnings": []}
        if function == "mod_assign_get_submission_status":
            self.status_asked.append(int(fields["assignid"]))
            status = "submitted" if int(fields["assignid"]) in self.submitted else "new"
            return {"lastattempt": {"submission": {"status": status}, "graded": False,
                                    "gradingstatus": "notgraded"}}  # fmt: skip
        raise AssertionError(function)

    def open(self, address):
        self.asked.append(address)
        if self.down:
            raise OSError("down")
        parts = urllib.parse.urlsplit(address)
        assert ("token", TOKEN) in urllib.parse.parse_qsl(parts.query)
        name = urllib.parse.unquote(parts.path.rsplit("/", 1)[-1])
        body = self.bodies.get(parts.path, self.bodies.get(name, f"content of {name}".encode()))
        response = io.BytesIO(body)
        response.headers = {"Content-Length": str(len(body))}
        return response

    def downloads(self):
        return [
            urllib.parse.unquote(urllib.parse.urlsplit(a).path.rsplit("/", 1)[-1])
            for a in self.asked
        ]


@pytest.fixture(autouse=True)
def no_sync_outlives_its_test(isolated_config):
    """A background sync must end before the test's private config folder is taken away.

    Otherwise a test that fails early could leave a thread behind that goes on
    with the real settings.
    """
    yield
    for _ in range(3000):
        if not ms.RUN_LOCK.locked():
            break
        time.sleep(0.01)
    assert not ms.RUN_LOCK.locked()


@pytest.fixture
def site(monkeypatch):
    stand_in = Site()
    monkeypatch.setattr(ml, "_post", stand_in.post)
    monkeypatch.setattr(moodle, "_open", stand_in.open)
    return stand_in


@pytest.fixture
def folder(tmp_path, ask_dir):
    path = tmp_path / "home" / "courses"
    path.mkdir()
    return path


@pytest.fixture
def configured(isolated_config, folder):
    path = isolated_config / "env"
    path.write_text(f"MOODLE_URL={SITE}/\nMOODLE_TOKEN={TOKEN}\nMOODLE_DOWNLOAD_DIR={folder}\n")
    path.chmod(0o600)
    return isolated_config


@pytest.fixture
def notified(tmp_path):
    log = tmp_path / "notify.log"
    script = tmp_path / "notify.sh"
    script.write_text(f'#!/bin/sh\nprintf "%s|%s|%s\\n" "$1" "$2" "$3" >> "{log}"\n')
    script.chmod(0o755)
    return script, lambda: log.read_text().splitlines() if log.exists() else []


def git(path, *args):
    command = ["git", "-C", str(path), *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout  # noqa: S603


@pytest.fixture
def brain(ask_dir):
    git(ask_dir, "init", "-q")
    (ask_dir / "CLAUDE.md").write_text("# How to use this memory\n")
    (ask_dir / "INDEX.md").write_text("# Index\n\n## Courses\n\n## People\n")
    git(ask_dir, "add", "-A")
    git(ask_dir, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "init")
    return ask_dir


def commits(path):
    return git(path, "log", "--format=%s").splitlines()


def sync(folder, when=NOW, script=None, brain=None, limits=None):
    return ms.sync(moodle.Client(SITE, TOKEN), lambda: when, folder=folder, memory_dir=brain,
                   notify_script=script, limits=limits)  # fmt: skip


def state():
    return json.loads(ms.state_path().read_text())


def tree(path):
    return sorted(str(p.relative_to(path)) for p in path.rglob("*") if p.is_file())


def later(hours):
    return NOW + timedelta(hours=hours)


# ---- names ----------------------------------------------------------------------


def test_course_folder_names_are_plain_and_unique():
    assert ms.course_folder_name("EX 101", 5) == "ex-101-5"
    assert ms.course_folder_name("../../Résumé.x", 7) == "resume-x-7"
    assert (
        ms.course_folder_name("", 3) == "course-3" and ms.course_folder_name("...", 3) == "course-3"
    )
    long = ms.course_folder_name("a" * 300, 12)
    assert long == "a" * 60 + "-12"


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("../../x", "x"),
        ("/etc/passwd", "passwd"),
        ("..\\..\\win.txt", "win.txt"),
        ("...hidden", "hidden"),
        (".env", "env"),
        ("a\x00b\nc\x1b.pdf", "a_b_c_.pdf"),
        ("résumé naïve 1.pdf", "résumé naïve 1.pdf"),
        ("", "file"),
        ("..", "file"),
        ("a:b*c?.pdf", "a_b_c_.pdf"),
    ],
)
def test_file_names_stay_in_their_folder(name, expected):
    assert ms.safe_file_name(name) == expected


def test_long_file_names_are_cut_but_keep_their_ending():
    name = ms.safe_file_name("x" * 500 + ".pdf")
    assert len(name) == 120 and name.endswith(".pdf")
    assert len(ms.safe_file_name("y" * 500)) == 120


# ---- the first sync ----------------------------------------------------------------


def test_first_sync_writes_folders_files_and_memory_without_news(
    site, configured, folder, brain, notified
):
    script, lines = notified
    result = sync(folder, script=script, brain=brain)
    assert tree(folder) == [
        f"{FOLDER_A}/announcements.md",
        f"{FOLDER_A}/assignments/example-homework-part-1-41.md",
        f"{FOLDER_A}/assignments/old-example-homework-42.md",
        f"{FOLDER_A}/grades.md", f"{FOLDER_A}/links.md",
        f"{FOLDER_A}/materials/notes.pdf", f"{FOLDER_A}/materials/reading.pdf",
        f"{FOLDER_A}/materials/slides.pdf",
        f"{FOLDER_A}/pages/a-page-27.md", f"{FOLDER_A}/pages/example-book-28.md",
        f"{FOLDER_B}/announcements.md", f"{FOLDER_B}/assignments/example-report-43.md",
        f"{FOLDER_B}/grades.md", f"{FOLDER_B}/links.md",
        f"{FOLDER_B}/materials/sheet.pdf",
    ]  # fmt: skip
    assert (folder / FOLDER_A / "materials" / "notes.pdf").read_bytes() == b"content of notes.pdf"
    assert result["first"] and result["courses"] == 2 and result["downloaded"] == 4
    assert result["pages"] == 2 and result["page_errors"] == 0
    assert result["new"] == {
        "files": 0,
        "pages": 0,
        "assignments": 0,
        "announcements": 0,
        "deadlines": 0,
        "grades": 0,
    }
    assert result["announcements"] == 2 and result["grade_items"] == 4 and result["links"] == 2
    assert result["part_errors"] == 0 and result["memory"] == "ok" and result["memory_commit"]
    assert lines() == ["LifeOS: new in your courses|Courses were synced for the first time.|"]
    stored = state()
    assert stored["news"] == [] and stored["last_sync"] == "2027-01-15T10:00:00Z"
    assert set(stored["courses"]) == {str(A), str(B)}  # the hidden course is left out
    assert all(sum(c["new"].values()) == 0 for c in stored["courses"].values())
    assert ms.state_path().stat().st_mode & 0o777 == 0o600


def test_links_announcements_and_grades_files(site, configured, folder):
    sync(folder)
    links = (folder / FOLDER_A / "links.md").read_text()
    assert links.startswith("# Links: Example course A\n\n<!-- Written by the LifeOS bridge")
    assert "outside text" in links and "Updated: 2027-01-15" in links
    assert "## General\n\n- Example video: <https://video.example.invalid/watch?v=1>" in links
    assert "## Week 1\n\n- Outside reading: <https://read.example.invalid/x>" in links
    assert "javascript" not in links and "Not downloaded" not in links
    news = (folder / FOLDER_A / "announcements.md").read_text()
    first, second = news.index("Example room change"), news.index("Example welcome")
    assert first < second  # newest first
    assert "## 2027-01-13 — Example room change\n\nRoom 2\n" in news
    assert "Hello all.\n\n\\# not a heading" in news and "x()" not in news and "<b>" not in news
    assert "Some Teacher" not in news
    grades = (folder / FOLDER_A / "grades.md").read_text()
    assert (
        "Overall: 70.00" in grades
        and "| Example quiz | 70.00 | 0\u2013100 | 70.00 % | - |" in grades
    )
    assert "No announcements." in (folder / FOLDER_B / "announcements.md").read_text()
    assert "Overall: none yet" in (folder / FOLDER_B / "grades.md").read_text()


def test_pages_and_books_become_markdown_files(site, configured, folder):
    sync(folder)
    page = (folder / FOLDER_A / "pages" / "a-page-27.md").read_text()
    assert page.startswith("# A page\n\n<!-- Written by the LifeOS bridge")
    assert "Page of the course Example course A." in page
    assert "## Overview\n\nWatch [the intro video](https://video.example.invalid/watch?v=1)" in page
    assert "[the guide](https://docs.example.invalid/guide?x=1)" in page
    assert "- first point\n- second **point**" in page
    assert "[Lecture 1](https://video.example.invalid/embed/2)" in page
    assert "\\--- not a rule" in page and "alert" not in page and "javascript" not in page
    assert "leak" not in page and "ignored" not in page  # no token parameter; no intro
    book = (folder / FOLDER_A / "pages" / "example-book-28.md").read_text()
    first, sub = book.index("## First chapter"), book.index("### A sub chapter")
    assert first < sub and "Chapter text." in book and "<title>" not in book
    assert book.count("Example book") == 1  # the title of the chapter's HTML head is dropped
    assert "Sub chapter text with the [outside reading](https://read.example.invalid/x)." in book
    links = (folder / FOLDER_A / "links.md").read_text()
    inner = links[links.index("## In pages and books") :]
    assert inner.splitlines()[2:] == [
        "- the guide: <https://docs.example.invalid/guide?x=1> (from: A page)",
        "- Lecture 1: <https://video.example.invalid/embed/2> (from: A page)",
        "- Embedded media: <https://video.example.invalid/clip.mp4> (from: Example book)",
    ]  # links already on the course page are not repeated
    assert links.count("https://video.example.invalid/watch?v=1") == 1
    assert links.count("https://read.example.invalid/x") == 1


def test_changed_pages_are_rewritten_and_count_as_new(site, configured, folder, notified):
    script, lines = notified
    sync(folder, script=script)
    site.asked.clear()
    assert sync(folder, when=later(7))["new"]["pages"] == 0
    assert site.asked == []  # an unchanged book is not fetched again
    site.text_pages[0].update(content="<p>New text</p>", revision=2)
    site.books[0]["revision"] = 4
    site.bodies[chapter_path(301)] = b"<p>Rewritten chapter.</p>"
    result = sync(folder, when=later(14), script=script)
    assert result["new"]["pages"] == 2 and lines()[-1].endswith("|2 new pages.|")
    assert "New text" in (folder / FOLDER_A / "pages" / "a-page-27.md").read_text()
    assert "Rewritten chapter." in (folder / FOLDER_A / "pages" / "example-book-28.md").read_text()
    assert sorted(site.downloads()) == ["index.html", "index.html"]
    links = (folder / FOLDER_A / "links.md").read_text()
    assert "docs.example.invalid" not in links  # the link left the page, and links.md
    site.text_pages[0]["revision"] = 3  # a new revision with the same text is not news
    assert sync(folder, when=later(21))["new"]["pages"] == 0
    site.text_pages.append({"id": 502, "coursemodule": 29, "course": A, "name": "Second page",
                            "content": "<p>x</p>", "revision": 1, "timemodified": TS})  # fmt: skip
    assert sync(folder, when=later(28))["new"]["pages"] == 1


def test_html_to_markdown_keeps_structure_and_drops_scripts():
    text, links = moodle.to_markdown(
        "<h1>T</h1><ol><li>a</li><li>b<ul><li>c</li></ul></li></ol><pre>x  y</pre>"
        "<p><code>z</code> <i>w</i> <img alt='A chart'> <a href='/relative'>r</a></p>"
        "<style>p{}</style><a href='https://e.example.invalid/?wstoken=s&amp;q=1'>[x]</a>"
    )
    assert text.startswith("## T\n\n1. a\n2. b\n  - c")
    assert "```\nx  y\n```" in text and "`z` _w_ [image: A chart] r" in text
    assert links == [{"name": "[x]", "url": "https://e.example.invalid/?q=1"}]
    assert "[(x)](https://e.example.invalid/?q=1)" in text and "p{}" not in text


def test_memory_gets_course_files_index_lines_and_one_commit(site, configured, folder, brain):
    sync(folder, brain=brain)
    assert commits(brain) == ["Sync courses from the university platform", "init"]
    note = (brain / "courses" / f"{FOLDER_A}.md").read_text()
    assert note.startswith(
        '---\ntitle: "Example course A"\ntype: course\nupdated: 2027-01-15\n---\n'
    )
    assert (
        "3 files, 2 links, 2 announcements (last on 2027-01-13). Next deadline: 2027-01-18." in note
    )
    assert "## Facts\n- [external:moodle 2027-01-15] Overall grade: 70.00.\n" in note
    assert "- [external:moodle 2027-01-15] 3 files on the course page; 3 have a local copy." in note
    assert f"## Links\n- Materials, links, announcements and grades: `{folder / FOLDER_A}`" in note
    index = (brain / "INDEX.md").read_text()
    assert index == (
        f"# Index\n\n## Courses\n- [[courses/{FOLDER_A}]] — course\n- [[courses/{FOLDER_B}]] — course\n\n## People\n"
    )
    assert "Example" not in index  # no outside text in the always-loaded file
    assert git(brain, "status", "--porcelain") == ""


def test_upcoming_deadlines_become_calendar_events(site, configured, folder, brain):
    result = sync(folder, brain=brain)
    events = memory.all_events(brain)
    assert result["deadlines_written"] == 3
    assert sorted(e.note for e in events) == [
        "moodle:assign-41",
        "moodle:assign-43",
        "moodle:event-72",
    ]
    homework = next(e for e in events if e.note == "moodle:assign-41")
    due = datetime.fromtimestamp(TS + 3 * DAY).astimezone()
    assert homework.title == "EX 101: Example homework [part 1]"
    assert (homework.day, homework.start, homework.end) == (due.date(), f"{due:%H:%M}", None)
    # The past assignment is not written, and the assignment's timeline twin is not doubled.
    assert not any("Old example" in e.title or "is due" in e.title for e in events)


def homework_task(brain):
    return memory.task_with_field(brain, "assignment", "moodle-41")


def test_open_assignments_become_tasks_once(site, configured, folder, brain, monkeypatch):
    from lifeos_bridge import claude

    async def refuse(*args, **kwargs):
        raise AssertionError("the sync started a model run")

    monkeypatch.setattr(claude, "_start", refuse)
    result = sync(folder, brain=brain)
    assert result["tasks_written"] == 1 and commits(brain)[0] == COMMIT
    (task,) = memory.list_tasks(brain)  # the past one and the submitted one get none
    due = datetime.fromtimestamp(TS + 3 * DAY).astimezone().date().isoformat()
    assert task.title == "EX 101: Example homework [part 1]"
    assert task.fields == {"due": due, "course": FOLDER_A, "assignment": "moodle-41",
                           "added": NOW.astimezone().date().isoformat()}  # fmt: skip
    assert task.as_json()["assignment"] == "moodle-41"
    assert len(memory.all_events(brain)) == 3  # the deadline events stay as they are
    assert state()["courses"][str(A)]["assignments"][0]["task"] == task.id
    # Lost state: the task is found again by its field, never duplicated.
    ms.state_path().unlink()
    sync(folder, when=later(7), brain=brain)
    assert [t.id for t in memory.list_tasks(brain)] == [task.id]
    assert git(brain, "status", "--porcelain") == ""


def test_a_submitted_assignment_completes_its_task(site, configured, folder, brain):
    sync(folder, brain=brain)
    site.submitted.add(41)
    result = sync(folder, when=later(7), brain=brain)
    assert result["tasks_written"] == 1 and memory.list_tasks(brain) == []
    done = homework_task(brain)
    assert done.done and done.fields["done"] == later(7).astimezone().date().isoformat()
    assert commits(brain)[0] == COMMIT and git(brain, "status", "--porcelain") == ""
    before = len(commits(brain))
    again = sync(folder, when=later(14), brain=brain)
    assert again["tasks_written"] == 0 and len(commits(brain)) == before


def test_the_owners_note_survives_a_moved_deadline_and_the_hand_in(site, configured, folder, brain):
    sync(folder, brain=brain)
    task = homework_task(brain)
    memory.update_task(brain, task.id, {"note": "Ask about part (2) first"})
    site.assignments[A][0]["duedate"] = TS + 5 * DAY  # the site moves the deadline
    sync(folder, when=later(7), brain=brain)
    moved = homework_task(brain)
    new_due = datetime.fromtimestamp(TS + 5 * DAY).astimezone().date().isoformat()
    assert moved.fields["due"] == new_due and moved.fields["note"] == "Ask about part [2] first"
    assert moved.fields["assignment"] == "moodle-41" and moved.fields["course"] == FOLDER_A
    site.submitted.add(41)
    sync(folder, when=later(14), brain=brain)
    done = homework_task(brain)
    assert done.done and done.fields["note"] == "Ask about part [2] first"
    assert done.as_json()["note"] == "Ask about part [2] first"


def test_a_task_the_owner_closed_or_removed_stays_that_way(site, configured, folder, brain):
    sync(folder, brain=brain)
    memory.complete_task(brain, homework_task(brain).id)
    site.assignments[A][0]["duedate"] = TS + 5 * DAY  # the site moves the deadline
    result = sync(folder, when=later(7), brain=brain)
    assert result["tasks_written"] == 0 and memory.list_tasks(brain) == []  # not reopened
    assert state()["courses"][str(A)]["assignments"][0]["task_closed"] == "owner"
    # Removed by hand: never created again.
    (brain / "tasks-done.md").write_text("# Finished tasks\n\n")
    sync(folder, when=later(14), brain=brain)
    assert homework_task(brain) is None and memory.list_tasks(brain) == []


def test_an_open_task_gets_its_hand_in_state_first(site, configured, folder, brain, monkeypatch):
    sync(folder, brain=brain)
    monkeypatch.setattr(ms, "MAX_STATUS_CALLS", 1)
    site.status_asked.clear()
    site.submitted.add(41)
    sync(folder, when=later(7), brain=brain)
    assert site.status_asked == [41] and homework_task(brain).done


def test_assignments_name_their_task(site, configured, folder, brain, settings_, auth):
    sync(folder, brain=brain)
    client = TestClient(create_app(settings_))
    listing = client.get("/courses/5/assignments", headers=auth).json()["assignments"]
    task = next(a for a in listing if a["id"] == 41)["task"]
    assert task == homework_task(brain).id
    assert next(a for a in listing if a["id"] == 42)["task"] is None
    tasks = client.get("/tasks", headers=auth).json()["tasks"]
    assert [(t["id"], t["assignment"], t["course"]) for t in tasks] == [
        (task, "moodle-41", FOLDER_A)
    ]


def test_an_assignment_marked_by_hand_leaves_the_lists_and_gets_no_task_again(
    site, configured, folder, brain, settings_, auth, monkeypatch
):
    monkeypatch.setattr(time, "time", lambda: float(TS))
    sync(folder, brain=brain)
    client = TestClient(create_app(settings_))
    task = homework_task(brain).id

    def due_ids():
        deadlines = client.get("/courses/summary", headers=auth).json()["deadlines"]
        return [d.get("assignment_id") for d in deadlines]

    assert 41 in due_ids()
    put = client.put("/courses/5/assignments/41/mark", json={"mark": "done"}, headers=auth)
    assert put.json() == {"mark": "done", "previous": None, "task": task}
    assert 41 not in due_ids() and client.get("/tasks", headers=auth).json()["tasks"] == []
    listing = client.get("/courses/5/assignments", headers=auth).json()["assignments"]
    assert next(a for a in listing if a["id"] == 41)["mark"] == "done"

    client.put("/courses/5/assignments/41/mark", json={"mark": "removed"}, headers=auth)
    listing = client.get("/courses/5/assignments", headers=auth).json()["assignments"]
    assert 41 not in [a["id"] for a in listing]
    assert client.get("/courses/5/assignments/41", headers=auth).json()["mark"] == "removed"
    sync(folder, when=later(7), brain=brain)  # the sync opens no task for it again
    assert client.get("/tasks", headers=auth).json()["tasks"] == []

    cleared = client.put("/courses/5/assignments/41/mark", json={"mark": None}, headers=auth)
    assert cleared.json()["previous"] == "removed" and 41 in due_ids()
    assert [t["id"] for t in client.get("/tasks", headers=auth).json()["tasks"]] == [task]
    bad = client.put("/courses/5/assignments/41/mark", json={"mark": "x"}, headers=auth)
    assert bad.status_code == 400
    assert client.put("/courses/5/assignments/99/mark", json={"mark": "done"},
                      headers=auth).status_code == 404  # fmt: skip
    assert client.put("/courses/5/assignments/41/mark", json={}, headers=auth).status_code == 400


def test_a_second_sync_without_changes_does_nothing(site, configured, folder, brain, notified):
    script, lines = notified
    sync(folder, script=script, brain=brain)
    before, asked = tree(folder), len(site.asked)
    result = sync(folder, when=later(7), script=script, brain=brain)
    assert not result["first"] and result["downloaded"] == 0 and len(site.asked) == asked
    assert not result["memory_commit"] and result["deadlines_written"] == 0
    assert tree(folder) == before and len(lines()) == 1 and len(commits(brain)) == 2
    assert len(memory.all_events(brain)) == 3 and state()["news"] == []


# ---- the second sync: what is new -----------------------------------------------------


def change_the_site(site):
    general = site.contents[A][0]["modules"]
    general.append(resource(26, "Extra", file_entry("extra.pdf", 700, TS)))
    general[1]["contents"][0].update(filesize=2500, timemodified=TS + 60)
    site.bodies["slides.pdf"] = b"second version of the slides"
    site.discussions[61].insert(0, discussion(3, "Example exam date", "On Monday", TS + 100))
    site.grades[A][0] = grade_item(81, "Example homework", "90.00")
    site.grades[A][2] = grade_item(89, "", "80.00", "course")
    site.overall[0]["grade"] = "80.00"
    site.assignments[A][0]["duedate"] = TS + 4 * DAY


def test_second_sync_fetches_and_reports_only_what_changed(
    site, configured, folder, brain, notified
):
    script, lines = notified
    sync(folder, script=script, brain=brain)
    site.asked.clear()
    change_the_site(site)
    result = sync(folder, when=later(7), script=script, brain=brain)
    assert sorted(site.downloads()) == ["extra.pdf", "slides.pdf"]
    assert (
        folder / FOLDER_A / "materials" / "slides.pdf"
    ).read_bytes() == b"second version of the slides"
    assert result["new"] == {
        "files": 2,
        "pages": 0,
        "assignments": 0,
        "announcements": 1,
        "deadlines": 1,
        "grades": 1,
    }
    assert lines()[1:] == [
        "LifeOS: new in your courses|2 new files, 1 announcement, 1 deadline, 1 grade.|"
    ]
    stored = state()
    assert stored["courses"][str(A)]["new"] == {
        "files": 2,
        "pages": 0,
        "assignments": 0,
        "announcements": 1,
        "deadlines": 1,
        "grades": 1,
    }
    assert sum(stored["courses"][str(B)]["new"].values()) == 0
    assert sorted(n["kind"] for n in stored["news"]) == [
        "announcement",
        "deadline",
        "file",
        "file",
        "grade",
    ]
    assert all(
        set(n) == {"kind", "course_id", "at"} and n["course_id"] == A for n in stored["news"]
    )
    # The moved deadline changed its event; nothing was duplicated.
    events = memory.all_events(brain)
    assert len(events) == 3 and result["deadlines_written"] == 1
    moved = next(e for e in events if e.note == "moodle:assign-41")
    assert moved.day == datetime.fromtimestamp(TS + 4 * DAY).astimezone().date()
    assert (
        commits(brain)[0] == "Sync courses from the university platform"
        and len(commits(brain)) == 3
    )
    changed = git(brain, "show", "--name-only", "--format=", "HEAD").split()
    assert sorted(changed) == ["calendar/events.md", f"courses/{FOLDER_A}.md", "tasks.md"]
    (task,) = memory.list_tasks(brain)  # the assignment's task followed the moved due date
    assert task.fields["due"] == moved.day.isoformat()
    assert "Overall grade: 80.00." in (brain / "courses" / f"{FOLDER_A}.md").read_text()
    assert "Example exam date" in (folder / FOLDER_A / "announcements.md").read_text()


def test_every_change_of_one_sync_is_one_push_with_counts(site, configured, folder, monkeypatch):
    from lifeos_bridge import push

    hints = []
    monkeypatch.setattr(
        push, "send_hint", lambda event, ref="", **kw: hints.append((event, ref, kw))
    )
    sync(folder)
    assert hints == [("courses", "", {"opens": None, "counts": None})]  # the first sync
    change_the_site(site)  # a new and a changed file, an announcement, a grade, a moved date
    site.text_pages[0].update(content="<p>Changed</p>", revision=2)
    site.assignments[A].append(
        {"id": 44, "course": A, "name": "New homework", "duedate": TS + 9 * DAY}
    )
    result = sync(folder, when=later(7))
    assert result["new"] == {"files": 2, "pages": 1, "assignments": 1, "announcements": 1,
                             "deadlines": 1, "grades": 1}  # fmt: skip
    assert len(hints) == 2
    assert hints[1] == ("courses", str(A), {"opens": "course", "counts": result["new"]})
    # Changes in two courses: one push that opens the course list.
    site.contents[B][0]["modules"].append(resource(32, "More", file_entry("more.pdf")))
    site.discussions[61].insert(0, discussion(4, "Another", "x", TS + 200))
    sync(folder, when=later(14))
    assert hints[2][0:2] == ("courses", "") and hints[2][2]["opens"] == "courses"
    assert hints[2][2]["counts"]["files"] == 1 and hints[2][2]["counts"]["announcements"] == 1


def test_notification_text_is_built_from_counts_only():
    text = ms.notification_text
    assert (
        text({"files": 2, "announcements": 1, "deadlines": 0, "grades": 1})
        == "2 new files, 1 announcement, 1 grade."
    )
    assert text({"files": 1, "announcements": 0, "deadlines": 0, "grades": 0}) == "1 new file."
    assert (
        text({"files": 0, "announcements": 3, "deadlines": 2, "grades": 0})
        == "3 new announcements, 2 deadlines."
    )


def test_nothing_of_the_courses_reaches_notifications_or_logs(
    site, configured, folder, brain, notified, caplog
):
    script, lines = notified
    caplog.set_level(logging.DEBUG)
    sync(folder, script=script, brain=brain)
    change_the_site(site)
    sync(folder, when=later(7), script=script, brain=brain)
    spoken = "\n".join(lines()) + caplog.text
    for secret in ("Example", "EX", "ex-10", "70", "90", "pdf", TOKEN, "example.invalid", "Room"):
        assert secret not in spoken, secret


def test_a_course_that_appears_later_is_a_baseline_too(site, configured, folder, notified):
    script, lines = notified
    sync(folder, script=script)
    site.courses.append({"id": 11, "shortname": "EX103", "fullname": "Example course C"})
    site.contents[11] = [
        {
            "id": 8,
            "section": 0,
            "name": "General",
            "modules": [resource(51, "N", file_entry("c.pdf"))],
        }
    ]
    site.grades[11] = [grade_item(91, "Example test", "50.00")]
    result = sync(folder, when=later(7), script=script)
    assert result["courses"] == 3 and result["downloaded"] == 1 and not any(result["new"].values())
    assert len(lines()) == 1
    site.contents[11][0]["modules"].append(resource(52, "M", file_entry("d.pdf")))
    assert sync(folder, when=later(14), script=script)["new"]["files"] == 1


def test_a_course_that_disappears_for_a_while_is_not_fetched_again(
    site, configured, folder, auth, settings_
):
    sync(folder)
    hidden = site.courses.pop(1)
    result = sync(folder, when=later(7))
    assert result["courses"] == 1 and state()["courses"][str(B)]["listed"] is False
    client = TestClient(create_app(settings_))
    assert [c["id"] for c in client.get("/courses", headers=auth).json()["courses"]] == [A]
    assert client.get("/courses/7/materials", headers=auth).status_code == 404
    assert client.post("/courses/7/seen", headers=auth).status_code == 404
    site.courses.append(hidden)
    result = sync(folder, when=later(14))
    assert result["courses"] == 2 and result["downloaded"] == 0 and not any(result["new"].values())
    assert [p.name for p in (folder / FOLDER_B / "materials").iterdir()] == ["sheet.pdf"]
    assert client.get("/courses/7/materials", headers=auth).status_code == 200


def test_memory_file_keeps_the_owners_lines(site, configured, folder, brain):
    sync(folder, brain=brain)
    path = brain / "courses" / f"{FOLDER_A}.md"
    text = path.read_text().replace(
        "## Links", "- [owner 2027-01-16] Example fact of the owner.\n\n## Links"
    )
    path.write_text(text + "- Related: [[people/example-person]]\n")
    git(brain, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-qam", "owner")
    site.overall[0]["grade"] = "85.00"
    sync(folder, when=later(30), brain=brain)
    text = path.read_text()
    assert "- [external:moodle 2027-01-16] Overall grade: 85.00." in text and "70.00" not in text
    assert text.count("[external:moodle") == 2 and "updated: 2027-01-16" in text
    assert "- [owner 2027-01-16] Example fact of the owner.\n\n## Links\n" in text
    assert text.endswith("- Related: [[people/example-person]]\n")
    assert (brain / "INDEX.md").read_text().count(f"[[courses/{FOLDER_A}]]") == 1


def test_an_event_the_owner_removed_is_not_added_again(site, configured, folder, brain):
    sync(folder, brain=brain)
    events = brain / memory.EVENTS_FILE
    events.write_text(
        "".join(line for line in events.read_text().splitlines(True) if "assign-43" not in line)
    )
    result = sync(folder, when=later(7), brain=brain)
    assert result["deadlines_written"] == 0 and len(memory.all_events(brain)) == 2


def test_events_are_found_again_by_their_note_when_the_state_is_lost(
    site, configured, folder, brain
):
    sync(folder, brain=brain)
    ms.state_path().unlink()
    sync(folder, when=later(7), brain=brain)
    assert len(memory.all_events(brain)) == 3


def test_without_the_memory_the_rest_still_works(site, configured, folder, ask_dir):
    result = sync(folder, brain=ask_dir)  # a folder that is no memory
    assert result["memory"] == "not_set_up" and not result["memory_commit"]
    assert result["downloaded"] == 4 and result["deadlines_written"] == 0
    assert not (ask_dir / "courses").exists() and not (ask_dir / "calendar").exists()
    assert sync(folder, when=later(7))["memory"] == "not_set_up"


def test_a_memory_with_a_remote_is_never_written(site, configured, folder, brain):
    git(brain, "remote", "add", "origin", "https://example.invalid/x.git")
    result = sync(folder, brain=brain)
    assert result["memory"] == "not_set_up" and commits(brain) == ["init"]


# ---- names, caps, links -----------------------------------------------------------------


def test_unsafe_names_stay_inside_the_course_folder(site, configured, folder, tmp_path):
    names = ["../../x", "/abs/y.pdf", "...dots", "ctrl\x00\n.pdf", "z" * 400 + ".pdf", "same.pdf"]
    modules = [resource(100 + i, "m", file_entry(name)) for i, name in enumerate(names)]
    modules.append({"id": 200, "name": "f", "modname": "folder", "contents": [
        dict(file_entry("same.pdf"), filepath="/a/"), dict(file_entry("SAME.pdf"), filepath="/b/")]})  # fmt: skip
    site.contents[B] = [{"id": 3, "section": 0, "name": "../General", "modules": modules}]
    site.courses[1]["shortname"] = "../../../escape"
    before = set(tree(tmp_path))
    result = sync(folder)
    assert result["downloaded"] == 3 + len(names) + 2
    created = set(tree(tmp_path)) - before
    inside = {p for p in created if p.startswith("home/courses/")}
    assert created - inside == {"config/moodle-sync.json"}
    materials = folder / "escape-7" / "materials"
    found = sorted(p.name for p in materials.iterdir())
    assert found == sorted(["x", "y.pdf", "dots", "ctrl .pdf", "z" * 116 + ".pdf", "same.pdf",
                            "same-2.pdf", "SAME-3.pdf"])  # fmt: skip
    assert all(p.is_file() and not p.is_symlink() for p in materials.iterdir())


def test_a_file_already_in_the_folder_is_not_overwritten(site, configured, folder):
    materials = folder / FOLDER_B / "materials"
    materials.mkdir(parents=True)
    (materials / "sheet.pdf").write_text("the owner's own file")
    sync(folder)
    assert (materials / "sheet.pdf").read_text() == "the owner's own file"
    assert (materials / "sheet-2.pdf").read_bytes() == b"content of sheet.pdf"


def test_links_planted_in_the_course_folder_are_never_written_through(
    site, configured, folder, tmp_path
):
    outside = tmp_path / "home" / "outside"
    outside.mkdir()
    for name in ("victim.txt", "part.txt", "links.txt", "tmp.txt"):
        (outside / name).write_text("untouched")
    (folder / FOLDER_A / "materials").mkdir(parents=True)
    (folder / FOLDER_A / "materials" / "notes.pdf").symlink_to(outside / "victim.txt")
    (folder / FOLDER_A / "materials" / "slides.pdf.part").symlink_to(outside / "part.txt")
    (folder / FOLDER_A / "links.md").symlink_to(outside / "links.txt")
    (folder / FOLDER_A / "grades.md.tmp").symlink_to(outside / "tmp.txt")
    (folder / FOLDER_B).mkdir()
    (folder / FOLDER_B / "materials").symlink_to(outside, target_is_directory=True)
    result = sync(folder)
    assert sorted(p.name for p in outside.iterdir()) == [
        "links.txt",
        "part.txt",
        "tmp.txt",
        "victim.txt",
    ]
    assert all(p.read_text() == "untouched" for p in outside.iterdir())
    assert (folder / FOLDER_A / "materials" / "notes-2.pdf").read_bytes() == b"content of notes.pdf"
    assert (folder / FOLDER_A / "materials" / "slides.pdf").is_file()
    links = folder / FOLDER_A / "links.md"
    assert not links.is_symlink() and links.read_text().startswith("# Links")
    assert not (folder / FOLDER_A / "grades.md").is_symlink()
    assert (
        result["failed"] == 1 and result["downloaded"] == 3
    )  # course B's file had nowhere safe to go


def test_a_course_folder_that_points_outside_gets_nothing(site, configured, folder, tmp_path):
    outside = tmp_path / "home" / "outside"
    outside.mkdir()
    (folder / FOLDER_B).symlink_to(outside, target_is_directory=True)
    result = sync(folder)
    assert list(outside.iterdir()) == [] and result["failed"] == 1 and result["write_errors"] == 4
    assert result["downloaded"] == 3


def test_too_large_files_are_listed_and_not_tried_again(site, configured, folder):
    site.contents[A][0]["modules"][0]["contents"][0]["filesize"] = 50 * ms.MB
    limits = ms.Limits(file_bytes=10 * ms.MB)
    result = sync(folder, limits=limits)
    assert result["skipped_too_large"] == 1 and result["downloaded"] == 3
    assert "notes.pdf" not in site.downloads()
    links = (folder / FOLDER_A / "links.md").read_text()
    assert "## Not downloaded (too large)\n\n- notes.pdf\n" in links
    again = sync(folder, when=later(7), limits=limits)
    assert again["downloaded"] == 0 and again["skipped_too_large"] == 0
    assert "Not downloaded (too large)" in (folder / FOLDER_A / "links.md").read_text()


def test_a_file_that_is_larger_than_the_site_said_is_stopped(site, configured, folder):
    site.bodies["notes.pdf"] = b"x" * 5000
    result = sync(folder, limits=ms.Limits(file_bytes=2000))
    assert result["skipped_too_large"] == 1
    assert sorted(p.name for p in (folder / FOLDER_A / "materials").iterdir()) == [
        "reading.pdf",
        "slides.pdf",
    ]


def test_the_count_cap_leaves_the_rest_for_the_next_sync(site, configured, folder, notified):
    script, lines = notified
    result = sync(folder, script=script, limits=ms.Limits(downloads=1))
    assert (result["downloaded"], result["skipped_later"]) == (1, 3)
    assert "## Not downloaded yet" in (folder / FOLDER_A / "links.md").read_text()
    result = sync(folder, when=later(7), script=script)
    assert (result["downloaded"], result["skipped_later"]) == (3, 0)
    assert not any(result["new"].values()) and len(lines()) == 1  # fetched late, but not news
    assert "Not downloaded" not in (folder / FOLDER_A / "links.md").read_text()


def test_the_total_size_cap(site, configured, folder):
    result = sync(folder, limits=ms.Limits(total_bytes=1500))
    # notes (1000 declared, 20 real) fits; slides (2000) does not; reading (300) and sheet (500) do.
    assert (result["downloaded"], result["skipped_later"]) == (3, 1)
    assert "slides.pdf" not in site.downloads()


def test_failed_downloads_are_retried_and_a_dead_site_is_given_up(
    site, configured, folder, monkeypatch
):
    def broken(address):
        site.asked.append(address)
        raise OSError(f"cannot fetch {address}")

    monkeypatch.setattr(moodle, "_open", broken)
    monkeypatch.setattr(ms, "MAX_FAILURES_IN_A_ROW", 2)
    result = sync(folder)
    assert (result["downloaded"], result["failed"], result["skipped_later"]) == (0, 2, 2)
    files = [a for a in site.asked if "mod_book" not in a]
    assert len(files) == 2 and TOKEN not in ms.state_path().read_text()
    assert result["page_errors"] == 1  # the book could not be read: tried again next time
    assert not (folder / FOLDER_A / "pages" / "example-book-28.md").exists()
    monkeypatch.setattr(moodle, "_open", site.open)
    again = sync(folder, when=later(7))
    assert again["downloaded"] == 4 and again["page_errors"] == 0
    assert (folder / FOLDER_A / "pages" / "example-book-28.md").is_file()


# ---- failures -----------------------------------------------------------------------------


def test_a_rejected_token_changes_nothing(site, configured, folder, brain, notified):
    script, lines = notified
    site.token = "another"
    with pytest.raises(moodle.MoodleError) as caught:
        sync(folder, script=script, brain=brain)
    assert caught.value.code == "token_expired"
    assert tree(folder) == [] and not ms.state_path().exists() and lines() == []
    assert commits(brain) == ["init"]


def test_run_records_the_refusal_and_keeps_the_previous_state(site, configured, folder, settings_):
    assert ms.run(settings_, lambda: NOW) == "ok"
    before = state()
    site.token = "another"
    assert ms.run(settings_, lambda: later(7)) == "token_expired"
    after = state()
    assert after["last_error"] == "token_expired" and after["last_sync"] == before["last_sync"]
    assert after["courses"] == before["courses"] and after["last_attempt"] == "2027-01-15T17:00:00Z"
    site.token, site.down = TOKEN, True
    assert ms.run(settings_, lambda: later(8)) == "unreachable"
    assert state()["courses"] == before["courses"] and not ms.RUN_LOCK.locked()
    site.down = False
    assert ms.run(settings_, lambda: later(9)) == "ok" and state()["last_error"] is None


def test_refused_parts_keep_what_was_known(site, configured, folder):
    sync(folder)
    before = state()["courses"][str(A)]
    site.refuse = {"mod_forum_get_forums_by_courses", "gradereport_user_get_grade_items",
                   "mod_assign_get_assignments", "gradereport_overview_get_course_grades",
                   "mod_page_get_pages_by_courses", "mod_book_get_books_by_courses"}  # fmt: skip
    site.contents[A][0]["modules"].append(resource(26, "Extra", file_entry("extra.pdf")))
    result = sync(folder, when=later(7))
    after = state()["courses"][str(A)]
    assert result["part_errors"] == 7 and result["errorcode"] == "nopermission"
    assert result["new"] == {
        "files": 1,
        "pages": 0,
        "assignments": 0,
        "announcements": 0,
        "deadlines": 0,
        "grades": 0,
    }
    assert (
        result["pages"] == 2
        and "In pages and books" in (folder / FOLDER_A / "links.md").read_text()
    )
    for part in ("announcements", "grades", "overall", "assignments", "known_grades", "texts"):
        assert after[part] == before[part], part
    assert "assign-41" in after["deadlines"] and "example.invalid" not in json.dumps(result)


def test_parts_refused_from_the_start_are_a_baseline_when_they_first_work(site, configured, folder):
    site.refuse = {"mod_forum_get_forum_discussions", "gradereport_user_get_grade_items"}
    assert sync(folder)["announcements"] == 0
    site.refuse = set()
    result = sync(folder, when=later(7))
    assert result["announcements"] == 2 and not any(result["new"].values())


def test_the_folder_must_exist_and_be_allowed(site, configured, folder, settings_, settings):
    assert ms.run(settings, lambda: NOW) == "folder_not_allowed"  # not among the allowed folders
    assert tree(folder) == [] and site.calls == [] and state()["last_error"] == "folder_not_allowed"
    folder.rmdir()
    assert ms.run(settings_, lambda: later(1)) == "folder_missing" and site.calls == []


def test_not_configured_means_nothing_happens(site, isolated_config, settings_, folder):
    assert not ms.configured() and not ms.due(lambda: NOW)
    assert (
        ms.run(settings_, lambda: NOW) == "not_configured"
        and ms.start(settings_) == "not_configured"
    )
    (isolated_config / "env").write_text(f"MOODLE_URL={SITE}\nMOODLE_TOKEN={TOKEN}\n")
    (isolated_config / "env").chmod(0o600)
    assert not ms.due(lambda: NOW) and ms.run(settings_, lambda: NOW) == "not_configured"
    assert site.calls == [] and not ms.state_path().exists() and not ms.RUN_LOCK.locked()


# ---- schedule ---------------------------------------------------------------------------


@pytest.fixture
def settings_(settings, folder):
    """Settings in which the course folder is an allowed folder."""
    return dataclasses.replace(settings, allowed_paths=(folder,))


def test_due_follows_the_clock(site, configured, folder, settings_, isolated_config):
    assert ms.due(lambda: NOW)
    assert ms.run(settings_, lambda: NOW) == "ok"
    assert not ms.due(lambda: later(5.9)) and ms.due(lambda: later(6))
    site.down = True
    assert ms.run(settings_, lambda: later(6)) == "unreachable"
    assert not ms.due(lambda: later(6.4)) and ms.due(lambda: later(6.6))
    site.down, site.token = False, "another"
    assert ms.run(settings_, lambda: later(7)) == "token_expired"
    assert ms.due(lambda: later(7.6))
    (isolated_config / ml.WATCH_FILE).write_text('{"result": "expired"}')
    assert not ms.due(lambda: later(7.6)) and ms.due(lambda: later(13))


def test_never_two_syncs_at_once(site, configured, folder, settings_):
    assert ms.RUN_LOCK.acquire(blocking=False)
    try:
        assert ms.run(settings_, lambda: NOW) == "running" and ms.start(settings_) == "running"
        assert not ms.due(lambda: NOW) and ms.status()["running"]
    finally:
        ms.RUN_LOCK.release()
    assert site.calls == []


def test_limits_come_from_the_settings(configured, monkeypatch):
    assert ms.limits_from_env() == ms.Limits(200 * ms.MB, 1024 * ms.MB, 200)
    with (configured / "env").open("a") as handle:
        handle.write("LIFEOS_MOODLE_MAX_FILE_MB=20\n")
    assert ms.limits_from_env().file_bytes == 20 * ms.MB
    monkeypatch.setenv("LIFEOS_MOODLE_MAX_FILE_MB", "5")
    assert ms.limits_from_env().file_bytes == 5 * ms.MB


def test_the_bridge_runs_the_sync_by_itself(site, configured, folder, settings_, monkeypatch):
    monkeypatch.setattr(reminders, "CHECK_INTERVAL_SECONDS", 0.05)
    with TestClient(create_app(settings_)) as client:
        for _ in range(200):
            client.get("/health")
            if ms.load_state().get("last_sync") and not ms.RUN_LOCK.locked():
                break
            time.sleep(0.02)
        for _ in range(5):
            client.get("/health")
    assert (
        state()["last_result"]["downloaded"] == 4
        and site.calls.count("core_enrol_get_users_courses") == 1
    )


# ---- endpoints -----------------------------------------------------------------------------


@pytest.fixture
def bridge(site, configured, folder, settings_, auth, monkeypatch):
    """The bridge after a first sync and a second one that found new things."""
    monkeypatch.setattr(ms, "now_utc", lambda: later(7))
    monkeypatch.setattr(time, "time", lambda: float(TS + 7 * 3600))
    sync(folder)
    change_the_site(site)
    sync(folder, when=later(7))
    client = TestClient(create_app(settings_))

    def call(path, method="get"):
        return getattr(client, method)(path, headers=auth)

    call.client = client
    return call


ROUTES = [
    ("get", "/courses"), ("get", "/courses/summary"), ("get", "/courses/grades"),
    ("get", "/courses/sync"), ("post", "/courses/refresh"), ("get", "/courses/5/materials"),
    ("get", "/courses/5/announcements"), ("get", "/courses/5/assignments"),
    ("get", "/courses/5/assignments/41"),
    ("get", "/courses/5/grades"), ("post", "/courses/5/seen"),
    ("post", "/courses/5/files/abc/address"), ("post", "/courses/5/files/abc/offer"),
]  # fmt: skip


def test_every_route_needs_the_bearer_token(bridge, site):
    site.calls.clear()
    before = ms.state_path().read_text()
    for method, path in ROUTES:
        assert getattr(bridge.client, method)(path).status_code == 401, path
        wrong = getattr(bridge.client, method)(path, headers={"Authorization": "Bearer wrong"})
        assert wrong.status_code == 401, path
    assert site.calls == [] and ms.state_path().read_text() == before


def test_summary_and_list(bridge):
    summary = bridge("/courses/summary").json()
    assert summary["configured"] and not summary["stale"] and summary["new_total"] == 5
    assert summary["last_sync"] == "2027-01-15T17:00:00Z"
    assert [(d["course_id"], d["course"], d["name"]) for d in summary["deadlines"]] == [
        (A, "Example course A", "Example homework (part 1)"),
        (B, "Example course B", "Example quiz closes"),
    ]  # the report of course B is due in twenty days: outside the two weeks
    assert summary["deadlines"][0]["due_iso"] == moodle.iso(TS + 4 * DAY)
    # An assignment's deadline names the assignment; other deadlines are as before.
    homework, quiz = summary["deadlines"]
    assert (homework["course_id"], homework["assignment_id"]) == (A, 41)
    assert set(quiz) == {"course_id", "course", "name", "due", "due_iso"}
    detail = bridge(f"/courses/{homework['course_id']}/assignments/{homework['assignment_id']}")
    assert detail.status_code == 200 and detail.json()["name"] == "Example homework (part 1)"
    listing = bridge("/courses").json()
    assert listing["courses"][0]["next_deadline"]["assignment_id"] == 41
    first, second = listing["courses"]
    assert (first["id"], first["name"], first["has_new"], first["folder_exists"]) == (
        A,
        "Example course A",
        True,
        True,
    )
    assert first["new"] == {
        "files": 2,
        "pages": 0,
        "assignments": 0,
        "announcements": 1,
        "deadlines": 1,
        "grades": 1,
    }
    assert first["next_deadline"]["due"] == TS + 4 * DAY
    assert second["next_deadline"]["name"] == "Example quiz closes" and not second["has_new"]


def test_materials_announcements_assignments_grades(bridge, folder):
    materials = bridge("/courses/5/materials").json()
    assert [s["name"] for s in materials["sections"]] == ["General", "Week 1"]
    files = {f["name"]: f for s in materials["sections"] for f in s["files"]}
    assert set(files["notes.pdf"]) == {"key", "name", "type", "size", "modified", "modified_iso", "new",
                                       "downloaded", "skipped"}  # fmt: skip
    assert {n: (f["new"], f["downloaded"]) for n, f in files.items()} == {
        "notes.pdf": (False, True), "slides.pdf": (True, True), "extra.pdf": (True, True),
        "reading.pdf": (False, True),
    }  # fmt: skip
    assert files["slides.pdf"]["size"] == 2500 and files["slides.pdf"]["type"] == "application/pdf"
    assert [link["url"] for link in materials["links"]] == [
        "https://video.example.invalid/watch?v=1", "https://read.example.invalid/x",
        "https://docs.example.invalid/guide?x=1", "https://video.example.invalid/embed/2",
        "https://video.example.invalid/clip.mp4",
    ]  # fmt: skip
    assert [link.get("source") for link in materials["links"]] == [
        None,
        None,
        "page",
        "page",
        "book",
    ]
    (folder / FOLDER_A / "materials" / "notes.pdf").unlink()
    again = bridge("/courses/5/materials").json()["sections"][0]["files"][0]
    assert (again["name"], again["downloaded"]) == ("notes.pdf", False)
    news = bridge("/courses/5/announcements").json()["announcements"]
    assert [n["subject"] for n in news] == [
        "Example exam date",
        "Example room change",
        "Example welcome",
    ]
    assert news[0]["text"] == "On Monday" and "author" not in news[0]
    assignments = bridge("/courses/5/assignments").json()["assignments"]
    assert [(a["id"], a["due"]) for a in assignments] == [(42, TS - 40 * DAY), (41, TS + 4 * DAY)]
    assert assignments[1]["status"] == {
        "submission": "new",
        "grading": "notgraded",
        "graded": False,
    }
    assert assignments[0]["status"] is None  # too old to be asked about
    assert (
        bridge("/courses/7/assignments").json()["assignments"][0]["status"]["submission"]
        == "submitted"
    )
    grades = bridge("/courses/5/grades").json()
    assert grades["overall"] == "80.00" and [g["grade"] for g in grades["items"]] == [
        "90.00",
        "70.00",
        "80.00",
    ]
    everything = bridge("/courses/grades").json()["courses"]
    assert [(c["id"], c["overall"], len(c["items"])) for c in everything] == [
        (A, "80.00", 3),
        (B, None, 1),
    ]
    assert bridge("/courses/99/materials").status_code == 404
    assert bridge("/courses/abc/materials").status_code == 422


def test_seen_clears_the_markers_of_one_course(bridge):
    assert bridge("/courses/5/seen", "post").json() == {"seen": True}
    listing = bridge("/courses").json()["courses"]
    assert not listing[0]["has_new"] and sum(listing[0]["new"].values()) == 0
    files = [f for s in bridge("/courses/5/materials").json()["sections"] for f in s["files"]]
    assert not any(f["new"] for f in files) and bridge("/courses/summary").json()["new_total"] == 0
    assert bridge("/courses/99/seen", "post").status_code == 404


def test_seen_during_a_sync_is_not_lost(site, configured, folder):
    sync(folder)
    change_the_site(site)
    real = moodle.Client.download

    def download_and_look(self, *args):
        assert ms.RUN_LOCK.acquire(blocking=False)  # as during a scheduled sync
        try:
            ms.seen(A)
        finally:
            ms.RUN_LOCK.release()
        return real(self, *args)

    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(moodle.Client, "download", download_and_look)
        sync(folder, when=later(7))
    assert sum(state()["courses"][str(A)]["new"].values()) == 0


def test_refresh_starts_one_sync_and_is_rate_limited(bridge, site, monkeypatch):
    def finished():
        for _ in range(200):
            if not ms.RUN_LOCK.locked():
                return
            time.sleep(0.01)
        raise AssertionError("the sync did not finish")

    site.calls.clear()
    started = bridge("/courses/refresh", "post")
    assert started.status_code == 202 and started.json()["started"]
    assert set(started.json()) >= {"running", "last_sync", "last_result", "last_error", "stale"}
    finished()
    assert site.calls.count("core_enrol_get_users_courses") == 1
    again = bridge("/courses/refresh", "post")  # a sync was tried just now
    assert again.status_code == 429 and again.json()["reason"] == "too_soon"
    assert site.calls.count("core_enrol_get_users_courses") == 1
    monkeypatch.setattr(ms, "now_utc", lambda: later(7.1))
    assert bridge("/courses/refresh", "post").status_code == 202
    finished()
    status = bridge("/courses/sync").json()
    assert status["last_sync"] == "2027-01-15T17:06:00Z" and not status["running"]
    assert status["last_result"]["downloaded"] == 0 and status["last_error"] is None


def test_endpoints_serve_the_saved_copy_while_the_site_is_down(
    bridge, site, settings_, monkeypatch
):
    site.down = True
    assert ms.run(settings_, lambda: later(8)) == "unreachable"
    site.calls.clear()
    for path in ("/courses", "/courses/summary", "/courses/5/materials", "/courses/5/announcements",
                 "/courses/5/assignments", "/courses/5/grades", "/courses/grades"):  # fmt: skip
        body = bridge(path).json()
        assert body["stale"] is True and body["last_sync"] == "2027-01-15T17:00:00Z", path
    assert len(bridge("/courses").json()["courses"]) == 2 and site.calls == []
    status = bridge("/courses/sync").json()
    assert status["last_error"] == "unreachable" and status["stale"]


def test_not_configured_answers(site, isolated_config, settings, auth):
    client = TestClient(create_app(settings))
    assert client.get("/courses/summary", headers=auth).json()["configured"] is False
    assert client.get("/courses", headers=auth).json()["courses"] == []
    refused = client.post("/courses/refresh", headers=auth)
    assert refused.status_code == 503 and refused.json()["error"] == "moodle_not_configured"


def key_of(bridge, name, course=5):
    sections = bridge(f"/courses/{course}/materials").json()["sections"]
    return next(f["key"] for s in sections for f in s["files"] if f["name"] == name)


def test_the_download_address_is_the_only_place_the_token_appears(bridge, site, configured, caplog):
    caplog.set_level(logging.DEBUG)
    key = key_of(bridge, "notes.pdf")
    answer = bridge(f"/courses/5/files/{key}/address", "post")
    assert answer.status_code == 200 and answer.headers["cache-control"] == "no-store"
    body = answer.json()
    assert set(body) == {"url", "name", "size", "type", "expires_in"}
    assert (body["name"], body["size"], body["type"], body["expires_in"]) == (
        "notes.pdf",
        1000,
        "application/pdf",
        600,
    )
    parts = urllib.parse.urlsplit(body["url"])
    assert (parts.scheme, parts.netloc) == ("https", "moodle.example.invalid")
    assert parts.path == "/webservice/pluginfile.php/11/mod_resource/content/1/notes.pdf"
    assert urllib.parse.parse_qsl(parts.query) == [("forcedownload", "1"), ("token", TOKEN)]
    # Nowhere else: no other endpoint, no state file, no log, not in the audit log.
    for method, path in [*ROUTES[:-2], ("post", f"/courses/5/files/{key}/offer")]:
        assert TOKEN not in bridge(path, method).text, path
    stored = [p.read_text() for p in configured.rglob("*") if p.is_file() and p.name != "env"]
    assert stored and not any(TOKEN in text for text in stored)
    assert TOKEN not in caplog.text and "pluginfile" not in caplog.text
    # The audit log has a record of the address (course, name, size), without it.
    records = [
        json.loads(line)
        for path in sorted((configured / "audit").glob("*.jsonl"))
        for line in path.read_text().splitlines()
    ]
    given = [r for r in records if r["tool"] == "CourseFileAddress"]
    assert [r["detail"] for r in given] == [{"course_id": A, "name": "notes.pdf", "size": 1000}]
    assert given[0]["session_id"] == f"course-{A}"
    offered = [r["detail"] for r in records if r["tool"] == "FileOffer"]
    assert offered == [{"course_id": A, "name": "notes.pdf", "size": 20}]  # the local copy
    log = "".join(p.read_text() for p in (configured / "audit").glob("*.jsonl"))
    assert TOKEN not in log and "pluginfile" not in log and SITE not in log
    assert bridge("/courses/5/files/unknown/address", "post").status_code == 404
    assert bridge(f"/courses/7/files/{key}/address", "post").status_code == 404


def test_no_address_without_its_audit_record(bridge, configured):
    key = key_of(bridge, "notes.pdf")
    shutil.rmtree(configured / "audit", ignore_errors=True)
    (configured / "audit").write_text("not a folder")  # the log cannot be written
    refused = bridge(f"/courses/5/files/{key}/address", "post")
    assert refused.status_code == 503 and refused.json()["error"] == "audit_unavailable"
    assert TOKEN not in refused.text and "url" not in refused.json()
    offer = bridge(f"/courses/5/files/{key}/offer", "post")
    assert offer.status_code == 503 and offer.json()["error"] == "audit_unavailable"


def test_the_address_needs_a_working_configuration(bridge, isolated_config):
    key = key_of(bridge, "notes.pdf")
    (isolated_config / "env").write_text("")
    (isolated_config / "env").chmod(0o600)
    refused = bridge(f"/courses/5/files/{key}/address", "post")
    assert refused.status_code == 503 and refused.json()["error"] == "moodle_not_configured"


def test_a_stored_address_outside_the_site_is_refused(bridge):
    key = key_of(bridge, "notes.pdf")
    stored = state()
    stored["courses"][str(A)]["files"][key]["url"] = (
        "https://elsewhere.example.invalid/pluginfile.php/1/x"
    )
    ms.state_path().write_text(json.dumps(stored))
    refused = bridge(f"/courses/5/files/{key}/address", "post")
    assert (
        refused.status_code == 409 and TOKEN not in refused.text and "elsewhere" not in refused.text
    )


def test_the_offer_sends_the_computers_copy(bridge, folder):
    key = key_of(bridge, "slides.pdf")
    card = bridge(f"/courses/5/files/{key}/offer", "post").json()
    assert set(card) == {"offer", "name", "type", "size", "session_id"}
    assert (card["name"], card["type"], card["size"]) == ("slides.pdf", "application/pdf", 28)
    fetched = bridge(f"/files/{card['offer']}?session_id={card['session_id']}")
    assert fetched.status_code == 200 and fetched.content == b"second version of the slides"
    assert bridge(f"/files/{card['offer']}?session_id=another").status_code == 404
    (folder / FOLDER_A / "materials" / "slides.pdf").unlink()
    assert bridge(f"/courses/5/files/{key}/offer", "post").status_code == 404
    assert bridge(f"/courses/5/files/{key}/offer", "post").json()["error"] == "no_local_copy"
    assert bridge("/courses/5/files/unknown/offer", "post").status_code == 404


def brief_entry(name="brief.pdf", size=900, modified=TS - DAY):
    url = f"{SITE}/webservice/pluginfile.php/13/mod_assign/introattachment/0/{name}"
    return {"filename": name, "filepath": "/", "filesize": size, "fileurl": url,
            "timemodified": modified, "mimetype": "application/pdf"}  # fmt: skip


def give_homework_in_full(site):
    site.assignments[A][0].update(
        cmid=141, allowsubmissionsfromdate=TS - 5 * DAY, cutoffdate=TS + 6 * DAY,
        gradingduedate=TS + 10 * DAY, timemodified=TS - DAY,
        intro="<p>Write <b>two</b> pages; see <a href='https://docs.example.invalid/style'>the style guide</a>.</p>",
        introattachments=[brief_entry()],
    )  # fmt: skip


def test_assignments_in_full_with_their_files(site, configured, folder, brain):
    give_homework_in_full(site)
    result = sync(folder, brain=brain)
    assert result["downloaded"] == 5
    attached = folder / FOLDER_A / "assignments" / "example-homework-part-1-41" / "brief.pdf"
    assert attached.read_bytes() == b"content of brief.pdf"
    text = (folder / FOLDER_A / "assignments" / "example-homework-part-1-41.md").read_text()
    assert text.startswith("# Example homework (part 1)\n")
    assert "Assignment of the course Example course A." in text and "- Submission: new" in text
    assert "Write **two** pages; see [the style guide](https://docs.example.invalid/style)." in text
    assert "- brief.pdf (`example-homework-part-1-41/brief.pdf`)" in text
    assert "outside text" in text and "<p>" not in text
    # The attachment is not a file of the course page: not in Materials, not in the summary.
    stored = state()["courses"][str(A)]
    materials = [k for s in stored["sections"] for k in s["files"]]
    assert all(stored["files"][k]["name"] != "brief.pdf" for k in materials)
    assert "3 files, 2 links" in (brain / "courses" / f"{FOLDER_A}.md").read_text()
    # A later sync: the attachment changed, another one was added; both are new files.
    site.asked.clear()
    site.assignments[A][0]["introattachments"] = [brief_entry(size=950, modified=TS),
                                                  brief_entry("data.csv", 50)]  # fmt: skip
    again = sync(folder, when=later(7))
    assert sorted(site.downloads()) == ["brief.pdf", "data.csv"] and again["new"]["files"] == 2
    # Removed on the site: gone from the assignment, the local copy stays.
    site.assignments[A][0]["introattachments"] = []
    sync(folder, when=later(14))
    assert attached.is_file()
    assert (
        "Attached files"
        not in (folder / FOLDER_A / "assignments" / "example-homework-part-1-41.md").read_text()
    )


def test_after_an_upgrade_existing_pages_and_attachments_are_not_news(site, configured, folder):
    give_homework_in_full(site)
    sync(folder)
    # A state written before pages, books and attachments were synced.
    stored = state()
    for record in stored["courses"].values():
        record["parts"] = [p for p in record["parts"] if p not in ("pages", "attachments")]
        record["files"] = {k: f for k, f in record["files"].items() if f.get("assignment") is None}
        record.pop("texts", None)
        for item in record.get("assignments") or []:
            for name in ("files", "folder", "description", "task"):
                item.pop(name, None)
    ms.state_path().write_text(json.dumps(stored))
    result = sync(folder, when=later(7))
    assert result["new"] == {"files": 0, "pages": 0, "assignments": 0, "announcements": 0, "deadlines": 0,
                             "grades": 0}  # fmt: skip


def test_assignment_endpoints(site, configured, folder, settings_, auth, monkeypatch):
    give_homework_in_full(site)
    monkeypatch.setattr(time, "time", lambda: float(TS))
    sync(folder)
    client = TestClient(create_app(settings_))
    listing = client.get("/courses/5/assignments", headers=auth).json()["assignments"]
    homework = next(a for a in listing if a["id"] == 41)
    assert set(homework) == {"id", "name", "opens", "opens_iso", "due", "due_iso", "cutoff",
                             "cutoff_iso", "status", "submitted", "files", "task", "mark",
                             "marked_on"}  # fmt: skip
    assert (homework["opens"], homework["cutoff"], homework["files"]) == (
        TS - 5 * DAY,
        TS + 6 * DAY,
        1,
    )
    assert homework["status"]["submission"] == "new" and homework["submitted"] is False
    detail = client.get("/courses/5/assignments/41", headers=auth).json()
    assert detail["course"] == "Example course A" and detail["grading_due"] == TS + 10 * DAY
    assert detail["description"].startswith("Write **two** pages")
    (brief,) = detail["files"]
    assert (brief["name"], brief["size"], brief["downloaded"]) == ("brief.pdf", 900, True)
    # Attached files use the course file routes.
    card = client.post(f"/courses/5/files/{brief['key']}/offer", headers=auth).json()
    fetched = client.get(f"/files/{card['offer']}?session_id={card['session_id']}", headers=auth)
    assert fetched.content == b"content of brief.pdf"
    address = client.post(f"/courses/5/files/{brief['key']}/address", headers=auth).json()
    assert "/mod_assign/introattachment/0/brief.pdf" in address["url"]
    assert client.get("/courses/5/assignments/99", headers=auth).status_code == 404
    assert client.get("/courses/7/assignments/41", headers=auth).status_code == 404
    assert TOKEN not in client.get("/courses/5/assignments/41", headers=auth).text
    assert "fileurl" not in client.get("/moodle/assignments", headers=auth).text


def test_the_offer_follows_the_rules_for_files(bridge, settings_, folder, auth):
    key = key_of(bridge, "notes.pdf")
    small = TestClient(create_app(dataclasses.replace(settings_, allowed_paths=())))
    refused = small.post(f"/courses/5/files/{key}/offer", headers=auth)
    assert refused.status_code == 409 and refused.json()["error"] == "file_unavailable"
    assert str(folder) not in refused.text


def test_the_phone_may_call_the_course_routes():
    from lifeos_bridge import relay

    assert "/courses" in relay.ALLOWED_PREFIXES and "/moodle" in relay.ALLOWED_PREFIXES


def test_moodle_sync_is_the_same_module():
    assert moodle_sync is ms
