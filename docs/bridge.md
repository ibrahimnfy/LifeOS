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
| GET | `/folders` | yes | Folders a new conversation may run in |
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
| GET | `/models/routing` | yes | Model, effort and advisor of each job, and its plan usage today and this week |
| GET | `/files/{offer}` | yes | Downloads a file the assistant offered |
| GET / POST | `/session-groups` | yes | Lists or creates the owner's session groups |
| PATCH / DELETE | `/session-groups/{id}` | yes | Renames or deletes a group |
| GET / POST | `/reminders` | yes | Lists or creates session reminders |
| PATCH / DELETE | `/reminders/{id}` | yes | Changes or deletes a reminder |
| GET / POST | `/notes` | yes | Lists notes, or captures one into the memory's inbox |
| GET / PATCH | `/notes/{id}` | yes | Reads a note in full, or changes a captured note |
| GET / POST | `/tasks` | yes | Lists open tasks or adds one |
| PATCH | `/tasks/{id}` | yes | Changes a task's title or due date |
| POST | `/tasks/{id}/complete` | yes | Ticks a task |
| GET / POST | `/events` | yes | Lists or creates calendar events |
| PATCH | `/events/{id}` | yes | Changes an event |
| GET | `/agenda` | yes | Today's events and tasks, overdue tasks, the coming days |
| POST | `/activity/usage`, `/activity/sleep` | yes | The phone's daily screen-time and sleep summaries |
| GET | `/activity` | yes | Recorded days of phone activity |
| GET | `/context-map` | yes | Allowed folders and their summary files in the memory |
| POST | `/context-map/refresh` | yes | Writes or updates those summaries |
| GET / POST / DELETE | `/allowed-paths` | yes | Lists, adds or removes an allowed folder |
| GET | `/coach/plan` | yes | The coach's messages, top three and ordered tasks, with the plan's age |
| POST | `/coach/plan/refresh` | yes | Prepares a new plan now |
| GET | `/coach/question` | yes | The one open coach question, if any |
| POST | `/coach/question/answer` | yes | Records the owner's answer and files what it says |
| POST | `/coach/question/skip` | yes | Skips the open question |
| GET / PUT | `/coach/settings` | yes | Notification frequency, quiet hours, questions on or off |
| GET | `/moodle/status` | yes | Whether the Moodle sign-in is set up, its age and state |
| GET | `/moodle/courses` | yes | The owner's courses on the university Moodle |
| GET | `/moodle/courses/{id}` | yes | Sections, modules and files of one course |
| GET | `/moodle/assignments` | yes | Assignments with their dates |
| GET | `/moodle/deadlines` | yes | What is due in the coming days |
| GET | `/courses/summary` | yes | Deadlines of all courses in the next two weeks, last sync, how much is new |
| GET | `/courses` | yes | The synced courses with their next deadline and "new" counters |
| GET | `/courses/{id}/materials` | yes | Sections and files of a course (new ones marked), and its links |
| GET | `/courses/{id}/announcements` | yes | The announcements of a course, newest first |
| GET | `/courses/{id}/assignments` | yes | Assignments with dates and hand-in state |
| GET | `/courses/{id}/assignments/{aid}` | yes | One assignment in full: description, dates, state, attached files |
| GET | `/courses/{id}/grades` | yes | The grade items of a course |
| GET | `/courses/grades` | yes | The grades of all courses |
| POST | `/courses/{id}/seen` | yes | Clears the "new" counters and marks of a course |
| POST | `/courses/refresh` | yes | Starts a sync now (at most one per two minutes) |
| GET | `/courses/sync` | yes | Whether a sync is running, the last sync and its counts |
| POST | `/courses/{id}/files/{key}/address` | yes | A download address on the university site (**contains the Moodle token**) |
| POST | `/courses/{id}/files/{key}/offer` | yes | Offers the computer's copy of a file for `GET /files/{offer}` |

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

### Choosing the folder of a new conversation

`GET /folders` lists where a new conversation may run: the memory folder
(the default) and the allowed folders, each with `path`, `label`, `kind`
and `default`. `POST /ask` accepts `"folder": "<path from that list>"` for a
new conversation; anything else is refused (`403 folder_not_allowed`).
Later messages need only the `session_id`: a session always continues in the
folder it was started in. Only conversations in the memory folder start with
the memory loaded.

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
  (`high`) for chat sessions.
