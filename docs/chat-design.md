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

**Today:** switched off on purpose. Every run starts with skills and
commands disabled, without MCP servers and with project settings ignored;
the guardrail probes check that a hook or MCP server inside the run folder
does not run.

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

## 7. What only the Agent SDK offers

| Wanted | With `claude -p` (current) | With the Agent SDK |
|---|---|---|
| Option cards for questions | not possible in a documented way | native |
| List of available models | not available; configured aliases | native |
| List of skills and commands | read from the folder (works) | native |
| Reading a session's past messages | own reader of an internal format | official function |
| Interrupt, queue | signals and the bridge's own queue (work) | native |

Costs of switching the runs to the SDK: a large extra package that bundles
its own copy of the CLI; the run path and the capable-mode guardrail check
would have to be rebuilt and passed again; the capable mode would be off
until then. Raised as a human decision; nothing here depends on it except
the option cards.

## 8. Build issues

| Part | Bridge | App |
|---|---|---|
| History | history endpoint | lazy loading in chat |
| Run control | queue, force send, cancel | queue UI, confirmations |
| Skills and commands | listing; per-session switch with new probes; installs with approval | "/" list, highlight, approval screen |
| Models | per-session model and effort, `GET /models` | picker and default setting |
| Files | offers, checks, chunked sealed transfer | file card, download to Downloads |
| Questions | — (plain text works) | option cards only if the SDK is adopted |
