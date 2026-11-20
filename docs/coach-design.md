# Coach design

Status: **design, with the owner's decisions of 2026-11-20 applied**
(section 8). Nothing here is built. It builds on the approved memory design
([`memory-design.md`](memory-design.md)). Examples are generic placeholders.

The owner's request (see `decisions.md`): the assistant should not only
answer. It should ask the owner short questions about their work, learn from
the answers, use what it learned to prioritise tasks and give daily advice,
and nudge the owner with notifications, without becoming noise.

## 1. What the owner sees

| Where | What |
|---|---|
| Today, top | **Coach card**: one message from today's batch (advice, an observation, a question), a different one each time the app is opened |
| Today | **Top three**: the three most important tasks, each with a one-line reason |
| Today | The tasks that belong to today |
| Tasks | All open tasks in priority order; the first few shown, the rest behind "show all" |
| Chat | **Coach questions**: short questions from the assistant, marked as such, with "Answer" and "Skip" |
| Notifications | **The daily notification**: one message from today's batch; more only if the owner raises the setting; never at night |

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

**One scheduled run each morning** (owner's decision). A headless Claude
Code run (read-only tools, the memory folder as its folder) reads the open
tasks, due dates, the daily logs of the last days and the coach's notes, and
returns one small JSON object with **a small batch of coach messages**:

```json
{"messages": [
   {"id": "m1", "kind": "focus", "text": "…", "why": "…", "task": "t-20270115-01"},
   {"id": "m2", "kind": "observation", "text": "…", "why": "…"},
   {"id": "m3", "kind": "question", "question": "waiting", "text": "…", "task": "t-20270109-02"}],
 "notification": "m1",
 "top_three": [{"task": "t-20270115-01", "reason": "…"}],
 "order": ["t-20270115-01", "t-20270112-03"]}
```

- **The batch** holds about five to eight short messages: what to focus on
  and why, observations, encouragement tied to something concrete, at most
  one question (section 4).
- **One of them is the daily notification** (`notification`); the bridge
  sends it once, at the end of quiet hours or when the run finishes,
  whichever is later.
- **The others are shown one at a time at the top of the app**, a different
  one each time the app is opened: the app picks at random among the
  messages not shown most recently, so the same one does not appear twice in
  a row and all get their turn. **Opening the app never starts a model
  run**; the batch is fetched once and rotated on the phone. A message whose
  task was ticked meanwhile is dropped from the rotation.
- **Manual refresh** (pull down on Today) may start a new run and replaces
  the batch; refreshes are rate-limited (at most one run in ten minutes).

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
- If the computer was off in the morning, the run happens when the bridge
  next starts, once for that day.

What the model is told to weigh, in this order: hard deadlines and their
lead time; tasks that block other tasks; what the owner said about a task
(section 4); how long it has waited; the size of the task against the time
of day; balance, so that one area does not starve the others.

## 4. Questions and learning

**When a question is asked.** The morning batch may contain at most one
question; it takes its turn in the rotation at the top of the app and also
appears in Chat. Typical triggers: a task has waited long, a task was moved out of
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
  following the memory design's rule on inferred facts (the owner allowed
  inferred facts, always tagged).
- Optional fields on the task line in `tasks.md`: `(blocked-by: …)`,
  `(next: …)`, `(effort: small|medium|large)`.

The `coach/` folder is part of the approved memory layout.

**The owner can always** read `coach/log.md`, correct or delete a fact, and
say in chat "forget what I said about …" (the line is moved to the trash
folder, as the memory rules require).

## 5. Nudges

A nudge is one message of the morning batch, sent as a notification. By
default there is exactly one per day: the daily notification. If the owner
raises the frequency, further notifications are taken from the same batch;
they never need another model run.

Limits, enforced by the bridge regardless of what the batch proposes:

| Setting | Default | Range |
|---|---|---|
| Frequency | 1 per day | off / 1 per day / up to 3 per day |
| Quiet hours | 22:00–08:00 | any start and end |
| Minimum gap between nudges | 2 hours | fixed |
| Open coach questions | 1 | fixed |

- Nothing is sent in quiet hours. The daily notification waits for their
  end; additional nudges that fall into them are dropped.
- A nudge about a task that was ticked meanwhile is dropped.
- Session reminders the owner set explicitly and reply notifications are not
  nudges and do not count against the cap.
- **Delivery** is the push path of `security.md`, section 5.1: the push has
  no readable content; the app fetches the text through the encrypted
  channel and shows it. On the computer, `scripts/notify.sh` announces only
  that a nudge exists.
- Tapping a nudge opens the task, or Chat for a question.

## 6. Cost and privacy

- **Plan usage.** One short headless run each morning, one per processed
  answer, and one per manual refresh, on the owner's subscription. Opening
  the app costs nothing.
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
| `GET /coach/plan` | The message batch, top three, ordered tasks, age of the plan |
| `POST /coach/plan/refresh` | Prepare a new plan now |
| `GET /coach/question` · `POST /coach/question/answer` · `POST /coach/question/skip` | The open question |
| `GET` / `PUT /coach/settings` | Frequency and quiet hours |

Build order:

| Issue | Content | Depends on |
|---|---|---|
| Bridge: task priorities and daily advice | sections 3 and 7 (plan) | memory structure, capture endpoints, memory wired into `/ask` |
| Bridge: coach questions and learning | section 4 | priorities |
| Bridge: nudges with cap and quiet hours | section 5 | priorities, push |
| App: Today and prioritised Tasks | section 1 | app task screens, priorities |
| App: coach questions, nudges, settings | sections 1, 4, 5 | chat screen, questions, nudges |

## 8. Decisions

- **How often the assistant runs on its own (owner):** one scheduled run
  each morning that prepares a small batch of coach messages. One is sent as
  the daily notification; the others are shown one at a time at the top of
  the app on each opening, rotating with some randomness, without a model run
  per opening. Manual refresh may start a new run.
- **Inferred facts (owner):** allowed, always tagged.
- **Defaults chosen by the agent**, cheap to change in settings: quiet hours
  22:00–08:00, the two-hour gap, the morning run at 06:30 local time, five to
  eight messages per batch, one refresh run per ten minutes at most. They
  are logged in `decisions.md`.
