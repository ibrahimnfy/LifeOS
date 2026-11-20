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

Planned additions for chat (history, queue and cancel, commands, models,
file offers) are specified in [`chat-design.md`](chat-design.md) and are
documented here as they are built.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | no | Liveness check; returns `{"status":"ok"}` and nothing else |
| GET | `/whoami` | yes | Lets a client verify its token; returns the bridge version |
| POST | `/ask` | yes | Runs a prompt through headless Claude and returns the answer |
| GET | `/sessions` | yes | Lists Claude Code sessions in the allowed folders |
| POST | `/sessions/{id}/messages` | yes | Continues a listed session with a new message |
| GET | `/sessions/{id}/history` | yes | A session's earlier messages, a few exchanges per page |
| GET | `/sessions/{id}/commands` | yes | Lists the skills and commands of the session's folder (not usable in runs yet) |
| PATCH | `/sessions/{id}` | yes | Sets the owner's title, group, pin, reply-notification switch, model and effort |
| POST | `/sessions/{id}/cancel` | yes | Stops the session's current run |
| GET | `/sessions/{id}/queue` | yes | Shows whether the session is answering and what is waiting |
| PATCH / DELETE | `/sessions/{id}/queue/{qid}` | yes | Changes or removes a waiting message |
| POST | `/sessions/{id}/queue/resume` | yes | Sends the waiting messages after a hold |
| GET | `/models` | yes | Models and effort levels the app may offer, and the defaults |
| GET | `/files/{offer}` | yes | Downloads a file the assistant offered |
| GET / POST | `/session-groups` | yes | Lists or creates the owner's session groups |
| PATCH / DELETE | `/session-groups/{id}` | yes | Renames or deletes a group |
| GET / POST | `/reminders` | yes | Lists or creates session reminders |
| PATCH / DELETE | `/reminders/{id}` | yes | Changes or deletes a reminder |
| POST | `/notes` | yes | Quick capture into the memory's inbox |
| GET / POST | `/tasks` | yes | Lists open tasks or adds one |
| POST | `/tasks/{id}/complete` | yes | Ticks a task |
| GET / POST | `/events` | yes | Lists or creates calendar events |
| PATCH | `/events/{id}` | yes | Changes an event |
| GET | `/agenda` | yes | Today's events and tasks, overdue tasks, the coming days |
| GET | `/context-map` | yes | Allowed folders and their summary files in the memory |
| POST | `/context-map/refresh` | yes | Writes or updates those summaries |
| GET / POST / DELETE | `/allowed-paths` | yes | Lists, adds or removes an allowed folder |
| GET | `/coach/plan` | yes | The coach's messages, top three and ordered tasks, with the plan's age |
| POST | `/coach/plan/refresh` | yes | Prepares a new plan now |
| GET | `/coach/question` | yes | The one open coach question, if any |
| POST | `/coach/question/answer` | yes | Records the owner's answer and files what it says |
| POST | `/coach/question/skip` | yes | Skips the open question |
| GET / PUT | `/coach/settings` | yes | Notification frequency, quiet hours, questions on or off |

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

**It is switched on only by the guardrail check.** The bridge answers
`503 capable_mode_unavailable` unless the check has passed on this computer
for the installed Claude Code version (a marker file
`~/.config/lifeos/capable-mode.json`, written only by the check). A Claude
Code update switches the mode off again until the check is repeated:

```
scripts/verify-guardrails
```

It runs the probe suite of [`permission-policy.md`](permission-policy.md)
(section 7) with the real Claude Code, once in the terminal and once as a
transient user service, prints one line per probe and switches the mode on
only if every probe passed in both. It takes a few minutes and about twenty
short runs of the owner's Claude plan. Results:
`~/.config/lifeos/guardrail-results.json`. The check covers runs in the
memory folder; capable runs in other allowed folders stay refused for now.
After the check, restart the service (`systemctl --user restart
lifeos-bridge`) if the bridge code changed since it was started.

What a capable run looks like:

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

