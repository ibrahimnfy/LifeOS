# Chat and sessions: history, run control, skills, models, files, questions

Status: **design**, written from the owner's requirements of 2026-11-20.
Each section says what exists today, what is added, and which issue builds
it. Research behind it: the official Claude Code documentation (headless
mode, sessions, skills, permission modes, model configuration, Agent SDK
user input), read on 2026-11-20, and checks on the owner's machine with
Claude Code 2.1.328. Examples are generic placeholders.

Everything here travels between phone and bridge inside the end-to-end
layer of [`security.md`](security.md); the endpoint shapes below are those
of the bridge's local API, which the relay dispatcher exposes one to one.

## 1. History on the phone

**Today:** the bridge lists sessions and continues them; it has no way to
return a session's earlier messages.

**Added:** `GET /sessions/{id}/history?limit=4&before=<cursor>`.

- Returns the **last few exchanges** (default 4, at most 20 per call),
  oldest first, plus `older`: a cursor when earlier messages exist, else
  `null`. An exchange is one message of the owner and the assistant's
  answer to it. Scrolling up asks for the next page with `before`.
- A message is `{role, text, at}`. Tool calls are summarised as short
  labels ("read 3 files", "ran 2 commands"), not replayed; file offers
  (section 5) and questions (section 6) keep their own shape.
- **The computer keeps the full transcript.** The phone holds only what is
  on screen; it does not store whole sessions (the last page of the open
  conversation may be kept in memory for a smooth restart, not on disk).
- Only sessions inside the allowed folders, as everywhere else.
- **Source:** Claude Code's session files. Their entry format is documented
  as internal and may change between versions, so the reader is tolerant
  (unknown entries are skipped), is covered by tests with synthetic files,
  and is part of the manual check to repeat after a Claude Code update. The
  officially supported way to read past messages is the Agent SDK; see
  section 7.

## 2. Messages while a run is in progress

**Today:** one run per session is enforced; a second request for the same
session is refused with `409`. A run is stopped only by the time limit
(interrupt first, then terminate, then kill). There is no queue and no
cancel request.

**Added:** the bridge owns one small queue per session.

| Action | Behaviour |
|---|---|
| **Queue** (default) | A message sent while a run is in progress is stored in the session's queue (`202`, with a queue id) and is sent when the current run ends, in order, as in the terminal. |
| See and change the queue | `GET /sessions/{id}/queue`; `PATCH …/queue/{qid}` changes the text; `DELETE …/queue/{qid}` removes it. Possible until the moment it is sent. |
| **Force send** | `"when": "now"` on the message: the current run is interrupted cleanly, then the new message is sent at once, ahead of the queue. |
| **Cancel** | `POST /sessions/{id}/cancel`: the current run is interrupted cleanly. The queue is kept but paused until the owner sends or resumes, so a cancel never triggers the next message by surprise. |

- **Clean interruption:** the bridge sends the interrupt signal to the run
  (the documented way to end a turn), waits a few seconds, and only then
  terminates and, as a last resort, kills the process group. The session
  stays resumable; the interrupted turn is left as it is and the next
  message drives the conversation.
- **Never two writers:** the queue is the only way a second message reaches
  a busy session; the per-session lock and the check against sessions open
  on the computer stay exactly as they are. A queued message is sent only
  after the previous process has exited.