- **Model routing per job.** Every run the bridge starts on its own belongs
  to a job with its own model: `coach_plan` (morning run and refresh) and
  `coach_chat` (the coach conversation; today the run that files the
  owner's answer to the coach's question) use Opus with high effort;
  `consolidation`, `course_resources`, `summaries` and `context_map` use
  Sonnet with Opus as **advisor** (Claude Code's documented `--advisor`
  flag: the run may consult Opus at hard steps; see
  `docs/research/claude-headless.md`, section 8). If the CLI refuses the
  advisor at launch, the run is made once without it. Change any job with
  `LIFEOS_MODEL_ROUTING` (`config/env.example`). `LIFEOS_BACKGROUND_MODEL`,
  when set, still applies to the background jobs that setting does not
  name, without advisor. Saving notes and tasks, the course sync and
  notifications use no model.
- `GET /models/routing` → `{"jobs": [{"job", "label", "model", "effort",
  "advisor", "source": "default" | "routing" | "background_model",
  "per_session", "usage": {"today": U, "week": U}}], "no_model", "today",
  "week_from", "note"}` with `U` = `{"runs", "input_tokens",
  "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens",
  "cost_usd", "models"}`: the plan usage the runs reported themselves
  (`cost_usd` is Claude Code's own estimate, not a bill; "week" starts on
  Monday). The coach conversation is counted apart from the morning run.
  Chat sessions are one job (`chat`); a resumed run adds only the growth
  of the session's cumulative cost. Kept per day for two months in
  `~/.config/lifeos/usage.json`, no text. Never starts a run.

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
  What else the audit log holds (course download addresses, every phone
  request through the relay, pairing, revocation, allowed-folder changes,
  Moodle sign-ins) is listed in [`security.md`](security.md#61-the-audit-log).
- Through the relay the same offer is sent as sealed chunks (see "Paired
  phones and the relay").

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
  (`201`). Up to 4,000 characters; further lines are kept, indented. The
  answer is `{"saved": true, "repeat": false, "id": "i-…", "at": "…",
  "client_id": … | null}`.
- **Phone ids (offline saving).** `POST /notes` and `POST /tasks` take an
  optional `client_id`: a random id the phone chose (16 to 64 letters,
  digits, `-`, `_`; a UUID fits). A second create with the same `client_id`
  and the same content answers `200` with the stored item and `"repeat":
  true` and writes nothing, also after a restart, after the note was edited
  (the answer carries its current id) or the task was completed (the answer
  shows it done). The same `client_id` with different content answers `409
  client_id_conflict`. The mapping lives in the memory's `phone-ids.md` (ids
  and a fingerprint of the first request, no text), committed with the item.
  Requests without `client_id` behave as before. None of this starts a run.
- `GET /notes?query=&limit=` lists the captures of the inbox, newest first
  (`kind: "inbox"`), and the topic files under `notes/` (`kind: "topic"`,
  with their title), each with `id`, `at` and a `preview`; `query` searches
  title and text. `GET /notes/{id}` returns one with its full `text`.
  `PATCH /notes/{id}` with `{"text": "…"}` changes a captured note (its id
  is derived from its text, so the answer carries the new id); topic notes
  are read-only here.
- `PATCH /tasks/{id}` with any of `{"title": "…", "due": "YYYY-MM-DD" |
  null}` changes an open task.
- `GET /tasks` returns the open tasks in file order: `{"tasks": [{"id":
  "t-20270115-01", "title": "…", "done": false, "due": "2027-01-20" | null,
  "project": …, "course": …, "added": "…"}]}`.
- `POST /tasks` with `{"title": "…", "due": "YYYY-MM-DD", "project":
  "file-name", "course": "file-name", "client_id": "…"}` (only `title` is
  required) adds a line to `tasks.md` and returns the task with its new id,
  `client_id` and `"repeat": false` (`201`).
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

- **Phone activity.** `POST /activity/usage` with `{"date",
  "screen_minutes", "unlocks", "apps": [{"label", "minutes"}]}` and
  `POST /activity/sleep` with `{"date" (the morning), "bed": "HH:MM",
  "wake": "HH:MM", "estimated": bool}` store one line per day and kind in
  `activity/YYYY-MM.md` of the memory folder (a second upload for the same
  day replaces the line; at most 20 apps are kept). `GET /activity?start=
  &end=` returns the recorded days (default: the last 30).

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

### Moodle: `/moodle/...`

Read-only views of the university Moodle, through the site's web service
and the token that `scripts/moodle-login` stored (see "Moodle sign-in").
Responses never contain the site's address, a file address or the token.
Times are given twice: the site's number (seconds since 1970, `null` when
not set) and `<field>_iso`, the same moment in local time.