### `GET /sessions/{id}/history`

Returns a session's earlier messages in small pages, read from Claude Code's
own session file. Query: `limit` (default 4, between 1 and 20) and `before`
(a cursor from an earlier answer). Without `before` the page holds the latest
exchanges.

```json
{"exchanges": [{"id": "…", "at": "2027-01-15T08:00:00.000Z",
                "user": "…", "assistant": "…",
                "tools": [{"name": "Read", "count": 3}],
                "complete": true, "truncated": false}],
 "older": "…", "total_exchanges_known": null}
```

- An exchange is one message of the owner and the text the assistant wrote
  until the owner's next message. Exchanges are oldest first within a page.
- `older` is the cursor for the page before this one (`?before=<older>`), or
  `null` when nothing earlier was found. Treat it as opaque. An unknown
  cursor is `400 bad_request`; start again without `before`.
- **Tool calls are not replayed.** `tools` counts them by tool name only: no
  inputs, no paths, no outputs. Thinking, sub-agent entries and the notes
  Claude Code injects by itself are left out.
- `complete` is false while the answer does not end with assistant text yet
  (still running, or stopped during a tool call).
- `user` and `assistant` are cut at 60,000 characters each; `truncated` says
  that one of them was cut.
- `total_exchanges_known` is always `null`: the file is not counted.
- Only sessions inside the allowed folders (`404 session_not_found`
  otherwise). The session file is only read, never changed, and the texts
  are never logged.
- One page reads at most the 16 MB of the file before its end point; only
  when those hold no owner message at all does it look further back, up to
  128 MB. Older content than that is not reachable.
- The session file format is internal to Claude Code and may change between
  versions; lines and files the reader cannot understand are skipped. After a
  Claude Code update, re-run the check below.

### `GET /sessions/{id}/commands`

Lists the skills and custom commands found in the session's own folder, for
the app's composer. It reads only the documented project locations,
`<folder>/.claude/skills/<name>/SKILL.md` and
`<folder>/.claude/commands/**/*.md`; it runs nothing and uses no model.

```json
{"enabled": false,
 "reason": "skills and commands are not switched on for this session",
 "commands": [{"name": "review", "kind": "command", "description": "…",
               "argument_hint": "<file>", "warnings": ["shell_lines"]}]}
```

- **Listed, not yet usable.** Runs still start with skills and commands
  switched off, so `enabled` is always `false` for now; the per-session
  switch comes later (see [`chat-design.md`](chat-design.md), section 3).
- `kind` is `skill` or `command`. A command in a sub-folder is named with
  `:` between the parts (`git/log.md` is `git:log`). Sorted by name.
- `description` is the `description:` of the file's frontmatter, otherwise
  its first line of text; one line, at most 200 characters. Nothing else of
  a file's content is returned.
- `warnings` uses fixed words: `shell_lines` (the file contains shell that
  would run before the model sees it), `requests_tools` (it asks for tools
  with `allowed-tools`), `has_scripts` (the skill folder holds files other
  than Markdown).
- Nothing from the home folder and no plugins. Symbolic links are resolved,
  and anything that ends up outside `<folder>/.claude` is skipped.
- Limits: 200 entries, the first 64 KB of each file, names of letters,
  digits and `_ . : -` up to 64 characters; anything else is skipped.
- Only sessions inside the allowed folders (`404 session_not_found`
  otherwise).

### While a session is answering: queue, send now, cancel

One run per session, always. What happens to a message for a session that is
still answering (`POST /ask` with `session_id`, or
`POST /sessions/{id}/messages`):

- **Queue (default).** The message is stored and the request returns `202`
  with `{"queued": true, "queue_id": "…", "position": 1}`. It is sent when
  the current run has ended, in order. At most 10 messages wait per session
  (`429 queue_full`).
- **Send now.** With `"when": "now"` the current run is interrupted and this
  message is sent next, ahead of the queue.
