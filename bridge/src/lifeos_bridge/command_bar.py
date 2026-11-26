"""The command bar (#185): a typed or spoken request becomes actions of the registry.

``POST /command`` hands the request to a fast, cheap model (the routing job
``command``, default ``haiku`` at low effort) that has **no tools** and no
memory files: it only reads the request, the phone's language and local time,
and the list of actions (``actions.prompt_spec``), and answers with one JSON
object. That object is checked against the registry; an unknown action or a
bad parameter refuses the whole answer (``422 not_understood``), so nothing
half-understood is done. A request the model finds ambiguous comes back as one
short question; things named by words that match several items come back as
one question with options, each standing for the resolved action.

Every request carries the phone's ``request_id``: a repeat (an answer that got
lost on the way) is answered with the stored result and does nothing twice.
Results, questions and undo tokens live in the bridge's memory only (15
minutes); nothing of the request is written anywhere but where an action writes.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import secrets
import time
import uuid
from collections.abc import Awaitable, Callable
from datetime import datetime
from pathlib import Path

from . import actions, claude, routing
from .claude import ClaudeError
from .config import Settings

JOB = "command"
TIMEOUT_SECONDS = 45.0
MAX_TEXT = 2000
MAX_ACTIONS = 4
MAX_OPTIONS = 5
KEEP_SECONDS = 15 * 60
REQUEST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{15,63}$")
LANGUAGES = ("en", "tr")

INSTRUCTIONS = """\
You are the command interpreter of LifeOS, the owner's personal assistant app. You get one \
request the owner typed or spoke (Turkish or English) as JSON, and you answer with exactly ONE \
JSON object and nothing else, no prose and no code fence:

{"actions": [{"name": "<action>", "params": {...}}], "question": null}
or, only when the request is truly ambiguous:
{"actions": [], "question": {"text": "<one short question>", "options": ["<answer>", ...]}}

Rules:
- Use only the actions listed below, with only their parameters. Leave out parameters the \
owner did not give. Never invent ids: name notes, tasks, events, chats, folders and courses by \
the owner's own words; the app finds them.
- Dates and times are the owner's local time. Turn words like "tomorrow", "next Monday", \
"in two hours" (in either language) into exact values from "now" in the input.
- Something to do becomes add_task; a thought, idea or fact to keep becomes create_note; a \
request to be reminded becomes create_note with remind_at (the note says what to be reminded \
of), unless it clearly names an existing note or chat (then set_reminder).
- Several things in one request become several actions, in order (at most four).
- Keep the owner's words and language in texts and titles; drop only the command words \
("note that", "not al", "remind me to").
- "Go to / continue / open that chat (or session) and do X there" is ONE continue_chat: chat \
is the words that name the chat (its subject, title or folder), message is X in the owner's \
words, self-contained, without the part that names the chat. Never do X yourself with other \
actions and never answer it. Work to do in a folder is work_in_folder with text; a question \
or work for a new chat is new_chat with text.
- new_chat, continue_chat and work_in_folder take "changes", the only chat setting you pick. \
Decide it from the owner's request alone: "may_change" only when the request clearly asks for \
work that edits, creates or deletes files or runs commands (fix, implement, write the code, \
refactor, rename, install, commit, update); "read_only" for questions, explanations, \
summaries, reviews, looking something up or planning, and when no work is named. When unsure, \
"read_only".
- Ask a question only when you cannot tell what the owner wants (which of two different \
actions, or a time that is missing for a reminder). Never ask about details the action does \
not need. Ask in the request's language, in at most 15 words, with up to four short options.
- If "previous" is given, the request is the owner's answer to your earlier question about \
that earlier request: decide from both.

