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

if args == ["--version"]:
    print("fake-claude 1.0")
    sys.exit(0)

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

tool_events = []
if prompt.startswith(("FAKE_TOOL ", "FAKE_SKIPHOOK ")):
    # Simulate one Bash tool call, with the PreToolUse hook from --settings.
    import subprocess

    command = prompt.split(" ", 1)[1]
    tool_events.append(
        {
            "type": "assistant",
            "message": {
                "content": [
                    {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": command}}
                ]
            },
        }
    )
    blocked = None
    if prompt.startswith("FAKE_TOOL "):
        hook = json.loads(option("--settings"))["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
        payload = {
            "session_id": session_id,
            "cwd": os.getcwd(),
            "hook_event_name": "PreToolUse",
            "tool_name": "Bash",
            "tool_input": {"command": command},
        }
        done = subprocess.run(  # noqa: S602
            hook, shell=True, input=json.dumps(payload), text=True, capture_output=True
        )
        if done.returncode == 2:
            blocked = done.stderr.strip()
    tool_events.append(
        {
            "type": "user",
            "message": {
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": "t1",
                        "is_error": blocked is not None,
                        "content": blocked or "ran",
                    }
                ]
            },
        }
    )
    result["result"] = f"blocked: {blocked}" if blocked else "ran the command"

if streaming:
    if "--verbose" not in args:
        print("stream-json requires --verbose", file=sys.stderr)
        sys.exit(1)

    def emit(obj):
        print(json.dumps(obj), flush=True)

    emit({"type": "system", "subtype": "init", "session_id": session_id})
    for tool_event in tool_events:
        emit(tool_event)
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