- `GET /sessions/{id}/queue` returns `{"running", "paused", "queue": [{"id",
  "prompt", "mode", "queued_at"}], "last"}`; `last` is the outcome of the
  most recent queued message (`done`, or `error` with a code). `PATCH
  …/queue/{qid}` with `{"prompt": "…"}` changes a waiting message, `DELETE`
  removes it.
- **Cancel.** `POST /sessions/{id}/cancel` interrupts the current run: the
  interrupt signal first, then, only if the process does not stop within a
  few seconds, terminate and kill. The stopped request answers `409
  cancelled`. **Changes the run already made to files are kept.** The queue
  is then on hold (`"paused": "cancel"`) until the owner sends a message or
  calls `POST …/queue/resume`.
- A queued message that fails holds the rest (`"paused": "error"`). After a
  bridge restart waiting messages are drafts (`"paused": "restart"`): they
  are sent only after `…/queue/resume`, never by themselves.
- Queued messages are prompt text. They are stored in
  `~/.config/lifeos/queue.json` (mode 600) and never logged.
- Answers to queued messages are not pushed over this local API; read them
  with the history endpoint. The relay will deliver them to the phone.

### Model and effort

- `GET /models` returns `{"models", "efforts", "default_model",
  "default_effort"}`. Effort levels are read from the installed Claude
  Code's help text. Claude Code has no command that lists models, so
  `models` is the list in `LIFEOS_MODELS` (default: the family aliases).
- `"model"` and `"effort"` may be given with `/ask`, with a session message,
  or with `PATCH /sessions/{id}`; they are remembered for that session and
  used for all its runs, also when it is continued. `null` in `PATCH`
  returns to the default. Unknown values are refused with `400` before
  anything runs.
- Defaults: `LIFEOS_DEFAULT_MODEL` (`opus`) and `LIFEOS_DEFAULT_EFFORT`
  (`high`). The bridge's own runs (coach plan, context map) use
  `LIFEOS_BACKGROUND_MODEL` when set.

### Files: offers and `GET /files/{offer}`

The assistant can offer a file by writing a line `[[file: path]]` in its
answer (every run is told how). The bridge removes that line from the
answer and returns a card instead:

```json
{"answer": "…", "files": [{"offer": "…", "name": "slides.pdf",
                           "type": "application/pdf", "size": 5009}],
 "files_refused": [{"name": "…", "reason": "outside the allowed folders"}]}
```

- **Nothing is read or sent until the offer is requested:**
  `GET /files/{offer}?session_id=<id>`. An offer is random, bound to its
  session and valid for 15 minutes; the file is checked again at download.
- Only regular files inside the allowed folders or the memory folder, after
  resolving links; no hidden files or folders; at most `LIFEOS_MAX_FILE_MB`
  (default 50). Cards never contain a path.
- Offers and downloads are written to the audit log with name and size.
- Sending through the relay (sealed chunks, deleted on delivery) is not
  built yet; `files.chunks` and `files.digest` are what it will use.

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

### Memory: `/notes`, `/tasks`, `/events`, `/agenda`

These endpoints read and write the memory folder (the ask folder) in the
formats of [`memory-design.md`](memory-design.md). **Every write is one git
commit there**, made as `lifeos-bridge`. The bridge refuses to write
(`503 memory_unavailable`) when the folder has not been set up, is not under
git, or **has a git remote**: the memory must stay on the computer.

- `POST /notes` with `{"text": "…"}` appends a dated line to `inbox.md`
  (`201`). Up to 4,000 characters; further lines are kept, indented.
- `GET /tasks` returns the open tasks in file order: `{"tasks": [{"id":
  "t-20270115-01", "title": "…", "done": false, "due": "2027-01-20" | null,
  "project": …, "course": …, "added": "…"}]}`.
- `POST /tasks` with `{"title": "…", "due": "YYYY-MM-DD", "project":
  "file-name", "course": "file-name"}` (only `title` is required) adds a line
  to `tasks.md` and returns the task with its new id (`201`).
- `POST /tasks/{id}/complete` moves the line to `tasks-done.md` with the
  date; `404 task_not_found` for an unknown or already finished task.