Actions:
"""


def instructions() -> str:
    return INSTRUCTIONS + actions.prompt_spec() + "\n"


def run_dir() -> Path:
    """An empty folder of its own to run in: outside the memory, every project and git."""
    base = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache")
    folder = base / "lifeos" / "command-runs"
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    return folder


def _prompt(text: str, language: str, now: datetime, previous: dict | None) -> str:
    data = {
        "request": text,
        "language": language,
        "now": now.strftime("%Y-%m-%d %H:%M, %A"),
        "previous": previous,
    }
    return json.dumps(data, ensure_ascii=False)


async def interpret(
    settings: Settings, text: str, language: str, now: datetime, previous: dict | None
) -> dict:
    """One model run without tools; its JSON answer (not yet checked)."""
    async with _RUNS:
        try:
            return await _interpret(settings, text, language, now, previous)
        except TimeoutError as exc:
            raise ClaudeError("timeout", 504, "the request took too long") from exc


# At most two interpretations at once: the bar never takes a chat's run slot.
_RUNS = asyncio.Semaphore(2)


async def _interpret(
    settings: Settings, text: str, language: str, now: datetime, previous: dict | None
) -> dict:
    result = await asyncio.wait_for(
        claude.run(
            settings,
            _prompt(text, language, now, previous),
            session_id=str(uuid.uuid4()),
            resume=False,
            persist=False,  # never a session in the owner's list
            cwd=run_dir(),
            context=instructions(),
            policy=claude.Policy(tools="", clean=True),  # no tool at all, no memory files
            **routing.run_options(settings, JOB),
        ),
        TIMEOUT_SECONDS,
    )
    return claude.json_from_answer(result.answer)


def read_plan(data: dict) -> tuple[list[tuple[str, dict]], dict | None]:
    """The checked actions, or the model's question. Raises ``actions.Refused``."""
    raw = data.get("actions") or []
    question = data.get("question")
    if not isinstance(raw, list) or len(raw) > MAX_ACTIONS:
        raise actions.Refused("actions must be a list of at most four")
    planned = []
    for item in raw:
        if not isinstance(item, dict):
            raise actions.Refused("an action must be an object")
        params = actions.check(item.get("name"), item.get("params"))
        planned.append((item["name"], params))
    if planned:
        return planned, None
    if not isinstance(question, dict) or not isinstance(question.get("text"), str):
        raise actions.Refused("neither actions nor a question")
    options = question.get("options") or []
    if not isinstance(options, list):
        raise actions.Refused("options must be a list")
    words = [" ".join(o.split())[:80] for o in options if isinstance(o, str) and o.strip()]
    return [], {"text": " ".join(question["text"].split())[:300], "options": words[:MAX_OPTIONS]}


# ---- what the bridge keeps for a while: answers by request id, picks of questions

_ANSWERS: dict[str, tuple[float, asyncio.Future]] = {}
_PENDING: dict[str, tuple[float, actions.Pending]] = {}


def _sweep() -> None:
    now = time.monotonic()
    for store in (_ANSWERS, _PENDING):
        for key in [k for k, (expires, _) in store.items() if expires < now]:
            del store[key]


def _read_now(value: object) -> datetime:
    if value is None:
        return datetime.now().astimezone()
    try:
        moment = datetime.fromisoformat(str(value))
    except ValueError as exc:
        raise ClaudeError("bad_request", 400, "`now` must be a time with its offset") from exc
    if moment.tzinfo is None:
        raise ClaudeError("bad_request", 400, "`now` must be a time with its offset")
    return moment


def _read_previous(value: object) -> dict | None:
    if value is None:
        return None
    if (
        not isinstance(value, dict)
        or not isinstance(value.get("request"), str)
        or not isinstance(value.get("question"), str)
    ):
        raise ClaudeError("bad_request", 400, "`previous` needs `request` and `question`")
    return {"request": value["request"][:MAX_TEXT], "question": value["question"][:300]}


Interpreter = Callable[[Settings, str, str, datetime, dict | None], Awaitable[dict]]


