"""The action registry (#185): every feature of the app, as one action each.

Rule (CLAUDE.md section 7): every app feature is exposed as an action here; a
feature without its action is not done. ``tests/test_actions.py`` fails when a
route of the bridge or a navigation route of the app is reached by no action
and is not in :data:`EXEMPT_ROUTES` / :data:`EXEMPT_SCREENS` with a reason.

Each :class:`Action` names its parameters, **where it runs** (``bridge``: the
bridge executes it here; ``app``: the app performs it, e.g. opening a screen)
and its undo, if any. The command bar's interpreter (``command_bar.py``) turns
a typed or spoken request into these actions; it names things by words
("the dentist task"), never by ids, and the bridge resolves the words: one
match acts, several become one question with options, none says so.

Nothing here runs a model, and nothing is destroyed: an undo of something
added moves it to the memory folder's trash (rule 8).
"""

from __future__ import annotations

import secrets
import threading
import time
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import (
    activity,
    allowed,
    folder_requests,
    folders,
    memory,
    moodle_sync,
    permission,
    projects,
    reminders,
    session_meta,
)
from .claude import ClaudeError
from .config import Settings

# ---------------------------------------------------------------- the registry


@dataclass(frozen=True)
class Param:
    name: str
    kind: str  # text, date (YYYY-MM-DD), time (HH:MM), datetime (local YYYY-MM-DDTHH:MM), choice
    description: str
    required: bool = False
    choices: tuple[str, ...] = ()
    limit: int = 500


@dataclass(frozen=True)
class Action:
    name: str
    description: str
    params: tuple[Param, ...] = ()
    runs: str = "bridge"  # "bridge" executes it here; "app": the app performs it
    undo: str | None = None  # what Undo does; None: it has no undo
    routes: tuple[str, ...] = ()  # the bridge routes ("METHOD /path") it stands for
    screens: tuple[str, ...] = ()  # the app's navigation routes (``Routes.<NAME>``) it opens


# The screens of ``open_screen``: the name the model uses → the app's ``Routes`` constant.
# The app maps the same names (app/.../data/CommandBar.kt, checked by the test).
SCREENS: dict[str, str] = {
    "today": "TODAY",
    "coach": "COACH",
    "chats": "CHAT",
    "sessions": "SESSIONS",
    "tasks": "TASKS",
    "all_tasks": "ALL_TASKS",
    "more": "MORE",
    "notes": "NOTES",
    "projects": "PROJECTS",
    "calendar": "CALENDAR",
    "courses": "COURSES",
    "grades": "GRADES",
    "activity": "ACTIVITY",
    "settings": "SETTINGS",
    "models": "MODELS",
    "consolidation": "CONSOLIDATION",
}

TEXT = 4000  # a note's length (memory.MAX_TEXT)

# The change permission of a chat the bar opens or continues (#190): the only per-chat
# setting the interpreter picks, from the owner's request alone. The names are the
# interpreter's; ``permission.granted`` turns them into the mode the chat really gets.
CHANGES = {"read_only": "read_only", "may_change": "capable"}
CHANGES_PARAM = Param(
    "changes", "choice",
    "may_change only when the owner's request clearly asks for work that changes files or "
    "runs commands; else read_only",
    choices=tuple(CHANGES),
)  # fmt: skip

