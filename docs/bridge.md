# Bridge server

The bridge is a small HTTP server on the owner's computer. It runs Claude
Code headless and returns the answers. It listens on the loopback interface
only, for local tools such as `scripts/ask`. The phone app never connects to
it directly: both sides make outbound HTTPS connections to a relay and
exchange end-to-end encrypted messages (design: [`security.md`](security.md);
not built yet).

## Security defaults

- Listens on `127.0.0.1` by default. Only loopback addresses are accepted as
  bind addresses; every other address, including wildcard (`0.0.0.0`, `::`),
  private-network and public addresses, is refused at start-up.
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
| PATCH | `/sessions/{id}` | yes | Sets the owner's title, group, pin and reply-notification switch |
| GET / POST | `/session-groups` | yes | Lists or creates the owner's session groups |
| PATCH / DELETE | `/session-groups/{id}` | yes | Renames or deletes a group |
| GET / POST | `/reminders` | yes | Lists or creates session reminders |
| PATCH / DELETE | `/reminders/{id}` | yes | Changes or deletes a reminder |
| GET / POST / DELETE | `/allowed-paths` | yes | Lists, adds or removes an allowed folder |

### `POST /ask`

Request body (JSON):

| Field | Type | Meaning |
|---|---|---|
| `prompt` | string, required | The prompt, at most 100 000 characters |
| `session_id` | string, optional | Continue an earlier `/ask` conversation |
| `stream` | boolean, optional | Send the answer as it is written (see below) |
| `mode` | string, optional | `read_only` (default) or `capable`; see "Capable mode" |

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
| 403 | `folder_not_allowed` | Capable mode: the folder may never be used for capable runs |
| 502 | `audit_mismatch` | Capable mode: a tool ran without an audit record; the run was stopped |
| 502 | `claude_failed`, `bad_output`, `claude_error`, `error_…` | The Claude run failed (e.g. `error_max_turns`) |
| 503 | `claude_not_found`, `working_directory_missing` | Set-up problem on the computer |
| 503 | `capable_mode_unavailable` | Capable mode is switched off on this computer |
| 504 | `timeout` | The run took too long and was stopped |

With `"stream": true` the response is `text/event-stream`:

```
event: session      data: {"session_id": "..."}      once, first
event: delta        data: {"text": "..."}            many: pieces of the answer
event: done         data: {same fields as the normal response}
event: error        data: {"error": "...", "message": "..."}     instead of done
```

Closing the connection is meant to stop the run; this is not covered by a
test yet, so rely on the timeout as the hard limit.

### Capable mode

Every request is read-only unless it says `"mode": "capable"`. The capable
mode lets Claude edit files and run shell commands inside guardrails; the
full design is in [`permission-policy.md`](permission-policy.md).

**It is switched off.** The bridge answers `503 capable_mode_unavailable`
until the guardrail check has passed on this computer for the installed
Claude Code version (a marker file `~/.config/lifeos/capable-mode.json`,
written only by the check). A Claude Code update switches it off again.

What a capable run looks like once enabled:

- It works in the run's folder and the memory folder; other allowed folders
  are readable. The home folder, the private settings, keys and browser
  profiles are hidden from it, and its commands have no network.
- It requires Claude Code's OS sandbox and refuses to start without it.
- Every tool call is written to the audit log
  `~/.config/lifeos/audit/YYYY-MM.jsonl` **before** it runs (commands in
  full; for file tools the path, never the content). If the log cannot be
  written, nothing runs.
- Actions that need the owner's approval (deleting, force operations,
  pushing, installing packages, network and messaging commands) are refused
  with an explanation; the response lists them in `refused_actions`, e.g.
  `["push"]`. `sudo` and system-setting commands are always refused.
- The mode belongs to the request, not the session: the next request without
  `"mode": "capable"` is read-only again.

Folders that can never be allowed, whatever `allowed_paths` says: the home
folder itself, anything outside it, hidden folders, the LifeOS program
folder and the private settings folder. Symbolic links are resolved first.

### `GET /sessions`

Lists the Claude Code sessions stored on the computer whose folder is the
ask folder or one of the folders in `~/.config/lifeos/allowed_paths` (one
absolute path per line, `#` for comments), including their sub-folders.
Sessions of any other folder are never opened or listed.

Sessions started in a terminal on the computer appear here like any other,
as long as their folder is allowed.

Query: `limit` (default 50, at most 200). Response, pinned sessions first,
then newest first:

```json
{"sessions": [{"id": "…", "title": "…", "detected_title": "…",
               "folder": "/path/to/folder",
               "last_activity": "2027-01-15T08:08:20Z", "in_use": false,
               "group": null, "auto_group": "folder", "pinned": false,
               "notify_replies": true}],
 "total": 1, "groups": [{"id": "…", "name": "…"}]}
```

- `title` is the owner's own title when one was set (see below), otherwise
  `detected_title`.
- `group` is the id of one of the owner's groups, or `null`; a session
  without a group is shown under `auto_group`, the name of its folder.

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

### Organising sessions: `PATCH /sessions/{id}` and `/session-groups`

The owner's own order on top of the list: groups, titles, pins and a
per-session switch for reply notifications.

