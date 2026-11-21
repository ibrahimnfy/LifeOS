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
| POST | `/ask` | yes | Runs a prompt through headless Claude and returns the answer; a new conversation without a folder is a blank chat |
| GET | `/folders` | yes | Where a new conversation may run: a blank chat, the memory folder, the allowed folders |
| GET | `/sessions` | yes | Lists Claude Code sessions in the allowed folders and the blank chats, each with its folder kind, and one summary entry per folder |
| POST | `/sessions/{id}/messages` | yes | Continues a listed session with a new message |
| GET | `/sessions/{id}/history` | yes | A session's earlier messages, a few exchanges per page |
| GET | `/sessions/{id}/commands` | yes | Lists the skills and commands of the session's folder (not usable in runs yet) |
| PATCH | `/sessions/{id}` | yes | Sets the owner's title, pin, reply-notification switch, model and effort |
| POST | `/sessions/{id}/cancel` | yes | Stops the session's current run |
| GET | `/sessions/{id}/queue` | yes | Shows whether the session is answering and what is waiting |
| PATCH / DELETE | `/sessions/{id}/queue/{qid}` | yes | Changes or removes a waiting message |
| POST | `/sessions/{id}/queue/resume` | yes | Sends the waiting messages after a hold |
| GET | `/models` | yes | Models and effort levels the app may offer, and the defaults |
| GET | `/models/routing` | yes | Model, effort and advisor of each job, and its plan usage today and this week |
| PUT | `/models/routing` | yes | Changes one job's model, effort or advisor (audited, no restart) |
| GET | `/app/release` | yes | The registered release of the app: version, size, SHA-256, certificate |
| POST | `/app/release/offer` | yes | Offers the release APK for `GET /files/{offer}?session_id=app-release` |
| GET | `/files/{offer}` | yes | Downloads a file the assistant offered |
| GET / POST | `/reminders` | yes | Lists or creates session reminders |
| PATCH / DELETE | `/reminders/{id}` | yes | Changes or deletes a reminder |
| GET / POST | `/notes` | yes | Lists notes, or captures one into the memory's inbox |
| GET / PATCH | `/notes/{id}` | yes | Reads a note in full, or changes a captured note |
| GET / POST | `/tasks` | yes | Lists open tasks or adds one |
| PATCH | `/tasks/{id}` | yes | Changes a task's title, due date or note |
| POST | `/tasks/{id}/complete` | yes | Ticks a task |
| GET / POST | `/events` | yes | Lists or creates calendar events |
| PATCH | `/events/{id}` | yes | Changes an event |
| GET | `/agenda` | yes | Today's events and tasks, overdue tasks, the coming days |
| GET | `/memory/consolidation` | yes | The waiting consolidation proposal and its operations |
| GET | `/projects` · `/projects/{id}` | yes | Projects: status, state, last activity, open tasks, sessions (no run) |
| POST | `/memory/consolidation/approve` · `/reject` | yes | Approves (applies, one commit) or rejects operations, one by one or all |
| POST | `/memory/consolidation/run` | yes | Prepares a proposal now (one run, in the background) |
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
| GET | `/coach/chat` | yes | The coach conversation: session id, size, summary, model, usage (apart from the morning run) |
| POST | `/coach/chat/messages` | yes | A message to the coach (compacts first when the conversation is long) |
| POST | `/coach/chat/compact` | yes | Compacts the coach conversation now |
| GET | `/moodle/status` | yes | Whether the Moodle sign-in is set up, its age and state |
| POST | `/moodle/signin/start` | yes | Sign-in from the phone (#100): the site's launch link with a one-time passport (`link`, `wwwroot`, `passport`, `scheme`, `expires_in` 600 s); the sign-in button chosen at the computer is reused; never asks anything. `502 signin_unavailable` when the site cannot be asked |
| POST | `/moodle/signin/finish` | yes | Body `{"address": "moodlemobile://token=…"}`: checked against the waiting passport (used up by this call), tested at the site, and only then stored like `scripts/moodle-login` does; answers `{"stored", "release", "missing"}`, never the token. `409 signin_not_started`, `400 signin_mismatch`, `400 token_rejected`, `502 signin_unavailable` |
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
| GET | `/courses/{id}/resources` | yes | Books and documents found from the syllabus: downloaded or listed only |
| POST | `/courses/{id}/resources/refresh` | yes | Asks for a new course-resources run (in the background) |
| POST | `/courses/{id}/resources/{index}/offer` | yes | Offers a downloaded resource for `GET /files/{offer}` |

### `POST /ask`

Request body (JSON):

| Field | Type | Meaning |
|---|---|---|
| `prompt` | string, required | The prompt, at most 100 000 characters |
| `session_id` | string, optional | Continue an earlier `/ask` conversation |
| `folder` | string, optional | For a new conversation: a path from `GET /folders`. Left out: a blank chat in its own new folder (see "Blank chats") |
| `new_session_id` | string, optional | A lowercase UUID the phone chose for a **new** conversation (not with `session_id`). Sending the same first message (same text and folder) again with it runs nothing and answers `{"repeat": true, "session_id", "running": bool}`; the same id with another message or folder, or the id of an existing session, answers `409 session_id_conflict`. A first message refused before its run began (for example `429 busy`, or a failed check) does not keep the id: sending it again starts it. Follow-ups go to `POST /sessions/{id}/messages` as usual |
| `stream` | boolean, optional | Send the answer as it is written (see below) |
| `mode` | string, optional | `read_only` (default) or `capable`; see "Capable mode" |

Response:

```json
{"answer": "...", "session_id": "...", "duration_ms": 3400, "num_turns": 2, "denied_tools": []}
```

`denied_tools` names the tools Claude tried to use but was not allowed to
(for example `["Write"]`); the run still completes.

How a prompt runs:

- A new conversation runs in its own new folder under the chat root (a
  blank chat), or in the folder named by `folder`; a continued one in the
  folder it was started in. Through `claude -p` (or the Agent SDK runner)
  with every flag set explicitly.
- **Read-only unless the request says `"mode": "capable"`**: Claude may
  read and search files inside the run's folder and nothing else: no shell
  commands, no edits, no web access, no files outside the folder. A blank
  chat also has the memory folder in reach, to read it when the owner asks.
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
| 503 | `chat_root_unusable` | A blank chat was asked for, and the chat root may not be used (the message says why) |
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
memory folder and in a blank chat's folder (#163); capable runs in other
allowed folders stay refused for now.

**Each runner has its own check (#106).** `scripts/verify-guardrails
--runner sdk` runs the same suite through the Agent SDK runner. It adds
the approval probes: a deletion answered "no" does not run; one answered
"yes" runs, and only after the yes; a never-allowed command is refused
without being asked about. It writes `capable-mode-sdk.json` and
`guardrail-results-sdk.json`, recording the runner, the Claude Code
version and the Agent SDK version. With `LIFEOS_RUNNER=sdk`, a capable
request:

- runs through the SDK when that marker matches the installed versions;
- otherwise falls back to `claude -p` if the `cli` marker matches (the
  session's SDK process is closed first, so a session never has two kinds
  of writer);
- otherwise is refused with `503`.

An update of Claude Code or of the Agent SDK switches the capable mode
off on both runners (decision #84).

**Folders inside another git repository (#139).** A capable request is
refused with `403 folder_inside_repository` when the folder (or the memory
folder) sits inside another git repository without being its root. The
reason is that the sandbox would let commands write anywhere in that
repository. The message names the repository root to allow instead.
Subfolders of the memory folder are fine. Details:
`permission-policy.md`, section 9.9.

**After an update the check reruns by itself (#107, #140).**

- Every capable request compares the installed versions with the marker,
  read at that moment: the Claude Code version and the Agent SDK version
  (loaded and on disk), for both runners. A mismatch refuses the request.
- The marker is signed with a key kept next to it (`guardrail-key`), so a
  marker changed by hand is ignored. While a check runs, the mode of that
  runner is off.
- At bridge start and every 15 minutes, the bridge starts the check by
  itself (`scripts/verify-guardrails`) for every runner whose marker does
  not match, once per version pair:
  - the SDK runner **always**, whether or not it is selected, so one
    restart of the bridge produces its marker;
  - the `claude -p` runner if it was checked on this computer before.
  Checks run one after the other, each in the terminal environment and
  then in the real service environment. The owner is notified when a check
  starts and when it ends, with fixed texts.
- **Outcomes.**
  - **Passed:** the mode is on again.
  - **Failed:** this check's own results show a failed probe. This is final
    for these versions.
  - **Not run:** everything else: the bridge stopped, a crash, the 3-hour
    time limit, another check still running, or only the check's own
    environment failed. It is never recorded as failed. It is retried 3
    times, 30 minutes apart; after that it is "incomplete", and it runs
    again from the start at the next bridge start.
  - Each check gets an id that it writes into its results, so results of
    an earlier check never count.
- **Clean-up.** Only one check runs at a time (a lock in the private
  settings folder). Before it starts, a check stops the service-environment
  run that an interrupted check may have left (a transient user service
  with the fixed name `lifeos-guardrail-check`). It also moves an old
  scratch folder to the memory folder's trash.
  - A check removes its own conversations from Claude Code's store when it
    ends (its run folder and its chat folders), and the bridge never lists a
    session of those folders: probe runs are not the owner's conversations.
    The coach's plan and note-filing runs are one-off runs without a stored
    session, like the other jobs.
- **Plan usage of one restart:** a runner needs a check after an update of
  Claude Code or the Agent SDK, after a new version of the probe suite (the
  skill probes of #91 are suite version 2), or when it was never checked.
  - The `claude -p` suite takes about 15 short runs on the small model per
    environment.
  - The SDK suite takes about 19 per environment, because it adds the
    approval probes.
  - Both runners in both environments come to **about 68 short runs**. The
    skill probes of #91 and #92 add 12 of these (3 runs per environment and
    runner), the personal-skill probes of #162 add 4 (1 run per environment
    and runner), and the chat-folder probes of #163 add 12 (a shell run, a
    file-tool run and a read-only run in a blank chat's folder, per
    environment and runner); the MCP probes of #108 and #162 need no model
    run. Probes without a model run (invalid settings, nested folders) are
    not counted.
  - A restart where both markers still match costs nothing.
  - Each runner has its own suite version (`cli` 6, `sdk` 7), and only a
    runner whose suite changed is checked again. The probes for personal
    skills and personal MCP servers (#162) and for chat folders (#163)
    changed both (`cli` 5, `sdk` 6). So did the corrected judgement of the
    personal-skill run (`cli` 6, `sdk` 7; `permission-policy.md`, section
    9.2), which adds no run. The next start checks both once. The approval probes
    may add up to 8 more per environment when the model does not make the
    needed call at once, the personal-skill run and the skill-copy steps
    (#171, part of an existing run) up to 2 more each, and the chat-folder
    shell run 1 more.
- State: `~/.config/lifeos/guardrail-check.json`.
- `LIFEOS_GUARD_AUTOCHECK=off` stops the automatic rerun only. The mode
  still goes off after an update; run the check by hand then. The owner
  keeps it on (2026-11-21).
- A marker written before #107 has no signature. After this update the
  mode is therefore off once, until the check reruns by itself.
After the check, restart the service (`systemctl --user restart
lifeos-bridge`) if the bridge code changed since it was started.

What a capable run looks like:

- It works in the run's folder and the memory folder; other allowed folders
  are readable. The home folder, the private settings, keys and browser
  profiles are hidden from it, and its commands have no network. In a blank
  chat the run's folder is the chat's own folder; the chat root and every
  other chat are out of its reach.
- It requires Claude Code's OS sandbox and refuses to start without it.
- Every tool call is written to the audit log
  `~/.config/lifeos/audit/YYYY-MM.jsonl` **before** it runs (commands in
  full; for file tools the path, never the content). If the log cannot be
  written, nothing runs.
- Actions that need the owner's approval (deleting, force operations,
  pushing, installing packages, network and messaging commands) are refused
  with an explanation; the response lists them in `refused_actions`, e.g.
  `["push"]`. `sudo` and system-setting commands are always refused.
- It is offered the owner's personal skills and commands (#162), as copies
  made by the bridge, and the session folder's own when they are switched
  on for the session; see `GET /sessions/{id}/commands`. It gets the MCP
  servers the owner approved; see "MCP servers".
- The mode belongs to the request, not the session: the next request without
  `"mode": "capable"` is read-only again.

Folders that can never be allowed, whatever `allowed_paths` says: the home
folder itself, anything outside it, hidden folders, the LifeOS program
folder and the private settings folder. Symbolic links are resolved first.

### Choosing the folder of a new conversation

**A new conversation without `folder` is a blank chat** (owner's decision,
#163): it runs in a new, empty folder of its own and starts with no
personal context; see "Blank chats" below. The app's "+" and "New chat"
send the first message without `folder`.

`GET /folders` lists where a new conversation may run, each with `path`,
`label`, `kind`, `default` and `colour` (the folder's colour number, as in
`GET /sessions`):

- first the blank chat (`kind: "chat"`, `default: true`, `path` the chat
  root, plus `usable` and, when blank chats are refused on this computer,
  the `reason`);
- the memory folder (`kind: "memory"`);
- the allowed folders (`kind: "project"`, or `"course"` for the course
  folder of the Moodle sync).

`POST /ask` accepts `"folder": "<path from that list>"` for a new
conversation; the chat root's path means a blank chat too. Anything else is
refused (`403 folder_not_allowed`). Later messages need only the
`session_id`: a session always continues in the folder it was started in.

**What a conversation is given** (clean chats, #163):

| Conversation | Context |
|---|---|
| Blank chat | nothing personal; one short fixed instruction (below) |
| In an allowed folder (project, course) | that folder in reach of the file tools, nothing else; no memory text. Claude Code's restricted mode loads no `CLAUDE.md`, also not the folder's own |
| Started in the memory folder by naming it | as for an allowed folder: the files are in reach, no memory text is passed |
| The coach conversation | its full personal context, as before (the memory's always-loaded part, the coach's instructions and summary) |
| A conversation that existed before | it continues in the folder it was started in, with the system prompt Claude Code recorded at its first message |

Claude Code records a conversation's system prompt at its first message
and sends that record on every later message and resume, until the
conversation is compacted (`--system-prompt-snapshot`, on by default;
observed with the real CLI: a resume that passed another appended text
still answered from the first one). So a conversation started before this
change keeps what it was started with, and a new conversation keeps its
fixed instruction for its whole life.

### Blank chats (#163)

**An own folder per chat.** The bridge creates
`<chat root>/<YYYY-MM-DD>-<six hex digits>/` (mode 700) for every blank
chat and runs it there. The chat root is `LIFEOS_CHAT_DIR` (default
`~/LifeOS-Chats`); the bridge creates it at the first blank chat. It must be
a folder inside the home folder that

- is not hidden and is neither the LifeOS program folder nor the private
  settings folder (nor inside or around them);
- neither contains nor is contained in the memory folder or an allowed
  folder;
- holds none of `CLAUDE.md`, `CLAUDE.local.md`, `AGENTS.md`, `.claude`,
  `.mcp.json`;
- is not a git repository and is not inside one.

When that is not true, a blank chat is refused with
`503 chat_root_unusable` and the reason, and nothing runs. The root is
never an allowed folder: a run works in its own chat folder and never
reaches another chat's folder. Of what lies under the root only the
bridge's own chat folders count: sessions and files of anything else there
are never listed, continued or offered, and a root that lies where none may
lie (the home folder itself, a hidden folder, around an allowed folder)
makes no session known at all.

**No personal context.** Nothing from the memory, the tasks, the courses or
the daily logs is put into the prompt or the system prompt. Claude Code
gives the run nothing either:

- the restricted mode (as for every run of the bridge) loads no `CLAUDE.md`
  of the folder or of any folder above it, and no auto-memory;
- on top of that a blank chat's process gets
  `CLAUDE_CODE_DISABLE_CLAUDE_MDS=1` and
  `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1` (Claude Code's own switches; fixed
  values, no secret).

Checked with the real Claude Code 2.1.328 (small model, probe folders
under the chat root, a scratch settings and memory folder; 2026-11-21):

| What was run | What was seen |
|---|---|
| A blank chat through the bridge, with canary instructions planted in a `CLAUDE.md` of a folder above the chat root and in the memory folder's `CLAUDE.md`, and a task in the memory's `tasks.md`; asked to list every canary word, what it knows about the owner's tasks, and the memory folder it was told | no canary, "nothing", the memory folder's path |
| The same chat continued, after a canary was planted in Claude Code's auto-memory for that chat's folder | no canary |
| The same question with Claude Code started in that folder **without** the restricted mode (control) | both canaries reported, with the two files' paths: the check does see them when they are loaded |
| The control again with the two switches set | no canary: the switches work on their own too |
| A probe chat folder holding its own `CLAUDE.md` (the guardrail probes, both runners) | not loaded |

Not checkable here: a **user-level** `~/.claude/CLAUDE.md`. None exists on
this computer, and writing one would reach the owner's running sessions.
What stands for it: the restricted mode ignores the user-level settings
files, and the switch above, named for every `CLAUDE.md`, turned off both
kinds that could be tested.

**Memory on request only.** The memory folder is in reach: a read-only run
gets it as an added folder for the file tools (there is no write tool); a
capable run may also write it, as every capable run may. One fixed
instruction is appended to the system prompt (about 90 words, no personal
data; `chats.context`): the chat starts without personal context; the
memory is the folder at this path, read only when the owner asks to use it
and written only when the owner asks to remember something; files are
moved into a project with a `[[move-files: …]]` line. Checked with the real
CLI: asked to use the memory, a read-only blank chat read a note from the
memory folder; the memory folder's own `CLAUDE.md` was not loaded by being
an added folder.

**A chat that made no files leaves no folder.** When a run ends, the
chat's folder is removed if it holds no files:

- "No files" means: at every depth nothing but folders. Any other entry
  counts as a file and keeps the folder: a regular file, a hidden file, a
  symbolic link (also one to a folder).
- Only empty folders are removed, one by one, so nothing can be destroyed.
  Only the bridge's own chat folders (the name pattern above, directly
  under the chat root) are ever touched.
- Claude Code itself leaves nothing in the folder (seen after read-only
  and capable runs with the real CLI: a read-only run left it empty, a
  capable run left only the file it was asked to create).
- **The conversation stays.** Claude Code keeps it under the folder's
  path, so the next message creates the same path again and resumes there
  (checked with the real CLI on both runners: the resumed run remembered
  the earlier message). `409 folder_missing` is therefore never answered
  for a blank chat. To continue such a chat in a terminal, create the
  folder first.
- On the SDK runner a chat's folder is the working directory of its live
  process, so it is kept while that process lives and removed after it was
  closed (idle time, shutdown); the bridge looks for such folders every
  minute. A first message that is refused before it runs leaves no folder.

**Capable mode** works in a chat folder with the same guardrails: the
sandbox lets commands write the chat's own folder and the memory folder,
and read the allowed folders; the gate, the audit log and the
nested-repository rule apply unchanged. It needs a guardrail check whose
chat-folder probes passed (`"chat_folders": true` in the marker, suite
version `cli` 5 / `sdk` 6); until then a capable request in a chat folder
answers `503 capable_mode_unavailable`. A marker written by a check from
before these probes does not have that field, and **nothing reruns the
check by itself for it**: run `scripts/verify-guardrails` (and with
`--runner sdk`) once after this change. Details: `permission-policy.md`,
section 9.11.

As in every capable run, the owner's personal skills and approved personal
MCP servers are offered in a capable blank chat too (#162): the run is told
their names and descriptions. That is the owner's own tooling, not the
memory, but it is something personal in an otherwise clean chat; whether a
blank chat should get it is the owner's call. A read-only blank chat has
none. (Not covered by a real run here: a chat folder together with a copy
of personal skills.)

**Moving a chat's files into a project.** A run cannot write into a
project from a chat. It proposes the move with a line of its own,
`[[move-files: <target folder> | <file> | <file>]]`. The bridge removes the
line from the answer and lists the proposal in the answer (JSON answer and
`done` event): `"file_moves": [card]`. A usable proposal also waits among
the session's pending approvals:

```json
{"id": "a1b2c3d4e5f6", "kind": "move_files", "session_id": "…",
 "folder": "<the chat's folder>", "state": "ready",
 "target": "<absolute target folder>", "target_label": "project/notes",
 "files": [{"path": "report.md", "size": 812}], "total_size": 812,
 "created_at": "…", "expires_at": "…"}
```

- **Target:** an allowed folder other than the memory folder, named by its
  path or its folder name, or an existing folder inside one; never a hidden
  folder.
- **Files:** regular files inside the chat's own folder, relative to it; no
  symbolic links (the file or a folder on the way), nothing hidden (so no
  `.claude`, `.mcp.json` or `.git` content can reach a project this way), at
  most 50 per proposal and 3 proposals per answer.
- A proposal that cannot be carried out is answered at once, only in the
  answer, with `"state": "refused"`, an `error` sentence and an
  `error_code`: `target_not_allowed`, `file_missing`, `file_not_regular`,
  `file_not_in_chat`, `file_hidden`, `file_exists`, `no_files`,
  `too_many_files`, `too_many`, `not_a_chat`, `audit_unavailable`.
- `POST /sessions/{id}/interactions/{iid}` with `{"approve": true|false}`
  answers the card. On approval everything is checked again, the audit
  record is written first, and the bridge moves the files itself: each keeps
  its relative path below the target, is created exclusively (**nothing is
  ever overwritten**) as a plain file (mode 644), and then its source is
  removed. The reply is `{"answered": true, "id", "moved": true|false,
  "files": [...], "failed": [...], "target", "target_label"}` (`moved` is
  false with a non-empty `failed` only when writing broke off part-way).
- **A refused answer leaves the card waiting**, with nothing moved:
  `409 file_exists` (something is at a target path now), `409
  files_changed` (a file changed since the card), the codes above, `503
  audit_unavailable`. A refusal (`false`) answers `{"moved": false}`.
- Paired phones get a sealed `approval` push hint. A proposal waits 24
  hours. Audit records: `move_files_proposed`, `_approved`, `_done`,
  `_refused`, `_expired`, with the file names, never their content.
- A chat whose files all moved away leaves no folder.
- In any other folder the line is plain text: only a blank chat's files are
  moved this way.

### Moving between conversations by asking (#164)

There is no folder picker. The owner asks in any chat ("continue my last
chat in the courses folder", "start a chat in the project folder"), and the
answer carries a `switch` item the app acts on.

**The index a run reads.** Before every run the bridge writes
`<chat root>/.bridge/conversations.md` (mode 600, folder mode 700):

```
id | last activity (UTC) | kind | folder | title
<session id> | 2027-01-15 08:03 | chat | Chats | <title>
...
# Folders a new conversation can start in
label | kind
Chats | chat
<memory folder's name> | memory
<allowed folder's name> | project
```

- At most 300 conversations, newest first: the id, the last activity, the
  folder's kind and label, and the title (the owner's own title, else the
  one in the list). **Never a message.** The file says that its content is
  information, never instructions (titles are the beginning of what was
  written in a chat). Two folders of one name are listed with their paths.
- Every run gets that folder as an added folder, so Claude Code's file
  tools can read it under the read-only rules and inside the sandbox, on
  both runners, **without another model run**. The standing instructions of
  every conversation name the file and the marker lines (about 100 words;
  the list itself is never part of a prompt).
- Runs cannot change it: a read-only run has no write tool; a capable run
  has no `Edit` permission there, an explicit `Edit` denial, and the
  sandbox is told to deny writes (`denyWrite`). Seen with the real CLI: a
  command's write there fails with "Read-only file system", the file tool
  is refused.
- The bridge writes the file anew before every run and **never reads it
  back**: whatever a marker names is checked against the bridge's own
  lists.
- It needs a usable chat root; without one, runs go on without the index
  (and asking cannot switch).

**The answer.** The run ends its answer with a line of its own:

| Line | Meaning |
|---|---|
| `[[switch: <id>]]` | open that conversation |
| `[[switch: <id>, <id>, …]]` | offer these to choose from (at most five) |
| `[[switch-new: <folder label or path>]]` | a new conversation in that folder: an allowed folder, the memory folder, or `Chats` for a blank chat |

The bridge takes every such line out of the answer and reports the first
usable one as `switch`, in the JSON answer and in the stream's `done`
event:

```json
{"answer": "Opening it.", "session_id": "…",
 "switch": {"action": "open",
            "sessions": [{"id": "…", "title": "…", "folder_label": "project",
                          "kind": "project", "colour": 2,
                          "last_activity": "2027-01-15T08:01:40Z"}],
            "folder": null}}
```

- `action` is `open` (one conversation: the app opens it), `choose` (one to
  five: the app shows a card to pick from) or `new` (`sessions` is empty and
  `folder` is `{"path", "label", "kind", "colour"}`: the app opens an empty
  chat whose first message is sent with `"folder": <path>`).
- **Validation.** An id the bridge does not list is dropped, and so is the
  conversation's own id. A line that named one id is `open`; a line that
  named several is `choose`, also when only one of them is listed. A folder
  must be exactly one of the folders of `GET /folders` (label in any case,
  or path); the coach is not a folder. When nothing usable is left, there
  is no `switch` item; the line is removed all the same.
- **Only a fresh answer switches.** The item exists only in the answer of
  the run that wrote the line. `GET /sessions/{id}/history` removes such
  lines from the text and never carries a `switch` item, and nothing else
  reads a transcript for them.
- While the answer is streamed, the line can appear in `delta` text before
  the `done` event replaces it; the app hides lines of this form as it does
  the other marker lines.
- The switched-to conversation continues in its own folder with its own
  history: the app sends the next message to `POST
  /sessions/{id}/messages`.
- **Limit: conversations started before this change.** Claude Code keeps
  the system prompt a conversation was started with, so an older
  conversation (the current coach session included) does not know these
  lines until it is compacted or renewed. New conversations do.

Checked with the real CLI through the bridge (2026-11-21, small model):
from a blank chat, "open the chat in which I asked you to remember a
number" gave `open` with that conversation; from a conversation in a
project folder, "start a new chat in my <folder> folder" gave `new` with
that folder; "open one of my earlier chats about numbers or files, I am not
sure which" gave `choose` with the two that fit. An earlier, weaker wording
of the instruction made the model list the chats with letters instead of
writing the line; the wording now forbids that.

### `GET /sessions`

Lists the Claude Code sessions stored on the computer whose folder is the
ask folder, one of the folders in `~/.config/lifeos/allowed_paths` (one
absolute path per line, `#` for comments) or a blank chat's folder under
the chat root, including their sub-folders. Sessions of any other folder
are never opened or listed.

Sessions started in a terminal on the computer appear here like any other,
as long as their folder is allowed.

Query: `limit` (default 50, at most 200). Response, pinned sessions first,
then newest first:

```json
{"sessions": [{"id": "…", "title": "…", "detected_title": "…",
               "folder": "/path/to/folder",
               "last_activity": "2027-01-15T08:08:20Z", "in_use": false,
               "pinned": false, "notify_replies": true,
               "folder_key": "/path/to/folder", "folder_label": "folder",
               "kind": "project", "colour": 2}],
 "total": 1,
 "folders": [{"key": "chats", "name": "Chats", "kind": "chat",
              "path": "<chat root>", "colour": 0, "count": 12,
              "last_activity": "2027-01-15T08:08:20Z"},
             {"key": "/path/to/folder", "name": "folder", "kind": "project",
              "path": "/path/to/folder", "colour": 2, "count": 1,
              "last_activity": "2027-01-15T08:08:20Z"}]}
```

- **Folders (#163).** Every session says which folder it belongs to:
  `folder_key`, `folder_label`, `kind` and `colour`; `folder` stays the
  exact working directory. `folders` has one entry per folder for the
  app's tiles, with the `count` and newest `last_activity` of **all** its
  sessions (not only the listed ones; `0` and `null` when it has none):

  | `kind` | Sessions | `key` | `name` |
  |---|---|---|---|
  | `chat` | every blank chat: **all of them form one entry** | `chats` | `Chats` |
  | `coach` | the coach conversation and its earlier sessions (only once it exists) | `coach` | `Coach` |
  | `memory` | other sessions in the memory folder | the folder's path | its name |
  | `course` | sessions in the course folder of the Moodle sync | the folder's path | its name |
  | `project` | sessions in any other allowed folder | the folder's path | its name |

  A session in a sub-folder belongs to the root that contains it. The
  entries come in this order: chats, coach, memory, then the allowed
  folders as listed.
- **`colour`** is a small number the app maps to a colour of its palette.
  A folder keeps the number it was first given for as long as it exists
  (it is an allowed folder, or the chats or coach entry); a folder that is
  gone frees its number; a new folder takes the lowest free one. Stored in
  `folder-colours.json` in the private settings folder.

- `title` is the owner's own title when one was set (see below), otherwise
  `detected_title`.
- Custom session groups were removed (#164): there is no `group`,
  `auto_group` or `groups` any more. The app groups sessions by
  `folder_key` (see "Folders" below).

- `title` is the session's own title when it has one, otherwise the
  beginning of its first prompt. Titles are conversation content: they are
  sent to the authenticated client only and never logged.
- `in_use` is true while the bridge itself is running a prompt in that
  session; `running_here` is the same, named for what it means.
  `open_elsewhere` is true when another Claude Code process on the computer
  holds the session (a terminal, the desktop app), from `claude agents
  --json` (the bridge's own processes left out, reused for 10 seconds), and
  `null` when that could not be checked. A message to a session open
  elsewhere answers `409 session_in_use`.
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
- `409 folder_missing` when the session's folder no longer exists. Not for
  a blank chat: its folder is created again at the same path.

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
                "complete": true, "truncated": false,
                "mcp_servers": [{"name": "tools", "outside_sandbox": true}]}],
 "older": "…", "total_exchanges_known": null}
```

- `mcp_servers` (#157) lists the MCP servers that answer had, the same as
  the `done` event's `mcp_servers`; empty for every other answer. The bridge
  keeps only the names, for the last 200 such answers of the last 200
  sessions (`~/.config/lifeos/mcp-used.json`); answers from before #157
  show none.

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

Lists the skills and custom commands a capable run of the session can use,
for the app's composer: those of the session's own folder, then the
owner's personal ones (#162). It reads only the documented locations,
`.claude/skills/<name>/SKILL.md` and `.claude/commands/**/*.md`, of the
session's folder and of the personal Claude Code folder (`~/.claude` of the
user the bridge runs as); it runs nothing and uses no model.

```json
{"enabled": false, "switched_on": false, "capable_only": true, "prefix": "folder:",
 "reason": "skills are switched off for this session",
 "personal": {"enabled": true, "reason": null, "prefix": "personal:"},
 "commands": [{"name": "review", "kind": "command", "description": "…",
               "argument_hint": "<file>", "warnings": ["shell_lines"],
               "source": "folder"},
              {"name": "standup", "kind": "skill", "description": "…",
               "argument_hint": null, "warnings": [], "source": "personal"}]}
```

- Each entry says where it comes from: `"source": "folder"` or
  `"personal"`. The folder's entries come first, each group sorted by name.
- **The owner's personal skills are offered in every capable run (#162).**
  They need no switch.
  - `personal.enabled` is true when the capable mode is on and the guardrail
    check passed with the personal-skill probes
    (`docs/permission-policy.md`, section 9.2). Otherwise `personal.reason`
    says why, in one sentence, and capable runs are offered none.
  - In a run they are named with `personal.prefix` (`personal:standup`).
- **The folder's own are usable in capable runs of the session when switched
  on (#91).**
  - The owner switches them on per session with `PATCH /sessions/{id}`
    `{"skills": true}`. The session list and the PATCH answer carry
    `"skills": true|false`.
  - `enabled` is true only when the switch is on **and** the guardrail check
    passed with the skill probes. Otherwise `reason` says why, in one
    sentence. `enabled`, `switched_on`, `reason` and `prefix` are about the
    folder's own only, as before #162.
  - `switched_on` is the owner's switch alone. `capable_only` is always
    true: read-only runs never get skills.
  - In a run they are named with `prefix` (`folder:review`).
  - A capable message in a session whose switch is on, while the check with
    the skill probes has not passed yet, is refused with
    `503 skills_unavailable`. It never runs silently without the skills;
    switch them off to continue without.
- **Messages that start with `/`.** In a capable run that is offered any
  skill, such a message must name one of the usable entries: by its name
  alone (the folder's own wins a name both have) or with its prefix (the
  bridge adds the prefix). Any other `/...` message (for example a built-in
  command such as `/config`, or a folder entry while the switch is off) is
  refused with `400 command_not_allowed`. Since #162 this also holds in
  sessions whose switch is off, as soon as the owner has a personal skill.
  In a read-only run, and in a capable run that is offered no skill, the
  message is plain text.
- The assistant can also use the offered skills itself (its `Skill` tool),
  unless a skill says `disable-model-invocation`.
- **What a run gets is a copy.** Of a skill's or command's frontmatter only
  the name, the description, the argument hints and the two "who may invoke
  it" flags are kept; `hooks`, `allowed-tools`, a model or a subagent are
  dropped, and shell lines do not run. The copies lie in the bridge's
  folder for copies (`LIFEOS_SKILL_COPIES_DIR`, by default
  `~/.local/share/lifeos/skill-copies`; never inside the private settings
  folder, an allowed folder, the memory folder or a git repository, else
  `503 skills_unavailable`). A run can read the supporting files of its
  skills there (reference files, scripts), with the file tools and from its
  sandboxed commands, and can never change them (#171; section 9.2).
- `kind` is `skill` or `command`. A command in a sub-folder is named with
  `:` between the parts (`git/log.md` is `git:log`).
- `description` is the `description:` of the file's frontmatter, otherwise
  its first line of text; one line, at most 200 characters. Nothing else of
  a file's content is returned.
- `warnings` uses fixed words: `shell_lines` (the file contains shell that
  would run before the model sees it), `requests_tools` (it asks for tools
  with `allowed-tools`), `has_scripts` (the skill folder holds files other
  than Markdown).
- No plugins, and nothing else of the personal folder. Symbolic links are
  resolved, and anything that ends up outside the folder that is listed is
  skipped.
- Limits: 200 entries per source, the first 64 KB of each file, names of
  letters, digits and `_ . : -` up to 64 characters; anything else is
  skipped.
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
- **Send a waiting message now.** `POST /sessions/{id}/queue/{qid}/send-now`
  makes it the next message and sends it now: a running run is interrupted
  exactly as with `"when": "now"` (its end then sends this message), an idle
  session sends it at once; a hold on the queue ends. Answers `{"promoted":
  true, "queue_id", "interrupted_current_run": bool, "queue": [...]}`, or
  `404 not_queued`.
- **Cancel.** `POST /sessions/{id}/cancel` interrupts the current run: the
  interrupt signal first, then, only if the process does not stop within a
  few seconds, terminate and kill. The stopped request answers `409
  cancelled`. **Changes the run already made to files are kept.** A coach
  compaction is never stopped (`409 compaction_running`). The queue
  is then on hold (`"paused": "cancel"`) until the owner sends a message or
  calls `POST …/queue/resume`, which answers at once (`{"resumed": true,
  "queue": [...]}`); the waiting messages are then sent one after the other
  in the background.
- A queued message that fails holds the rest (`"paused": "error"`). After a
  bridge restart waiting messages are drafts (`"paused": "restart"`): they
  are sent only after `…/queue/resume`, never by themselves.
- Queued messages are prompt text. They are stored in
  `~/.config/lifeos/queue.json` (mode 600) and never logged.
- **Following a run after the fact.** `GET /sessions/{id}/live` is a stream
  of server-sent events for the session's current run, also one that began
  earlier (a queued message the bridge sent by itself, or a streamed answer
  the client lost): first `live {"session_id", "running", "followable",
  "queue_id": id | null}`, then what the run said so far (text merged into
  one `delta`; `replay_truncated {}` first when more than a million
  characters were said), then `replay_end {"events": n}` (#157: everything
  after it is live; `n` is the number of replayed events), then its events
  as they come (`delta`, `interaction`, `interaction_closed`, `keepalive`,
  then `done` or `error`), and the stream ends with the run. With no run
  that can be followed it sends only `live` with `"followable": false` and
  `replay_end {"events": 0}`, and ends (a plain non-streamed run is not
  followable). Through the relay, a phone that stops reading sends
  `POST /relay/detach` (see "Paired phones and the relay"). It only reads: it never starts or
  resumes a run. A queued message of a client that said `"interactive":
  true` may ask questions and approvals; a follower answers them through
  `POST /sessions/{id}/interactions/{iid}`. `404 session_not_found`.
- **Live file offers.** `GET /sessions/{id}/files` → `{"files": [{"offer",
  "name", "type", "size", "expires_at"}]}`: the session's offers that have
  not expired, so a file card keeps its download after the thread is read
  again. Fetching stays `GET /files/{offer}?session_id=…` (audited, as
  before); listing reads nothing.
- **One session.** `GET /sessions/{id}` → the item of `GET /sessions`
  (`id`, `title`, `detected_title`, `folder`, `last_activity`, `in_use`,
  `running_here`, `open_elsewhere`, `folder_key`, `folder_label`, `kind`,
  `colour`, `pinned`,
  `notify_replies`, `skills`, `model`, `effort`), read fresh, plus
  `followable`, `queue` (the waiting messages) and `queue_paused`. `404
  session_not_found`.

### Runner: `claude -p` or the Agent SDK

`LIFEOS_RUNNER` selects how conversations run. The HTTP API is the same
for both; only how the assistant process lives differs.

- **`cli` (default).** One `claude -p` process per message, as before.
- **`sdk`.** One Agent SDK client per active conversation, kept between
  messages, so a follow-up starts without delay
  (`bridge/src/lifeos_bridge/sdk_runner.py`).
  - Same restrictions as `cli` read-only runs: the command line is built by
    the same function (tools `Read,Glob,Grep`, `--restricted`, no MCP, no
    slash commands, memory context, model and effort per session), and the
    child gets the same allow-listed environment plus the SDK's three
    protocol markers. No API key variable reaches it.
  - One difference (#105): the bridge is the permission host. The run
    starts in the `default` permission mode with
    `--permission-prompt-tool stdio` instead of `dontAsk`, and it has the
    question tool `AskUserQuestion`. Every call that would ask for
    permission reaches the bridge's callback, which denies it, exactly as
    `dontAsk` would. The only exceptions are the owner's answers to
    questions and, in capable runs (#106), the confirmations described
    under "Questions and approvals".
  - Sign-in is the subscription login of the installed Claude Code (#115).
    The bridge starts the installed `claude` named by `LIFEOS_CLAUDE_BIN`
    through its own transport. It never uses the copy bundled in the SDK
    package and refuses it if `LIFEOS_CLAUDE_BIN` points there.
  - A conversation's process closes `LIFEOS_SDK_IDLE_MINUTES` (15) after its
    last message and when the bridge stops. At most `LIFEOS_SDK_MAX_LIVE`
    (3) are alive at once; when another is needed, the longest-idle is
    closed first, and if none is idle the request gets `429 busy`. Closing
    loses nothing: the next message resumes from the session transcript.
  - A process is also restarted (resuming the transcript) when the next
    message needs another model, effort or memory context, or when the
    transcript changed since its last answer (the owner continued the
    session in a terminal). It never writes over a newer conversation.
  - The in-use check ignores the bridge's own live processes. If another
    process (a terminal) holds the session, the bridge closes its own and
    answers `409 session_in_use`.
  - Cancel and send now use the SDK's interrupt. Only when the turn does not
    end within a few seconds is the process closed (terminate, then kill).
    The same happens in the background when the phone goes away mid-answer.
  - **The capable mode runs on this runner only after its own guardrail
    check passed** (`scripts/verify-guardrails --runner sdk`, see "Capable
    mode"). Until then a capable request falls back to `claude -p` if that
    path passed its check, and is refused otherwise.
- Sessions move freely between the bridge and the terminal: a session
  started through the SDK runner continues with `claude -p --resume <id>`
  or `claude --resume <id>`, and the reverse (verified with the real CLI).

Manual check (`sdk`, uses the plan; keep it small): set `LIFEOS_RUNNER=sdk`,
restart the bridge, ask twice in the same conversation. The second answer
should come faster, and `ps` should show one `claude` process for the
conversation. After the idle time it is gone, and a third message still
continues the conversation.

### Questions and approvals (SDK runner)

A run can stop and wait for the owner, in two cases:

- **Question.** The assistant uses the question tool: 1-4 questions with
  2-4 options each, single or multiple choice.
- **Approval** (capable runs only, once #106 enables them on this runner).
  A command the gate marks as needing confirmation: obvious deletion, force
  or history-rewriting git operations, push, package install, sending a
  message, network action. The gate answers `ask` instead of refusing.
  The bridge shows exactly what will run and where, and lets it run only
  after an explicit yes. Commands that are never allowed (`sudo`, system
  settings) are still refused by the gate and are never asked about. Only
  a plain command can be confirmed: a call that asks to run outside the
  sandbox, or carries anything besides the command, is refused.

How it reaches the owner:

- Only while a client streams the answer **and opts in** (`"stream":
  true, "interactive": true`, also through the relay; a client without
  question cards leaves it out), the stream carries an event
  `interaction {"id", "session_id", "kind": "question"|"approval", ...,
  "created_at", "expires_at"}`. A question carries `questions: [{"question",
  "header", "multi_select", "options": [{"label", "description"}]}]`; an
  approval carries `tool`, `command`, `folder`, `category` and `reason`.
  Paired phones also get a sealed push hint (`question` or `approval`).
  When the interaction is over, the stream sends `interaction_closed
  {"id", "reason": "answered" | "timed_out" | "run_ended"}` (answered from
  any client, the time ran out, or the run ended or was stopped first).
- **Keep-alives:** a streamed run that sends nothing for 20 seconds (a long
  tool call; also on `claude -p`) and a run that waits for the owner (every
  30 seconds) send the event `keepalive {}`. The relay forwards it as a
  small sealed chunk `{"event": "keepalive", "data": {}}`, so the phone can
  tell a working run from a dead connection. (Before #147 this was an SSE
  comment the relay dropped.)
- `GET /sessions/{id}/interactions` → `{"pending": [...]}` (same objects).
- `POST /sessions/{id}/interactions/{iid}` answers:
  - question: `{"answers": {"<question>": "<label or own text>"}}` (a list
    of labels for multiple choice), optionally `"response": "<free text>"`,
    or `{"decline": true}`;
  - approval: `{"approve": true}` or `{"approve": false}`.
  It returns `{"answered": true, "id": ...}`, or `404 not_pending` once
  the question was answered, expired or cancelled.
- No answer within `LIFEOS_INTERACTION_TIMEOUT` seconds (default 600)
  counts as **no**. The run's own time limit is paused while it waits.
  Cancel, send now, the end of the run and the bridge stopping also count
  as no. Nothing is ever approved by default.
- **Without an answering client** (a request that does not stream or
  does not opt in, or a queued message) the behaviour is today's: a question is refused at once
  with the instruction to ask in plain text, and an approval action is
  refused with the usual explanation.
- **Audit log:** an approval request, then its outcome (`approved`, or
  `refused` with `owner`, `timeout`, `cancelled` or `no_client`), next to
  the gate's own entry (`asked`). If the log cannot be written, the action
  is refused. Questions and answers are prompt text and are kept in memory
  only, never logged.

Manual check (`sdk`, one short run on the plan): send `"stream": true`
with a prompt such as "Use the question tool to ask me Red or Blue, then
say what I chose". An `interaction` event appears. Answer it with the POST
above, and the reply names the choice. Without `"stream": true` the same
prompt is answered with a plain-text question.

### Skill installs (#92)

A capable run can propose a skill for its folder with a line of its own,
`[[skill-install: https://…]]` (a `SKILL.md`, `.zip` or `.tar.gz`). The
bridge removes the line from the answer and lists the proposal in the
`done` event: `"skill_installs": [card]`. It downloads the source into a
quarantine in the private settings folder, and the card then appears among
the session's pending approvals:

```json
{"id": "a1b2c3d4e5f6", "kind": "skill_install", "session_id": "…",
 "folder": "…", "source": "https://…/SKILL.md", "state": "ready",
 "name": "tidy", "target": "<folder>/.claude/skills/tidy",
 "description": "Tidy the notes.",
 "warnings": ["shell_lines", "requests_tools", "has_scripts"],
 "files": [{"path": "SKILL.md", "size": 812}], "total_size": 812,
 "created_at": "…", "expires_at": "…"}
```

- `state` is `downloading` (wait), `ready` (can be approved), or `failed`
  with an `error` sentence and a stable `error_code` (it can only be
  refused). A proposal from a non-HTTPS source is answered at once, in the
  `done` card, with `"state": "refused"`. The codes (#157), for the app to
  choose its own words: `source_not_https`, `audit_unavailable` (both
  `refused`), `source_refused` (an unsafe or unusable source: a link, an
  absolute or `..` path, no `SKILL.md`, no usable name, too many files,
  a redirect to something other than HTTPS), `source_too_large`,
  `source_unreadable` (a broken archive), `skill_exists`,
  `download_failed` (network or server error).
- `GET /sessions/{id}/interactions` lists it next to the questions and
  approvals of a running turn. `POST /sessions/{id}/interactions/{iid}`
  answers it with `{"approve": true|false}`. The reply is
  `{"answered": true, "id": …, "installed": true|false, "name", "target"}`.
  A missing or expired proposal answers `404 not_pending`.
- **A refused answer leaves the card waiting** (#157), with its files in
  quarantine, so the owner can answer again: `409 still_downloading`
  (approved while `downloading`), `409 skill_exists` (a skill of that name
  appeared meanwhile), `503 audit_unavailable`, `500 install_failed`
  (nothing was written). A refusal (`false`) is always accepted, also while
  downloading; the download is then thrown away. Two answers at the same
  time are taken one after the other: the second gets `404 not_pending`.
- Paired phones get a sealed `approval` push hint. Proposals wait 24 hours
  (also across bridge restarts); after that they count as refused.
- Details and limits: `permission-policy.md`, section 9.4.

### MCP servers (#108, #162)

- `GET /sessions/{id}/mcp-servers` lists the servers of the session
  folder's `.mcp.json`, then the owner's personal (user-scope) servers, and
  starts nothing:

  ```json
  {"capable_only": true, "note": "An approved MCP server runs outside the sandbox: …",
   "servers": [{"name": "tools", "scope": "folder", "type": "stdio", "command": "npx",
                "args": ["-y", "some-server"], "url": null,
                "env_names": ["API_TOKEN"], "header_names": [], "hash": "…",
                "outside_sandbox": true,
                "state": "not_approved", "approved_at": null}]}
  ```

  - `scope` is `folder` or `personal`. Personal servers are the top-level
    `mcpServers` of `~/.claude.json` of the user the bridge runs as, where
    Claude Code keeps user-scope servers. Nothing else of that file is
    read: no per-project servers, no claude.ai connectors, no plugins.
  - `state` is `not_approved`, `approved` or `changed` (approved, but the
    definition changed since: not used), or `unsupported`: its definition
    is not a plain stdio or http one (for example it names a helper command
    for headers, which the card would not show), so it can never be
    approved. Since #171 this holds for a folder's servers too.
  - Values of environment variables and headers are never returned, only
    their names.
  - The same name can appear once per scope: two servers, two approvals.
- `POST /sessions/{id}/mcp-servers/{name}/approval` creates an approval
  card (and a sealed `approval` push hint):
  `{"id", "kind": "mcp_server", "session_id", "folder", "scope", "server":
  {…as above}, "warning": "…outside the sandbox…", "created_at",
  "expires_at", "already_waiting": false}`.
  - `?scope=folder|personal` says which server is meant. Without it: the
    folder's own server of that name, else the personal one.
    `400 bad_request` for another value; `404 mcp_server_not_found`;
    `409 mcp_server_unsupported`.
  - Asking again while a card for the same definition (same scope and hash)
    waits in that session returns that card with `"already_waiting": true`,
    and sends no second push (#157). A changed definition gets a card of
    its own.
  - It is answered like every approval: `POST
    /sessions/{id}/interactions/{iid}` with `{"approve": true|false}`, which
    returns `{"answered": true, "id": …, "approved": true|false, "name"?,
    "reason"?}`. `503 audit_unavailable` leaves the card waiting.
  - A folder's server is approved for that folder. A personal server is
    approved once, for every folder.
- `DELETE /sessions/{id}/mcp-servers/{name}` revokes an approval
  (`{"revoked": true|false}`). `?scope=` as above; without it the folder's
  approval of that name is revoked, else the personal one.
- A capable answer that used servers carries `"mcp_servers": [{"name",
  "scope", "outside_sandbox": true}]` and `"mcp_note"`. When the folder and
  the owner both have an approved server of one name, the run gets the
  folder's.
- The definitions reach Claude Code as a file in the private settings folder
  (`mcp-run/`, mode 600; files not used for a day are removed), never on the
  command line.
- `503 mcp_unavailable`: there are approved servers, but the check with
  the MCP probes has not passed yet. Personal servers are passed only after
  the check with their own probes (suite `cli` 5, `sdk` 6); until then a
  capable run simply gets none of them.
- Details: `permission-policy.md`, section 9.3.

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
  "advisor", "source": "app" | "routing" | "background_model" | "default",
  "default": {"model", "effort", "advisor", "source"}, "per_session",
  "usage": {"today": U, "week": U}}], "no_model", "today",
  "week_from", "note"}` with `U` = `{"runs", "input_tokens",
  "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens",
  "cost_usd", "models"}`: the plan usage the runs reported themselves
  (`cost_usd` is Claude Code's own estimate, not a bill; "week" starts on
  Monday). The coach conversation is counted apart from the morning run.
  Chat sessions are one job (`chat`); a resumed run adds only the growth
  of the session's cumulative cost (a session older than the counting
  counts from its first resume on). Token counts are the main model's
  (advisor tokens are not in them). Kept per day for two months in
  `~/.config/lifeos/usage.json`, no text. Never starts a run.
- `PUT /models/routing` (from the app) changes one job. Body: `{"job":
  "<job>", "model"?: "<model>" | null, "effort"?: "<level>" | null,
  "advisor"?: "<model>" | "none" | null}` or `{"job": "<job>", "reset":
  true}`. A field left out stays as it is, `null` returns it to the default
  underneath, `"none"` switches the advisor off, `reset` returns the whole
  job. Models are those of `GET /models` (`LIFEOS_MODELS`) or a full
  `claude-…` name; efforts those of `GET /models`. The answer is the
  overview of `GET /models/routing` plus `"changed": "<job>"`; the job's
  `"source"` is then `"app"`, and its `"default"` shows what it would use
  without the choice. Stored in `~/.config/lifeos/model-routing.json`,
  applied from the next run (no restart). `LIFEOS_MODEL_ROUTING`,
  `LIFEOS_BACKGROUND_MODEL` and the built-in defaults stay underneath.
  Every change is written to the audit log first (`ModelRouting`, with
  the job's choice before and after); when it cannot be, nothing changes
  (`503 audit_unavailable`). `400` for an unknown job, model, effort,
  advisor or field. Manual check: `curl -s -X PUT -H "$H" -H "$J" -d
  '{"job":"summaries","model":"haiku"}' $B/models/routing`, then `GET`
  shows `"source": "app"`; `-d '{"job":"summaries","reset":true}'` undoes it.
- Precedence for chat sessions: the session's own choice, then the app's
  choice for `chat`, then `LIFEOS_MODEL_ROUTING`'s `chat=…`, then
  `LIFEOS_DEFAULT_MODEL`/`_EFFORT`.
  `LIFEOS_CLAUDE_MODEL` (the guardrail probes' fixed model) replaces every
  job's model and switches the advisor off.

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

### Organising sessions: `PATCH /sessions/{id}`

The owner's own order on top of the list: titles, pins and a per-session
switch for reply notifications.

- `PATCH /sessions/{id}` accepts any of `{"title": "…" | null,
  "pinned": true | false, "notify_replies": true | false}` (and `model`,
  `effort`, `skills`) and returns the session as in the list. `null`
  returns to the detected title.
- **Custom session groups were removed (#164).** `/session-groups` no
  longer exists (`404`), and `group` is not a field any more (`400` in a
  `PATCH`, like any unknown field). The app shows sessions by folder
  (`GET /sessions`, "Folders"). Metadata stored before still loads: its
  `groups` list and an entry's `group` are ignored without an error and
  left out the next time the file is written; titles and pins are kept.
- Only sessions inside the allowed folders and blank chats can be changed
  (`404` otherwise).
- **Claude Code's session files are never modified.** The metadata is one
  file, `~/.config/lifeos/sessions-meta.json` (mode 600). Deleting that file
  loses the titles and pins, nothing else.
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

### Projects: `/projects`

For the app's Projects screen (#152), read from files only, without a model
run. A project is a memory file `projects/<name>.md` and/or an allowed folder
with its context-map file; they belong together when the project file links
`[[map/<slug>]]` or has the map's name. The course folder of the Moodle sync
is not a project.

- `GET /projects` → `{"projects": [P]}`, newest activity first, with `P` =
  `{"id": "p-<name>" (from a memory file) | "f-<slug>" (a folder only),
  "name", "title", "status": "active" | "paused" | "done" | null (front
  matter), "summary": str | null (the file's first paragraph, else the map's
  "What it is"), "state": str | null (the map's "State"), "folder": path |
  null (start a chat there with `POST /ask` `"folder"`), "folder_exists",
  "memory_file", "map_file", "last_activity": ISO | null (the newest of the
  last session in the folder, the folder's last git commit and the memory
  file's `updated` day), "open_task_count", "open_tasks": [task as in
  /tasks, at most 20, those with `(project: <name>)`], "sessions": [{"id",
  "title", "last_activity"}, at most 5, newest first]}`.
- `GET /projects/{id}` → one `P`; `404 project_not_found`.

Manual check: `curl -s -H "$H" $B/projects | python3 -m json.tool | head -40`
lists the allowed folders and the memory's project files, with tasks that
carry `(project: …)`; `/models/routing` shows no new run.

### Memory consolidation: `/memory/consolidation`

The nightly proposal of [`memory-design.md`](memory-design.md) §6 (trigger B:
propose, then approve). Nothing is written to the memory until the owner
approves; approve and reject start no run.

- `GET /memory/consolidation` → `{"proposal": null | {"id", "created_at",
  "operations": [OP]}, "waiting": int, "running": bool, "last_run",
  "last_result"}` with `OP` = `{"id": "op1", "op": "file_capture" |
  "merge_facts" | "add_fact", "file": "<topic file>", "why": str | null,
  "status": "pending" | "applied" | "rejected" | "failed", "reason"?}` plus,
  per kind, `"capture", "capture_text", "capture_at"` (file_capture),
  `"keep", "drop"` (merge_facts) or `"text"` (add_fact, tagged inferred).
- `POST /memory/consolidation/approve` with `{"operations": ["op1", …]}` or
  `{"all": true}` applies them in **one** commit by `lifeos-consolidation`
  and answers the view plus `{"applied": [ids], "failed": [ids],
  "committed": bool}`; an operation that no longer applies (its capture was
  edited, its line is gone) fails alone with `"reason": "no longer
  applies"`. `409 check_failed` (nothing written) if a fact line would
  disappear. `404 no_proposal`, `404 operation_not_found`, `400` for a bad
  body.
- `POST /memory/consolidation/reject` (same body) → the view plus
  `{"rejected": [ids]}`; rejected operations are not proposed again
  unchanged. When no operation is pending any more, `proposal` is `null`.
- `POST /memory/consolidation/run` → `202 {"started": true}`, or `409`
  (`"reason": "waiting"` or `"running"`) / `429 "too_soon"` (ten minutes).
- Push: `coach` event, opens `consolidation`, `r` = proposal id; fixed text.

Manual check: add two captures from the phone, `curl -s -X POST -H "$H"
$B/memory/consolidation/run`, wait a minute, `curl -s -H "$H"
$B/memory/consolidation` (operations with "why"); approve one with `-d
'{"operations":["op1"]}' .../approve`; `git -C ~/brain log -1
--format='%an %s'` shows `lifeos-consolidation`; reject the rest with `-d
'{"all":true}' .../reject`.

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
  shows it done). The same `client_id` with different content (for a task:
  another title, date, project, course or note; a note that differs only in
  spacing is the same) answers `409 client_id_conflict`. The mapping lives in the memory's `phone-ids.md` (ids
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
  null, "note": "…" | null}` changes an open task; `null` (or blank text)
  removes the date or the note, and the other marks of the line are kept.
- `GET /tasks` returns the open tasks in file order: `{"tasks": [{"id":
  "t-20270115-01", "title": "…", "done": false, "due": "2027-01-20" | null,
  "project": …, "course": …, "added": "…"}]}`; a task with a note also has
  `"note": "…"` (as one with a coach step has `"next"`).
- `POST /tasks` with `{"title": "…", "due": "YYYY-MM-DD", "project":
  "file-name", "course": "file-name", "note": "…", "client_id": "…"}` (only
  `title` is required) adds a line to `tasks.md` and returns the task with
  its new id, `client_id` and `"repeat": false` (`201`).
- **Task notes (#170).** `note` is the owner's free text on a task, stored
  as `(note: …)` on the task's line (`memory-design.md` 3.3): one paragraph
  (line breaks become spaces, `()` become `[]`), at most 500 characters;
  control characters or an id marker answer `400`. A blank note is no note.
  Saving or changing a note never starts a model run.
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

**Memory in conversations.** Only the coach conversation starts with the
memory loaded (clean chats, #163): the bridge passes the memory's
`CLAUDE.md`, `INDEX.md`, `profile.md`, the open tasks and the daily logs of
today and yesterday to the model explicitly (at most about 60,000
characters). No other conversation gets it: a blank chat reads the memory
only when the owner asks ("Blank chats"), and a conversation in an allowed
folder or one started in the memory folder gets no memory text. The text
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
  `## Facts` heading on is kept. The run is not saved as a session
  (`--no-session-persistence`), so it never appears in the session list of
  that folder.
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

**The coach conversation** (`/coach/chat…`, [`coach-design.md`](coach-design.md)
section 4.1): one persistent, read-only session in the memory folder on the
`coach_chat` job (Opus), through the same path as chat sessions on either
runner; compacted when its transcript passes about 400 KB, with the facts it
learned written to the memory (`[owner …]` / `[inferred …]`) and the summary
to `coach/conversation.md` in one commit; a new session continues from the
summary. `GET /coach/chat` → `{"session_id", "started", "transcript_bytes",
"compact_at_bytes", "compactions", "summary", "last_compaction",
"earlier_sessions", "model", "effort", "usage": {"today", "week"},
"morning_usage": {"today", "week"}}`. `POST /coach/chat/messages` answers
like `POST /sessions/{id}/messages` (also streaming), plus `"compacted":
{"facts", "summary", "session_id"}` when a compaction ran first; a model
choice or `"mode": "capable"` is refused (`400`). **Streamed, with a
compaction first:** one stream that sends `keepalive {}` every 20 seconds
while the compaction runs, then `compacted {"facts", "summary": bool,
"session_id"}`, then the message's own events (`session`, `delta`, …,
`done`); an error before the message runs comes as an `error` event. Every
streamed message whose conversation switched sessions carries `compacted`
before `session` (#157), also when the conversation only grew too long
while the message was on its way (then without the keep-alives first); a
queued answer (`202`) carries it as `"compacted"` in its body. The
compaction is finished even when the client goes away, and it is never
interrupted: `POST /sessions/{id}/cancel` on the session being compacted
answers `409 compaction_running`, and a message sent with `"when": "now"`
or `…/send-now` waits behind it (`"interrupted_current_run": false`).
Manual check:

```
curl -s -H "$H" -H "$J" -d '{"prompt":"What should I start with today?"}' $B/coach/chat/messages
curl -s -H "$H" $B/coach/chat        # "started": true, "usage.today.runs" 1, "morning_usage" apart
curl -s -X POST -H "$H" $B/coach/chat/compact   # one run; then a new "session_id"
git -C ~/brain log -1 --format=%s     # "Compact the coach conversation" when facts or a summary came
```

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
  out keep their value. `frequency`: `off` sends no notification, `"1"`
  (`"low"`, the default) the daily note only, `"3"` (`"normal"`) the daily
  note and up to two further nudges from the same batch, two hours apart,
  never in quiet hours, never about a task done meanwhile (coach-design.md
  section 5; no run per nudge). With
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
new assignments, new announcements, new or moved deadlines, or new or
changed grades sends **one** notification "LifeOS: new in your courses"
whose text is built from counts only, for example "2 new files, 1 page, 1
assignment, 1 announcement, 1 grade.". It goes through `scripts/notify.sh`
and as one sealed push with the counts (see "Push notifications to the
phone"). No notification, log line or error contains a course name, a
file name, a grade or an address.

**Endpoints.** All read the stored state, answer at once and keep working
while the site cannot be reached. Lists carry `"last_sync"` and `"stale"`
(`true` when the last attempt failed or the last sync is older than six and
a half hours). Times are the site's number plus `…_iso`.

- `GET /courses/summary` → `{"configured", "deadlines": [{"course_id",
  "course", "name", "due", "due_iso", "assignment_id"?}], "new_total",
  "last_sync", "stale"}`: what is due in the next 14 days, nearest first.
  A deadline that belongs to an assignment carries `"assignment_id"` (a
  number), so `GET /courses/{course_id}/assignments/{assignment_id}` opens
  it directly; other deadlines (quizzes and other timeline entries) have no
  such field. A course's `next_deadline` in `GET /courses` carries the same
  two fields.
- `GET /courses` → `{"courses": [{"id", "name", "shortname",
  "next_deadline": {"name", "due", "due_iso"} | null, "new": {"files",
  "pages", "assignments", "announcements", "deadlines", "grades"}, "has_new",
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

### Course resources from the syllabus

The one course feature that uses a model, kept apart from the sync. The
sync only notes each course's syllabus: a downloaded course file or a page
whose name looks like one ("syllabus", "course outline", "izlence", "ders
planı" and similar), with a fingerprint of its version. When the
fingerprint is new or changed, or the owner asks, the bridge's background
loop runs one **course-resources job** (Sonnet with the Opus advisor, see
"Model routing per job"), one course at a time. The run's permissions are
the profile in [`permission-policy.md`](permission-policy.md) section 9.10:
it sees only a copy of the syllabus, may read and search the web, writes
nothing, and proposes a list. **The bridge downloads** by its own rules
(#160; there is no host allow-list any more):

- **Any public HTTPS host.** No plain HTTP, no proxy; private and local
  addresses are refused (the name is resolved first, and the address
  actually connected to is checked again); every redirect hop passes the
  same checks. Piracy sources are dropped, not even listed. Paid books
  (and items of kind `other`) are listed only.
- **PDF or EPUB, decided by the file's first bytes**, never by its name or
  the server's content type: a PDF starts with `%PDF-`; an EPUB is a zip
  whose first entry is `mimetype`, stored uncompressed, holding
  `application/epub+zip` (as the EPUB container format requires). Anything
  else is discarded and listed as `not_a_document`. The extension of the
  saved file comes from that check.
- **A size limit per file**, `LIFEOS_RESOURCE_MAX_FILE_MB` in
  `~/.config/lifeos/env` (default 200, 1 to 2000), enforced while
  streaming: a larger reported size stops before the body, a missing or
  wrong one changes nothing; a partial file is removed. At most ten fetches
  per run.
- **Plain files**: written as `<title>.pdf|.epub` with mode 600 (never
  executable, never through a link) and never opened, parsed or run by the
  bridge afterwards.
- **The owner's existing resource folders are checked first.** Their paths
  are in `~/.config/lifeos/resource_folders`, one per line (absolute or
  starting with `~`; `#` comments and empty lines skipped; relative lines
  ignored). **Until that file exists nothing is downloaded** (items are
  listed as `folders_not_named`, and the view's `"folders_named"` is
  false); an empty file means "there are none". When the file appears, one
  more run (without the model) tries the waiting items. The bridge only
  reads names and sizes there, never opens or writes a file.
- **Matching rule for "already present"** (listed as `already_present`,
  final): the owner's folders and the course's own folder are walked (no
  links followed, hidden entries skipped, at most 20 000 entries) and the
  PDF and EPUB files in them are compared after normalising names (lower
  case, accents and Turkish dotless i folded, punctuation and `_`/`-` turned
  into single spaces, extension dropped). An item is present when (1) its
  title equals a file's name, or one contains the other as whole words and
  the shorter has at least three words; or (2) the file name in its address
  equals a file's name, unless that name is generic (`download`, `document`
  …), shorter than six characters or digits only; or (3) the server reports
  a size (Content-Length) of at least 64 KB that equals a local file's size
  exactly (checked after connecting, before any of the body is read). (1)
  and (2) need no request at all. A book downloaded in the run counts for
  later items of the same run.
- **One summary notification per day at most**: when no run is going or
  waiting, the bridge's loop sends "LifeOS: course resources" / "N course
  resources were downloaded." (fixed text, a count only) through the
  notification script and as a "courses" push (the count sealed, the phone
  shows its own fixed text). Downloads after that day's summary are in the
  next day's. No approval step.

A downloaded address, or one listed for a final reason, is never fetched
again; items listed because of the per-run limit, a failed or too-large
download, an unreachable host, folders not named yet (or a host that was not
on the old allow-list) are tried again at the next run. A run that failed
counts the syllabus as read (no loop); ask again from the app.
`"last_result"` is `ok`, `web_denied` (the web tools were refused; nothing
stored), `folder_not_allowed`, `folder_missing`, `no_syllabus`, or a run
error code. `LIFEOS_RESOURCE_HOSTS` is no longer read.

- In the course folder: `resources/` with the downloaded files and
  `resources/sources.md` (per item: title, link, kind, licence, why it is
  legal, "Downloaded as …" or "Listed only (reason)"; outside text).
- `GET /courses/{id}/resources` → `{"course_id", "syllabus": {"name"} |
  null, "items": [{"title", "url", "kind": "open_access_book" |
  "vendor_documentation" | "public_material" | "paid" | "other",
  "licence", "why_legal", "reference", "status": "downloaded" | "listed",
  "file", "reason": null | "paid" | "other" | "folders_not_named" |
  "already_present" | "not_https" | "private_address" | "not_a_document" |
  "too_large" | "download_failed" | "unreachable" | "later" | …}],
  "running", "pending", "folders_named", "last_run", "last_result"}`.
  Never starts a run. Local paths and file names from the owner's folders
  never appear here, in `sources.md` or in the audit log.
- `POST /courses/{id}/resources/refresh` → `202` with `"reason": "queued"`
  and the view above; `429 "too_soon"` within ten minutes of the last run;
  `409 no_syllabus`; `404 course_not_found`.
- `POST /courses/{id}/resources/{index}/offer` → a file offer (as for
  course files) for a downloaded item, by its position in `items`; `404
  no_local_copy` for a listed one.
- Audit log: one `ResourceDownload` record per fetch (course, host, file
  name, size, result) and one `ResourcesRun` per run (result and counts);
  never page text or addresses. A downloaded file whose record cannot be
  written is removed and listed as `audit_unavailable` (retried). Manual
  check: `grep -h 'Resource' ~/.config/lifeos/audit/*.jsonl | tail -n 3`.
- State: `~/.config/lifeos/course-resources.json` (mode 600), apart from
  the sync's state.

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
  `/sessions`, `/reminders`, `/notes`, `/tasks`,
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
- **Streams the phone stops reading** (#157). The relay answers with four
  workers; a stream (a chat message, `GET /sessions/{id}/live`) holds one
  for as long as it runs. When the phone stops reading one (screen locked,
  another session opened, connection lost) it sends
  `{"m": "POST", "path": "/relay/detach", "body": {"r": "<request id of the
  stream>"}}`. This request is answered at once, even when every worker is
  busy, with `{"status": 200, "body": {"detached": true}}`
  (`false`: no such stream, for example it had ended already). Then:
  - a `/live` follower is closed at once (following is read-only);
  - a chat stream is **never cut**, because closing it would stop the
    run. Its messages stop at the next event (at the latest the next
    keep-alive, 20 seconds), and the run goes on to its end unseen. The
    phone sees the answer through `GET /sessions/{id}/live` or the history.
  - whatever the stream left in the mailbox is removed, and no error
    message follows.
  - a file transfer (`/files/…`) stops before its next chunk; its chunks
    are removed and no `transfer_abandoned` notice follows (#158).
  - a request that still **waits for a free worker** is remembered (#158;
    at most 256, for 10 minutes) and also answers `"detached": true`.
    When a worker reaches it, a `/live` follower or a file transfer is not
    started at all; any other request, such as a chat message, still runs
    (the owner's message is never dropped), but in a thread of its own
    that reads it to the end and sends nothing, so it holds no worker.
    Nothing is sent for it.

  Without a detach message (app killed, connection lost), the relay notices
  by itself: when four or more of a stream's messages wait unread and the
  oldest is more than 45 seconds old, the stream is let go in the same way,
  and one last message, an error `{"status": 504, "error":
  "stream_abandoned"}`, is left for that request: a phone that comes back
  follows the run again with `GET /sessions/{id}/live`. A quiet run sends a
  keep-alive every 20 seconds, so this happens about 80 seconds after the
  phone stopped reading. **The phone should always send the detach:** a
  phone that keeps taking every message out of its mailbox (as a running
  app does, also for a stream it no longer shows) never looks abandoned.
  The phone sends its requests one at a time with rising key times, as
  for every request, so a detach is never taken after a later request.
  Manual check: start a long chat message from the app, lock the phone,
  unlock, and repeat five times during the same run; the app's other
  screens (`/whoami`, notes) still answer at once, and the run's answer is
  there afterwards.
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
- **The sealed hint** is a JSON object: `e` (event kind), `c` (the Android
  channel), `o` (the screen a tap opens), `r` (the item, optional), `n`
  (counts, courses only) and, for a notice, `t` and `l`:

  | `e` | `c` | `o` | `r` | When |
  |---|---|---|---|---|
  | `answer` | `chat` | `session` | session id | a run of a session ended (off per session with `notify_replies`) |
  | `question`, `approval` | `chat` | `session` | session id | a run waits for the owner (SDK runner) |
  | `courses` | `courses` | `course` / `courses` | course id when one course changed, else none | a course sync found something (one push per sync) |
  | `signin` | `courses` | `moodle_signin` | – | the course sign-in needs renewal |
  | `coach` | `coach` | `coach` | message id of `GET /coach/plan` | the coach's daily note, and further nudges (frequency `"3"`) |
  | `coach` | `coach` | `consolidation` | proposal id | a consolidation proposal waits for approval |
  | `reminder` | `reminders` | `reminder` | reminder id | a session reminder is due |
  | `notice` | `reminders` | `notice` | – (`t`, `l`) | `scripts/notify.sh` (for example a new owner issue) |
  | `update` | `reminders` | `app_update` | version code | a new app release was registered (`lifeos-bridge release`) |

- **Courses: one push per sync.** Every change one sync finds (new or
  changed files, new or changed pages and books, new assignments, new
  announcements, new or moved deadlines, new or changed grades) goes into a
  single push whose `n` holds the counts per kind, for example `{"files": 2,
  "assignments": 1, "grades": 1}`. A new assignment counts as an assignment,
  not also as a deadline. The first sync ever sends one push without counts.
- **Lock screen:** the counts, the notice title and every reference stay
  inside the sealed hint. The app shows a **fixed text per event kind** on
  the lock screen (for example "New in your courses", "An answer is
  ready"); it may show counts only once the phone is unlocked.
- `POST /push/notify` with `{"title": "…", "link": "…"}` is what
  `scripts/notify.sh` calls when the bridge is running; title and link
  travel inside the sealed hint only. `LIFEOS_NOTIFY_PUSH=off` makes the
  script skip it.
- A push that cannot be sent is not retried; the phone sees everything when
  it next connects. Nothing about pushes is logged.
- The app's side (receiving, showing the notification) is not built yet.
- Manual check (bridge side, with a paired phone that registered its push
  address): `curl -s -X POST -H "$H" -H "$J" -d '{"title":"test"}'
  $B/push/notify` answers `{"phones_reached": 1}`; after a course refresh
  that found something, the bridge log shows no course names, and the
  phone (once the app side exists) shows one "courses" notification.

## App releases for the phone

`lifeos-bridge release` registers a new release build of the app; the
paired phones are told and can install it with one tap (#138, app side
separate). No third-party update service.

```
uv run lifeos-bridge release --build     # Gradle builds and signs (it reads the key itself), then registers
uv run lifeos-bridge release             # registers the existing build output instead
uv run lifeos-bridge release --apk FILE --version-code N --version-name X   # another APK
```

- **What it checks and records:** the APK from Gradle's release output
  (`app/app/build/outputs/apk/release/`, with version code and name from
  `output-metadata.json`), its size (at most 200 MB), the SHA-256 of the
  file, and the SHA-256 of its signing certificate, read from the APK
  Signature Scheme v3/v2 block; an unsigned APK is refused. The first
  registered certificate is pinned: an APK signed with another key is
  refused unless `--new-certificate` is given. The version code must be
  newer than the current release. The command never asks for or reads the
  keystore password: Gradle reads `~/.config/lifeos/release/` itself.
- **Where:** a private copy in `~/.config/lifeos/app-release/lifeos-<code>.apk`
  (mode 600; only the current one is kept) and the record in
  `~/.config/lifeos/app-release.json`. The `AppRelease` audit record (version,
  size, hashes) is written first; without it nothing is registered.
- **Push:** one sealed hint `update` (channel `reminders`, opens
  `app_update`, `r` = version code) to every paired phone, sent by the
  running bridge (the command asks it with `POST /push/notify` `{"event":
  "update", "ref": "<code>"}`): only the bridge may advance a phone's
  message counters. When the bridge is not running, no push is sent and the
  command says so; the app finds the release when it opens.
- **Verified** on a real release output (read-only): Gradle's
  `output-metadata.json` gives `versionCode`, `versionName`, `outputFile`;
  the certificate taken from the v3 signing block is a valid X.509
  certificate whose SHA-256 equals `keytool`'s fingerprint. The bridge does
  not verify the signature itself (Android's installer does).
- `GET /app/release` → `{"version_code": int, "version_name": str, "size":
  int, "sha256": hex, "cert_sha256": hex, "registered_at": iso}`; `404
  no_release` when none is registered.
- `POST /app/release/offer` → `{"offer", "name", "type":
  "application/vnd.android.package-archive", "size", "session_id":
  "app-release", "version_code", "version_name", "sha256", "cert_sha256",
  "registered_at"}`. The phone fetches it with `GET
  /files/{offer}?session_id=app-release` (through the relay: sealed chunks
  of 700 KB, three waiting at a time, the SHA-256 of the whole file with
  the last message), within 15 minutes. The release has its own size limit
  (200 MB), independent of `LIFEOS_MAX_FILE_MB` (50 MB for ordinary file
  offers), and it is offered only from its own folder; the offer and the
  download are audited like every file. The phone checks the hash and the
  certificate before it opens the installer.

Manual check: `uv run lifeos-bridge release --build` prints "Registered
release …; the paired phones were told"; `curl -s -H "$H"
$B/app/release` shows the same version and hashes; `curl -s -X POST -H
"$H" $B/app/release/offer` gives a card; `grep AppRelease
~/.config/lifeos/audit/*.jsonl | tail -n 1`.

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
scripts/ask "What is a monad? Two lines."      # ask (a blank chat, in its own new folder)
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

Ask a question (this uses the owner's Claude plan; keep it small). Without
`"folder"` it is a blank chat; with `"folder":"<the memory folder's path>"`
it runs there, and that folder must exist and contain a file to ask about:

```
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"Which files are in this folder? Answer in one line.","folder":"/home/<you>/brain"}' \
  http://127.0.0.1:8787/ask
# {"answer":"...","session_id":"...","duration_ms":...,"num_turns":...,"denied_tools":[]}

# continue the same conversation: add "session_id":"<id from above>"
# moving by asking: in any chat ask "open my chat about <topic>" -> the answer has a
#   "switch" item with "action":"open" and that session; `GET /sessions/<id>/history` has none
# blank chat: leave "folder" out and ask "What do you know about me?" -> nothing; afterwards
#   `ls ~/LifeOS-Chats` shows no folder for it, and `/sessions` lists it with "kind":"chat";
#   continue it with its session id -> it answers, and remembers the first message
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

Titles, pins and reminders (replace `<id>` with a session id from
`/sessions`; these change only the bridge's own two files under
`~/.config/lifeos/`):

```
H="Authorization: Bearer $LIFEOS_BRIDGE_TOKEN"; B=http://127.0.0.1:8787
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
# a task note: set, read back on the line, removed
curl -s -X PATCH -H "$H" -H "$J" -d '{"note":"Bring the (old) notes"}' $B/tasks/<id>   # "note":"Bring the [old] notes"
grep -c '(note: Bring the \[old\] notes)' ~/brain/tasks.md   # 1
curl -s -X PATCH -H "$H" -H "$J" -d '{"note":null}' $B/tasks/<id>                      # no "note" any more
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
curl -s -H "$H" -H "$J" -X PUT -d '{"frequency":"3"}' $B/coach/settings   # "normal" works too
# then, two and four hours after the daily note (outside quiet hours), "A note from your coach."
# appears; never a fourth that day, never in quiet hours; no new run (`/models/routing` unchanged)
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

Course resources (one routine-job run on the owner's plan per syllabus; a
course whose materials include a file or page named like a syllabus):

```
curl -s -H "$H" $B/courses/<id>/resources        # "syllabus": {"name"}; "pending": true after the sync found it
# within a minute or two the run is done (the bridge's loop starts it); then:
curl -s -H "$H" $B/courses/<id>/resources        # "items": downloaded or listed, "last_result": "ok"
ls "$MOODLE_DOWNLOAD_DIR"/<course folder>/resources     # sources.md and the downloaded PDFs/EPUBs
grep -c 'Listed only' "$MOODLE_DOWNLOAD_DIR"/<course folder>/resources/sources.md
# before ~/.config/lifeos/resource_folders exists: "folders_named": false, nothing downloaded
grep -o 'folders not named\|already present' "$MOODLE_DOWNLOAD_DIR"/<course folder>/resources/sources.md | sort | uniq -c
ls -l "$MOODLE_DOWNLOAD_DIR"/<course folder>/resources/*.pdf   # -rw------- : never executable
file "$MOODLE_DOWNLOAD_DIR"/<course folder>/resources/*        # PDF or EPUB documents; sources.md is text
# after a day with downloads: one "LifeOS: course resources" notification, counts only
curl -s -X POST -H "$H" $B/courses/<id>/resources/refresh   # 429 "too_soon" within ten minutes of a run
curl -s -H "$H" $B/models/routing | grep -o '"course_resources"[^}]*'   # model sonnet, advisor opus; runs today 1
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