ACTIONS: tuple[Action, ...] = (
    Action(
        "create_note",
        "Save a note. With remind_at the owner gets a notification at that time that opens it "
        '("remind me tomorrow at 10 to call X": text "call X").',
        (
            Param("text", "text", "the note, in the owner's words", True, limit=TEXT),
            Param("remind_at", "datetime", "when to remind"),
        ),
        undo="moves the note to the trash, with its reminder",
        routes=("POST /notes",),
    ),
    Action(
        "edit_note",
        "Replace the text of a captured note.",
        (
            Param("note", "text", "words that find the note", True),
            Param("text", "text", "the new text", True, limit=TEXT),
        ),
        undo="puts the old text back",
        routes=("PATCH /notes/{note_id}",),
    ),
    Action(
        "delete_note",
        "Delete a note (it goes to the trash).",
        (Param("note", "text", "words that find the note", True),),
        undo="brings the note back",
        routes=("DELETE /notes/{note_id}", "POST /notes/{note_id}/restore"),
    ),
    Action(
        "open_note",
        "Show one note.",
        (Param("note", "text", "words that find the note", True),),
        routes=("GET /notes/{note_id}",),
        screens=("NOTE",),
    ),
    Action(
        "search_notes",
        "Search the notes and show the matches.",
        (Param("query", "text", "what to look for", True, limit=200),),
        runs="app",
        routes=("GET /notes",),
        screens=("NOTES",),
    ),
    Action(
        "set_reminder",
        "Remind the owner about an existing note or a chat at a time. For a new thing to "
        "remember, use create_note with remind_at instead.",
        (
            Param("note", "text", "words that find the note"),
            Param("chat", "text", "words that find the chat (instead of a note)"),
            Param("at", "datetime", "when", True),
            Param("repeat", "choice", "how often", choices=("none", "daily", "weekly")),
        ),
        undo="removes the reminder",
        routes=("POST /reminders", "PATCH /reminders/{reminder_id}"),
    ),
    Action(
        "cancel_reminder",
        "Remove the next reminder of a note or a chat.",
        (
            Param("note", "text", "words that find the note"),
            Param("chat", "text", "words that find the chat (instead of a note)"),
        ),
        undo="sets the reminder again",
        routes=("DELETE /reminders/{reminder_id}", "GET /reminders"),
    ),
    Action(
        "add_task",
        "Add a task to do.",
        (
            Param("title", "text", "the task", True, limit=200),
            Param("due", "date", "due date"),
            Param("note", "text", "a note on the task"),
        ),
        undo="moves the task to the trash",
        routes=("POST /tasks",),
    ),
    Action(
        "complete_task",
        "Tick an open task as done.",
        (Param("task", "text", "words that find the task", True),),
        undo="opens the task again",
        routes=("POST /tasks/{task_id}/complete",),
    ),
    Action(
        "edit_task",
        'Change an open task: title, due date ("none" removes it) or note.',
        (
            Param("task", "text", "words that find the task", True),
            Param("title", "text", "the new title", limit=200),
            Param("due", "text", "the new due date YYYY-MM-DD, or none"),
            Param("note", "text", "the new note"),
        ),
        undo="puts the old title, date and note back",
        routes=("PATCH /tasks/{task_id}",),
    ),
    Action(
        "add_event",
        "Add a calendar event (all day without a start time).",
        (
            Param("title", "text", "the event", True, limit=200),
            Param("date", "date", "its day", True),
            Param("start", "time", "start time"),
            Param("end", "time", "end time"),
            Param("end_date", "date", "last day of a several-day event"),
            Param("note", "text", "a note"),
        ),
        undo="moves the event to the trash",
        routes=("POST /events",),
    ),
    Action(
        "change_event",
        "Change a calendar event: title, day, times or note.",
        (
            Param("event", "text", "words that find the event", True),
            Param("on", "date", "the day it is on now, if the owner said"),
            Param("title", "text", "the new title", limit=200),
            Param("date", "date", "the new day"),
            Param("start", "time", "the new start time"),
            Param("end", "time", "the new end time"),
            Param("note", "text", "the new note"),
        ),
        undo="puts the old values back",
        routes=("PATCH /events/{event_id}", "GET /events"),
    ),
    Action(
        "new_chat",
        "Open a new, blank chat with the assistant; text goes into its message field (not sent).",
        (Param("text", "text", "what to ask, if the owner said", limit=TEXT), CHANGES_PARAM),
        routes=("POST /ask",),
        screens=("CHAT_THREAD",),
    ),
    Action(
        "continue_chat",
        "Open an earlier chat or a Claude Code session of the computer, found by its title, "
        'subject or folder. With message ("go to that chat and do X there") X is sent there '
        "as the owner's next message.",
        (
            Param("chat", "text", "words that find the chat", True, limit=200),
            Param("message", "text", "what to do there, if the owner said", limit=TEXT),
            CHANGES_PARAM,
        ),
        routes=(
            "GET /sessions",
            "GET /sessions/{session_id}",
            "POST /sessions/{session_id}/messages",
            "GET /sessions/{session_id}/history",
        ),
        screens=("CHAT_THREAD",),
    ),
    Action(
        "work_in_folder",
        "Find a folder on the computer by name and start a chat there (it becomes allowed); "
        "text goes into its message field (not sent).",
        (
            Param("folder", "text", "the folder's name", True, limit=200),
            Param("text", "text", "what to do there, if the owner said", limit=TEXT),
            CHANGES_PARAM,
        ),
        routes=("GET /folders", "POST /folders/open", "POST /allowed-paths", "GET /allowed-paths"),
        screens=("CHAT_THREAD",),
    ),
    Action(
        "stop_using_folder",
        "Stop using a folder: it is no longer allowed (nothing in it is touched).",
        (Param("folder", "text", "the folder's name", True, limit=200),),
        routes=("DELETE /allowed-paths",),
    ),
    Action(
        "open_screen",
        "Open a screen of the app: " + ", ".join(SCREENS) + ".",
        (Param("screen", "choice", "which screen", True, choices=tuple(SCREENS)),),
        runs="app",
        routes=(
            "GET /agenda",
            "GET /tasks",
            "GET /coach/plan",
            "GET /coach/question",
            "GET /courses",
            "GET /courses/summary",
            "GET /courses/grades",
            "GET /projects",
            "GET /activity",
            "GET /memory/consolidation",
            "GET /models",
            "GET /models/routing",
            "GET /coach/settings",
        ),
        screens=(*dict.fromkeys(SCREENS.values()), "CHAT_COACH", "CHAT_SESSIONS"),
    ),
    Action(
        "set_home_style",
        "Choose what the Today tab shows: the physics tiles or the classic list of cards "
        '(the setting "Home style").',
        (Param("style", "choice", "which home", True, choices=("tiles", "classic")),),
        runs="app",
    ),
    Action(
        "open_course",
        "Open one course (its materials, assignments and grades).",
        (Param("course", "text", "the course's name or code", True, limit=200),),
        routes=(
            "GET /courses/{course_id}/materials",
            "GET /courses/{course_id}/assignments",
            "GET /courses/{course_id}/announcements",
            "GET /courses/{course_id}/grades",
            "GET /courses/{course_id}/resources",
        ),
        screens=("COURSE",),
    ),
    Action(
        "open_assignment",
        "Open one course assignment.",
        (Param("assignment", "text", "words that find the assignment", True, limit=200),),
        routes=("GET /courses/{course_id}/assignments/{assignment_id}",),
        screens=("ASSIGNMENT",),
    ),
    Action(
        "open_project",
        "Open one project.",
        (Param("project", "text", "the project's name", True, limit=200),),
        routes=("GET /projects/{project_id}",),
        screens=("PROJECT",),
    ),
    Action(
        "ask_coach",
        "Open the coach conversation; text goes into its message field (not sent).",
        (Param("text", "text", "what to tell the coach, if the owner said", limit=TEXT),),
        runs="app",
        routes=("GET /coach/chat", "POST /coach/chat/messages"),
        screens=("CHAT_COACH",),
    ),
    Action(
        "refresh_plan",
        "Prepare the coach's plan for today again.",
        routes=("POST /coach/plan/refresh",),
    ),
    Action(
        "sync_courses",
        "Check the university site for new course material now.",
        routes=("POST /courses/refresh", "GET /courses/sync"),
    ),
    Action(
        "record_sleep",
        "Record a night's sleep.",
        (
            Param("date", "date", "the morning the owner woke up", True),
            Param("bed", "time", "when the owner went to bed", True),
            Param("wake", "time", "when the owner woke up", True),
        ),
        routes=("POST /activity/sleep",),
    ),
)

