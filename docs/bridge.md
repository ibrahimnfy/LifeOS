# Bridge server

The bridge is a small HTTP server on the owner's computer. The phone app
talks to it over the private network; it runs Claude Code headless and
returns the answers.

## Security defaults

- Listens on `127.0.0.1` by default. Only localhost or the machine's
  Tailscale address are accepted as bind addresses; wildcard (`0.0.0.0`,
  `::`) and public addresses are refused at start-up.
- Every route except `GET /health` requires `Authorization: Bearer <token>`.
  Unknown paths are rejected with `401` too (default deny).
- The token lives in `~/.config/lifeos/env`. The bridge refuses to start
  without one, with one shorter than 32 characters, or when that file is
  readable by other users.
- Prompt contents are never logged. The access log holds method, path and
  status only.
- Interactive API docs and schema endpoints are disabled.

## Setup

Requirements: Python 3.12 or newer and [`uv`](https://docs.astral.sh/uv/).

```
scripts/init-config.sh        # creates ~/.config/lifeos/env with a random token
cd bridge
uv sync
```

Settings are documented in [`config/env.example`](../config/env.example).
Real environment variables override the file. `LIFEOS_CONFIG_DIR` points the
bridge (and the scripts) at a different config folder.

## Run

```
cd bridge
uv run lifeos-bridge
```

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | no | Liveness check; returns `{"status":"ok"}` and nothing else |
| GET | `/whoami` | yes | Lets a client verify its token; returns the bridge version |
| POST | `/ask` | yes | Runs a prompt through headless Claude and returns the answer |
| GET | `/sessions` | yes | Lists Claude Code sessions in the allowed folders |

### `POST /ask`

Request body (JSON):

| Field | Type | Meaning |
|---|---|---|
| `prompt` | string, required | The prompt, at most 100 000 characters |
| `session_id` | string, optional | Continue an earlier `/ask` conversation |
| `stream` | boolean, optional | Send the answer as it is written (see below) |

Response:

```json
{"answer": "...", "session_id": "...", "duration_ms": 3400, "num_turns": 2, "denied_tools": []}
```

`denied_tools` names the tools Claude tried to use but was not allowed to
(for example `["Write"]`); the run still completes.

How a prompt runs:

- In the folder `LIFEOS_ASK_DIR` (default `~/brain`), through
  `claude -p` with every flag set explicitly.
- **Read-only for now**: Claude may read and search files inside that folder
  and nothing else — no shell commands, no edits, no web access, no files
  outside the folder. This stays until the owner decides the permission
  policy.
- The prompt is passed on standard input, never on the command line, and is
  never logged.
- The Claude process gets a minimal environment; API keys and other
  variables of the bridge's own environment are not passed on, so runs use
  the Claude Code login of the owner's account.
- At most `LIFEOS_MAX_CONCURRENT` runs at a time, and one per session.
- A run that exceeds `LIFEOS_ASK_TIMEOUT` seconds is stopped.

Errors are JSON `{"error": "<code>", "message": "<text>"}`:

| Status | `error` | Meaning |
|---|---|---|
| 400 | `bad_request` | Body is not a JSON object or `prompt` is missing/empty |
| 404 | `session_not_found` | `session_id` is not an `/ask` session of this folder |
| 409 | `session_in_use` | That session is already answering a prompt |
| 413 | `prompt_too_large`, `too_large` | Prompt or request body too big |
| 429 | `busy` | Too many runs are active; try again shortly |
| 502 | `claude_failed`, `bad_output`, `claude_error`, `error_…` | The Claude run failed (e.g. `error_max_turns`) |
| 503 | `claude_not_found`, `working_directory_missing` | Set-up problem on the computer |
| 504 | `timeout` | The run took too long and was stopped |

With `"stream": true` the response is `text/event-stream`:

```
event: session      data: {"session_id": "..."}      once, first
event: delta        data: {"text": "..."}            many: pieces of the answer
event: done         data: {same fields as the normal response}
event: error        data: {"error": "...", "message": "..."}     instead of done
```

Closing the connection stops the run.

### `GET /sessions`

Lists the Claude Code sessions stored on the computer whose folder is the
ask folder or one of the folders in `~/.config/lifeos/allowed_paths` (one
absolute path per line, `#` for comments), including their sub-folders.
Sessions of any other folder are never opened or listed.

Query: `limit` (default 50, at most 200). Response, newest first:

```json
{"sessions": [{"id": "…", "title": "…", "folder": "/path/to/folder",
               "last_activity": "2027-01-15T08:08:20Z", "in_use": false}],
 "total": 1}
```

- `title` is the session's own title when it has one, otherwise the
  beginning of its first prompt. Titles are conversation content: they are
  sent to the authenticated client only and never logged.
- `in_use` is true while the bridge itself is running a prompt in that
  session.
- Claude Code deletes old sessions by itself (after 30 days by default), so
  a listed session can disappear later.
- Session files are an internal format of Claude Code; files the bridge
  cannot read are skipped. After a Claude Code update, re-run the check below.

## Manual check

With the bridge running in another terminal:

```
source ~/.config/lifeos/env
curl -s http://127.0.0.1:8787/health                      # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8787/whoami    # 401
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" http://127.0.0.1:8787/whoami
                                                          # {"status":"ok","version":"..."}
```

Ask a question (this uses the owner's Claude plan; keep it small). The
folder in `LIFEOS_ASK_DIR` must exist and contain a file to ask about:

```
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"Which files are in this folder? Answer in one line."}' \
  http://127.0.0.1:8787/ask
# {"answer":"...","session_id":"...","duration_ms":...,"num_turns":...,"denied_tools":[]}

# continue the same conversation: add "session_id":"<id from above>"
# read-only check: ask it to create a file -> the answer says it cannot, and no file appears
# streaming: add "stream":true and use `curl -N`
```

List sessions (after at least one `/ask` call there is one to show):

```
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" http://127.0.0.1:8787/sessions
# {"sessions":[{"id":"...","title":"Which files are in this folder? ...","folder":"...",...}],"total":1}
```

Start-up refusals to try (each prints a configuration error and exits):

```
LIFEOS_BRIDGE_HOST=0.0.0.0 uv run lifeos-bridge
LIFEOS_BRIDGE_TOKEN=short uv run lifeos-bridge
```

## Development

```
cd bridge
uv run pytest
uv run ruff check . && uv run ruff format --check .
```

Tests never read the real `~/.config/lifeos`; they use a temporary folder.
