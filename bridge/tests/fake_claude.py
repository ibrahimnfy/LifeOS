#!/usr/bin/env python3
"""Stand-in for the ``claude`` command, used by the tests.

Records how it was called in ``.fake-claude-calls.jsonl`` (in the working
directory) and behaves according to the first word of the prompt:

  FAKE_SLEEP        never answers (ignores SIGINT, so the caller must escalate)
  FAKE_ERROR        answers with an error result
  FAKE_DENIED       answers, reporting a denied tool call
  FAKE_GARBAGE      prints something that is not JSON
  FAKE_CRASH        prints nothing and exits 1
  anything else     answers "echo: <prompt>", or the content of ~/.fake-claude-answer
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

if args == ["--help"]:
    print(
        "  --effort <level>   Effort level for the current session\n"
        "                     (low, medium, high, xhigh, max)"
    )
    sys.exit(0)

if args[:2] == ["agents", "--json"]:
    # Active sessions: taken from ~/.fake-claude-agents.json when present.
    state = Path.home() / ".fake-claude-agents.json"
    content = state.read_text() if state.exists() else "[]"
    if content.strip() == "FAIL":
        sys.exit(1)
    print(content)
    sys.exit(0)


def sdk_mode():
    """The Agent SDK's protocol (``--input-format stream-json``): a live process per session.

    Answers ``initialize`` and ``interrupt`` control requests and one user
    message after another. Prompts (first word): FAKE_SLEEP works until
    interrupted; FAKE_HANG ignores the interrupt, end of input and SIGINT;
    FAKE_EXIT ends the process mid-turn; FAKE_ERROR, FAKE_DENIED as in -p mode.
    """

    def opt(name):
        return args[args.index(name) + 1] if name in args else None

    resume = opt("--resume")
    session_id = resume or opt("--session-id") or "00000000-0000-4000-8000-000000000000"
    project = Path.home() / ".claude" / "projects" / re.sub(r"[^A-Za-z0-9]", "-", os.getcwd())
    transcript = project / f"{session_id}.jsonl"

    def log(entry):
        with open(".fake-claude-calls.jsonl", "a") as handle:
            handle.write(json.dumps({"pid": os.getpid(), "cwd": os.getcwd(), **entry}) + "\n")

    def emit(obj):
        print(json.dumps(obj), flush=True)

    def result(text="", **extra):
        base = {
            "type": "result", "subtype": "success", "is_error": False, "result": text,
            "session_id": session_id, "duration_ms": 7, "duration_api_ms": 5, "num_turns": 1,
            "permission_denials": [],
        }  # fmt: skip
        emit(base | extra)

    log({"args": args, "env": dict(os.environ), "sdk": True})
    if resume and not transcript.exists():
        print(f"No conversation found with session ID: {resume}", file=sys.stderr, flush=True)
        result(subtype="error_during_execution", is_error=True, num_turns=0)
        sys.exit(1)

    # FAKE_TOOL <tool> <json input>: the "CLI" asks the host (can_use_tool) and
    # answers with the host's decision as JSON. FAKE_TOOL Bash with "rm" first
    # runs the gate's ask path, as the gate hook would.
    asks = {}
    working = False
    for raw in sys.stdin:
        message = json.loads(raw)
        if message.get("type") == "control_response":
            response = message["response"]
            if response.get("request_id") in asks:
                del asks[response["request_id"]]
                decision = response.get("response") or {"error": response.get("error")}
                log({"host_decision": decision})
                result(json.dumps(decision))
                working = False
            continue
        if message.get("type") == "control_request":
            request = message["request"]
            emit(
                {
                    "type": "control_response",
                    "response": {
                        "subtype": "success",
                        "request_id": message["request_id"],
                        "response": {"commands": []} if request["subtype"] == "initialize" else {},
                    },
                }
            )
            if request["subtype"] == "interrupt":
                log({"interrupt": True})
                if working in ("sleep", "ask"):
                    asks.clear()
                    result(subtype="error_during_execution", is_error=True, result=None)
                    working = False
            continue
        if message.get("type") != "user":
            continue
        prompt = message["message"]["content"]
        log({"sdk_prompt": prompt})
        project.mkdir(parents=True, exist_ok=True)
        with transcript.open("a") as handle:
            line = {"type": "user", "sessionId": session_id, "cwd": os.getcwd(),
                    "message": {"role": "user", "content": prompt[:200]}}  # fmt: skip
            handle.write(json.dumps(line) + "\n")
        if prompt.startswith("FAKE_SLEEP"):
            working = "sleep"
            continue
        if prompt.startswith("FAKE_HANG"):
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            working = "hang"
            continue
        if prompt.startswith("FAKE_EXIT"):
            print("something broke", file=sys.stderr, flush=True)
            sys.exit(1)
        if prompt.startswith("FAKE_TOOL "):
            _, tool, tool_input = prompt.split(" ", 2)
            request_id = f"perm_{len(asks)}_{os.getpid()}"
            asks[request_id] = tool
            working = "ask"
            emit(
                {
                    "type": "control_request",
                    "request_id": request_id,
                    "request": {
                        "subtype": "can_use_tool", "tool_name": tool,
                        "input": json.loads(tool_input), "tool_use_id": "t1",
                    },
                }
            )  # fmt: skip
            continue
        emit({"type": "system", "subtype": "init", "session_id": session_id})
        for piece in ("echo: ", prompt):
            emit(
                {
                    "type": "stream_event", "uuid": "u1", "session_id": session_id,
                    "event": {"type": "content_block_delta",
                              "delta": {"type": "text_delta", "text": piece}},
                }
            )  # fmt: skip
        print("not json, must be skipped", flush=True)
        if prompt.startswith("FAKE_UNAUDITED"):
            # A tool "ran" without the gate's audit record (the bridge must stop the run).
            emit(
                {
                    "type": "user", "session_id": session_id, "parent_tool_use_id": None,
                    "message": {"role": "user", "content": [
                        {"type": "tool_result", "tool_use_id": "t1", "content": "ran",
                         "is_error": False}]},
                }
            )  # fmt: skip
            working = "sleep"  # the run goes on until the bridge stops it
            continue
        if prompt.startswith("FAKE_ERROR"):
            result(subtype="error_max_turns", is_error=True, errors=["turn limit"])
        elif prompt.startswith("FAKE_DENIED"):
            result("done", permission_denials=[{"tool_name": "Write", "tool_use_id": "x"}])
        else:
            result(f"echo: {prompt}")
    if working == "hang":
        time.sleep(60)
    sys.exit(0)


if "--input-format" in args and args[args.index("--input-format") + 1] == "stream-json":
    sdk_mode()

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

if "--advisor" in args and (Path.home() / ".fake-claude-no-advisor").exists():
    print("Error: the advisor model cannot be used with this model", file=sys.stderr)
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
    line = {
        "type": "user",
        "sessionId": session_id,
        "cwd": os.getcwd(),
        "message": {"role": "user", "content": prompt[:200]},
    }
    handle.write(json.dumps(line) + "\n")

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
    # What a real run reports about its plan usage (fixed numbers here).
    "usage": {"input_tokens": 100, "output_tokens": 20, "cache_creation_input_tokens": 5,
              "cache_read_input_tokens": 50},
    "total_cost_usd": 0.01,
    "modelUsage": {f"model-{option('--model') or 'default'}": {"inputTokens": 100}},
}  # fmt: skip
canned = Path.home() / ".fake-claude-answer"
if canned.exists():  # tests that need a specific answer write it here
    result["result"] = canned.read_text()
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
