# ruff: noqa: E501
"""Moodle client and endpoints, against a stand-in site. No network, synthetic courses."""

import io
import urllib.error
import urllib.parse

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import moodle, moodle_api
from lifeos_bridge import moodle_login as ml
from lifeos_bridge.app import create_app

SITE = "https://moodle.example.invalid"
TOKEN = "tok-secret-123"
FILE_URL = f"{SITE}/webservice/pluginfile.php/11/mod_resource/content/1/notes.pdf?forcedownload=1"

COURSES = [
    {"id": 5, "shortname": "EX101", "fullname": "Example course", "visible": 1,
     "startdate": 1790000000, "enddate": 0, "lastaccess": 1795000000, "summary": "<p>ignored</p>",
     "enrolledusercount": 40},
    {"id": 7, "shortname": "EX102", "fullname": "Another example course"},
    "not a course",
]  # fmt: skip
CONTENTS = [
    {"id": 1, "section": 0, "name": "General", "visible": 1, "summary": "<p>ignored</p>", "modules": [
        {"id": 21, "name": "Lecture notes", "modname": "resource", "visible": 1,
         "url": f"{SITE}/mod/resource/view.php?id=21", "contents": [
             {"type": "file", "filename": "notes.pdf", "filepath": "/", "filesize": 1234,
              "mimetype": "application/pdf", "timemodified": 1795000000, "fileurl": FILE_URL,
              "author": "Someone"},
             {"type": "url", "filename": "A link", "fileurl": "https://elsewhere.example.invalid/"},
             {"type": "file", "filename": "no address.txt"}]},
        {"id": 22, "name": "Forum", "modname": "forum"},
        "not a module"]},
    {"id": 2, "section": 1, "name": "Week 1", "visible": 0},
]  # fmt: skip
ASSIGNMENTS = {
    "courses": [
        {"id": 5, "fullname": "Example course", "assignments": [
            {"id": 31, "course": 5, "name": "Example assignment", "duedate": 1796000000,
             "cutoffdate": 0, "allowsubmissionsfromdate": 1794000000, "intro": "<p>ignored</p>"},
            {"id": 32, "name": "No dates"}]},
        {"id": 7},
    ],
    "warnings": [],
}  # fmt: skip


def event(number):
    return {"id": 100 + number, "name": f"Example deadline {number}", "timesort": 1796000000 + number,
            "course": {"id": 5, "fullname": "Example course"}, "modulename": "assign",
            "eventtype": "due", "url": f"{SITE}/mod/assign/view.php?id=1",
            "description": "<p>ignored</p>", "action": {"url": f"{SITE}/x", "name": "Add submission"}}  # fmt: skip


class Site:
    """Stands in for the web-service address; records the decoded fields of every call."""

    def __init__(self):
        self.calls = []
        self.error = None
        self.down = False
        self.events = [event(n) for n in range(3)]
        self.answers = {}

    def post(self, url, data, content_type):
        assert url == f"{SITE}/webservice/rest/server.php"
        assert content_type == "application/x-www-form-urlencoded"
        fields = dict(urllib.parse.parse_qsl(data.decode()))
        self.calls.append(fields)
        if self.down:
            raise ml.LoginError("the Moodle site could not be reached (URLError)")
        if fields["wstoken"] != TOKEN:
            return {"exception": "moodle_exception", "errorcode": "invalidtoken",
                    "message": "Invalid token - token not found"}  # fmt: skip
        if self.error:
            return {"exception": "x", "errorcode": self.error, "message": f"secret text at {SITE}"}
        function = fields["wsfunction"]
        if function in self.answers:
            return self.answers[function]
        if function == "core_webservice_get_site_info":
            return {"userid": 42, "username": "someone", "fullname": "Some One", "sitename": "A site",
                    "release": "4.1.9 (Build: 1)", "downloadfiles": 1, "siteurl": SITE,
                    "functions": [{"name": "core_course_get_contents", "version": "1"}, {"name": "a"}]}  # fmt: skip
        if function == "core_enrol_get_users_courses":
            return COURSES
        if function == "core_course_get_contents":
            return CONTENTS
        if function == "mod_assign_get_assignments":
            return ASSIGNMENTS
        if function == "core_calendar_get_action_events_by_timesort":
            after = int(fields.get("aftereventid", 0))
            left = [e for e in self.events if e["id"] > after]
            return {"events": left[: int(fields["limitnum"])], "firstid": 0, "lastid": 0}
        raise AssertionError(function)

    def named(self, function):
        return [call for call in self.calls if call["wsfunction"] == function]