BY_NAME: dict[str, Action] = {action.name: action for action in ACTIONS}

# Routes no action stands for, each with the reason.
EXEMPT_ROUTES: dict[str, str] = {
    "GET /health": "not a feature: the unauthenticated liveness check",
    "GET /whoami": "not a feature: the app's token check",
    "GET /files/{offer_id}": "a download of a file a chat offered; started from that chat's card",
    "POST /push/notify": "used by scripts/notify.sh on the computer, never by the owner",
    "GET /app/release": "the app's own update check",
    "GET /app/update": "the app's own update check",
    "POST /app/release/offer": "the app's own update download",
    "PUT /models/routing": "a setting: changed on the Models screen (open_screen models)",
    "POST /sessions/{session_id}/cancel": "stops a running answer: a button of that chat",
    "GET /sessions/{session_id}/interactions": "a run's questions and approvals: chat cards",
    "POST /sessions/{session_id}/interactions/{interaction_id}": (
        "an approval or an answer to a run's question must be the owner's own tap on its card"
    ),
    "GET /sessions/{session_id}/mcp-servers": "a chat's MCP servers: its sheet (approval cards)",
    "POST /sessions/{session_id}/mcp-servers/{name}/approval": (
        "approving an MCP server must be the owner's own tap on its card"
    ),
    "DELETE /sessions/{session_id}/mcp-servers/{name}": "revoked on the chat's sheet, by a tap",
    "GET /sessions/{session_id}/queue": "messages waiting in a running chat: shown in that chat",
    "PATCH /sessions/{session_id}/queue/{queue_id}": "edits a waiting message in that chat",
    "DELETE /sessions/{session_id}/queue/{queue_id}": "removes a waiting message in that chat",
    "POST /sessions/{session_id}/queue/resume": "a button in that chat",
    "POST /sessions/{session_id}/queue/{queue_id}/send-now": "a button in that chat",
    "GET /sessions/{session_id}/live": "follows a running answer inside the chat screen",
    "GET /sessions/{session_id}/files": "files of a chat's answers: cards in that chat",
    "GET /sessions/{session_id}/commands": 'the "/" list in a chat\'s message field',
    "PATCH /sessions/{session_id}": (
        "title, pin, model and switches of a chat: its sheet (open it with continue_chat)"
    ),
    "GET /context-map": "the computer's folder summaries: read by runs, not a screen",
    "POST /context-map/refresh": "a maintenance run of the computer, started from the terminal",
    "POST /activity/usage": "the phone's daily usage sync, not a request of the owner",
    "POST /memory/consolidation/approve": (
        "approving memory changes is the owner's own choice per change on its screen"
    ),
    "POST /memory/consolidation/reject": "chosen per change on the consolidation screen",
    "POST /memory/consolidation/run": "the nightly job; a manual start is on its screen",
    "POST /coach/question/answer": "the answer is typed on the coach's question card",
    "POST /coach/question/skip": "a button on the coach's question card",
    "PUT /coach/settings": "a setting: changed on the Settings screen (open_screen settings)",
    "POST /coach/chat/compact": "a maintenance step of the coach conversation, on its screen",
    "POST /courses/{course_id}/seen": "the app marks what it showed, by itself",
    "POST /courses/{course_id}/resources/refresh": "a button on the course's Resources tab",
    "POST /courses/{course_id}/resources/{index}/offer": "a download from the Resources tab",
    "POST /courses/{course_id}/files/{key}/address": "a file download from the course page",
    "POST /courses/{course_id}/files/{key}/offer": "a file download from the course page",
    "GET /moodle/status": "the site connection's state, shown on the Courses screen",
    "POST /moodle/signin/start": "signing in to the university site is the owner's own step",
    "POST /moodle/signin/finish": "signing in to the university site is the owner's own step",
    "GET /moodle/courses": "the site's raw lists; the app reads /courses (open_course)",
    "GET /moodle/courses/{course_id}": "the site's raw lists; the app reads /courses",
    "GET /moodle/assignments": "the site's raw lists; the app reads /courses",
    "GET /moodle/deadlines": "the site's raw lists; the app reads /courses/summary",
    "POST /command": "the command bar itself",
    "POST /command/undo": "the command bar's own undo",
    "GET /command/actions": "the registry itself, for the app and for checks",
}

# The app's navigation routes no action opens, each with the reason.
EXEMPT_SCREENS: dict[str, str] = {
    "PAIR": "pairing is done on the phone with the computer's code, never by a request",
    "SECTION": "the placeholder of More sections that are not built yet",
}