- `GET /moodle/courses` → `{"courses": [{"id", "shortname", "fullname",
  "visible", "startdate", "enddate", "lastaccess", "…_iso"}]}`.
- `GET /moodle/courses/{id}` → `{"course_id": 5, "sections": [{"id",
  "number", "name", "visible", "modules": [{"id", "name", "type", "visible",
  "files": [{"key", "filename", "filesize", "mimetype", "timemodified",
  "timemodified_iso"}]}]}]}`. `type` is the kind of activity (`resource`,
  `folder`, `assign`, …). A file has no address here: `key` is an opaque
  name that the bridge can resolve again by re-reading the course.
- `GET /moodle/assignments` (optional `?course=<id>`) → `{"assignments":
  [{"id", "course", "name", "duedate", "cutoffdate",
  "allowsubmissionsfromdate", "…_iso"}]}`.
- `GET /moodle/deadlines?days=30` (1 to 365) → `{"from", "to", "deadlines":
  [{"id", "name", "course_id", "module", "type", "timesort",
  "timesort_iso"}]}`: the site's timeline, in order of time.
- `GET /moodle/status` → `{"configured": true, "token_saved": "2027-01-15" |
  null, "age_days": 12 | null, "state": "ok" | "expiring" | "expired" |
  "unknown" | "not_configured", "last_check": "…" | null}`. It reads stored
  state only and never contacts the site. `unknown`: not checked yet.
  `token_saved` and `age_days` are `null` for a token stored before the
  day was recorded.
- **Caching.** Each answer is kept in memory for ten minutes per call (per
  course, per `days`), to be gentle with the site. `?refresh=true` asks the
  site again. Errors are not kept.
