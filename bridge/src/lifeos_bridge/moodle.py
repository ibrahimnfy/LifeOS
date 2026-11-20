"""Read-only client for the university Moodle: courses, contents, assignments, deadlines.

Speaks Moodle's web-service protocol (docs/research/moodle-access.md,
section 6) with the token that ``scripts/moodle-login`` stored: ``POST
<site>/webservice/rest/server.php`` over HTTPS, standard library only.
Requests carry the token, so addresses and bodies are never logged, and no
error message contains the site's address, a file address, the token or any
text the site sent: errors are a :class:`MoodleError` with a fixed code.

Every function returns plain dicts with a fixed set of fields. Times are
given twice: the site's number (seconds since 1970, ``None`` when unset) and
``<field>_iso``, the same moment in local time.
"""

from __future__ import annotations

import contextlib
import hashlib
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Any

from . import moodle_login
from .config import ENV_FILE_NAME, ConfigError, config_dir, read_env_file

MAX_EVENTS_PER_CALL = 50  # the site refuses more
MAX_EVENT_PAGES = 20
DOWNLOAD_TIMEOUT = 60
CHUNK_BYTES = 64 * 1024
PLUGINFILE = "/pluginfile.php/"
WS_PLUGINFILE = "/webservice/pluginfile.php/"

MESSAGES = {
    "not_configured": "Moodle is not set up: MOODLE_URL and a stored token are needed",
    "token_expired": "the Moodle sign-in has expired; run scripts/moodle-login",
    "unreachable": "the Moodle site could not be reached",
    "bad_answer": "the Moodle site sent an answer that was not understood",
    "moodle_error": "the Moodle site refused the request",
    "bad_file_url": "that is not a file of the configured Moodle site",
    "too_large": "the file is larger than the allowed size",
    "download_failed": "the file could not be downloaded",
}


class MoodleError(Exception):
    """A call failed. ``code`` is one of :data:`MESSAGES`; the text is safe to show.

    ``errorcode`` is Moodle's own short code (letters, digits, ``_``) when the
    site named one; the site's message is never kept.
    """

    def __init__(self, code: str, errorcode: str | None = None) -> None:
        self.code = code
        self.errorcode = errorcode
        self.message = MESSAGES.get(code, code) + (f" ({errorcode})" if errorcode else "")
        super().__init__(self.message)


# ---- protocol -----------------------------------------------------------------


def flatten(params: dict[str, Any], prefix: str = "") -> list[tuple[str, str]]:
    """Moodle's form encoding: ``{"courseids": [5]}`` becomes ``courseids[0]=5``."""
    pairs: list[tuple[str, str]] = []
    for key, value in params.items():
        name = f"{prefix}[{key}]" if prefix else str(key)
        pairs.extend(_flatten_value(name, value))
    return pairs


def _flatten_value(name: str, value: Any) -> list[tuple[str, str]]:
    if value is None:
        return []
    if isinstance(value, dict):
        return flatten(value, name)
    if isinstance(value, (list, tuple)):
        return [
            pair
            for index, item in enumerate(value)
            for pair in _flatten_value(f"{name}[{index}]", item)
        ]
    if isinstance(value, bool):
        return [(name, "1" if value else "0")]
    return [(name, str(value))]


def _safe_code(value: object) -> str | None:
    code = re.sub(r"[^A-Za-z0-9_]", "", str(value or ""))[:60]
    return code or None