@pytest.fixture
def site(monkeypatch):
    stand_in = Site()
    monkeypatch.setattr(ml, "_post", stand_in.post)
    return stand_in


@pytest.fixture
def client_():
    return moodle.Client(SITE + "/", TOKEN)


def configure(config, token=TOKEN):
    path = config / "env"
    path.write_text(f"MOODLE_URL={SITE}/\nMOODLE_TOKEN={token}\n")
    path.chmod(0o600)


# ---- protocol -------------------------------------------------------------------


def test_parameters_are_flattened_the_moodle_way():
    assert moodle.flatten({"courseids": [5, 7]}) == [("courseids[0]", "5"), ("courseids[1]", "7")]
    assert moodle.flatten({"options": [{"name": "x", "value": True}], "userid": 3, "skip": None}) == [
        ("options[0][name]", "x"), ("options[0][value]", "1"), ("userid", "3"),
    ]  # fmt: skip
    assert moodle.flatten({"a": {"b": [1, False]}}) == [("a[b][0]", "1"), ("a[b][1]", "0")]
    assert moodle.flatten({}) == []


def test_a_call_sends_token_function_and_format(site, client_):
    site.answers = {"some_function": {"fine": True}}
    assert client_.call("some_function", {"courseids": [5]}) == {"fine": True}
    assert site.calls == [{"wstoken": TOKEN, "wsfunction": "some_function",
                           "moodlewsrestformat": "json", "courseids[0]": "5"}]  # fmt: skip


def test_site_info_keeps_no_names(site, client_):
    info = client_.site_info()
    assert info == {"userid": 42, "release": "4.1.9", "downloadfiles": True,
                    "functions": ["a", "core_course_get_contents"]}  # fmt: skip


def test_courses(site, client_):
    found = client_.courses()
    assert site.named("core_enrol_get_users_courses")[0]["userid"] == "42"
    assert found[0] == {
        "id": 5, "shortname": "EX101", "fullname": "Example course", "visible": True,
        "startdate": 1790000000, "startdate_iso": moodle.iso(1790000000),
        "enddate": None, "enddate_iso": None,
        "lastaccess": 1795000000, "lastaccess_iso": moodle.iso(1795000000),
    }  # fmt: skip
    assert found[1]["id"] == 7 and found[1]["visible"] is True and found[1]["startdate"] is None
    assert len(found) == 2
    client_.courses()
    assert len(site.named("core_webservice_get_site_info")) == 1  # the user number is remembered
    assert moodle.iso(1790000000).startswith("2026-09-2") and len(moodle.iso(1790000000)) == 22


def test_contents(site, client_):
    sections = client_.contents(5)
    assert site.named("core_course_get_contents")[0]["courseid"] == "5"
    assert [(s["id"], s["number"], s["name"], s["visible"]) for s in sections] == [
        (1, 0, "General", True), (2, 1, "Week 1", False),
    ]  # fmt: skip
    assert set(sections[0]) == {"id", "number", "name", "visible", "modules"}
    notes, forum = sections[0]["modules"]
    assert (notes["id"], notes["name"], notes["type"], notes["visible"]) == (
        21,
        "Lecture notes",
        "resource",
        True,
    )
    assert set(notes) == {"id", "name", "type", "visible", "files"}
    assert notes["files"] == [{
        "key": notes["files"][0]["key"], "filename": "notes.pdf", "filesize": 1234,
        "mimetype": "application/pdf", "fileurl": FILE_URL,
        "timemodified": 1795000000, "timemodified_iso": moodle.iso(1795000000),
    }]  # fmt: skip
    assert len(notes["files"][0]["key"]) == 16
    assert forum["files"] == [] and sections[1]["modules"] == []