- **File changes are not undone.** Cancelling or force-sending stops the
  assistant; whatever it already wrote or ran stays. The app says so on the
  cancel and force-send confirmations ("Stops the assistant. Changes it has
  already made to files are kept.").
- The queue lives in memory and in a small file in the private config
  folder, so queued messages survive a bridge restart but are never sent
  automatically after one: they wait as drafts.
- Limits: at most 10 queued messages per session; the usual prompt size.

## 3. Skills and slash commands

**Before #91:** switched off on purpose. Every run starts with skills and
commands disabled, without MCP servers and with project settings ignored;
the guardrail probes check that a hook or MCP server inside the run folder
does not run.

**Built (#91):** capable runs of a session whose `skills` switch is on get
a copy of the folder's own skills and commands, loaded as a plugin
(`folder:<name>`). Everything else stays as described here: read-only
runs, no personal skills, no shell lines, no hooks, MCP servers or project
settings. It is enabled only after the guardrail check with the skill
probes passed. Details: `docs/permission-policy.md`, section 9.2; API:
`docs/bridge.md`, `GET /sessions/{id}/commands`.

**Changed (#162, owner's decision #149):** every capable run is also offered
the owner's personal skills and commands, as a second copy
(`personal:<name>`), and personal MCP servers once approved. "No personal
skills" above no longer holds for capable runs; everything else does.

What the documentation says, and what follows:

- Skills and custom commands work in headless runs: a prompt that starts
  with `/name` is expanded before the run.
- Project skills live in `<folder>/.claude/skills/<name>/SKILL.md`,
  project commands in `<folder>/.claude/commands/`. Personal ones live in
  the home folder.
- A skill can pre-approve tools for itself, and can contain lines that run
  shell commands before the model sees the skill; the latter can be
  switched off with a setting.
- Without the restricted flags a headless run executes a project's hooks
  and starts its MCP servers **without any trust question**, and those run
  as ordinary programs: **outside the OS sandbox** that confines the
  assistant's own commands.

**Design.**

- **Scope: the session's folder only.** A session may use the skills and
  commands found in its own folder's `.claude/` directory. Personal
  (home-folder) skills, plugins and settings are never loaded into runs
  from the phone.
- **Only in capable mode, only in allowed folders, switched on per
  session** by the owner ("Use this folder's skills and commands"). The
  read-only default stays exactly as locked down as it is now.
- **Listing for the composer:** `GET /sessions/{id}/commands` returns the
  folder's skills and commands (name, one-line description, argument hint)
  by reading those documented folders. It runs nothing and needs no model.
  The app shows the list when "/" is typed, completes names, and
  highlights a recognised command in the composer.
- **In the run:** shell lines inside skills are disabled; a skill's
  self-approval of tools cannot widen the tool list the bridge passes; all
  commands a skill causes go through the same sandbox, the same gate
  program and the same audit log as any other command.
- **MCP servers, project hooks and project settings cannot be put inside
  the guardrails** with the mechanisms that exist: they run outside the
  sandbox, with network access and the owner's rights. Enabling them as
  asked would weaken guardrails the owner declared non-negotiable, so this
  part is **a human decision** (see `permission-policy.md`, section 9).
  Until it is answered they stay off.
- Before any of this is switched on, the probe suite gets new probes
  (section 9 of the policy) and must pass.

**Installing a skill.**

1. The owner asks which skill would help; the assistant answers with an
   **install proposal**: name, source address, one line of why.
2. The bridge, not the sandboxed run, downloads the proposed skill into a
   **quarantine folder** in the private config area, where nothing loads
   it. It is treated as untrusted text: never executed, never fed to a run.
3. The app shows an **approval screen**: source, the list of files with
   sizes, the skill's own description, and warnings for anything notable
   (scripts, shell lines, requested tools), and where it would be installed
   (`<session folder>/.claude/skills/<name>/`).
4. Only after the owner's explicit approval is it copied there, and the
   install is written to the audit log. Declined or expired proposals are
   removed from quarantine.

Installs are always per folder, never global. The approval travels the same
path as the other approvals of the capable mode.

## 4. Model per session

**Today:** one optional setting for all runs; no effort setting.

**Added:**

- Each session has a model and an effort level; `PATCH /sessions/{id}`
  accepts `model` and `effort`, and a new conversation may name them. They
  are passed to every run of that session (also on resume, where the
  documentation says a named model takes precedence).
- **Default: Opus with high effort** (`LIFEOS_DEFAULT_MODEL=opus`,
  `LIFEOS_DEFAULT_EFFORT=high`), changeable in the app's settings.
  The alias always means the latest model of that family.
- `GET /models` returns what the app offers: models, effort levels, and the
  defaults.
  - **Effort levels are read from the installed CLI** (its help text lists
    them), so a new level appears without a code change.
  - **Models:** the CLI has no documented way to list models (only the
    Agent SDK has; section 7). The list is therefore the CLI's documented
    family aliases, configurable in the private settings, and every choice
    is validated by the CLI itself when the run starts. This is the one
    place where the owner's "do not hard-code" cannot be met fully without
    the SDK.
- The bridge's own background runs (coach plan, context map) use the same
  default unless `LIFEOS_BACKGROUND_MODEL` names another one.

## 5. Files in chat

**Today:** nothing; the building blocks exist (allowed-folder checks,
sealed messages of at most 1.4 MB).

**Flow.**

1. The owner asks for a file. The assistant answers with a **file offer**
   in its reply: a line the bridge recognises and checks, turned into a
   card `{offer id, name, type, size}`. **Nothing is transferred yet.**
2. The owner taps **Download**. The phone sends a request for that offer.
3. The bridge reads the file, splits it into chunks of about 700 KB, seals
   each chunk like any message and writes it to the relay; the phone
   acknowledges each chunk, **the bridge deletes it from the relay as soon
   as it is acknowledged**, and at most a few chunks are in the relay at
   any time. A final message carries the size and a hash of the whole file.
4. The phone writes the chunks straight into the system Downloads folder
   through the platform's download storage, checks size and hash, and
   **keeps no copy and no extra data** of its own; an incomplete download
   is removed.

**Rules.**

- **Only files inside the allowed folders or the memory folder**, resolved
  first (no symbolic-link escapes), regular files only, never the private
  config folder or hidden credential folders. The same fixed limits as for
  allowed paths apply.
- An offer is created only by the bridge after checking the path; an offer
  id is random, valid for 15 minutes, and bound to the session. The model
  cannot make the phone fetch anything: a download happens only on the
  owner's tap.
- Offers and downloads are written to the audit log (name and size, not
  content).
- **Size limit: 50 MB per file by default** (`LIFEOS_MAX_FILE_MB`), derived
  from the relay's verified no-cost limits: a file travels as roughly 1.4
  times its size, so 50 MB is about 70 MB of the 10 GB monthly download
  allowance (0.7 %) and stays far below the 1 GB storage limit even if no
  chunk were deleted. Hard upper bound 200 MB.

**Cost** (official pricing page, read 2026-11-20). No-cost plan: 1 GB
stored, 10 GB downloaded per month. Pay-as-you-go: no cost up to 360 MB
downloaded per day, then USD 1 per GB; storage USD 5 per GB above 1 GB.
Outbound traffic including connection overhead is what counts.

| Use per month | Download volume | No-cost plan | Pay-as-you-go |
|---|---|---|---|
| 20 files of 10 MB | about 0.3 GB | USD 0 | USD 0 |
| 200 files of 10 MB | about 2.8 GB | USD 0 | USD 0 if spread over days; at most about USD 2.5 if all on one day |
| about 700 files of 10 MB | about 10 GB | the no-cost allowance ends here | USD 0 to about USD 10, depending on the days |

The pay-as-you-go plan **cannot be capped** for this database: budget
alerts do not stop spending, and the provider's spend-cap budgets do not
cover it. The no-cost plan cannot bill at all. Conclusion: stay on the
no-cost plan; realistic use is a few percent of it. A change of plan is
raised as a decision only if real use approaches the allowance.

## 6. Interactive questions

**Requirement:** when the assistant wants to ask structured questions, the
app shows selectable options with a free-text answer, as the terminal does.

**Finding.** The terminal's question tool exists in headless runs, but
plain `claude -p` has **no documented way for a host to answer it**: in the
permission mode the bridge uses, the tool is denied; with prompts switched
off, it is removed. The documented way to receive such a question and send
back the selection is the Agent SDK's user-input callback. The CLI's
control protocol underneath is not publicly documented, and building on it
would be exactly the fragile workaround the owner ruled out.

**Therefore:**

- **Now: plain text.** The assistant asks its question in its answer, the
  owner replies in the composer; that already works with session resume.
  The run's instructions tell the assistant to ask one clear question at a
  time and to offer its options as a short lettered list, so a reply can be
  one letter.
- **Native option cards** need the Agent SDK. Using it for runs would
  change a logged decision (keep `claude -p`), so it is **a human decision**
  (section 7).

**Built (#105) on the SDK runner.** The question tool reaches the bridge
through the SDK's permission callback. While the app streams the answer,
the question is shown as an `interaction` event with options and free text.
The run waits, and its time limit is paused. The owner's answer is passed
to the assistant, which continues. With no streaming client, the tool is
refused at once with the plain-text instruction above, so a queued message
or a non-streaming request behaves as before. No answer within the time
limit counts as "no". Verified with the real CLI: a question with two
options was answered by script, and the reply named the chosen option.
In the same run, a read outside the folder was still denied and listed in
`denied_tools`. API: `docs/bridge.md`, "Questions and approvals".

## 7. The Agent SDK

The owner decided on 2026-11-20 to move the runs to the Agent SDK, to get
question cards and approvals in the middle of a run, a live process per
session (no restart between messages) and clean interruption. Conditions:
the capable mode stays off until every guardrail check passes again on the
new path (the current path stays as a fallback until then); after any
version change of Claude Code or the SDK the checks rerun by themselves and
the mode is off until they pass; sessions stay interchangeable with the
terminal.

Findings from the official documentation and from the package itself
(version 0.2.186; read, not installed, nothing run):

| Topic | Finding |
|---|---|
| Package | About 166 MB to download and about 362 MB installed (measured with 0.2.186); it bundles its own Claude Code binary (a newer patch version than the installed one) and uses the bundled one unless told otherwise. The bridge will name the installed binary explicitly, so the version that is verified is the version that runs |
| Sign-in | **The SDK documentation describes API-key sign-in only** and says that subscription logins may not be offered by third-party developers in their products. Whether the owner's own use with the installed, signed-in Claude Code is acceptable is **the owner's decision (human issue); the SDK work waits for it**. Answered (#115): the subscription login of the installed Claude Code |
| Questions and approvals | Supported through a callback, but **not in the permission mode the bridge uses today**: in that mode such calls are denied without asking. The SDK path therefore has to use the default mode with an explicit allow-list, which changes how every guardrail is expressed and is why the whole probe suite must pass again |
| Audit before every call | A callback that runs before every tool call, with a documented option that blocks the call when the callback fails (the default lets the call through; the option is mandatory here) |
| Sandbox settings | The SDK's own sandbox option does not document two fields the guardrails rely on (refusing to start without the sandbox, and hiding the home folder); the bridge keeps passing its complete settings JSON, and the probes must show that it is honoured |
| Settings loading | By default the SDK loads the owner's personal and project settings like the terminal does; the bridge must switch all of that off explicitly, as today |
| Sessions | Stored in the same place as terminal sessions and resumable by id in both directions (hidden from the terminal's session picker, which lists only its own); two writers on one session at the same time lose messages, so the bridge's one-run-per-session rule stays |
| Model list, session history | Official functions exist for both |
| Versions | The SDK version and the Claude Code version it starts can both be read, so the guardrail marker can be tied to both |

Not documented, to be established by tests before anything is relied on:
whether settings-file hooks passed by the bridge still run; how the sandbox's
automatic approval of commands interacts with the approval callback; idle
behaviour of a long-lived process; whether a subscription login works at
all.

**Implemented (#104), read-only only:** the SDK runner (`LIFEOS_RUNNER=sdk`,
default still `cli`) keeps one client per active session and starts the
installed `claude` through its own transport with the allow-listed
environment and the `claude -p` flags (the SDK's launcher would pass the
whole environment on and fall back to the bundled binary). With the real
CLI: the subscription login works through it, the bridge's own idle process
is listed by `claude agents --json` as holding its session (the in-use
check therefore ignores the bridge's own process ids), and sessions moved
between the SDK runner and `claude -p --resume` in both directions with the
conversation intact. On one live process: the start message lists exactly
the tools Read, Glob and Grep, no MCP server, no slash command and the
`dontAsk` mode; the turn limit counts per message; an interrupt ends the
turn at once and the process stays usable; `claude -p --resume` from a
terminal works while the bridge's process sits idle, and the bridge notices
the changed transcript and resumes instead of writing over it. See
`docs/bridge.md`, "Runner".

### 7.1 Chat through the relay (#147)

Three gaps the chat screen found, closed in the bridge:

- **Signs of life.** A streamed run that says nothing for 20 seconds (a long
  tool call) or waits for the owner sends a `keepalive` event; the relay
  forwards it sealed. The phone treats a stream without any event for well
  over 30 seconds as a lost connection, and a stream with keep-alives as a
  working run.
- **A new conversation the phone names itself.** The phone sends its first
  message with `new_session_id` (a UUID it chose). If the answer is lost,
  it sends the same message again with the same id: the bridge recognises
  the repeat (same text and folder) and runs nothing, answering
  `{"repeat": true, "session_id", "running"}`; the phone then follows the
  session (queue, history) instead of starting a second conversation.
- **Closed interactions.** A question or approval card closes on the
  stream's `interaction_closed {"id", "reason"}` (`answered` from any
  client, `timed_out`, or `run_ended`), so a card answered elsewhere or
  left over from a stopped run does not stay open.
- **Session list:** `running_here` (the bridge answers in it) and
  `open_elsewhere` (a terminal or the desktop app holds it; `null` when
  unknown) tell the two kinds of "busy" apart.

### 7.2 Queue and file cards (#154)

- **Send now in one request:** a waiting message's "Send now" calls
  `POST /sessions/{id}/queue/{qid}/send-now`; the current run is interrupted
  as with `"when": "now"`, and nothing is sent twice or deleted.
- **Resume does not wait:** `POST …/queue/resume` answers at once; the
  queued messages go out in the background.
- **Answers to waiting messages, live:** the app attaches to
  `GET /sessions/{id}/live` whenever the session is running and it has no
  stream of its own (after sending a queued message, after reopening the
  conversation, after a lost connection). It first receives what the run
  said so far, then the rest live, with question and approval cards when the
  message was sent from a client that shows them. Following never starts or
  resumes a run, so it can never be a second writer.
- **File cards after a re-read:** `GET /sessions/{id}/files` lists the
  session's offers that have not expired, so a card rebuilt from the history
  keeps its download for the rest of its 15 minutes.
- **Fresh session read:** `GET /sessions/{id}` gives one session's model,
  effort, switches (skills, reply notifications, pinned, group), whether it
  runs here or is open elsewhere, and its waiting messages.

### 7.3 Streams, cards and live follow (#157)

- **Leaving a stream frees the relay.** The relay has four workers, and a
  stream holds one while it runs. When the app stops reading a stream (the
  screen locks, another session opens, the connection breaks) it sends
  `POST /relay/detach` with `{"r": "<request id of the stream>"}` through
  the relay; the answer is `{"detached": true|false}`. A `/live` follower
  is closed at once. A chat stream is never cut: its run goes on to the
  end, and the app picks it up again with `GET /sessions/{id}/live` when it
  returns. If the app could not send the detach (killed, offline), the
  relay lets go by itself once the stream's messages wait unread for about
  a minute, and leaves one `stream_abandoned` error for that request. A
  running app takes every message out of its mailbox, also of a stream it
  no longer shows, so the relay cannot tell by itself: the app must send
  the detach whenever it stops reading. Then repeated lock and unlock
  during one long run never exhausts the workers.
- **Live follow, caught up.** `GET /sessions/{id}/live` sends
  `replay_end {"events": n}` after what the run said so far. Until then
  what arrives is the replay: one block of text that takes the place of the
  unfinished answer on screen, and cards whose time is what is left of it;
  after it, the text streams as usual and a card is new. Without a
  followable run the stream is `live`, `replay_end`, end.
- **Approval cards stay until the answer took effect.** A refused answer
  leaves the card waiting, with its files: `409 still_downloading` (the
  skill is still being fetched: keep the card, enable "Install" when its
  state is `ready`), `409 skill_exists`, `503 audit_unavailable`,
  `500 install_failed`. The app shows the error and keeps the card. A
  failed skill card carries `error_code` next to the `error` sentence, so
  the app can word it itself (codes in `bridge.md`, "Skill installs").
- **One MCP card per definition.** Asking again for an MCP server whose
  card already waits returns that card (`"already_waiting": true`) and wakes
  no phone a second time.
- **History shows the servers.** Each history exchange carries
  `mcp_servers` (the MCP servers that answer had), so a re-read
  conversation shows the same "outside the sandbox" note as the live one.
- **The coach conversation always says when it switched sessions.** A
  streamed coach message sends `compacted` before `session` whenever its
  conversation was compacted first, including when that was decided only
  after the message was sent.

### 7.4 In the app: session sheet, cards, live follow

How the app uses sections 3, 6 and 7.1 to 7.3 (details and manual checks:
`app/README.md`).

- **Session sheet** (the dots next to the pills above the composer, or a
  long press on a session): rename, pin, group, reminders, the reply
  notification switch, then **Use this folder's skills and commands** (off
  unless switched on; with what it means, how many the folder has, and a
  line when the computer's check for skills has not passed) and **MCP
  servers** (list with state and what would start, the outside-the-sandbox
  warning first, "Review and approve", "Revoke approval"). The session is
  read fresh (`GET /sessions/{id}`). The coach conversation's sheet has
  neither the skills switch nor the MCP page.
- **Cards** live in the conversation. Those of a run (question, approval)
  end with the run and the next message. Those that wait across runs (skill
  install, MCP approval) are read whenever a conversation is opened or read
  again, stay through messages and runs for up to a day, and count their
  time in hours. A skill card offers "Install" only once its contents are
  listed. A yes the computer refuses keeps the card open with the reason; a
  failed proposal is worded from its `error_code`.
- **Live follow.** The app has one stream per conversation at most: its own
  message's answer, or `GET /sessions/{id}/live` when the computer runs
  without one (a waiting message, an answer lost to a lock or a dropped
  connection). Where a run cannot be followed, the app asks every five
  seconds as before. A stream it stops reading is detached (7.3); on a lock
  the detach is sealed before the secret is forgotten.
- **Answers read again** keep their file cards for the time that is left of
  an offer (`GET /sessions/{id}/files`) and the line that names the MCP
  servers they used (history).

## 8. Build issues

| Part | Bridge | App |
|---|---|---|
| History | history endpoint | lazy loading in chat |
| Run control | queue, force send, cancel | queue UI, confirmations |
| Skills and commands | listing; per-session switch with new probes; installs with approval | "/" list, highlight, approval screen |
| Models | per-session model and effort, `GET /models` | picker and default setting |
| Files | offers, checks, chunked sealed transfer | file card, download to Downloads |
| Questions | — (plain text works) | option cards only if the SDK is adopted |
