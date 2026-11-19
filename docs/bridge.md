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

## Run as a service

The bridge can run as a systemd **user** service, so it starts by itself and
is restarted if it crashes. No `sudo` is involved.

```
scripts/install-service.sh              # install or update, enable and start
loginctl enable-linger "$USER"          # once: keep it running after logout and start it at boot
```

| Task | Command |
|---|---|
| Status | `systemctl --user status lifeos-bridge` |
| Start / stop / restart | `systemctl --user start\|stop\|restart lifeos-bridge` |
| Logs (follow) | `journalctl --user -u lifeos-bridge -f` |
| Logs (last hour) | `journalctl --user -u lifeos-bridge --since "1 hour ago"` |
| Disable autostart | `systemctl --user disable lifeos-bridge` |
| Remove | `scripts/install-service.sh --uninstall` |

Notes:

- After changing `~/.config/lifeos/env`, restart the service. After pulling
  new code, run `scripts/install-service.sh` again.
- The service looks for the `claude` command in `~/.local/bin`,
  `/usr/local/bin` and `/usr/bin`. If it is installed elsewhere, set
  `LIFEOS_CLAUDE_BIN` to its full path.
- A configuration error (exit status 2) is not retried; fix the config and
  start the service again. The reason is in the logs.
- The logs contain start-up messages and one line per request (method, path,
  status). They never contain prompts or answers.
- The unit template is `bridge/systemd/lifeos-bridge.service.in`; the install
  script fills in the repository location.

Check after installing: `systemctl --user status lifeos-bridge` shows
`active (running)`, and the manual check below works without starting the
bridge by hand.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | no | Liveness check; returns `{"status":"ok"}` and nothing else |
| GET | `/whoami` | yes | Lets a client verify its token; returns the bridge version |
| POST | `/ask` | yes | Runs a prompt through headless Claude and returns the answer |
| GET | `/sessions` | yes | Lists Claude Code sessions in the allowed folders |
| POST | `/sessions/{id}/messages` | yes | Continues a listed session with a new message |

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

### `POST /sessions/{id}/messages`

Continues a session from the list with a new message. Body:
`{"prompt": "...", "stream": false}`; response, streaming and errors are the
same as for `/ask`.

- The run starts in the folder the session was created in, with the same
  read-only policy as `/ask` (files of that folder only).
- Only sessions inside the allowed folders can be resumed; anything else is
  `404 session_not_found`.
- **A session that is open on the computer is refused** with
  `409 session_in_use`, because two writers would damage the conversation.
  The bridge asks Claude Code which sessions are active
  (`claude agents --json`) right before starting. If that check itself fails,
  nothing is resumed (`503 in_use_check_failed`).
- `409 folder_missing` when the session's folder no longer exists.

Limits of the in-use check: there is a short gap between the check and the
start of the run, and sessions held by other Claude apps (desktop or editor
extensions) may not be reported. Close a session on the computer before
continuing it from the phone.

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

Resume a session from the list:

```
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"What did I ask before? One line."}' \
  http://127.0.0.1:8787/sessions/<id from the list>/messages
# in-use check: open the same session in a terminal (`claude --resume <id>`), repeat the
# request -> 409 {"error":"session_in_use",...}
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