def test_assignments(site, client_):
    found = client_.assignments([5, 7])
    sent = site.named("mod_assign_get_assignments")[0]
    assert (sent["courseids[0]"], sent["courseids[1]"]) == ("5", "7")
    assert found[0] == {
        "id": 31, "course": 5, "name": "Example assignment",
        "duedate": 1796000000, "duedate_iso": moodle.iso(1796000000),
        "cutoffdate": None, "cutoffdate_iso": None,
        "allowsubmissionsfromdate": 1794000000, "allowsubmissionsfromdate_iso": moodle.iso(1794000000),
    }  # fmt: skip
    assert found[1]["course"] == 5 and found[1]["duedate"] is None and len(found) == 2
    client_.assignments()
    assert not any(
        key.startswith("courseids") for key in site.named("mod_assign_get_assignments")[1]
    )


def test_deadlines_are_paged(site, client_, monkeypatch):
    found = client_.deadlines(1000, 2000)
    sent = site.named("core_calendar_get_action_events_by_timesort")
    assert len(sent) == 1 and "aftereventid" not in sent[0]
    assert (sent[0]["timesortfrom"], sent[0]["timesortto"], sent[0]["limitnum"]) == (
        "1000",
        "2000",
        "50",
    )
    assert found[0] == {"id": 100, "name": "Example deadline 0", "course_id": 5, "module": "assign",
                        "type": "due", "timesort": 1796000000, "timesort_iso": moodle.iso(1796000000)}  # fmt: skip
    site.events = [event(n) for n in range(120)]
    found = client_.deadlines(1000, 2000)
    sent = site.named("core_calendar_get_action_events_by_timesort")[1:]
    assert [call.get("aftereventid") for call in sent] == [None, "149", "199"]
    assert [item["id"] for item in found] == list(range(100, 220))
    monkeypatch.setattr(moodle, "MAX_EVENT_PAGES", 2)  # an endless list cannot loop forever
    assert len(client_.deadlines(1000, 2000)) == 100


def test_missing_and_odd_fields_do_not_break_parsing(site, client_):
    site.answers = {
        "core_enrol_get_users_courses": [{"id": "9", "visible": 0, "startdate": "abc", "new_field": [1]}],
        "core_course_get_contents": [{"modules": [{"contents": [{"type": "file", "fileurl": FILE_URL}]}]}, 5],
        "mod_assign_get_assignments": {"courses": [{"assignments": [{}, None]}, None]},
        "core_calendar_get_action_events_by_timesort": {"events": [{"id": 1}, {"course": "x", "id": 2}]},
    }  # fmt: skip
    course = client_.courses()[0]
    assert (course["id"], course["visible"], course["startdate"], course["fullname"]) == (
        9,
        False,
        None,
        "",
    )
    section = client_.contents(9)[0]
    assert section["id"] is None and section["modules"][0]["files"][0]["filename"] == ""
    assert client_.assignments() == [moodle.parse_assignment({}, None)]
    assert [e["course_id"] for e in client_.deadlines(1, 2)] == [None, None]
    site.answers = {
        "core_enrol_get_users_courses": {"unexpected": 1},
        "core_course_get_contents": "text",
        "mod_assign_get_assignments": [],
        "core_webservice_get_site_info": {"release": "4"},
    }
    for call in (client_.contents, client_.assignments, moodle.Client(SITE, TOKEN).site_info):
        with pytest.raises(moodle.MoodleError) as error:
            call(1) if call == client_.contents else call()
        assert error.value.code == "bad_answer"
    with pytest.raises(moodle.MoodleError):
        client_.courses()


def test_errors_are_mapped_to_safe_codes(site, client_):
    with pytest.raises(moodle.MoodleError) as error:
        moodle.Client(SITE, "old-token").courses()
    assert error.value.code == "token_expired"
    site.error = "access exception!<b>"
    with pytest.raises(moodle.MoodleError) as error:
        client_.courses()
    assert (error.value.code, error.value.errorcode) == ("moodle_error", "accessexceptionb")
    site.down = True
    with pytest.raises(moodle.MoodleError) as down:
        client_.courses()
    assert down.value.code == "unreachable" and down.value.__cause__ is None
    for failure in (error.value, down.value):
        text = str(failure) + failure.message + repr(failure)
        assert "secret text" not in text and "example.invalid" not in text and TOKEN not in text
    assert TOKEN not in repr(client_) and "example" not in repr(client_)
    for url, token in (("", TOKEN), (SITE, "")):
        with pytest.raises(moodle.MoodleError) as error:
            moodle.Client(url, token)
        assert error.value.code == "not_configured"