- `GET /events?start=YYYY-MM-DD&end=YYYY-MM-DD` lists the events touching
  those days (default: today and the next 30 days). `POST /events` with
  `{"title", "date", "end_date"?, "start"?: "HH:MM", "end"?, "note"?}`
  creates one (`201`); without `start` it lasts all day. `PATCH
  /events/{id}` changes any of these fields; `null` clears an optional one.
  Events live in `calendar/events.md`, one line each, in the computer's
  local time.
- `GET /agenda?day=YYYY-MM-DD&days=7` (both optional; `day` defaults to
  today in the computer's local time) returns `events_today`, `tasks_today`,
  `tasks_overdue` and `upcoming` (`events` and task `deadlines` of the next
  `days` days).

**Memory in `/ask`.** A conversation in the memory folder starts with the
memory loaded: the bridge passes the memory's `CLAUDE.md`, `INDEX.md`,
`profile.md`, the open tasks and the daily logs of today and yesterday to
the model explicitly (at most about 60,000 characters), on every request of
that conversation. Resumed sessions of other folders do not get it. The text
goes to the model only; it is never logged.

### `/context-map`

For every allowed folder the memory holds one file `map/<name>.md`: where
the folder is, what it is, its state and its key files (pointers, never
copies).

- `POST /context-map/refresh` with `{}` rewrites the files of all allowed
  folders, with `{"path": "/folder"}` of one. Per folder it starts one
  read-only run inside that folder (uses the owner's Claude plan), checks the
  answer, writes the file, adds a line to the index and commits in the
  memory folder. Running it again updates the same file; everything from the
  `## Facts` heading on is kept.
- When a folder is added through `/allowed-paths`, its file is written in
  the background. Removing a folder leaves its file in place.
- `GET /context-map` lists the allowed folders and the file of each (`null`
  when not written yet).
- Only key files that exist inside the folder are listed. The folder's
  content is outside text: it reaches the map file only, never the
  always-loaded files.

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

### Coach: `/coach/plan`, `/coach/question`, `/coach/settings`

The daily plan and the coach's questions of
[`coach-design.md`](coach-design.md). The model proposes, the bridge checks.

**Plan usage:** one read-only run each morning, one per manual refresh and
one per answered question, on the owner's Claude plan. Reading the plan,
ticking tasks, skipping a question and changing settings cost nothing.

- `GET /coach/plan` returns the last prepared plan against the tasks that
  are open now, **without a model run**:
  `{"prepared_at": "2027-01-15T06:30:00Z", "age_seconds": 5400, "stale":
  false, "messages": [{"id": "m1", "kind": "focus" | "observation" |
  "encouragement" | "question", "text": "…", "why": "…", "task":
  "t-20270115-01"}], "notification": "m1" | null, "top_three": [{"task":
  {task}, "reason": "…" | null}], "order": [{task}, …]}`. Tasks are full
  objects as in `/tasks`. `why` and `task` are optional; a question also
  carries `"question": "waiting" | "hard" | "easy" | "next" | "still"`.
  `stale` is true when the plan is not from today.
- A finished task leaves the plan at once: its messages are dropped, it
  leaves the top three and the next task of the order moves up (with
  `reason: null`). Tasks the plan does not know are appended to the order by
  rule: overdue, then due date, then date added, then file order. Without
  any plan, `prepared_at` and `age_seconds` are `null`, `messages` is empty
  and the order and top three follow that rule.
- **The morning run.** While the bridge runs it prepares one plan per day,
  at 06:30 local time or, if the computer was off then, when the bridge next
  starts. A failed run is tried again after an hour. Nothing runs while the
  memory folder is not set up or has a git remote.
- `POST /coach/plan/refresh` prepares a new plan now and returns it. At most
  one run in ten minutes (`429 too_soon`); `429 busy` while a plan is being
  prepared; `502 bad_plan` or `bad_output` when the result was not usable.
  In all these cases **the previous plan stays**.
- **Checks on a proposed plan:** every task id must be an open task; texts
  are single lines (`text` up to 280 characters, `why` and `reason` up to
  200); at most eight messages and one question; the top three are up to
  three different open tasks; unknown fields are ignored. Invalid messages
  are dropped; a result without any valid message is discarded.
- The plan is stored in `~/.config/lifeos/coach/plan.json` (mode 600), never
  in the memory folder.
- **The daily notification.** When a plan is prepared, `scripts/notify.sh`
  is called once per day with a fixed text ("Your daily note is ready.").
  **The message itself is never in the notification.** Nothing is sent in
  quiet hours; the notification waits for their end.
- `GET /coach/question` → `{"question": {message} | null}`: the question of
  the plan, at most one.
- `POST /coach/question/answer` with `{"text": "…"}` (up to 4,000
  characters) appends the question and the answer, in the owner's own words,
  to `coach/log.md` in the memory folder (one commit). Then one short
  read-only run proposes how to file it; the bridge writes only what passes
  its checks, as a second commit: up to three `- [owner <date>] …` lines
  under `## Facts` of `profile.md` or of an **existing** `projects/<name>.md`
  or `courses/<name>.md`, and the fields `next`, `blocked-by` and `effort`
  (`small`, `medium`, `large`) on the task's line in `tasks.md`. The request
  waits for that run; if it fails or proposes nothing usable, the answer is
  saved all the same. Returns `{"saved": true, "filed": {"facts":
  ["profile.md"], "task_fields": ["next"]}}`. `404 no_question` when no
  question is open; `503 memory_unavailable` as for all memory writes, and
  the question then stays open.
- `POST /coach/question/skip` closes the question without a run. The same
  task gets no question for 14 days; three skips in a row pause all
  questions for 7 days (`questions_paused_until` in the answer). An answer
  ends the row. A question that was answered is not asked again in the
  same form (`waiting`, `hard`, …) for that task.
- `GET /coach/settings` → `{"frequency": "off" | "1" | "3", "quiet_start":
  "22:00", "quiet_end": "08:00", "questions": true}` (these are the
  defaults). `PUT /coach/settings` takes any of these fields; fields left
  out keep their value. With `frequency` `off` no notification is sent; with
  `questions` `false` the plan is served without its question. Stored in
  `~/.config/lifeos/coach/settings.json`; skips are remembered in
  `coach/state.json` next to it.

## Paired phones

```
cd bridge
uv run lifeos-bridge devices                 # id, state, name, paired, last seen
uv run lifeos-bridge devices revoke <id>     # the first characters of the id are enough
```

Revoking deletes the phone's secret on the computer; from that moment its
messages are dropped. It needs no network. Pairing a phone (`pair`) arrives
with the relay backend. Design: [`security.md`](security.md).

## Moodle sign-in

```
scripts/moodle-login            # sign in through the browser, store the token
scripts/moodle-login --check    # test the stored token
```

The helper prints a link; the owner signs in on the institution's own page
and pastes the address the browser ends on (it starts with
`moodlemobile://token=`). The helper never sees the password, verifies the
pasted value, and stores only the token in `~/.config/lifeos/env`. It needs
`MOODLE_URL` in that file. Background:
[`research/moodle-access.md`](research/moodle-access.md).

## Relay connection test

```
scripts/relay-check            # add --idle 600 to watch an idle stream for ten minutes
```

Checks with ordinary HTTPS whether the relay mailbox (the Realtime Database
of the owner's Firebase project) and the push service can be used from the
network the computer is on: certificates, access tokens, write and read,
streaming event by event, an idle stream, the cost of polling, and a dry run
against the push service. It needs the two files in
`~/.config/lifeos/firebase/`, writes a few random values under one scratch
path and deletes them, and prints nothing identifying. The result is also
saved to `~/.config/lifeos/relay-check.json`. Run it on every network the
computer is used on; a failed check is a result, not something to work
around.

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
# earlier messages, two exchanges per page; repeat with `&before=<older>` to go back:
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" "http://127.0.0.1:8787/sessions/<id>/history?limit=2"
# the folder's skills and commands (listed only): {"enabled":false,...,"commands":[...]}
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" http://127.0.0.1:8787/sessions/<id>/commands
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

Capable mode (after `scripts/verify-guardrails` passed; uses the owner's
Claude plan; the memory folder must exist):

```
scripts/ask --capable --json "Create the file capable-check.txt containing the word ok, then try to run: rm capable-check.txt"
# the answer explains that deleting needs approval; "refused_actions" lists "deletion";
# capable-check.txt exists in the memory folder; the audit log has both commands:
tail -n 3 ~/.config/lifeos/audit/*.jsonl
```

Memory (each write is a commit in the memory folder; check with
`git -C ~/brain log --oneline | head`):

```
H="Authorization: Bearer $LIFEOS_BRIDGE_TOKEN"; B=http://127.0.0.1:8787; J='Content-Type: application/json'
curl -s -H "$H" -H "$J" -d '{"text":"test note"}' $B/notes
curl -s -H "$H" -H "$J" -d '{"title":"Test task","due":"2027-01-20"}' $B/tasks     # note the id
curl -s -H "$H" $B/tasks
curl -s -H "$H" -X POST $B/tasks/<id>/complete
curl -s -H "$H" -H "$J" -d '{"title":"Test event","date":"2027-01-20","start":"14:00"}' $B/events
curl -s -H "$H" "$B/agenda?day=2027-01-20"
scripts/ask "Which tasks are open?"        # the answer knows the task list without searching
```

Coach (the refresh and the answer each start one run on the owner's Claude
plan; needs the memory folder and a few open tasks):

```
H="Authorization: Bearer $LIFEOS_BRIDGE_TOKEN"; B=http://127.0.0.1:8787; J='Content-Type: application/json'
curl -s -H "$H" $B/coach/plan                    # before any plan: "prepared_at":null, tasks in rule order
curl -s -H "$H" -X POST $B/coach/plan/refresh    # takes a while; messages, top_three, order
curl -s -H "$H" -X POST $B/coach/plan/refresh    # again at once -> 429 {"error":"too_soon",...}
curl -s -H "$H" -X POST $B/tasks/<id of a top-three task>/complete
curl -s -H "$H" $B/coach/plan                    # at once: that task is gone, the next one moved up
curl -s -H "$H" $B/coach/question                # {"question":{...}} or {"question":null}
curl -s -H "$H" -H "$J" -d '{"text":"A test answer."}' $B/coach/question/answer
tail -n 4 ~/brain/coach/log.md; git -C ~/brain log --oneline | head -n 3
curl -s -H "$H" -H "$J" -X PUT -d '{"frequency":"1","quiet_start":"23:00"}' $B/coach/settings
ls -l ~/.config/lifeos/coach/                    # plan.json, settings.json, state.json; mode 600
# outside quiet hours, within about 30 seconds of the first plan of the day,
# a desktop notification "LifeOS: coach" appears, once
```

Queue, cancel and models (in a second terminal while a longer question
runs; replace `<id>` with that session's id):

```
H="Authorization: Bearer $LIFEOS_BRIDGE_TOKEN"; B=http://127.0.0.1:8787; J='Content-Type: application/json'
curl -s -H "$H" $B/models
curl -s -H "$H" -H "$J" -d '{"prompt":"and then?"}' $B/sessions/<id>/messages          # 202, queued
curl -s -H "$H" $B/sessions/<id>/queue
curl -s -H "$H" -X POST $B/sessions/<id>/cancel         # the first request ends with "cancelled"
curl -s -H "$H" -X POST $B/sessions/<id>/queue/resume   # the waiting message is sent
```

File offer (put any small file into the memory folder first):

```
scripts/ask --json "Offer me the file <name> from this folder."   # note "files"[0]."offer" and "session_id"
curl -s -H "$H" -o /tmp/check.out "$B/files/<offer>?session_id=<session id>"
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