- `POST /session-groups` with `{"name": "…"}` creates a group (`201`, returns
  `{"id", "name"}`; names are unique, at most 60 characters);
  `PATCH /session-groups/{id}` renames it; `DELETE` removes it, and its
  sessions become ungrouped.
- `PATCH /sessions/{id}` accepts any of `{"title": "…" | null,
  "group": "<group id>" | null, "pinned": true | false,
  "notify_replies": true | false}` and returns the session as in the list.
  `null` returns to the detected title or to no group.
- Only sessions inside the allowed folders can be changed (`404` otherwise).
- **Claude Code's session files are never modified.** The metadata is one
  file, `~/.config/lifeos/sessions-meta.json` (mode 600). Deleting that file
  loses the groups, titles and pins, nothing else.
- `notify_replies` is stored now and used once phone push exists: a finished
  run then sends a push unless the switch is off.

### `/reminders`

"Remind me about this session at <time>."

- `POST /reminders` with `{"session_id": "…", "at":
  "2027-01-15T09:00:00+03:00", "note": "…", "repeat": "none" | "daily" |
  "weekly", "timezone": "Europe/Istanbul"}`. `at` must carry a time-zone
  offset; `note`, `repeat` (default `none`) and `timezone` are optional. With
  a `timezone`, a repeating reminder keeps its local hour across clock
  changes. Returns `201` and the reminder with its `id` and `state`.
- `GET /reminders` lists them, soonest first; filters: `session_id`,
  `state=scheduled` or `state=fired`. `PATCH /reminders/{id}` changes time,
  note or repetition (a new time schedules it again); `DELETE` removes it.
- While it runs, the bridge checks every 30 seconds and fires each due
  reminder once. Reminders that came due while the bridge was not running
  fire at its next start; a repeating reminder that missed several
  occurrences fires once and moves to its next time.
- Firing calls `scripts/notify.sh` with a fixed text. **The note, the session
  title and paths are never in the notification**; the note is returned only
  by this authenticated API. Once phone push exists, the same step wakes the
  phone, which fetches the fired reminder and opens the session.
- Stored in `~/.config/lifeos/reminders.json` (mode 600).

### `/allowed-paths`

The folders Claude may work in besides the memory folder, stored in
`~/.config/lifeos/allowed_paths`. The list starts empty.

- `GET` → `{"paths": [{"path": "...", "active": true, "reason": null}]}`.
  An entry written into the file by hand that breaks the limits is shown
  with `active: false` and the reason, and is never used.
- `POST` with `{"path": "/home/.../folder"}` adds a folder; `DELETE` with the
  same body removes it. Both answer with the new list.
- Changes apply immediately, without restarting the bridge.
- **Never addable** (`400 path_not_allowed` with the reason): the home
  folder itself; anything outside the home folder, which includes all system
  folders; hidden folders or anything inside one (settings, keys,
  credentials); the LifeOS program folder; the private settings folder;
  paths that do not exist or are not folders. Symbolic links are resolved
  before checking.
- Every change is written to `~/.config/lifeos/allowed_paths.log` and
  announced on the computer through `scripts/notify.sh`. The notification
  says that a folder was added or removed, never which one.

## Terminal client

`scripts/ask` talks to the bridge from a terminal. It needs only Python 3
and reads address and token from `~/.config/lifeos/env`; the token is never
printed.

```
scripts/ask --health                          # bridge reachable, token accepted?
scripts/ask "Which files are in this folder?" # ask
scripts/ask -c "And the newest one?"          # continue the last conversation
scripts/ask --stream "Explain it step by step"
scripts/ask --sessions                        # list sessions
scripts/ask --resume <session-id> "Go on"     # continue a listed session
echo "a long prompt" | scripts/ask -
```

`--json` prints the raw response. Exit status: 0 ok, 1 the bridge reported
an error, 2 usage or configuration error, 3 bridge not reachable, 4 token
rejected.

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

Allowed folders (the refusals are safe to try; they change nothing):

```
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" http://127.0.0.1:8787/allowed-paths
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"path":"~/.ssh"}' http://127.0.0.1:8787/allowed-paths
# {"error":"path_not_allowed","message":"hidden folders (settings, keys, credentials) cannot be allowed"}
# add a real folder with -d '{"path":"/home/<you>/<folder>"}'  -> a desktop notification appears
# remove it again with  -X DELETE  and the same body
```

Session groups, pins and reminders (replace `<id>` with a session id from
`/sessions`; these change only the bridge's own two files under
`~/.config/lifeos/`):

```
H="Authorization: Bearer $LIFEOS_BRIDGE_TOKEN"; B=http://127.0.0.1:8787
curl -s -H "$H" -H 'Content-Type: application/json' -d '{"name":"Example group"}' $B/session-groups
curl -s -H "$H" -H 'Content-Type: application/json' -X PATCH \
  -d '{"title":"My title","pinned":true}' $B/sessions/<id>
curl -s -H "$H" $B/sessions            # the session is first, with the new title
curl -s -H "$H" -H 'Content-Type: application/json' \
  -d "{\"session_id\":\"<id>\",\"at\":\"$(date -d '+1 minute' -Iseconds)\",\"note\":\"test\"}" $B/reminders
# within about 90 seconds a desktop notification "LifeOS: session reminder" appears
curl -s -H "$H" "$B/reminders?state=fired"
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