- **Errors.** `503 moodle_not_configured` when the address or the token is
  missing; `503 moodle_sign_in_needed` with the message "Run
  scripts/moodle-login" when the site refuses the token; `502
  moodle_unreachable` when the site does not answer; `502 moodle_error` for
  any other refusal (the message carries the site's short error code, never
  its text).
- Nothing is downloaded by these endpoints. Downloads, the folders per
  course, the calendar and the memory are the work of the course sync, see
  the next section.

### Courses: sync, lists, downloads

The bridge keeps the owner's courses current by itself and serves what the
app's Courses section shows. **No model run happens anywhere in this
section**: the sync is plain code that calls the site's web service, and
every endpoint reads stored state.

**Setting it up.** Besides `MOODLE_URL` and the token, the private env file
names the owner's course folder: `MOODLE_DOWNLOAD_DIR=<absolute path>`. The
folder must exist and be one of the allowed folders (`/allowed-paths`);
otherwise the sync stops with `folder_missing` or `folder_not_allowed` and
contacts nobody. Without the three settings nothing happens.

**Schedule.** While the bridge runs, a sync starts when the last successful
one is six hours old (or there was none) and the last attempt is at least
30 minutes old; `POST /courses/refresh` starts one on request. There is
never more than one sync at a time. If the site refuses the token, nothing
is synced; the sign-in watch (see "Moodle sign-in") sends the notification,
and the sync then asks again only every six hours until a new sign-in.

**What one sync does.** It first reads everything from the site: the
visible courses, each course page, assignments, the pages and books, and
the timeline of the next 180 days, the announcements forum, the grade report, and the hand-in state
of recent assignments (at most 40 calls). If the site cannot be reached or
refuses the token, the sync ends there and **nothing is changed**. A single
call the site refuses for another reason (for example a course without a
visible grade report) is counted as `part_errors`; that part keeps its
previous data. Then, course by course:

- **A folder per course** inside `MOODLE_DOWNLOAD_DIR`, named after the
  course's short name in lowercase ASCII letters, digits and hyphens plus
  the course id (`example-101-5`). It contains:
  - `materials/`: the files of the course's file and folder activities.
    Only **new or changed** files are fetched (the site's modification time
    or size differs from the last sync). File names are made safe: no path
    separators, no leading dot, no control characters, at most 120
    characters; a name already taken gets `-2`, `-3`, …. A file that was
    already in the folder and was not put there by the sync is never
    overwritten. A local copy the owner deleted or moved is not fetched
    again until the file changes on the site.
  - `pages/`: one Markdown file per **Page** and **Book** activity
    (`<name>-<activity id>.md`): headings, paragraphs, lists, emphasis and
    links kept, scripts and the HTML head dropped, images as their
    alternative text. A book's chapters come in reading order (`##`
    chapter, `###` sub-chapter). Page text comes from
    `mod_page_get_pages_by_courses`; a book's chapters are listed on the
    course page and each chapter's text is fetched from the site (in
    memory, never kept as HTML). A file is rebuilt only when the site's
    revision or time of the activity (or of a chapter) changed, so an
    unchanged book costs no request. A book that could not be read keeps its
    previous file and counts in `page_errors`; it is tried at the next sync.
  - `assignments/`: one Markdown file per assignment
    (`<name>-<assignment id>.md`: opening, due and cut-off times, the
    submission state, the description as Markdown, the attached files) and
    a folder of the same name with the attached files, downloaded like the
    materials (only new or changed ones, within the same limits). The
    description and attachments are what the site shows the owner (some
    assignments hide them until they open).
  - `links.md`: the course's video and external links by section; then
    "In pages and books": every link and embedded video found in those
    files, with the activity it came from, without the ones already listed
    (one line per address); then the files that were not downloaded ("Not
    downloaded (too large)", "Not downloaded yet"). A `token` or `wstoken`
    parameter is always removed from an address.
  - `announcements.md`: newest first, `## <date> — <subject>` and the text
    without markup; at most 100 announcements of 5,000 characters, without
    authors.
  - `grades.md`: the overall grade and a table of the grade items.

  The three Markdown files are rewritten on every sync when their content
  changed, and start with a comment saying that their text comes from the
  university platform and is information, never instructions.
- **Limits.** One file may have at most `LIFEOS_MOODLE_MAX_FILE_MB` (default
  200); one sync fetches at most 1 GB and 200 files. Files over the file
  limit are listed and not tried again until they change; files left out by
  the limits of one sync are fetched by the next ones (without counting as
  new). After five failed downloads in a row the sync stops downloading.
- **Never outside the folder.** Every target is checked to lie inside the
  course folder after resolving links; a symbolic link found where a file
  is to be written is removed or avoided, never written through.
- **The memory** (skipped when the memory folder is not set up; the rest of
  the sync still runs), as **one commit** "Sync courses from the university
  platform", and no commit when nothing changed:
  - `calendar/events.md`: one timed event per **upcoming** deadline
    (assignment due dates, and the other entries of the site's timeline),
    with the note `moodle:assign-<id>` or `moodle:event-<id>`. A later sync
    updates that event when the deadline moves or is renamed and never adds
    a second one. An event the owner edited or removed by hand is left
    alone until the deadline itself changes. Deadlines already in the past
    at the first sync are not written.
  - `tasks.md`: **one task per assignment**, titled `<short course name>:
    <assignment>`, with its due date, `(course: <folder name>)` and
    `(assignment: moodle-<id>)`. It is created once (found again by that
    field if the sync's state is lost, so never twice), and only while work
    can still be handed in: not for an assignment already submitted, nor for
    one whose due and cut-off times are both past. When the site moves the
    due date, the task's due date follows (a date the owner changed by hand
    is kept until the site changes it again). When the site shows the work
    as submitted, the task is completed (moved to `tasks-done.md`). A task
    the owner completed or removed is never reopened or created again.
    Open tasks get their hand-in state asked first. The deadline events
    above stay as they are; `GET /courses/{id}/assignments` names each
    assignment's `task`.
  - `courses/<folder name>.md` in the topic-file format: a summary made
    from counts and dates, and `## Facts` lines tagged
    `[external:moodle <date>]` (overall grade, number of files). Only those
    lines and the summary are replaced; other facts and everything from
    `## Links` on stay as they are.
  - `INDEX.md`: one line `- [[courses/<folder name>]] — course` under
    `## Courses`, added once. No name from the site goes into this
    always-loaded file.
- **State** is kept in `~/.config/lifeos/moodle-sync.json` (mode 600):
  what is known per course, the counters of what is new, a list of the last
  200 new items (`kind`, `course_id`, `at`; no names) and the last result.
  It is saved after every course, so an interrupted sync fetches nothing
  twice.

**What is new, and the notification.** The first sync ever records
everything as known and reports nothing as new; it sends one notification,
"Courses were synced for the first time.". The same holds for a course that
appears later. A course the site stops listing (hidden, or
left) disappears from the lists but keeps its folder and its record, so
nothing is fetched twice if it comes back. After that, a sync that found new or changed files, new
or changed pages and books (their text changed, not only their revision),
new announcements, new or moved deadlines, or new or changed grades sends
**one** notification "LifeOS: new in your courses" whose text is built from
counts only, for example "2 new files, 1 page, 1 announcement, 1 grade.". It goes
through `scripts/notify.sh`, so the phone will get it as a push once that
exists. No notification, log line or error contains a course name, a file
name, a grade or an address.

**Endpoints.** All read the stored state, answer at once and keep working
while the site cannot be reached. Lists carry `"last_sync"` and `"stale"`
(`true` when the last attempt failed or the last sync is older than six and
a half hours). Times are the site's number plus `…_iso`.

- `GET /courses/summary` → `{"configured", "deadlines": [{"course_id",
  "course", "name", "due", "due_iso"}], "new_total", "last_sync",
  "stale"}`: what is due in the next 14 days, nearest first.
- `GET /courses` → `{"courses": [{"id", "name", "shortname",
  "next_deadline": {"name", "due", "due_iso"} | null, "new": {"files",
  "pages", "announcements", "deadlines", "grades"}, "has_new",
  "folder_exists"}]}`.
- `GET /courses/{id}/materials` → `{"course_id", "name", "sections":
  [{"name", "files": [{"key", "name", "type", "size", "modified",
  "modified_iso", "new", "downloaded", "skipped"}]}], "links": [{"section",
  "name", "url"}]}`. `downloaded`: the computer has its copy. `skipped`:
  `null`, `"too_large"`, `"later"`, `"failed"` or `"unavailable"`. `links`
  are addresses outside the university site as the course page gives them,
  followed by the links found in pages and books, which also carry
  `"source": "page" | "book"` (their `section` is the activity's name).
- `GET /courses/{id}/announcements` → `{"announcements": [{"id",
  "subject", "text", "created", "created_iso", "modified_iso"}]}`.
- `GET /courses/{id}/assignments` → `{"assignments": [{"id", "name",
  "opens", "opens_iso", "due", "due_iso", "cutoff", "cutoff_iso", "status":
  {"submission", "grading", "graded"} | null, "submitted", "files"}]}`
  (`files`: how many attached files; `task`: the id of the assignment's
  task in `/tasks`, or `null`). `submission` is the site's word
  (`new`, `draft`, `submitted`, `reopened`); `submitted` is `true` for
  `submitted`. The state is asked for assignments without a date or due
  less than 30 days ago; older ones have `null`.
- `GET /courses/{id}/assignments/{assignment id}` → the same fields plus
  `"course"`, `"grading_due"`, `"grading_due_iso"`, `"modified_iso"`,
  `"description"` (Markdown; `""` when the site shows none) and `"files"`:
  `[{"key", "name", "type", "size", "modified", "modified_iso", "new",
  "downloaded", "skipped"}]`. An attached file downloads through the same
  routes as a material: `POST /courses/{id}/files/{key}/address` or
  `/offer`. `404 assignment_not_found` for an unknown id.
- `GET /courses/{id}/grades` → `{"overall", "items": [{"name", "type",
  "grade", "range", "percentage", "feedback", "graded_at",
  "graded_at_iso"}]}`; `GET /courses/grades` → the same for every course:
  `{"courses": [{"id", "name", "overall", "items"}]}`.
- `POST /courses/{id}/seen` → `{"seen": true}`; the course's `new` counters
  and the `new` marks of its files are cleared.
- `POST /courses/refresh` → `202` and the sync state when a sync was
  started; `429` with `"reason": "running"` or `"too_soon"` (an attempt in
  the last two minutes); `503 moodle_not_configured`.
- `GET /courses/sync` → `{"configured", "running", "last_sync",
  "last_attempt", "last_result": {counts}, "last_error", "last_errorcode",
  "stale"}`. `last_error` is a fixed code (`token_expired`, `unreachable`,
  `folder_missing`, `folder_not_allowed`, `moodle_error`, `bad_answer`,
  `failed`).
- Unknown course: `404 course_not_found`; unknown file key: `404
  file_not_found`.

**Downloading a file to the phone.** Materials are listed, not stored on
the phone. Two ways, neither with a model run:

1. `POST /courses/{id}/files/{key}/address` → `{"url", "name", "size",
   "type", "expires_in": 600}`. The phone fetches `url` directly from the
   university site over HTTPS and discards it.

   **Read this before using it.** Moodle has no one-time download
   addresses. `url` is the file's address with the **web-service token as
   a parameter**, so it is exactly as sensitive as the token: whoever has
   it can call the site's web service as the owner until the token expires
   or is renewed. It is "one-time" **only by convention**: the phone uses
   it once, keeps it in memory only, and drops it after `expires_in`
   seconds; the site does not enforce any of this. Therefore the bridge
   never logs or stores this answer (it is sent with `Cache-Control:
   no-store`), and it is only meant to travel over the loopback API or
   inside the sealed relay channel, where the relay's operator cannot read
   it. Do not paste it anywhere. Each address given out is recorded in the
   audit log as `CourseFileAddress` with course id, file name and size
   (never the address); when that record cannot be written the answer is
   `503 audit_unavailable` and no address is given.
2. `POST /courses/{id}/files/{key}/offer` → `{"offer", "name", "type",
   "size", "session_id"}`: an ordinary file offer for the **computer's
   copy** in the course folder. The phone fetches it with `GET
   /files/{offer}?session_id=<session_id>` (through the relay: in sealed
   chunks). The rules of "Files" apply, so the copy must exist (`404
   no_local_copy` otherwise), lie in an allowed folder and be at most
   `LIFEOS_MAX_FILE_MB` (`409 file_unavailable`). This is the fallback
   when the direct way fails.

## Paired phones and the relay

The phone never connects to the computer. Both sides make outbound HTTPS
requests to a mailbox in the owner's Firebase Realtime Database and leave
sealed messages for each other there (design: [`security.md`](security.md)).
When the two files in `~/.config/lifeos/firebase/` exist, the bridge starts
the relay by itself: it listens for each paired phone (a streaming request,
with polling as the fallback), checks every message, calls its own local API
for requests, and sends the answer back sealed. Without those files the
bridge is local only.

```
cd bridge
uv run lifeos-bridge pair "My phone"         # QR code in the terminal
uv run lifeos-bridge devices                 # id, state, name, paired, last seen
uv run lifeos-bridge devices revoke <id>     # the first characters of the id are enough
```

- **Pairing** shows a QR code that is valid for two minutes and for one
  phone. The app scans it and both sides show a six-digit code; type `yes`
  only if they match. Pairing is a one-time step per phone; afterwards the
  phone and the bridge reconnect by themselves. The QR code is the key: do
  not photograph or share it.
- **Revoking** deletes the phone's secret on the computer; from that moment
  its messages are dropped. It needs no network; the phone's mailbox is
  removed the next time the relay runs.
- **What a phone may call:** the endpoints of this document under `/ask`,
  `/sessions`, `/session-groups`, `/reminders`, `/notes`, `/tasks`,
  `/events`, `/agenda`, `/coach`, `/context-map`, `/allowed-paths`,
  `/models`, `/moodle`, `/courses` and `/whoami`. A request is `{"m": method, "path":
  "/…", "q": {query}, "body": {…}}`; the answer is one final message
  `{"status", "body"}`, or, for large or streamed answers, chunks followed by
  a final message.
- **Files:** `{"m": "GET", "path": "/files/<offer>", "q": {"session_id":
  "…"}}` sends an offered file as sealed chunks `{"i", "n", "b64"}` of about
  700 KB, then a final message with size, chunk count and SHA-256. At most
  three chunks wait in the mailbox; the phone takes each one out as soon as
  it has it. If the phone stops for two minutes the transfer is abandoned
  and the remaining chunks are removed; `"from": <chunk>` in the query
  continues an interrupted transfer. Measured through the real mailbox: a
  3 MB file in about 7 seconds.
- **In the mailbox:** only sealed messages; each is deleted as soon as it is
  taken, and anything older than 15 minutes is swept. Dropped messages are
  counted by reason in `~/.config/lifeos/relay-drops.json`, nothing else is
  kept.
- **Database rules:** `config/firebase-database.rules.json` restricts each
  phone to its own mailbox. The owner installs them once in the Firebase
  console (Realtime Database → Rules). The bridge itself uses the service
  account, which is not subject to the rules.

## Push notifications to the phone

Push goes through Firebase Cloud Messaging, with the same service-account
file as the relay. **A push has no readable content:** it is a small hint
sealed with the phone's own push key (what kind of event, which item); the
app wakes up, opens the hint, fetches the content through the relay and
builds the visible notification itself. Data messages with high priority
are used, so it works while the app is closed.

- The phone sends its push address through the sealed channel
  (`POST /push/token` with `{"token": "…"}`; this path exists only on the
  relay). It is stored with the device record and removed on revocation or
  when the push service reports it as gone.
- Events that wake the phone: an answer is ready (a run of a session ended;
  off per session with `notify_replies`), a session reminder is due, the
  coach's daily note, new course content, the course sign-in needs renewal,
  and a notice from `scripts/notify.sh`.
- `POST /push/notify` with `{"title": "…", "link": "…"}` is what
  `scripts/notify.sh` calls when the bridge is running; title and link
  travel inside the sealed hint only. `LIFEOS_NOTIFY_PUSH=off` makes the
  script skip it.
- A push that cannot be sent is not retried; the phone sees everything when
  it next connects. Nothing about pushes is logged.
- The app's side (receiving, showing the notification) is not built yet.

## Moodle sign-in

```
scripts/moodle-login                 # sign in through the browser, store the token
scripts/moodle-login --check         # test the stored token
scripts/moodle-login --choose        # ask for the sign-in button again
scripts/moodle-login --no-handler    # register nothing; paste the address by hand
```

It needs `MOODLE_URL` in `~/.config/lifeos/env`. Background:
[`research/moodle-access.md`](research/moodle-access.md).

1. The helper opens the sign-in page in the default browser by itself. The
   link is also printed, on one line, in case the browser did not open (and
   put on the clipboard when `wl-copy` or `xclip` is installed).
2. The owner signs in on the institution's own page. The helper never sees
   the password.
3. At the end the browser asks whether to open the link with **"LifeOS
   Moodle sign-in"**. After allowing it, the helper picks the sign-in up by
   itself, verifies it and stores only the token (mode 600).

The first run asks which sign-in button of the site the owner uses and
remembers the choice (`MOODLE_LOGIN_ISSUER`: a number or `none`, never the
button's name); `--choose` asks again.

**What is registered temporarily.** While the helper waits, it registers a
handler for `moodlemobile://` addresses for the owner's user account only
(no `sudo`): the file `lifeos-moodle-signin.desktop` in
`~/.local/share/applications`, made the default for that kind of address
with `xdg-mime` (an entry in `~/.config/mimeapps.list`). When the helper
ends, in whatever way (success, failure, Ctrl-C), the previous default is
restored, or the entry removed if there was none, and the file is deleted.
The handler only passes the address to the waiting helper through a file in
`~/.config/lifeos/` (mode 600) that is deleted as soon as it is read.
`--no-handler` skips all of this.

**If nothing arrives** within five minutes, or the owner presses Enter, the
helper asks for the address instead. It is not shown while pasted. Where to
find it, as a last resort:

1. Run `scripts/moodle-login --no-handler`. In the browser tab that opened,
   press F12 (developer tools), choose "Network", tick "Preserve log" /
   "Persist logs", and load the link again (it is printed in the terminal).
2. Sign in. At the end the page stays blank or shows "address not
   understood": that is expected. In the Network list click the last entry
   named `launch.php`, open "Headers" and find the response header
   `Location`. Its value starts with `moodlemobile://token=`. Copy that
   whole value (or the address bar, if it shows it) and paste it into the
   terminal.

**Expiry warning.** The token stops working after a time the site sets
(Moodle's default is 12 weeks). The helper stores the day of the sign-in
(`MOODLE_TOKEN_SAVED`), and the running bridge asks the site once per day
whether the token still works:

- the site refuses it: a notification "LifeOS: Moodle sign-in needed — The
  Moodle sign-in has expired. Run scripts/moodle-login.", once per day;
- it is older than 77 days: "… will probably expire within a week. …", once
  per three days. **The 77 days are an estimate** (12 weeks minus one): the
  site does not tell a client when a token expires, it may use another
  lifetime, and it may hand out a token it issued earlier. A token stored
  before the day was recorded gets no such warning;
- the site cannot be reached: no notification; tried again after an hour.

The state is in `~/.config/lifeos/moodle-watch.json` and is shown by
`GET /moodle/status`. The notifications contain no address and no names.

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
# offline saving: send the same create twice; the second answers 200 "repeat":true, one line only
P=$(python3 -c 'import uuid; print(uuid.uuid4())')
curl -s -w ' %{http_code}\n' -H "$H" -H "$J" -d "{\"text\":\"phone note\",\"client_id\":\"$P\"}" $B/notes
curl -s -w ' %{http_code}\n' -H "$H" -H "$J" -d "{\"text\":\"phone note\",\"client_id\":\"$P\"}" $B/notes
curl -s -w ' %{http_code}\n' -H "$H" -H "$J" -d "{\"text\":\"other text\",\"client_id\":\"$P\"}" $B/notes   # 409
grep -c 'phone note' ~/brain/inbox.md            # 1
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
curl -s -H "$H" $B/models/routing | python3 -c 'import json,sys; [print(j["job"], j["model"], j["effort"], j["advisor"], j["usage"]["today"]["runs"]) for j in json.load(sys.stdin)["jobs"]]'
#   coach_plan opus high None …; context_map sonnet … opus …; after a coach refresh its "runs" is 1
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

Moodle (after `scripts/moodle-login`; these only read from the site):

```
scripts/moodle-login            # the browser opens; allow "LifeOS Moodle sign-in"; "The token works."
scripts/moodle-login --check    # "The token works. Moodle <version>; ..."
ls ~/.local/share/applications/lifeos-moodle-signin.desktop   # No such file: it was removed again
xdg-mime query default x-scheme-handler/moodlemobile          # empty, or what it was before
grep -c '^MOODLE_TOKEN_SAVED=\|^MOODLE_LOGIN_ISSUER=' ~/.config/lifeos/env    # 2
H="Authorization: Bearer $LIFEOS_BRIDGE_TOKEN"; B=http://127.0.0.1:8787
curl -s -H "$H" $B/moodle/status                 # "state":"ok" within a minute of starting the bridge
curl -s -H "$H" $B/moodle/courses                # the courses; note an id
curl -s -H "$H" $B/moodle/courses/<id>           # sections, modules, files with "key" and no address
curl -s -H "$H" $B/moodle/assignments
curl -s -H "$H" "$B/moodle/deadlines?days=30"
curl -s -H "$H" "$B/moodle/courses?refresh=true" # asks the site again instead of the ten-minute copy
# without MOODLE_URL or a token: 503 {"error":"moodle_not_configured",...}
```

Courses (needs `MOODLE_DOWNLOAD_DIR` in the env file, naming an allowed folder; no model runs):

```
curl -s -H "$H" $B/courses/sync                  # "configured":true; "last_sync" set within a minute of starting
curl -s -X POST -H "$H" $B/courses/refresh       # 202 "started":true; again at once: 429 "too_soon"
ls "$MOODLE_DOWNLOAD_DIR"                        # one folder per course: materials/ pages/ links.md announcements.md grades.md
ls "$MOODLE_DOWNLOAD_DIR"/<course folder>/pages  # one .md per Page or Book; open one and compare with the site
grep -A5 '## In pages and books' "$MOODLE_DOWNLOAD_DIR"/<course folder>/links.md   # links from pages and books, once each
curl -s -H "$H" $B/courses/sync | python3 -c 'import json,sys; r=json.load(sys.stdin)["last_result"]; print(r["pages"], r["page_errors"])'
curl -s -H "$H" $B/courses/summary               # deadlines of the next two weeks, "new_total"
curl -s -H "$H" $B/courses                       # the courses; note an id
curl -s -H "$H" $B/courses/<id>/materials        # sections and files with "key", "new", "downloaded"; links
curl -s -H "$H" $B/courses/<id>/announcements
curl -s -H "$H" $B/courses/<id>/assignments    # note an assignment id with "files" > 0
curl -s -H "$H" $B/courses/<id>/assignments/<aid>   # "description" as Markdown; "files" with "key"
ls "$MOODLE_DOWNLOAD_DIR"/<course folder>/assignments   # <name>-<aid>.md and a folder with the attached files
grep 'assignment: moodle-' ~/brain/tasks.md      # one task per open assignment, with its due date
curl -s -H "$H" $B/tasks                         # the same tasks carry "assignment" and "course"
# after handing in an assignment on the site: refresh, then its task is in tasks-done.md
curl -s -X POST -H "$H" $B/courses/refresh; sleep 60; grep 'assignment: moodle-<aid>' ~/brain/tasks-done.md
curl -s -H "$H" $B/courses/<id>/grades ; curl -s -H "$H" $B/courses/grades
curl -s -X POST -H "$H" $B/courses/<id>/seen     # {"seen":true}; the course's "new" counters are 0 afterwards
curl -s -X POST -H "$H" $B/courses/<id>/files/<key>/offer    # a card; fetch it with /files/<offer>?session_id=<session_id>
curl -s -X POST -H "$H" $B/courses/<id>/files/<key>/address | python3 -c 'import json,sys; print(sorted(json.load(sys.stdin)))'
#   prints the field names only: the "url" holds the Moodle token, do not print or paste it
tail -n 2 ~/.config/lifeos/audit/$(date -u +%Y-%m).jsonl | grep -c CourseFileAddress   # 1: course, name, size
grep -c token= ~/.config/lifeos/audit/*.jsonl    # 0 in every file: never an address or the token
# after using the app through the relay: one "RelayRequest" per request, route templates only
grep RelayRequest ~/.config/lifeos/audit/$(date -u +%Y-%m).jsonl | tail -n 3
git -C ~/brain log -1 --format=%s                # "Sync courses from the university platform" after the first sync
curl -s -H "$H" "$B/events?start=<today>&end=<in 30 days>"   # upcoming deadlines, each with a note "moodle:…"
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
