# Coach design

Status: **design.** Nothing here is built. It depends on the memory design
([`memory-design.md`](memory-design.md), still under the owner's review) and
on one open decision (section 8). Examples are generic placeholders.

The owner's request (see `decisions.md`): the assistant should not only
answer. It should ask the owner short questions about their work, learn from
the answers, use what it learned to prioritise tasks and give daily advice,
and nudge the owner with notifications, without becoming noise.

## 1. What the owner sees

| Where | What |
|---|---|
| Today, top | **Advice card**: what to focus on today, and one line of why |
| Today | **Top three**: the three most important tasks, each with a one-line reason |
| Today | The tasks that belong to today |
| Tasks | All open tasks in priority order; the first few shown, the rest behind "show all" |
| Chat | **Coach questions**: short questions from the assistant, marked as such, with "Answer" and "Skip" |
| Notifications | **Nudges**: a few per day at most, never at night |

## 2. Principles

1. **The owner decides, the coach suggests.** Priorities are an ordering and
   a reason, never a lock. The owner can reorder, and that is itself a signal
   the coach learns from.
2. **Every suggestion carries its reason** in plain words ("due today; two
   other tasks wait for it"). No unexplained scores on screen.
3. **Ask little.** One open question at a time, skippable, never repeated
   once answered or dismissed.
4. **Calm by default.** Hard limits on nudges are enforced on the computer,
   not left to the model's judgement.
5. **Everything learned stays in the memory folder**, readable and editable
   by the owner as plain text. Nothing is learned silently elsewhere.
6. **Never wait for the model on screen.** The app shows the last prepared
   result with its age; fresh results are prepared in the background.

## 3. The daily plan

One headless Claude Code run (read-only tools, the memory folder as its
folder) reads the open tasks, due dates, the daily logs of the last days and
the coach's notes, and returns one small JSON object:

```json
{"advice": {"text": "…", "why": "…"},
 "top_three": [{"task": "t-20270115-01", "reason": "…"}],
 "order": ["t-20270115-01", "t-20270112-03"],
 "question": {"task": "t-20270109-02", "kind": "waiting", "text": "…"},
 "nudges": [{"task": "t-20270115-01", "not_before": "14:00", "text": "…"}]}
```

- The bridge **validates** it: every task id must exist and be open, the top
  three must be three different tasks from the order, texts have length
  limits. An invalid result is discarded and the previous plan stays, marked
  with its age. The model proposes; code checks.
- Tasks the plan does not mention (added later in the day) are appended to
  the order by a plain rule: overdue, then due date, then date added.
- **Ticking a task never needs a model run.** A finished task leaves the top
  three; the next task of the order moves up.
- The plan is stored outside the memory's history, in
  `~/.config/lifeos/coach/plan.json` (mode 600): it is derived, changes
  daily, and can be rebuilt at any time.
- When it is prepared is the owner's decision (section 8). The owner can
  always press refresh.

What the model is told to weigh, in this order: hard deadlines and their
lead time; tasks that block other tasks; what the owner said about a task
(section 4); how long it has waited; the size of the task against the time
of day; balance, so that one area does not starve the others.

## 4. Questions and learning

**When a question is asked.** The daily plan may propose at most one
question. Typical triggers: a task has waited long, a task was moved out of
the top three by the owner several times, a task has no clear next step, a
due date passed.

**Kinds.** `waiting` (why is this waiting?), `hard` (what is hard about
it?), `easy` (what would make it easy to start?), `next` (what is the first
step?), `still` (is this still wanted?).

**Flow.**

1. The question appears in Chat, marked "Coach", with "Answer" and "Skip".
   It can also arrive as a nudge that opens Chat.
2. The owner answers in their own words, or skips. A skipped question is not
   asked again for that task for two weeks; three skips in a row pause all
   questions for a week.
3. The answer is processed by a short run that may write only inside the
   memory folder, and only the lines below.

**What is stored, and where** (plain Markdown, in the memory folder, under
its own git like everything else there):

- `coach/log.md` — append-only: date, the question, the owner's answer in
  their own words. This is the source; nothing is paraphrased away.
- A line under `## Facts` of the matching topic file (project, course, or
  `profile.md` for general working habits), marked `[owner, <date>]` when the
  owner said it and `[inferred, <date>]` when the assistant concluded it,
  following the memory design's rule on inferred facts.
- Optional fields on the task line in `tasks.md`: `(blocked-by: …)`,
  `(next: …)`, `(effort: small|medium|large)`.

The `coach/` folder is an addition to the memory layout, of the kind that
document foresees ("later phases add their own folders"). Whether inferred
facts may be written at all is one of the open points of the memory design;
until it is answered, the coach stores only what the owner stated.

**The owner can always** read `coach/log.md`, correct or delete a fact, and
say in chat "forget what I said about …" (the line is moved to the trash
folder, as the memory rules require).

## 5. Nudges

A nudge is a short reminder about work: the next step of the top task, a
deadline that is close, or the open coach question.

Limits, enforced by the bridge regardless of what the plan proposes:

| Setting | Default | Range |
|---|---|---|
| Frequency | normal | off / low (1 per day) / normal (3 per day) |
| Quiet hours | 22:00–08:00 | any start and end |
| Minimum gap between nudges | 2 hours | fixed |
| Open coach questions | 1 | fixed |

- Nothing is sent in quiet hours; a nudge that falls into them is dropped,
  not queued for the morning.
- A nudge about a task that was ticked meanwhile is dropped.
- Session reminders the owner set explicitly and reply notifications are not
  nudges and do not count against the cap.
- **Delivery** is the push path of `security.md`, section 5.1: the push has
  no readable content; the app fetches the text through the encrypted
  channel and shows it. On the computer, `scripts/notify.sh` announces only
  that a nudge exists.
- Tapping a nudge opens the task, or Chat for a question.

## 6. Cost and privacy

- **Plan usage.** Each daily plan and each processed answer is one short
  headless run on the owner's subscription. How often the plan may be
  prepared without the owner asking is the decision in section 8.
- **Privacy.** Tasks, answers and learned facts are read by the model
  provider during a run, as in every Claude Code session, and are stored
  only on the owner's computer. Nothing of it goes to the relay or the push
  service in readable form, and nothing into this repository.
- **Tone** is fixed in the run's instructions: factual, short, no pressure,
  no streaks, no guilt. Advice follows the app's language setting.

## 7. Interfaces and build issues

Endpoints (final shape decided in the building issue):

| Endpoint | Purpose |
|---|---|
| `GET /coach/plan` | Advice, top three, ordered tasks, age of the plan |
| `POST /coach/plan/refresh` | Prepare a new plan now |
| `GET /coach/question` · `POST /coach/question/answer` · `POST /coach/question/skip` | The open question |
| `GET` / `PUT /coach/settings` | Frequency and quiet hours |

Build order:

| Issue | Content | Depends on |
|---|---|---|
| Bridge: task priorities and daily advice | sections 3 and 7 (plan) | memory structure, capture endpoints, memory wired into `/ask`, the decision in section 8 |
| Bridge: coach questions and learning | section 4 | priorities |
| Bridge: nudges with cap and quiet hours | section 5 | priorities, push |
| App: Today and prioritised Tasks | section 1 | app task screens, priorities |
| App: coach questions, nudges, settings | sections 1, 4, 5 | chat screen, questions, nudges |

## 8. Open decision for the owner

**How often may the assistant run on its own to prepare the plan?** Once in
the morning (recommended), up to three times a day, or only when the app is
opened. Raised as a human decision issue because it spends plan usage.

Defaults chosen without asking, because they are cheap to change in
settings and the owner asked for sensible ones: the nudge limits in section
5. They are logged in `decisions.md`.
