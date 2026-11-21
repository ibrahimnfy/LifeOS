# Memory design

Status: **approved by the owner on 2026-11-20**, with the three open points
answered (section 9). All examples are invented placeholders.

The memory is a folder of plain Markdown files, `~/brain`, under local git
with no remote. It is what lets the assistant start every conversation
already knowing the owner's projects, courses, people and open tasks.

Background and sources: [`docs/research/prior-art.md`](research/prior-art.md),
section 2 and ideas 4, 10, 11, 15 and 18.

## 1. Principles

1. **Plain files the owner can read.** No database, no hidden state. If the
   assistant disappeared tomorrow the folder would still be a usable set of
   notes.
2. **Small always-loaded part, everything else on demand.** One index file
   with a hard size limit is read at the start of every conversation; the
   rest is opened only when relevant. (Same pattern as Claude Code's own
   memory and other surveyed projects.)
3. **Pointers, never copies.** The memory says *where* things live on disk
   and what state they are in. It does not duplicate project files or course
   materials.
4. **Obvious places.** Few folders, fixed names. If the assistant has to
   guess where something belongs, the structure is too clever.
5. **Nothing is lost.** Every change is a git commit. Facts that stop being
   true are marked as superseded, not erased. Files are never deleted, only
   moved to `.trash/`.
6. **Know where a fact came from.** Each entry records whether the owner
   said it, the assistant inferred it, or it came from outside (Moodle, a web
   page, later e-mail). Outside text never becomes a standing instruction.
7. **No secrets.** Passwords and tokens live in `~/.config/lifeos/`, never in
   the memory.

## 2. Layout

```
~/brain/
├── CLAUDE.md            how to use this memory (rules for the assistant)
├── INDEX.md             always loaded; max. 200 lines
├── profile.md           stable facts and preferences about the owner
├── inbox.md             quick captures from the phone, not yet sorted
├── tasks.md             all open tasks
├── tasks-done.md        completed tasks (append-only archive)
├── phone-ids.md         written by the bridge: which phone id became which note or task
├── daily/
│   └── 2026-11-20.md    one log per day
├── map/                 context map: one file per allowed folder on disk
│   └── example-project.md
├── projects/
│   └── example-project.md
├── courses/
│   └── example-course.md
├── people/
│   └── example-person.md
├── notes/               everything else worth keeping, one topic per file
│   └── example-topic.md
├── calendar/            the local calendar (the chosen calendar backend)
│   └── events.md
├── coach/               what the coach asked and what the owner answered
│   └── log.md
└── .trash/              removed files are moved here, never deleted
```

`calendar/` is part of the layout from the start (the owner chose the local
calendar; its file format is defined by the calendar issue). `coach/` is
described in [`coach-design.md`](coach-design.md). Later phases add their own
folders when they are built (for example `activity/` for sleep and usage
summaries). They follow the same rules.

File names are lowercase words joined with hyphens. Dates are always
absolute and written `YYYY-MM-DD`.

## 3. File formats

### 3.1 Topic files (`projects/`, `courses/`, `people/`, `notes/`, `map/`, `profile.md`)

Markdown with a short frontmatter block:

```markdown
---
title: Example project
type: project            # project | course | person | note | map | profile
updated: 2026-11-20
status: active           # optional: active | paused | done
---

One-paragraph summary: what this is and its current state.

## Facts
- [owner 2026-11-20] Goal is a working demo before the end of term.
- [inferred 2026-11-21] Work usually happens in the evenings.
- [external:moodle 2026-11-22] Final report template was published.
- [owner 2026-11-10, superseded 2026-11-20] Demo was planned for October.

## Links
- Folder: see [[map/example-project]]
- Related: [[courses/example-course]], [[people/example-person]]
```

- Each fact is one line starting with its **source and date** in brackets:
  `owner` (the owner said it), `inferred` (the assistant concluded it),
  `external:<where>` (came from outside).
- A fact that is no longer true keeps its line and gets
  `superseded <date>`; the new fact is added as a new line.
- `[[folder/name]]` links to another memory file.

### 3.2 Context map (`map/`)

One file per folder listed in `~/.config/lifeos/allowed_paths`. Same format
as above with `type: map`, plus:

```markdown
## Location
`~/path/to/folder`

## What it is
Two or three sentences.

## State
What was last worked on, what is unfinished (as of the `updated` date).

## Key files
- `README.md` — overview
- `src/main.py` — entry point
```

Only paths and short descriptions; never file contents.

### 3.3 Tasks (`tasks.md`)

One line per task, plain Markdown checkboxes, grouped under fixed headings:

```markdown
## Open
- [ ] Hand in example assignment  (due: 2026-12-01) (course: example-course) (added: 2026-11-20) ^t-20261120-01
- [ ] Reply to example person  (added: 2026-11-20) ^t-20261120-02
```

- Optional fields in parentheses: `due`, `project`, `course`, `added`;
  `assignment: moodle-<id>` on a task the course sync created for an
  assignment (it completes when the site shows the work as submitted).
- The `^t-…` suffix is a stable id so the phone can tick a task without
  ambiguity.
- Completing a task moves its line to `tasks-done.md` with `(done: <date>)`.

### 3.4 Daily log (`daily/YYYY-MM-DD.md`)

Append-only notes of the day, newest at the bottom, each with a time:

```markdown
# 2026-11-20

- 09:12 [owner] Capture: idea for the example project — try a simpler layout.
- 14:30 [assistant] Answered a question about example-course deadlines.
- 21:05 [owner] Note: slept badly, tired all day.
```