def catalogue() -> list[dict]:
    """The registry as data (``GET /command/actions``)."""
    return [
        {
            "name": a.name,
            "description": a.description,
            "runs": a.runs,
            "undo": a.undo,
            "params": [
                {
                    "name": p.name,
                    "kind": p.kind,
                    "required": p.required,
                    **({"choices": list(p.choices)} if p.choices else {}),
                }
                for p in a.params
            ],
        }
        for a in ACTIONS
    ]


def prompt_spec() -> str:
    """The actions as the interpreter reads them: one line each."""
    lines = []
    for action in ACTIONS:
        params = ", ".join(
            f"{p.name}{'' if p.required else '?'}: {_kind_label(p)}" for p in action.params
        )
        lines.append(f"- {action.name}({params}): {action.description}")
    return "\n".join(lines)


def _kind_label(param: Param) -> str:
    if param.kind == "choice":
        return " | ".join(param.choices)
    return {
        "text": "text", "date": "YYYY-MM-DD", "time": "HH:MM",
        "datetime": "YYYY-MM-DDTHH:MM (local)",
    }[param.kind] + f" ({param.description})"  # fmt: skip


# ---------------------------------------------------------------- checking what the model said


class Refused(ValueError):
    """The interpreter named something the registry does not have, or bad parameters."""


def check(name: object, params: object) -> dict:
    """The parameters of one action, checked against the registry. Raises :class:`Refused`."""
    action = BY_NAME.get(name) if isinstance(name, str) else None
    if action is None:
        raise Refused(f"unknown action {name!r}")
    if params is None:
        params = {}
    if not isinstance(params, dict):
        raise Refused(f"{action.name}: params must be an object")
    known = {p.name: p for p in action.params}
    unknown = set(params) - set(known)
    if unknown:
        raise Refused(f"{action.name}: unknown parameters {sorted(unknown)}")
    clean: dict = {}
    for param in action.params:
        value = params.get(param.name)
        if value is None or (isinstance(value, str) and not value.strip()):
            if param.required:
                raise Refused(f"{action.name}: {param.name} is missing")
            continue
        clean[param.name] = _value(action, param, value)
    return clean


def _value(action: Action, param: Param, value: object) -> object:
    where = f"{action.name}.{param.name}"
    if not isinstance(value, str):
        raise Refused(f"{where} must be text")
    value = value.strip()
    if param.kind == "text":
        if len(value) > param.limit:
            raise Refused(f"{where} is too long")
        return value
    if param.kind == "choice":
        if value not in param.choices:
            raise Refused(f"{where} must be one of {param.choices}")
        return value
    formats = {"date": "%Y-%m-%d", "time": "%H:%M", "datetime": "%Y-%m-%dT%H:%M"}
    try:
        datetime.strptime(value, formats[param.kind])
    except ValueError as exc:
        raise Refused(f"{where} must look like {formats[param.kind]}") from exc
    return value


# ---------------------------------------------------------------- doing it


@dataclass
class Context:
    settings: Settings
    now: datetime  # the phone's local time, with its offset
    zone: str | None = None  # the phone's zone name, when it has one the computer knows
    sessions: Callable[[], list] = list  # the sessions the bridge lists (app.known_sessions)
    # The sessions no message can be sent to right now: answering here, or open in another
    # Claude Code process of the computer. ``None``: it could not be told (nothing is sent).
    busy: Callable[[], set[str] | None] = lambda: None

    @property
    def brain(self) -> Path:
        return self.settings.ask_dir

    def moment(self, local: str) -> datetime:
        """A local "YYYY-MM-DDTHH:MM" of the phone as an aware time."""
        naive = datetime.strptime(local, "%Y-%m-%dT%H:%M")
        if self.zone:
            try:
                return naive.replace(tzinfo=ZoneInfo(self.zone))
            except (ZoneInfoNotFoundError, ValueError):
                pass
        return naive.replace(tzinfo=self.now.tzinfo)


@dataclass
class Option:
    """One answer to a question: its label, and the action it stands for (with ids)."""

    label: str
    detail: str | None
    action: str
    params: dict


class Ask(Exception):  # an outcome, not a failure
    """Several things match: the owner picks one (``kind`` names what is picked)."""

    def __init__(self, kind: str, options: list[Option]) -> None:
        super().__init__(kind)
        self.kind = kind
        self.options = options


@dataclass
class Outcome:
    """What one action did: for the bar's confirmation line, its undo, and what the app opens."""

    action: str
    ok: bool = True
    title: str | None = None  # what it was about (a task's title, a note's start, a course)
    when: str | None = None  # a time or a day it set
    undo: str | None = None
    error: str | None = None
    perform: dict | None = None
    mode: str | None = None  # the change permission the chat it opens gets (#190)
    note: str | None = None  # why it did less than asked: one of :data:`NOTES`

    def as_json(self) -> dict:
        found = {"action": self.action, "ok": self.ok, "title": self.title, "when": self.when,
                 "undo": self.undo, "error": self.error}  # fmt: skip
        found |= {"perform": self.perform} if self.perform else {}
        found |= {"mode": self.mode} if self.mode else {}
        return found | ({"note": self.note} if self.note else {})


MAX_OPTIONS = 5


def fold(text: str) -> str:
    """Lower case without accents (Turkish dotless i too), for matching names."""
    decomposed = unicodedata.normalize("NFKD", text.casefold().replace("\u0131", "i"))
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch))


class Partial(list):
    """Items that match only some of the words: never acted on without asking."""