async def handle(
    settings: Settings,
    body: dict,
    *,
    context: Callable[[datetime, str | None], actions.Context],
    start_plan: Callable[[], None],
    interpreter: Interpreter | None = None,
) -> dict:
    """``POST /command``. Body: ``{"request_id", "text" | "choice", "language"?, "now"?,
    "timezone"?, "previous"?}``."""
    request_id = body.get("request_id")
    if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
        raise ClaudeError(
            "bad_request", 400, "`request_id` must be a random id of 16 to 64 characters"
        )
    _sweep()
    known = _ANSWERS.get(request_id)
    if known is not None:
        return {**await asyncio.shield(known[1]), "repeat": True}
    future: asyncio.Future = asyncio.get_running_loop().create_future()
    _ANSWERS[request_id] = (time.monotonic() + KEEP_SECONDS, future)
    try:
        answer = await _answer(settings, body, context, start_plan, interpreter or interpret)
    except BaseException as error:
        _ANSWERS.pop(request_id, None)  # nothing was done: the same id may be sent again
        if not future.done():
            future.set_exception(error)
            future.exception()  # retrieved: no warning when nobody else waits
        raise
    answer = {"request_id": request_id, **answer}
    future.set_result(answer)
    return answer


async def _answer(settings, body, context, start_plan, interpreter) -> dict:
    language = body.get("language") if body.get("language") in LANGUAGES else "en"
    now = _read_now(body.get("now"))
    zone = body.get("timezone") if isinstance(body.get("timezone"), str) else None
    ctx = context(now, zone)
    choice = body.get("choice")
    if choice is not None:
        found = _PENDING.pop(choice, None) if isinstance(choice, str) else None
        if found is None:
            raise ClaudeError("choice_expired", 404, "this choice is no longer open")
        pending = found[1]
        for other in [k for k, (_, p) in _PENDING.items() if p.group == pending.group]:
            del _PENDING[other]  # one pick per question
        steps = [(pending.option.action, pending.option.params), *pending.rest]
        return await _run(ctx, steps, start_plan)
    text = body.get("text")
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TEXT:
        raise ClaudeError("bad_request", 400, f"`text` must be 1 to {MAX_TEXT} characters")
    previous = _read_previous(body.get("previous"))
    started = time.monotonic()
    data = await interpreter(settings, text.strip(), language, now, previous)
    took = int((time.monotonic() - started) * 1000)
    try:
        planned, question = read_plan(data)
    except actions.Refused as exc:
        raise ClaudeError(
            "not_understood", 422, "the request could not be turned into actions"
        ) from exc
    if question is not None:
        return {
            "results": [],
            "question": {"kind": "clarify", "text": question["text"],
                         "options": [{"label": o, "answer": o} for o in question["options"]]},
            "previous": {"request": text.strip()[:MAX_TEXT], "question": question["text"]},
            "model_ms": took,
        }  # fmt: skip
    return await _run(ctx, planned, start_plan) | {"model_ms": took}


async def _run(ctx: actions.Context, steps: list[tuple[str, dict]], start_plan) -> dict:
    results: list[dict] = []
    for index, (name, params) in enumerate(steps):
        if name in actions.STARTED_BY_ENDPOINT:
            start_plan()
            results.append(actions.Outcome(name).as_json())
            continue
        try:
            outcome = await asyncio.to_thread(actions.execute, ctx, name, params)
        except actions.Ask as ask:
            options = []
            group = secrets.token_hex(8)
            for option in ask.options[:MAX_OPTIONS]:
                token = secrets.token_urlsafe(12)
                pending = actions.Pending(option, list(steps[index + 1 :]), group)
                _PENDING[token] = (time.monotonic() + KEEP_SECONDS, pending)
                options.append({"label": option.label, "detail": option.detail, "choice": token})
            question = {"kind": ask.kind, "action": name, "text": None, "options": options}
            return {"results": results, "question": question}
        except Exception:
            outcome = actions.Outcome(name, ok=False, error="failed")
        results.append(outcome.as_json())
    return {"results": results, "question": None}
