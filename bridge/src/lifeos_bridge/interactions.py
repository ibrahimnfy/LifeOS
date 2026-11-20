"""Questions and approvals that pause a run until the owner answers (SDK runner).

A run asks through the SDK's permission callback (``sdk_runner``): a
structured question from the assistant, or an action that needs the owner's
confirmation. The question waits here, is shown to the phone (an
``interaction`` event in the answer stream, ``GET /sessions/{id}/interactions``
and a push hint), and is answered with ``POST
/sessions/{id}/interactions/{iid}``. No answer before the time limit, a
cancel, or the end of the run all count as "no": nothing is ever approved by
default.

Questions and answers are prompt text: they live in memory only and are
never logged.
"""

from __future__ import annotations

import asyncio
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from .claude import ClaudeError

MAX_ANSWER_CHARS = 4000
QUESTION, APPROVAL = "question", "approval"

# How a wait ended.
ANSWERED, TIMEOUT, CANCELLED = "answered", "timeout", "cancelled"


def _now() -> datetime:
    return datetime.now(UTC)


def _stamp(moment: datetime) -> str:
    return moment.isoformat(timespec="seconds").replace("+00:00", "Z")


@dataclass
class Interaction:
    id: str
    session_id: str
    kind: str
    data: dict
    created_at: str
    expires_at: str
    future: asyncio.Future = field(repr=False)

    def public(self) -> dict:
        return {
            "id": self.id,
            "session_id": self.session_id,
            "kind": self.kind,
            **self.data,
            "created_at": self.created_at,
            "expires_at": self.expires_at,
        }


def _text(value: object, name: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > MAX_ANSWER_CHARS:
        raise ClaudeError(
            "bad_request", 400, f"`{name}` must be text of at most {MAX_ANSWER_CHARS} characters"
        )
    return value


def parse_answer(interaction: Interaction, body: dict) -> dict:
    """The owner's answer, checked against what was asked.

    Question: ``{"answers": {"<question>": "<label or own text>" | [labels]}}``,
    or ``{"response": "<free text>"}``, or ``{"decline": true}``.
    Approval: ``{"approve": true | false}``.
    """
    if interaction.kind == APPROVAL:
        if not isinstance(body.get("approve"), bool):
            raise ClaudeError("bad_request", 400, "`approve` must be true or false")
        return {"approve": body["approve"]}
    if body.get("decline") is True:
        return {"decline": True}
    asked = {q["question"] for q in interaction.data["questions"]}
    answers: dict[str, str | list[str]] = {}
    raw = body.get("answers")
    if raw is not None:
        if not isinstance(raw, dict) or not raw:
            raise ClaudeError("bad_request", 400, "`answers` must map questions to answers")
        for question, value in raw.items():
            if question not in asked:
                raise ClaudeError("bad_request", 400, "`answers` names a question not asked")
            if isinstance(value, list) and value:
                answers[question] = [_text(v, "answers") for v in value]
            else:
                answers[question] = _text(value, "answers")
    parsed: dict = {"answers": answers}
    if body.get("response") is not None:
        parsed["response"] = _text(body["response"], "response")
    if not answers and "response" not in parsed:
        raise ClaudeError("bad_request", 400, "give `answers`, `response` or `decline`")
    return parsed


def question_data(tool_input: dict) -> dict | None:
    """What the phone shows for an ``AskUserQuestion`` call; None if it is malformed."""
    questions = tool_input.get("questions")
    if not isinstance(questions, list) or not 1 <= len(questions) <= 4:
        return None
    shown = []
    for item in questions:
        if not isinstance(item, dict) or not isinstance(item.get("question"), str):
            return None
        options = item.get("options") if isinstance(item.get("options"), list) else []
        shown.append(
            {
                "question": item["question"],
                "header": str(item.get("header") or ""),
                "multi_select": bool(item.get("multiSelect")),
                "options": [
                    {
                        "label": str(o.get("label") or ""),
                        "description": str(o.get("description") or ""),
                    }
                    for o in options
                    if isinstance(o, dict)
                ],
            }
        )
    return {"questions": shown}


class Interactions:
    """Pending questions and approvals, per session. In memory only."""

    def __init__(self, timeout: float) -> None:
        self.timeout = timeout
        self.items: dict[str, Interaction] = {}

    def open(self, session_id: str, kind: str, data: dict) -> Interaction:
        now = _now()
        interaction = Interaction(
            id=uuid.uuid4().hex[:12],
            session_id=session_id,
            kind=kind,
            data=data,
            created_at=_stamp(now),
            expires_at=_stamp(now + timedelta(seconds=self.timeout)),
            future=asyncio.get_running_loop().create_future(),
        )
        self.items[interaction.id] = interaction
        return interaction

    async def wait(self, interaction: Interaction) -> tuple[str, dict | None]:
        """``(ANSWERED, answer)``, ``(TIMEOUT, None)`` or ``(CANCELLED, None)``; then forgets it."""
        try:
            return await asyncio.wait_for(asyncio.shield(interaction.future), self.timeout)
        except TimeoutError:
            return TIMEOUT, None
        finally:
            self.items.pop(interaction.id, None)
            if not interaction.future.done():
                interaction.future.set_result((CANCELLED, None))

    def pending(self, session_id: str) -> list[dict]:
        return [i.public() for i in self.items.values() if i.session_id == session_id]

    def answer(self, session_id: str, interaction_id: str, body: dict) -> dict:
        interaction = self.items.get(interaction_id)
        if interaction is None or interaction.session_id != session_id or interaction.future.done():
            raise ClaudeError(
                "not_pending", 404, "this question is no longer waiting (answered or expired)"
            )
        parsed = parse_answer(interaction, body)
        interaction.future.set_result((ANSWERED, parsed))
        return {"answered": True, "id": interaction_id}

    def cancel(self, session_id: str) -> int:
        """End every wait of the session as "no" (cancel, send now, end of the process)."""
        ended = 0
        for interaction in list(self.items.values()):
            if interaction.session_id == session_id and not interaction.future.done():
                interaction.future.set_result((CANCELLED, None))
                ended += 1
        return ended