def best(items: list, words: str, text_of: Callable[[object], str]) -> list:
    """The items ``words`` name: those with the whole phrase, else those with every word
    (of three letters or more); else, as :class:`Partial`, those with the most words."""
    phrase = " ".join(fold(words).split())
    if not phrase:
        return []
    texts = [(item, " ".join(fold(text_of(item)).split())) for item in items]
    whole = [item for item, text in texts if phrase in text]
    if whole:
        return whole
    parts = [w for w in phrase.split() if len(w) >= 3] or phrase.split()
    every = [item for item, text in texts if all(w in text for w in parts)]
    if every:
        return every
    scored = [(sum(w in text for w in parts), item) for item, text in texts]
    top = max((score for score, _ in scored), default=0)
    return Partial(item for score, item in scored if score == top) if top else []


def _one(found: list, kind: str, option: Callable[[object], Option], missing: str) -> object:
    """The one item meant; several, or one that only partly matches, become a question."""
    if not found:
        raise ClaudeError(missing, 404, f"nothing matches ({kind})")
    if len(found) > 1 or isinstance(found, Partial):
        raise Ask(kind, [option(item) for item in found[:MAX_OPTIONS]])
    return found[0]


def _ahead(moment: datetime) -> datetime:
    """A reminder time from the bar must lie ahead (a note delayed in the phone's outbox may
    fire late; a request of the bar is never meant for the past)."""
    if moment < datetime.now(UTC) - reminders.PAST_TOLERANCE:
        raise ClaudeError("time_in_past", 400, "that time has passed")
    return moment


def _snippet(text: str, limit: int = 80) -> str:
    line = " ".join(text.split())
    return line if len(line) <= limit else line[: limit - 1] + "…"


# ---- what words name


def _note(ctx: Context, params: dict, action: str) -> dict:
    if "note_id" in params:
        return memory.get_note(ctx.brain, params["note_id"])
    notes = memory.list_notes(ctx.brain, "", 500)
    found = best(notes, params["note"], lambda n: f"{n['title'] or ''} {n['preview']}")
    if len(found) == 1 and not isinstance(found, Partial):
        return memory.get_note(ctx.brain, found[0]["id"])  # in full
    return _one(
        found, "note",
        lambda n: Option(_snippet(n["title"] or n["preview"]), n["at"], action,
                         {**_rest(params, "note"), "note_id": n["id"]}),
        "note_not_found",
    )  # fmt: skip


def _task(ctx: Context, params: dict, action: str) -> memory.Task:
    tasks = memory.list_tasks(ctx.brain)
    if "task_id" in params:
        found = [t for t in tasks if t.id == params["task_id"]]
    else:
        found = best(tasks, params["task"], lambda t: t.title)
    return _one(
        found, "task",
        lambda t: Option(_snippet(t.title), t.fields.get("due"), action,
                         {**_rest(params, "task"), "task_id": t.id}),
        "task_not_found",
    )  # fmt: skip


def _event(ctx: Context, params: dict, action: str) -> memory.Event:
    events = memory.all_events(ctx.brain)
    if "event_id" in params:
        found = [e for e in events if e.id == params["event_id"]]
    else:
        if "on" in params:
            day = date.fromisoformat(params["on"])
            events = [e for e in events if e.day <= day <= e.last_day]
        else:  # the coming ones first: an old event of the same name is rarely meant
            today = ctx.now.date()
            events = [e for e in events if e.last_day >= today] or events
        found = best(events, params["event"], lambda e: e.title)
    return _one(
        found, "event",
        lambda e: Option(_snippet(e.title), e.day.isoformat() + (f" {e.start}" if e.start else ""),
                         action, {**_rest(params, "event", "on"), "event_id": e.id}),
        "event_not_found",
    )  # fmt: skip


def _chat(ctx: Context, params: dict, action: str):
    """The session the words name, among those the bridge lists: the chats of the app and
    the Claude Code sessions of the computer alike (``GET /sessions``). Its title and its
    folder's name are matched; they are never shown to the interpreter."""
    listed = ctx.sessions()
    meta = session_meta.load()
    known = folders.Folders(ctx.settings)

    def title(info) -> str:
        return session_meta.entry_for(info.id, meta)["title"] or info.title or ""

    def label(info) -> str:
        return known.describe(info)["folder_label"] or ""

    if "session_id" in params:
        found = [info for info in listed if info.id == params["session_id"]]
    else:
        found = best(listed, params["chat"], lambda info: f"{title(info)} {label(info)}")
    return _one(
        found, "chat",
        lambda info: Option(_snippet(title(info)),
                            f"{label(info)} · {info.last_activity.date().isoformat()}", action,
                            {**_rest(params, "chat"), "session_id": info.id}),
        "chat_not_found",
    ), title  # fmt: skip


def _rest(params: dict, *names: str) -> dict:
    return {k: v for k, v in params.items() if k not in names}


# ---- undo: kept in memory for a while (a restart of the bridge forgets them)

UNDO_SECONDS = 15 * 60
MAX_UNDO = 200
_UNDO: dict[str, tuple[float, str, Callable[[], None]]] = {}
_UNDO_LOCK = threading.Lock()


def _undo_token(action: str, undo: Callable[[], None]) -> str:
    token = secrets.token_urlsafe(12)
    with _UNDO_LOCK:
        now = time.monotonic()
        for old in [t for t, (expires, _, _) in _UNDO.items() if expires < now]:
            del _UNDO[old]
        while len(_UNDO) >= MAX_UNDO:
            del _UNDO[next(iter(_UNDO))]
        _UNDO[token] = (now + UNDO_SECONDS, action, undo)
    return token


