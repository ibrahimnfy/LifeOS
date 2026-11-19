#!/usr/bin/env python3
"""Stand-in for the ``claude`` command, used by the tests.

Records how it was called in ``.fake-claude-calls.jsonl`` (in the working
directory) and behaves according to the first word of the prompt:

  FAKE_SLEEP        never answers (ignores SIGINT, so the caller must escalate)
  FAKE_ERROR        answers with an error result
  FAKE_DENIED       answers, reporting a denied tool call
  FAKE_GARBAGE      prints something that is not JSON
  FAKE_CRASH        prints nothing and exits 1
  anything else     answers "echo: <prompt>"
"""

import json
import os
import re
import signal
import sys
import time
from pathlib import Path

args = sys.argv[1:]

if args[:2] == ["agents", "--json"]:
    # Active sessions: taken from ~/.fake-claude-agents.json when present.
    state = Path.home() / ".fake-claude-agents.json"
    content = state.read_text() if state.exists() else "[]"
    if content.strip() == "FAIL":
        sys.exit(1)
    print(content)
    sys.exit(0)

prompt = sys.stdin.read()


def option(name):
    return args[args.index(name) + 1] if name in args else None


resume = option("--resume")
session_id = resume or option("--session-id") or "00000000-0000-4000-8000-000000000000"
streaming = option("--output-format") == "stream-json"

with open(".fake-claude-calls.jsonl", "a") as log:
    log.write(
        json.dumps({"args": args, "prompt": prompt, "env": dict(os.environ), "cwd": os.getcwd()})
        + "\n"
    )

project = Path.home() / ".claude" / "projects" / re.sub(r"[^A-Za-z0-9]", "-", os.getcwd())
transcript = project / f"{session_id}.jsonl"
if resume and not transcript.exists():
    print(f"No conversation found with session ID: {resume}", file=sys.stderr)
    sys.exit(1)

if prompt.startswith("FAKE_SLEEP"):
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    time.sleep(60)
if prompt.startswith("FAKE_CRASH"):
    print("something broke", file=sys.stderr)
    sys.exit(1)
if prompt.startswith("FAKE_GARBAGE"):
    print("this is not json")
    sys.exit(0)

project.mkdir(parents=True, exist_ok=True)
with transcript.open("a") as handle:
    handle.write(json.dumps({"type": "user", "sessionId": session_id}) + "\n")

result = {
    "type": "result",
    "subtype": "success",
    "is_error": False,
    "result": f"echo: {prompt}",
    "session_id": session_id,
    "duration_ms": 12,
    "num_turns": 1,
    "permission_denials": [],
    "extra_unknown_key": 1,
}
if prompt.startswith("FAKE_ERROR"):
    result.update(subtype="error_max_turns", is_error=True, result="", errors=["turn limit"])
if prompt.startswith("FAKE_DENIED"):
    result["permission_denials"] = [
        {"tool_name": "Write", "tool_use_id": "x", "tool_input": {"file_path": "secret"}},
        {"tool_name": "Write", "tool_use_id": "y", "tool_input": {}},
    ]

if streaming:
    if "--verbose" not in args:
        print("stream-json requires --verbose", file=sys.stderr)
        sys.exit(1)

    def emit(obj):
        print(json.dumps(obj), flush=True)

    emit({"type": "system", "subtype": "init", "session_id": session_id})
    if not result["is_error"]:
        for piece in ("echo: ", prompt):
            emit(
                {
                    "type": "stream_event",
                    "event": {
                        "type": "content_block_delta",
                        "delta": {"type": "text_delta", "text": piece},
                    },
                }
            )
        emit({"type": "stream_event", "event": {"type": "message_stop"}})
    print("not json, must be skipped", flush=True)
    emit(result)
else:
    print(json.dumps(result))
sys.exit(1 if result["is_error"] else 0)