The assistant adds a line when something worth remembering happened in a
conversation. It never edits earlier lines.

### 3.5 Inbox (`inbox.md`)

Quick captures from the phone land here as dated lines when it is not obvious
where they belong. They are sorted into the right files later (section 6).

### 3.6 Index (`INDEX.md`)

The only file that is always loaded. At most 200 lines:

```markdown
# Index (updated 2026-11-20)

## Right now
- Three open tasks due this week → tasks.md
- Active: example-project (demo before end of term)

## Projects
- [[projects/example-project]] — active — one-line description

## Courses
- [[courses/example-course]] — one-line description

## People
- [[people/example-person]] — who they are in one line

## Notes
- [[notes/example-topic]] — one-line description

## Map
- [[map/example-project]] — `~/path/to/folder`
```

One line per file. If the index would exceed the limit, the least recently
updated entries are folded into a "see also" line per section; the files
themselves stay.

## 4. How Claude reads it

At the start of every conversation that goes through the bridge:

1. The bridge passes the memory to headless Claude **explicitly** (it does
   not rely on automatic discovery, which the CLI may change): the contents
   of `~/brain/CLAUDE.md`, `INDEX.md`, `profile.md`, the open part of
   `tasks.md`, and today's and yesterday's daily logs are added to the
   prompt context, and `~/brain` is made readable.
2. Everything else is opened on demand: Claude follows `[[links]]` from the
   index, or searches the folder by text, when the question needs it.
3. For work inside one of the owner's folders, Claude first reads that
   folder's `map/` file, then the folder itself.

Budget: the always-loaded part should stay under roughly 500 lines in total,
so it costs little on every prompt.

## 5. How it grows

| Event | What is written | Where |
|---|---|---|
| Quick note from the phone | one dated line | `inbox.md` (or the daily log) |
| Quick task from the phone | one task line | `tasks.md` |
| Task ticked | line moved | `tasks-done.md` |
| The owner states a lasting fact or preference in chat | one `[owner …]` fact line | the matching topic file or `profile.md` |
| Something notable happens in a conversation | one line | today's daily log |
| A new folder is allowed | new map file | `map/` |
| Moodle sync (later) | `[external:moodle …]` lines, deadlines as tasks | `courses/…`, `tasks.md` |

Rules for every write:

- One logical change = one git commit in `~/brain`, with a short message
  saying what changed and why. The bridge commits its own writes; headless
  Claude commits when it edits memory.
- A new topic file is added to `INDEX.md` in the same commit.
- Text from outside sources is stored only as tagged `external:` fact lines
  or in the daily log. It is never copied into `CLAUDE.md`, `INDEX.md` or
  `profile.md`, so a malicious document cannot plant instructions that are
  loaded every time.

## 6. Consolidation (nightly idea)

Daily logs and the inbox grow without limit; the curated files should stay
short and current. A consolidation run turns the first into the second:

1. Read the inbox and the daily logs since the last run.
2. Propose a list of **operations**, not rewritten files: *add* a fact to a
   topic file, *supersede* an old fact, *file* an inbox line into a topic
   file, *create* a topic file, *update* an index line. Each operation names
   the log line it came from.
3. Apply the operations, then check: no existing fact line disappeared, the
   index is within its limit, `external:` text did not enter always-loaded
   files. If a check fails, nothing is written.
4. Commit once, under a separate git author name ("lifeos-consolidation"),
   so the run is easy to recognise and to undo with one `git revert`. If
   nothing changed, no commit is made.

The run is a separate headless Claude call with access to `~/brain` only, no
shell and no network tools, and a cost limit.

**How it is triggered is part of this approval** (it spends plan usage
without the owner present and lets a model edit the memory):

- **A. Manual only** — runs when the owner asks ("tidy the memory").
- **B. Propose, then approve** *(recommended)* — runs nightly, but only
  writes its list of operations to a file; the owner approves from the phone
  and then it is applied.
- **C. Automatic nightly** — runs and commits by itself; the owner reviews
  afterwards in the git history.

**Decided: B.** The run proposes, the owner approves, then it is applied. It
may be switched to C later if it proves itself; that would be a new decision.

**Inferred facts in the proposal (#83).** The proposal may contain *add*
operations for `[inferred <date>]` facts, shown to the owner like every
other operation; only the approved ones are written, through the same check
as every fact writer (`bridge/src/lifeos_bridge/facts.py`: existing topic
files only, one line, appended, never over or beside an `[owner …]` fact
with the same text). The consolidation run itself is not built yet.

## 7. Search

Start with plain text search over the folder (Claude's own file search is
enough for a memory of this size). If that becomes too slow or imprecise, add
a local full-text index that is rebuilt from the files and kept out of git.
Embedding-based search is a separate open decision.

## 8. Privacy and safety

- `~/brain` has no git remote and is never pushed or copied anywhere.
- Nothing from it is written to the project repository, an issue, a log or a
  notification.
- No secrets in memory files.
- Deleting means moving to `.trash/` and committing.

## 9. The owner's answers (2026-11-20)

1. **Layout:** approved, with `calendar/` added now.
2. **Consolidation trigger: B** (propose, then approve); C possibly later.
3. **Inferred facts:** the assistant may write `[inferred …]` facts on its
   own, always tagged as such, so that it gets to know the owner well. An
   inferred fact never overrides an `[owner …]` fact, and the owner can
   correct or remove any of them.