def undo(token: object) -> dict:
    """``POST /command/undo``: take one action back, once. 404 when it is unknown or too old."""
    with _UNDO_LOCK:
        found = _UNDO.pop(token, None) if isinstance(token, str) else None
    if found is None or found[0] < time.monotonic():
        raise ClaudeError("undo_not_found", 404, "this can no longer be undone")
    found[2]()
    return {"undone": True, "action": found[1]}


# ---- the executors of the bridge's actions


def _create_note(ctx: Context, p: dict) -> Outcome:
    remind = None
    if "remind_at" in p:
        moment = _ahead(ctx.moment(p["remind_at"]))
        remind = {"at": moment.isoformat(), **({"timezone": ctx.zone} if ctx.zone else {})}
    # Stamped by the computer's clock, as every capture; the phone's time is only for meaning.
    saved = memory.add_note(ctx.brain, p["text"], None, None, remind)
    note_id = saved["id"]

    def back() -> None:
        memory.delete_note(ctx.brain, note_id)

    when = saved["reminder"]["at"] if saved.get("reminder") else None
    return Outcome("create_note", title=_snippet(p["text"]), when=when,
                   undo=_undo_token("create_note", back),
                   perform={"action": "note_saved", "note_id": note_id})  # fmt: skip


def _edit_note(ctx: Context, p: dict) -> Outcome:
    note = _note(ctx, p, "edit_note")
    old = note["text"]
    changed = memory.update_note(ctx.brain, note["id"], p["text"])

    def back() -> None:
        memory.update_note(ctx.brain, changed["id"], old)

    return Outcome("edit_note", title=_snippet(p["text"]), undo=_undo_token("edit_note", back))


def _delete_note(ctx: Context, p: dict) -> Outcome:
    note = _note(ctx, p, "delete_note")
    memory.delete_note(ctx.brain, note["id"])

    def back() -> None:
        memory.restore_note(ctx.brain, note["id"])

    title = note["title"] or note.get("text") or note.get("preview") or ""
    return Outcome("delete_note", title=_snippet(title), undo=_undo_token("delete_note", back),
                   perform={"action": "note_deleted", "note_id": note["id"]})  # fmt: skip


def _open_note(ctx: Context, p: dict) -> Outcome:
    note = _note(ctx, p, "open_note")
    title = note["title"] or note.get("text") or note.get("preview") or ""
    return Outcome("open_note", title=_snippet(title),
                   perform={"action": "open_note", "note_id": note["id"]})  # fmt: skip


def _reminder_target(ctx: Context, p: dict, action: str) -> tuple[dict, str]:
    """``({"note_id"} or {"session_id"}, its title)`` of a reminder."""
    if "note" in p or "note_id" in p:
        note = _note(ctx, p, action)
        return {"note_id": note["id"]}, _snippet(note["title"] or note.get("text") or "")
    if "chat" in p or "session_id" in p:
        info, title = _chat(ctx, p, action)
        return {"session_id": info.id}, _snippet(title(info))
    raise ClaudeError("bad_request", 400, "a reminder needs a note or a chat")


def _set_reminder(ctx: Context, p: dict) -> Outcome:
    target, title = _reminder_target(ctx, p, "set_reminder")
    at = ctx.moment(p["at"])
    body = {"at": (_ahead(at) if p.get("repeat", "none") == "none" else at).isoformat(),
            "repeat": p.get("repeat", "none")}  # fmt: skip
    if ctx.zone:
        body["timezone"] = ctx.zone
    before = None
    if "note_id" in target:  # a note has one reminder to come: a second time moves it (#191)
        made, before = reminders.set_for_note(target["note_id"], body)
    else:
        made = reminders.create(target["session_id"], body)

    def back() -> None:
        if before is None:
            reminders.delete(made["id"])
        else:
            reminders.update(made["id"], before)

    return Outcome("set_reminder", title=title, when=made["at"],
                   undo=_undo_token("set_reminder", back))  # fmt: skip


def _cancel_reminder(ctx: Context, p: dict) -> Outcome:
    target, title = _reminder_target(ctx, p, "cancel_reminder")
    found = [
        r for r in reminders.listing(target.get("session_id"), "scheduled", target.get("note_id"))
    ]
    if not found:
        raise ClaudeError("reminder_not_found", 404, "no reminder to cancel")
    gone = found[0]
    reminders.delete(gone["id"])

    def back() -> None:
        note_id = gone.get("note_id")
        if note_id is not None and reminders.listing(state="scheduled", note_id=note_id):
            return  # the note got a new reminder meanwhile: it has one to come (#191)
        fields = {k: gone[k] for k in ("at", "note", "repeat", "timezone") if gone.get(k)}
        reminders.create(gone.get("session_id"), fields, note_id=gone.get("note_id"),
                         allow_past=True)  # fmt: skip

    return Outcome("cancel_reminder", title=title, when=gone["at"],
                   undo=_undo_token("cancel_reminder", back))  # fmt: skip


def _add_task(ctx: Context, p: dict) -> Outcome:
    task = memory.add_task(ctx.brain, p)

    def back() -> None:
        memory.trash_task(ctx.brain, task.id)

    return Outcome("add_task", title=task.title, when=task.fields.get("due"),
                   undo=_undo_token("add_task", back))  # fmt: skip