def test_plain_http_is_never_used(monkeypatch):
    with pytest.raises(moodle.MoodleError) as error:
        moodle.Client("http://moodle.example.invalid", TOKEN).courses()
    assert error.value.code == "unreachable"


def test_from_env(isolated_config):
    with pytest.raises(moodle.MoodleError) as error:
        moodle.from_env()
    assert error.value.code == "not_configured"
    configure(isolated_config)
    assert isinstance(moodle.from_env(), moodle.Client)
    (isolated_config / "env").chmod(0o644)  # readable by others: not used
    with pytest.raises(moodle.MoodleError):
        moodle.from_env()


# ---- download ---------------------------------------------------------------------


class Response(io.BytesIO):
    def __init__(self, body, headers=None):
        super().__init__(body)
        self.headers = headers or {}


@pytest.fixture
def opened(monkeypatch, tmp_path):
    """Stands in for the download connection; records the addresses asked for."""
    (tmp_path / "files").mkdir()
    state = {
        "asked": [],
        "answer": lambda: Response(b"x" * 1000, {"Content-Length": "1000"}),
        "folder": tmp_path / "files",
    }

    def open_(address):
        state["asked"].append(address)
        return state["answer"]()

    monkeypatch.setattr(moodle, "_open", open_)
    return state


def test_download_writes_the_file_whole(client_, opened):
    target = opened["folder"] / "notes.pdf"
    assert client_.download(FILE_URL, target, 5000) == 1000
    assert target.read_bytes() == b"x" * 1000 and target.stat().st_mode & 0o777 == 0o600
    assert [p.name for p in opened["folder"].iterdir() if p.is_file()] == ["notes.pdf"]
    asked = urllib.parse.urlsplit(opened["asked"][0])
    assert asked.path == "/webservice/pluginfile.php/11/mod_resource/content/1/notes.pdf"
    assert urllib.parse.parse_qsl(asked.query) == [("forcedownload", "1"), ("token", TOKEN)]


def test_download_uses_the_token_address_and_one_token_only(client_, opened):
    plain = f"{SITE}/pluginfile.php/11/mod_resource/content/1/a%20b.pdf?token=other"
    client_.download(plain, opened["folder"] / "a", 5000)
    asked = urllib.parse.urlsplit(opened["asked"][0])
    assert asked.path == "/webservice/pluginfile.php/11/mod_resource/content/1/a%20b.pdf"
    assert asked.query == f"token={TOKEN}"


@pytest.mark.parametrize("address", [
    "https://elsewhere.example.invalid/webservice/pluginfile.php/1/a.pdf",
    "http://moodle.example.invalid/webservice/pluginfile.php/1/a.pdf",
    "https://moodle.example.invalid.evil.example/webservice/pluginfile.php/1/a.pdf",
    "https://moodle.example.invalid@evil.example/webservice/pluginfile.php/1/a.pdf",
    "https://moodle.example.invalid:8443/webservice/pluginfile.php/1/a.pdf",
    f"{SITE}/mod/resource/view.php?id=1",
    "/webservice/pluginfile.php/1/a.pdf",
    "",
])  # fmt: skip
def test_download_refuses_addresses_outside_the_site(client_, opened, address):
    with pytest.raises(moodle.MoodleError) as error:
        client_.download(address, opened["folder"] / "a", 5000)
    assert error.value.code == "bad_file_url"
    assert opened["asked"] == [] and list(opened["folder"].iterdir()) == []


def test_download_enforces_the_size_limit(client_, opened):
    target = opened["folder"] / "big"
    target.write_bytes(b"earlier version")
    for headers in ({"Content-Length": "1000"}, {}):  # declared, and found while reading
        opened["answer"] = lambda headers=headers: Response(b"x" * 1000, headers)
        with pytest.raises(moodle.MoodleError) as error:
            client_.download(FILE_URL, target, 999)
        assert error.value.code == "too_large"
    assert target.read_bytes() == b"earlier version"  # untouched
    assert [p.name for p in opened["folder"].iterdir() if p.is_file()] == ["big"]