def _number(value: object) -> int | None:
    """A whole number, or None for anything else (missing, text, a truth value)."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().lstrip("-").isdigit():
        return int(value)
    return None


def _time(value: object) -> int | None:
    """A time of the site; 0 means "not set" there."""
    number = _number(value)
    return number if number and number > 0 else None


def iso(timestamp: int | None) -> str | None:
    """Local time, to the minute, with its offset."""
    if timestamp is None:
        return None
    try:
        return datetime.fromtimestamp(timestamp).astimezone().isoformat(timespec="minutes")
    except (OverflowError, OSError, ValueError):
        return None


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def _dicts(value: object) -> list[dict]:
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def _timed(record: dict, source: dict, *fields: str) -> dict:
    for name in fields:
        record[name] = _time(source.get(name))
        record[f"{name}_iso"] = iso(record[name])
    return record


def file_key(module_id: int | None, entry: dict) -> str:
    """A short stable name for one file of a course, without its address."""
    identity = f"{module_id}|{_text(entry.get('filepath'))}|{_text(entry.get('filename'))}"
    return hashlib.sha256(identity.encode()).hexdigest()[:16]


# ---- parsing --------------------------------------------------------------------


def parse_course(item: dict) -> dict:
    record = {
        "id": _number(item.get("id")),
        "shortname": _text(item.get("shortname")),
        "fullname": _text(item.get("fullname")),
        "visible": bool(item.get("visible", 1)),
    }
    return _timed(record, item, "startdate", "enddate", "lastaccess")


def parse_file(module_id: int | None, entry: dict) -> dict:
    record = {
        "key": file_key(module_id, entry),
        "filename": _text(entry.get("filename")),
        "filesize": _number(entry.get("filesize")),
        "mimetype": _text(entry.get("mimetype")) or None,
        "fileurl": _text(entry.get("fileurl")),
    }
    return _timed(record, entry, "timemodified")


def parse_module(item: dict) -> dict:
    module_id = _number(item.get("id"))
    return {
        "id": module_id,
        "name": _text(item.get("name")),
        "type": _text(item.get("modname")),
        "visible": bool(item.get("visible", 1)),
        "files": [
            parse_file(module_id, entry)
            for entry in _dicts(item.get("contents"))
            if entry.get("type") == "file" and entry.get("fileurl")
        ],
    }


def parse_section(item: dict) -> dict:
    return {
        "id": _number(item.get("id")),
        "number": _number(item.get("section")),
        "name": _text(item.get("name")),
        "visible": bool(item.get("visible", 1)),
        "modules": [parse_module(module) for module in _dicts(item.get("modules"))],
    }


def parse_assignment(item: dict, course_id: int | None) -> dict:
    record = {
        "id": _number(item.get("id")),
        "course": _number(item.get("course")) or course_id,
        "name": _text(item.get("name")),
    }
    return _timed(record, item, "duedate", "cutoffdate", "allowsubmissionsfromdate")


def parse_event(item: dict) -> dict:
    course = item.get("course")
    record = {
        "id": _number(item.get("id")),
        "name": _text(item.get("name")),
        "course_id": _number(course.get("id")) if isinstance(course, dict) else None,
        "module": _text(item.get("modulename")) or None,
        "type": _text(item.get("eventtype")) or None,
    }
    return _timed(record, item, "timesort")


# ---- the client -----------------------------------------------------------------


class Client:
    """One site and one token. Use :func:`from_env` to build it from the private settings."""

    def __init__(self, url: str, token: str) -> None:
        self._url = url.strip().rstrip("/")
        self._token = token
        self._userid: int | None = None
        if not self._url or not token:
            raise MoodleError("not_configured")

    def __repr__(self) -> str:  # never leak the address or the token
        return "Client(<hidden>)"

    def call(self, function: str, params: dict[str, Any] | None = None) -> Any:
        """Call one web-service function and return its decoded answer."""
        fields = [
            ("wstoken", self._token),
            ("wsfunction", function),
            ("moodlewsrestformat", "json"),
            *flatten(params or {}),
        ]
        body = urllib.parse.urlencode(fields).encode()
        try:
            answer = moodle_login._post(
                f"{self._url}/webservice/rest/server.php", body, "application/x-www-form-urlencoded"
            )
        except moodle_login.LoginError:
            raise MoodleError("unreachable") from None
        if isinstance(answer, dict) and answer.get("exception"):
            code = _safe_code(answer.get("errorcode"))
            raise MoodleError("token_expired" if code == "invalidtoken" else "moodle_error", code)
        return answer

    def _object(self, function: str, params: dict[str, Any] | None = None) -> dict:
        answer = self.call(function, params)
        if not isinstance(answer, dict):
            raise MoodleError("bad_answer")
        return answer

    def site_info(self) -> dict:
        """Who the token belongs to (the number only), the release, the allowed functions."""
        info = self._object("core_webservice_get_site_info")
        userid = _number(info.get("userid"))
        if userid is None:
            raise MoodleError("bad_answer")
        self._userid = userid
        return {
            "userid": userid,
            "release": _text(info.get("release")).split(" ")[0],
            "functions": sorted(
                _text(item.get("name"))
                for item in _dicts(info.get("functions"))
                if item.get("name")
            ),
            "downloadfiles": bool(info.get("downloadfiles")),
        }

    def _user(self) -> int:
        return self._userid if self._userid is not None else self.site_info()["userid"]

    def courses(self) -> list[dict]:
        """The courses the owner is enrolled in."""
        answer = self.call("core_enrol_get_users_courses", {"userid": self._user()})
        if not isinstance(answer, list):
            raise MoodleError("bad_answer")
        return [parse_course(item) for item in _dicts(answer)]

    def contents(self, course_id: int) -> list[dict]:
        """Sections of a course with their modules, and the files of file-like modules."""
        answer = self.call("core_course_get_contents", {"courseid": course_id})
        if not isinstance(answer, list):
            raise MoodleError("bad_answer")
        return [parse_section(item) for item in _dicts(answer)]

    def assignments(self, course_ids: list[int] | None = None) -> list[dict]:
        """Assignments of the given courses, or of every course when none is given."""
        params = {"courseids": list(course_ids)} if course_ids else {}
        answer = self._object("mod_assign_get_assignments", params)
        found = []
        for course in _dicts(answer.get("courses")):
            course_id = _number(course.get("id"))
            found.extend(
                parse_assignment(item, course_id) for item in _dicts(course.get("assignments"))
            )
        return found

    def deadlines(self, from_ts: int, to_ts: int) -> list[dict]:
        """What is due between the two times (the site's timeline), in order of time."""
        found: list[dict] = []
        after: int | None = None
        for _ in range(MAX_EVENT_PAGES):
            params = {"timesortfrom": from_ts, "timesortto": to_ts, "limitnum": MAX_EVENTS_PER_CALL}
            if after is not None:
                params["aftereventid"] = after
            events = _dicts(
                self._object("core_calendar_get_action_events_by_timesort", params).get("events")
            )
            found.extend(parse_event(item) for item in events)
            after = found[-1]["id"] if found else None
            if len(events) < MAX_EVENTS_PER_CALL or after is None:
                break
        return found

    def _download_address(self, fileurl: str) -> str:
        """The address to fetch a file from, with the token; only files of this site."""
        site = urllib.parse.urlsplit(self._url)
        parts = urllib.parse.urlsplit(fileurl)
        same_site = (parts.scheme, parts.netloc.lower()) == ("https", site.netloc.lower())
        if site.scheme != "https" or not same_site:
            raise MoodleError("bad_file_url")
        path = parts.path
        if WS_PLUGINFILE not in path:
            if PLUGINFILE not in path:
                raise MoodleError("bad_file_url")
            # The plain address needs a browser session; this one takes the token.
            path = path.replace(PLUGINFILE, WS_PLUGINFILE, 1)
        query = [
            pair for pair in urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
            if pair[0] != "token"
        ]  # fmt: skip
        query.append(("token", self._token))
        return urllib.parse.urlunsplit(
            ("https", parts.netloc, path, urllib.parse.urlencode(query), "")
        )

    def download(self, fileurl: str, target_path: Path, max_bytes: int) -> int:
        """Fetch one file into ``target_path`` (written whole or not at all). Returns its size."""
        address = self._download_address(fileurl)
        temporary = target_path.with_name(target_path.name + ".part")
        try:
            size = _fetch(address, temporary, max_bytes)
            temporary.replace(target_path)
        except MoodleError:
            with contextlib.suppress(OSError):
                temporary.unlink(missing_ok=True)
            raise
        except (OSError, ValueError):
            # No chaining: the original error may name the address, which holds the token.
            with contextlib.suppress(OSError):
                temporary.unlink(missing_ok=True)
            raise MoodleError("download_failed") from None
        return size


def _open(address: str) -> Any:
    """Open a download. Replaced in tests; the address holds the token and is never logged."""
    request = urllib.request.Request(address, headers={"User-Agent": "lifeos-bridge"})  # noqa: S310
    return urllib.request.urlopen(request, timeout=DOWNLOAD_TIMEOUT)  # noqa: S310


def _is_refusal(start: bytes) -> bool:
    """The site answers a refused download with a small JSON error instead of the file."""
    head = start.lstrip()[:200]
    return head.startswith(b"{") and (b'"error"' in head or b'"exception"' in head)


def _fetch(address: str, temporary: Path, max_bytes: int) -> int:
    """Stream a download into ``temporary``, stopping as soon as it is too large."""
    try:
        response = _open(address)
    except (urllib.error.URLError, OSError, ValueError):
        raise MoodleError("download_failed") from None
    with response:
        declared = _number(response.headers.get("Content-Length"))
        if declared is not None and declared > max_bytes:
            raise MoodleError("too_large")
        refusal_possible = "json" in (response.headers.get("Content-Type") or "").lower()
        written = 0
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            while chunk := response.read(CHUNK_BYTES):
                if refusal_possible and not written and _is_refusal(chunk):
                    raise MoodleError("download_failed")
                written += len(chunk)
                if written > max_bytes:
                    raise MoodleError("too_large")
                handle.write(chunk)
            handle.flush()
            os.fsync(handle.fileno())
    return written


def from_env() -> Client:
    """The client for the site and token in the private env file."""
    try:
        values = read_env_file(config_dir() / ENV_FILE_NAME)
    except (ConfigError, OSError):
        raise MoodleError("not_configured") from None
    return Client(values.get("MOODLE_URL", ""), values.get("MOODLE_TOKEN", ""))