def _complete_task(ctx: Context, p: dict) -> Outcome:
    task = _task(ctx, p, "complete_task")
    memory.complete_task(ctx.brain, task.id)

    def back() -> None:
        memory.reopen_task(ctx.brain, task.id)

    return Outcome("complete_task", title=task.title, undo=_undo_token("complete_task", back))


def _edit_task(ctx: Context, p: dict) -> Outcome:
    task = _task(ctx, p, "edit_task")
    changes: dict = {}
    if "title" in p:
        changes["title"] = p["title"]
    if "due" in p:
        due = p["due"].strip()
        changes["due"] = None if fold(due) in ("none", "yok", "-") else due
    if "note" in p:
        changes["note"] = p["note"]
    if not changes:
        raise ClaudeError("bad_request", 400, "nothing to change")
    before = {"title": task.title, "due": task.fields.get("due"), "note": task.fields.get("note")}
    changed = memory.update_task(ctx.brain, task.id, changes)

    def back() -> None:
        memory.update_task(ctx.brain, task.id, {k: before[k] for k in changes})

    return Outcome("edit_task", title=changed.title, when=changed.fields.get("due"),
                   undo=_undo_token("edit_task", back))  # fmt: skip


def _add_event(ctx: Context, p: dict) -> Outcome:
    event = memory.create_event(ctx.brain, p)

    def back() -> None:
        memory.trash_event(ctx.brain, event.id)

    when = event.day.isoformat() + (f" {event.start}" if event.start else "")
    return Outcome("add_event", title=event.title, when=when, undo=_undo_token("add_event", back))


def _change_event(ctx: Context, p: dict) -> Outcome:
    event = _event(ctx, p, "change_event")
    names = ("title", "date", "start", "end", "note")
    changes = {k: p[k] for k in names if k in p}
    if not changes:
        raise ClaudeError("bad_request", 400, "nothing to change")
    before = event.as_json()
    changed = memory.update_event(ctx.brain, event.id, changes)

    def back() -> None:
        memory.update_event(ctx.brain, event.id, {k: before[k] for k in changes})

    when = changed.day.isoformat() + (f" {changed.start}" if changed.start else "")
    return Outcome("change_event", title=changed.title, when=when,
                   undo=_undo_token("change_event", back))  # fmt: skip


def _mode(ctx: Context, p: dict, folder: Path | None, kind: str | None = None) -> str:
    """The change permission the interpreter picked, as the chat may really get it."""
    return permission.granted(ctx.settings, folder, CHANGES.get(p.get("changes")), kind)


def _new_chat(ctx: Context, p: dict) -> Outcome:
    mode = _mode(ctx, p, None)
    perform = {"action": "new_chat", "mode": mode} | ({"text": p["text"]} if "text" in p else {})
    return Outcome("new_chat", perform=perform, mode=mode)


def _continue_chat(ctx: Context, p: dict) -> Outcome:
    """Open the chat; with a message, send it there. A chat that cannot take a message
    right now is opened with the message in its field; one that is not found leads to
    the list of sessions. Never a guess: words that match several chats, or only partly,
    are a question (:func:`_one`)."""
    try:
        info, title = _chat(ctx, p, "continue_chat")
    except ClaudeError:
        return Outcome("continue_chat", note="chat_not_found",
                       perform={"action": "open_screen", "screen": "sessions"})  # fmt: skip
    kind = folders.Folders(ctx.settings).describe(info)["kind"]
    mode = _mode(ctx, p, info.folder, kind)
    perform = {"action": "open_chat", "session_id": info.id, "mode": mode}
    note = None
    if "message" in p:
        perform["text"] = p["message"]
        busy = ctx.busy()
        if kind == "coach":
            note = "not_sent"  # the coach conversation is the owner's own to write in
        elif busy is None or info.id in busy:
            note = "chat_busy"
        else:
            perform["send"] = True
            # Written by the interpreter and sent by the app: not a message the owner
            # sent, whatever the app says when it arrives (``permission``).
            permission.remember(p["message"])
    return Outcome("continue_chat", title=_snippet(title(info)), perform=perform, mode=mode,
                   note=note)  # fmt: skip


def _work_in_folder(ctx: Context, p: dict) -> Outcome:
    if "path" in p:  # the owner picked one of several
        opened = folder_requests.open_folder(ctx.settings, p["path"])
        folder = opened["folder"]
    else:
        outcome = folder_requests.find(ctx.settings, p["folder"])
        switch = outcome.switch
        if switch is None:
            raise ClaudeError("folder_not_found", 404, outcome.note or "no such folder")
        if switch["action"] == "choose_folder":
            raise Ask("folder", [
                Option(f["label"], f.get("where"), "work_in_folder",
                       {**_rest(p, "folder"), "path": f["path"]})
                for f in switch["folders"][:MAX_OPTIONS]
            ])  # fmt: skip
        folder = switch["folder"]
    mode = _mode(ctx, p, Path(folder["path"]), folder.get("kind"))
    perform = {"action": "new_chat_in", "folder": folder, "mode": mode}
    if "text" in p:
        perform["text"] = p["text"]
    return Outcome("work_in_folder", title=folder["label"], perform=perform, mode=mode)


def _stop_using_folder(ctx: Context, p: dict) -> Outcome:
    file = ctx.settings.allowed_paths_file
    before = {item["path"] for item in allowed.listing(file)} if file else set()
    note = folder_requests.remove(ctx.settings, p["folder"])
    after = {item["path"] for item in allowed.listing(file)} if file else set()
    gone = before - after
    if not gone:
        raise ClaudeError("folder_not_removed", 404, note or "no such allowed folder")
    return Outcome("stop_using_folder", title=Path(next(iter(gone))).name)