def test_download_failures_never_show_the_token_or_the_address(client_, opened):
    def http_error():
        raise urllib.error.HTTPError(opened["asked"][-1], 404, "Not Found", {}, None)

    def refusal():
        return Response(b'{"error":"Invalid token","errorcode":"invalidtoken"}',
                        {"Content-Type": "application/json"})  # fmt: skip

    class Broken(Response):
        def read(self, size=-1):
            raise OSError(f"connection lost while reading {opened['asked'][-1]}")

    for answer in (http_error, refusal, lambda: Broken(b"")):
        opened["answer"] = answer
        with pytest.raises(moodle.MoodleError) as error:
            client_.download(FILE_URL, opened["folder"] / "a", 5000)
        failure = error.value
        assert failure.code == "download_failed"
        assert failure.__cause__ is None
        assert failure.__context__ is None or failure.__suppress_context__
        text = str(failure) + repr(failure) + failure.message
        assert TOKEN not in text and "example.invalid" not in text and "pluginfile" not in text
    assert list(opened["folder"].iterdir()) == []
    # a real JSON file is still a file
    opened["answer"] = lambda: Response(b'{"data": [1, 2]}', {"Content-Type": "application/json"})
    assert client_.download(FILE_URL, opened["folder"] / "data.json", 5000) == 16


# ---- endpoints --------------------------------------------------------------------


@pytest.fixture
def bridge(settings, isolated_config, site, auth):
    configure(isolated_config)
    client = TestClient(create_app(settings))
    return lambda path: client.get(path, headers=auth)


PATHS = ["/moodle/courses", "/moodle/courses/5", "/moodle/assignments", "/moodle/deadlines"]


