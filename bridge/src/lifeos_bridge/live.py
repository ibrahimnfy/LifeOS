"""Following a session's current run after the fact (#154). Read-only: never a writer.

Every run that goes through the bridge's event path (streamed messages and
the queued messages the bridge sends by itself) publishes its events here.
A client that attaches later (``GET /sessions/{id}/live``) first gets what
the current run said so far, then its events as they come, until it ends.
Text deltas are kept merged; at most :data:`MAX_TEXT` characters of a run are
kept for a late attacher (later text still reaches attached clients). Of the
run's ``progress`` lines (#199) only the last one is kept: a late attacher gets
it at the end of the replay, and ``running`` carries it for a client that only asks.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from datetime import UTC, datetime

MAX_TEXT = 1_000_000
MAX_EVENTS = 2000
END = ("_end", {})


class Live:
    def __init__(self) -> None:
        self._runs: dict[str, dict] = {}

    def running(self, session_id: str) -> dict | None:
        run = self._runs.get(session_id)
        if run is None:
            return None
        return {
            "queue_id": run["queue_id"],
            "started_at": run["started_at"],
            "progress": run["progress"],
        }

    def begin(self, session_id: str, queue_id: str | None = None) -> object:
        """Start publishing a run; the returned token ends exactly this run."""
        self.end(session_id)  # a leftover of an earlier run ends here
        token = object()
        self._runs[session_id] = {
            "token": token,
            "queue_id": queue_id,
            "started_at": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "events": [],
            "progress": None,
            "text": 0,
            "truncated": False,
            "followers": set(),
        }
        return token

    def publish(self, session_id: str, event: str, data: dict) -> None:
        run = self._runs.get(session_id)
        if run is None:
            return
        for follower in run["followers"]:
            follower.put_nowait((event, data))
        if event == "keepalive":
            return  # not worth replaying
        if event == "progress":
            run["progress"] = data  # only the last one matters to a late attacher
            return
        events = run["events"]
        if event == "delta":
            text = str(data.get("text") or "")
            if run["text"] + len(text) > MAX_TEXT:
                run["truncated"] = True
                return
            run["text"] += len(text)
            if events and events[-1][0] == "delta":
                events[-1] = ("delta", {"text": events[-1][1]["text"] + text})
                return
            events.append(("delta", {"text": text}))
        elif len(events) < MAX_EVENTS:
            events.append((event, data))
        else:
            run["truncated"] = True

    def end(self, session_id: str, token: object | None = None) -> None:
        """End the session's run (only the one of ``token``, when given)."""
        run = self._runs.get(session_id)
        if run is None or (token is not None and run["token"] is not token):
            return
        del self._runs[session_id]
        for follower in run["followers"]:
            follower.put_nowait(END)

    async def follow(self, session_id: str) -> AsyncIterator[tuple[str, dict]]:
        """What the current run said so far, then its events live, until it ends."""
        run = self._runs.get(session_id)
        if run is None:
            yield "replay_end", {"events": 0}  # nothing to replay, nothing live
            return
        follower: asyncio.Queue = asyncio.Queue()
        replay = list(run["events"])
        run["followers"].add(follower)
        try:
            if run["truncated"]:
                yield "replay_truncated", {}
            for item in replay:
                yield item
            if run["progress"] is not None:
                yield "progress", run["progress"]
            # Everything after this is live (#157): the app can stop showing "catching up".
            yield "replay_end", {"events": len(replay)}
            while True:
                item = await follower.get()
                if item is END:
                    return
                yield item
        finally:
            run["followers"].discard(follower)