def _courses() -> list[dict]:
    return moodle_sync.listed(moodle_sync.load_state())


def _open_course(ctx: Context, p: dict) -> Outcome:
    records = _courses()
    if "course_id" in p:
        found = [r for r in records if r["id"] == p["course_id"]]
    else:
        found = best(records, p["course"], lambda r: f"{r.get('shortname') or ''} {r['name']}")
    record = _one(found, "course",
                  lambda r: Option(_snippet(r["name"]), r.get("shortname"), "open_course",
                                   {"course_id": r["id"]}),
                  "course_not_found")  # fmt: skip
    return Outcome("open_course", title=record["name"],
                   perform={"action": "open_course", "course_id": record["id"]})  # fmt: skip


def _open_assignment(ctx: Context, p: dict) -> Outcome:
    found = []
    for record in _courses():
        for key, item in (record.get("deadlines") or {}).items():
            if key.startswith("assign-") and key[7:].isdigit():
                found.append(
                    {"course_id": record["id"], "course": record["name"],
                     "assignment_id": int(key[7:]), "name": item.get("name", "")}
                )  # fmt: skip
    if "assignment_id" in p:
        wanted = (p["course_id"], p["assignment_id"])
        found = [a for a in found if (a["course_id"], a["assignment_id"]) == wanted]
    else:
        found = best(found, p["assignment"], lambda a: f"{a['name']} {a['course']}")
    item = _one(found, "assignment",
                lambda a: Option(_snippet(a["name"]), a["course"], "open_assignment",
                                 {k: a[k] for k in ("course_id", "assignment_id")}),
                "assignment_not_found")  # fmt: skip
    return Outcome("open_assignment", title=item["name"],
                   perform={"action": "open_assignment", "course_id": item["course_id"],
                            "assignment_id": item["assignment_id"]})  # fmt: skip


def _open_project(ctx: Context, p: dict) -> Outcome:
    listed = projects.collect(ctx.settings)
    if "project_id" in p:
        found = [x for x in listed if x["id"] == p["project_id"]]
    else:
        found = best(
            listed, p["project"], lambda x: f"{x.get('name') or ''} {x.get('title') or ''}"
        )
    project = _one(found, "project",
                   lambda x: Option(_snippet(x.get("title") or x["name"]), None, "open_project",
                                    {"project_id": x["id"]}),
                   "project_not_found")  # fmt: skip
    return Outcome("open_project", title=project.get("title") or project["name"],
                   perform={"action": "open_project", "project_id": project["id"]})  # fmt: skip


def _sync_courses(ctx: Context, p: dict) -> Outcome:
    started = moodle_sync.start(ctx.settings)  # "started", "running", "too_soon", ...
    ok = started in ("started", "running")
    return Outcome("sync_courses", ok=ok, error=None if ok else started)


def _record_sleep(ctx: Context, p: dict) -> Outcome:
    saved = activity.record_sleep(ctx.brain, p)
    return Outcome("record_sleep", when=f"{p['bed']}-{p['wake']}", title=saved["date"])


EXECUTORS: dict[str, Callable[[Context, dict], Outcome]] = {
    "create_note": _create_note,
    "edit_note": _edit_note,
    "delete_note": _delete_note,
    "open_note": _open_note,
    "set_reminder": _set_reminder,
    "cancel_reminder": _cancel_reminder,
    "add_task": _add_task,
    "complete_task": _complete_task,
    "edit_task": _edit_task,
    "add_event": _add_event,
    "change_event": _change_event,
    "new_chat": _new_chat,
    "continue_chat": _continue_chat,
    "work_in_folder": _work_in_folder,
    "stop_using_folder": _stop_using_folder,
    "open_course": _open_course,
    "open_assignment": _open_assignment,
    "open_project": _open_project,
    "record_sleep": _record_sleep,
    "sync_courses": _sync_courses,
}

# Bridge actions the endpoint starts itself, as a task of its own (a model run): see
# command_bar.py.
STARTED_BY_ENDPOINT = ("refresh_plan",)

# What the bridge's actions ask the app to do afterwards (``Outcome.perform``), besides the
# app actions themselves. The app handles every one (data/CommandBar.kt, checked by the test).
PERFORMS = (
    "note_saved", "note_deleted", "open_note", "open_chat", "new_chat_in", "open_course",
    "open_assignment", "open_project", "new_chat",
)  # fmt: skip

# Why an action did less than asked (``Outcome.note``); the app says each in its language.
NOTES = ("chat_not_found", "chat_busy", "not_sent")


def app_action(name: str, params: dict) -> Outcome:
    """An action the app performs: handed over as it is."""
    perform = {"action": name, **params}
    title = params.get("screen") or params.get("query") or None
    return Outcome(name, title=_snippet(title) if title else None, perform=perform)


def execute(ctx: Context, name: str, params: dict) -> Outcome:
    """Do one checked action (synchronous: called in a worker thread). Raises :class:`Ask`;
    a refusal of the memory or of a lookup becomes an outcome that is not ``ok``."""
    action = BY_NAME[name]
    if action.runs == "app":
        return app_action(name, params)
    try:
        return EXECUTORS[name](ctx, params)
    except ClaudeError as error:
        return Outcome(name, ok=False, error=error.code)


@dataclass
class Pending:
    """An answered question: the action the picked option stands for, then the rest."""

    option: Option
    rest: list[tuple[str, dict]] = field(default_factory=list)
    group: str = ""  # the question it answers: one pick per question