def test_endpoints_need_the_bearer_token(settings, isolated_config, site):
    configure(isolated_config)
    client = TestClient(create_app(settings))
    for path in [*PATHS, "/moodle/status"]:
        assert client.get(path).status_code == 401
        assert client.get(path, headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert site.calls == []


def test_endpoints_return_the_parsed_views(bridge, site):
    courses = bridge("/moodle/courses").json()
    assert [c["fullname"] for c in courses["courses"]] == [
        "Example course",
        "Another example course",
    ]
    course = bridge("/moodle/courses/5").json()
    assert course["course_id"] == 5 and len(course["sections"]) == 2
    file = course["sections"][0]["modules"][0]["files"][0]
    assert set(file) == {
        "key",
        "filename",
        "filesize",
        "mimetype",
        "timemodified",
        "timemodified_iso",
    }
    assignments = bridge("/moodle/assignments").json()["assignments"]
    assert [a["id"] for a in assignments] == [31, 32]
    bridge("/moodle/assignments?course=5")
    assert site.named("mod_assign_get_assignments")[-1]["courseids[0]"] == "5"
    deadlines = bridge("/moodle/deadlines?days=7").json()
    assert set(deadlines) == {"from", "to", "deadlines"} and len(deadlines["deadlines"]) == 3
    sent = site.named("core_calendar_get_action_events_by_timesort")[-1]
    assert int(sent["timesortto"]) - int(sent["timesortfrom"]) == 7 * 86400
    bridge("/moodle/deadlines?days=100000")
    sent = site.named("core_calendar_get_action_events_by_timesort")[-1]
    assert int(sent["timesortto"]) - int(sent["timesortfrom"]) == 365 * 86400
    assert bridge("/moodle/courses/abc").status_code == 422


def test_responses_never_contain_addresses_names_or_the_token(bridge):
    for path in [*PATHS, "/moodle/status", "/moodle/courses/abc"]:
        text = bridge(path).text
        for secret in (TOKEN, "fileurl", "pluginfile", "example.invalid", "https://", "someone",
                       "Some One", "ignored", "Add submission"):  # fmt: skip
            assert secret not in text, (path, secret)


def test_answers_are_cached_for_ten_minutes_per_call(settings, isolated_config, site, auth):
    configure(isolated_config)
    now = [1000.0]
    api = moodle_api.MoodleApi(timer=lambda: now[0])
    count = lambda name: len(site.named(name))  # noqa: E731
    api.courses()
    api.courses()
    assert count("core_enrol_get_users_courses") == 1
    api.courses(refresh=True)
    assert count("core_enrol_get_users_courses") == 2
    now[0] += moodle_api.CACHE_SECONDS - 1
    api.courses()
    assert count("core_enrol_get_users_courses") == 2
    now[0] += 2
    api.courses()
    assert count("core_enrol_get_users_courses") == 3
    # one entry per call signature
    api.contents(5), api.contents(7), api.contents(5)
    assert count("core_course_get_contents") == 2
    api.assignments(), api.assignments(5), api.assignments(5), api.deadlines(30), api.deadlines(7)
    assert count("mod_assign_get_assignments") == 2
    assert count("core_calendar_get_action_events_by_timesort") == 2
    # errors are not kept
    site.down = True
    with pytest.raises(Exception, match="moodle_unreachable"):
        api.courses(refresh=True)
    site.down = False
    assert len(api.courses()) == 2  # the earlier answer is still there


def test_refresh_through_the_endpoint(bridge, site):
    bridge("/moodle/courses"), bridge("/moodle/courses")
    assert len(site.named("core_enrol_get_users_courses")) == 1
    bridge("/moodle/courses?refresh=true")
    assert len(site.named("core_enrol_get_users_courses")) == 2


def test_a_file_key_resolves_again_inside_the_bridge(isolated_config, site):
    configure(isolated_config)
    api = moodle_api.MoodleApi()
    shown = moodle_api.without_addresses(api.contents(5))
    key = shown[0]["modules"][0]["files"][0]["key"]
    assert api.find_file(5, key)["fileurl"] == FILE_URL
    assert api.find_file(5, "0" * 16) is None
    assert "fileurl" in api.contents(5)[0]["modules"][0]["files"][0]  # the stored answer is intact


def test_not_configured_is_a_503(settings, isolated_config, site, auth):
    client = TestClient(create_app(settings))
    for path in PATHS:
        answer = client.get(path, headers=auth)
        assert answer.status_code == 503 and answer.json()["error"] == "moodle_not_configured"
    assert site.calls == []
    assert client.get("/moodle/status", headers=auth).json()["state"] == "not_configured"


def test_an_expired_token_is_a_503_that_says_what_to_run(settings, isolated_config, site, auth):
    configure(isolated_config)
    client = TestClient(create_app(settings))
    assert client.get("/moodle/courses", headers=auth).status_code == 200
    configure(isolated_config, token="expired-token")
    for path in reversed(
        PATHS
    ):  # the courses last: once the site refused, nothing stored is served
        answer = client.get(path, headers=auth)
        assert answer.status_code == 503
        assert answer.json() == {
            "error": "moodle_sign_in_needed",
            "message": "Run scripts/moodle-login",
        }


def test_other_failures_are_a_502_without_the_sites_text(bridge, site):
    site.error = "accessexception"
    answer = bridge("/moodle/courses")
    assert answer.status_code == 502 and answer.json()["error"] == "moodle_error"
    assert "accessexception" in answer.json()["message"] and "secret text" not in answer.text
    site.down = True
    answer = bridge("/moodle/assignments")
    assert answer.status_code == 502 and answer.json()["error"] == "moodle_unreachable"


# ---- grades, announcements, links (for the course sync) ----------------------------


def test_plain_text_drops_markup_scripts_and_control_characters():
    html = "<p>One &amp; <b>two</b></p><script>alert(1)</script><style>p{}</style><ul><li>a</li><li>b\x00\x1b</li></ul>"
    assert moodle.plain_text(html) == "One & two\n\na\n\nb"
    assert moodle.plain_text("x" * 50, 10) == "x" * 10 and moodle.plain_text(None) == ""
    assert moodle.plain_text("<p>broken <b>markup") == "broken markup"
    assert moodle.one_line(" a\n<br>b\t c ") == "a b c"


def test_a_stored_address_never_keeps_a_token():
    assert moodle.without_token(f"{FILE_URL}&token=abc&wstoken=def") == FILE_URL
    assert moodle.without_token(FILE_URL) == FILE_URL


def test_course_returns_sections_and_links_in_one_call(site, client_):
    contents = [{"id": 1, "section": 0, "name": "Week <b>1</b>", "modules": [
        {"id": 31, "name": "Example video", "modname": "url", "contents": [
            {"type": "url", "filename": "ignored name", "fileurl": "https://video.example.invalid/v?id=1&x=2"}]},
        {"id": 32, "name": "Page", "modname": "resource", "contents": [
            {"type": "file", "filename": "a.pdf", "fileurl": FILE_URL},
            {"type": "url", "filename": "Outside page", "fileurl": "http://page.example.invalid/"},
            {"type": "url", "filename": "bad", "fileurl": "javascript:alert(1)"},
            {"type": "url", "filename": "bad", "fileurl": "https://a.example.invalid/x y"},
            {"type": "url", "filename": "bad", "fileurl": "https://a.example.invalid/" + "x" * 3000}]},
        {"id": 33, "name": "No address", "modname": "url", "contents": [{"type": "url"}]}]}]  # fmt: skip
    site.answers = {"core_course_get_contents": contents}
    course = client_.course(5)
    assert len(site.named("core_course_get_contents")) == 1
    assert course["links"] == [
        {
            "section": "Week 1",
            "name": "Example video",
            "url": "https://video.example.invalid/v?id=1&x=2",
        },
        {"section": "Week 1", "name": "Outside page", "url": "http://page.example.invalid/"},
    ]
    assert [f["filename"] for f in course["sections"][0]["modules"][1]["files"]] == ["a.pdf"]
    assert client_.contents(5) == course["sections"]


def test_grade_items(site, client_):
    site.answers = {"gradereport_user_get_grade_items": {"usergrades": [
        {"userid": 7, "userfullname": "Other", "gradeitems": [{"id": 1, "itemname": "not mine"}]},
        {"userid": 42, "userfullname": "Some One", "gradeitems": [
            {"id": 81, "itemname": "<span>Example quiz</span>", "itemtype": "mod", "itemmodule": "quiz",
             "graderaw": 7.5, "gradeformatted": "7.50", "rangeformatted": "0&ndash;10",
             "percentageformatted": "75.00 %", "feedback": "<p>Good <b>work</b></p>",
             "gradedategraded": 1795000000},
            {"id": 82, "itemname": "Example task", "itemtype": "mod", "gradeformatted": "-",
             "feedback": ""},
            {"id": 83, "itemtype": "course", "graderaw": 75, "gradeformatted": "75.00"},
            "not an item"]}], "warnings": []}}  # fmt: skip
    quiz, task, total = client_.grade_items(5)
    sent = site.named("gradereport_user_get_grade_items")[0]
    assert (sent["courseid"], sent["userid"]) == ("5", "42")
    assert quiz == {
        "id": 81, "name": "Example quiz", "type": "mod", "module": "quiz", "grade": "7.50",
        "graded": True, "range": "0\u201310", "percentage": "75.00 %", "feedback": "Good work",
        "has_feedback": True, "gradedategraded": 1795000000,
        "gradedategraded_iso": moodle.iso(1795000000),
    }  # fmt: skip
    assert (task["grade"], task["graded"], task["has_feedback"], task["gradedategraded"]) == (
        "-",
        False,
        False,
        None,
    )
    assert (total["name"], total["type"], total["graded"]) == ("", "course", True)
    client_.grade_items(5, 99)
    assert site.named("gradereport_user_get_grade_items")[-1]["userid"] == "99"


def test_course_grades(site, client_):
    site.answers = {"gradereport_overview_get_course_grades": {"grades": [
        {"courseid": 5, "grade": "81.00", "rawgrade": "81"}, {"courseid": 7, "grade": "-"},
        {"courseid": 8, "grade": ""}, {"grade": "1"}], "warnings": []}}  # fmt: skip
    assert client_.course_grades() == {5: "81.00"}
    assert site.named("gradereport_overview_get_course_grades")[0]["userid"] == "42"


def test_announcements_come_from_the_news_forum_without_authors(site, client_):
    site.answers = {
        "mod_forum_get_forums_by_courses": [
            {"id": 61, "course": 5, "type": "general", "name": "Questions"},
            {"id": 62, "course": 5, "type": "news", "name": "Announcements"},
            {"id": 63, "course": 7, "type": "news"}, {"id": 64, "course": 7, "type": "news"}, "x"],
        "mod_forum_get_forum_discussions": {"discussions": [
            {"id": 901, "discussion": 11, "name": "Older", "subject": "Older <i>one</i>",
             "message": "<p>First</p><p>Second</p>", "created": 1795000000, "modified": 1795000100,
             "userfullname": "Some Teacher", "userpictureurl": f"{SITE}/pic", "usermodifiedfullname": "Some Teacher"},
            {"id": 902, "discussion": 12, "name": "Newer", "subject": "Newer", "message": "Text",
             "created": 1796000000, "modified": 1796000000},
            {"name": "no id"}], "warnings": []},
    }  # fmt: skip
    assert client_.news_forums([5, 7]) == {5: 62, 7: 63}
    sent = site.named("mod_forum_get_forums_by_courses")[0]
    assert (sent["courseids[0]"], sent["courseids[1]"]) == ("5", "7")
    newer, older = client_.announcements(62)
    sent = site.named("mod_forum_get_forum_discussions")[0]
    assert (sent["forumid"], sent["sortorder"], sent["page"], sent["perpage"]) == (
        "62",
        "3",
        "0",
        "100",
    )
    assert older == {
        "id": 11, "subject": "Older one", "message": "First\n\nSecond",
        "created": 1795000000, "created_iso": moodle.iso(1795000000),
        "modified": 1795000100, "modified_iso": moodle.iso(1795000100),
    }  # fmt: skip
    assert newer["id"] == 12 and len(client_.announcements(62, limit=1)) == 1
    assert "Teacher" not in str([newer, older]) and "example.invalid" not in str([newer, older])


def test_submission_status(site, client_):
    site.answers = {"mod_assign_get_submission_status": {"lastattempt": {
        "submission": {"status": "submitted", "userid": 42}, "graded": True, "gradingstatus": "graded"}}}  # fmt: skip
    assert client_.submission_status(31) == {
        "submission": "submitted",
        "grading": "graded",
        "graded": True,
    }
    assert site.named("mod_assign_get_submission_status")[0]["assignid"] == "31"
    site.answers = {"mod_assign_get_submission_status": {"warnings": []}}
    assert client_.submission_status(31) == {"submission": None, "grading": None, "graded": False}


def test_new_calls_map_errors_like_the_others(site, client_):
    site.error = "nopermissions"
    for call in (lambda: client_.grade_items(5, 42), lambda: client_.news_forums([5]),
                 lambda: client_.announcements(1), lambda: client_.submission_status(1)):  # fmt: skip
        with pytest.raises(moodle.MoodleError) as caught:
            call()
        assert caught.value.code == "moodle_error" and "example.invalid" not in str(caught.value)


def test_address_for_is_the_token_address_of_a_site_file(client_):
    address = client_.address_for(
        FILE_URL.replace("/webservice/pluginfile.php/", "/pluginfile.php/")
    )
    parts = urllib.parse.urlsplit(address)
    assert (parts.scheme, parts.netloc) == ("https", "moodle.example.invalid")
    assert parts.path.startswith("/webservice/pluginfile.php/")
    assert urllib.parse.parse_qsl(parts.query) == [("forcedownload", "1"), ("token", TOKEN)]
    for bad in (
        "https://elsewhere.example.invalid/pluginfile.php/1/x",
        f"{SITE}/login/index.php",
        "",
    ):
        with pytest.raises(moodle.MoodleError) as caught:
            client_.address_for(bad)
        assert caught.value.code == "bad_file_url" and TOKEN not in str(caught.value)


def test_download_never_writes_through_a_link(client_, opened, tmp_path):
    outside = tmp_path / "outside.txt"
    outside.write_text("untouched")
    (opened["folder"] / "notes.pdf.part").symlink_to(outside)
    with pytest.raises(moodle.MoodleError) as caught:
        client_.download(FILE_URL, opened["folder"] / "notes.pdf", 5000)
    assert caught.value.code == "download_failed" and outside.read_text() == "untouched"
