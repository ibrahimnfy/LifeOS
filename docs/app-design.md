# App concept

Status: **approved by the owner (2026-11-20); the app may be built.**
Navigation, chat behaviour, the screen list and the content of Today, Tasks
and Sessions are agreed, and the visual direction is variant **D3** of the
mockups in [`docs/design/mockups/`](design/mockups/), light theme by
default. Examples below are generic
placeholders.

The Android app is the owner's remote control and window into LifeOS. It is
a thin client: the computer does the thinking and holds the data, the phone
shows it, captures new input fast and reports phone activity.

## 1. Principles

1. **Capture in two taps.** Adding a note or a task must be faster than
   opening a notes app. It works without a connection and syncs later.
2. **One place to talk.** Chat is the main way to ask for anything; the other
   screens are fast views of things the assistant already knows.
3. **Honest about state.** Every screen shows clearly whether it is live,
   loading, showing saved data from earlier, or failed — and offers a retry.
   The computer may be asleep or unreachable; that is normal, not an error
   to hide.
4. **Calm.** No feeds, no badges competing for attention, no streak pressure
   on the activity screens. Numbers are shown plainly.
5. **Two languages, fully.** English and Turkish for every string, chosen in
   settings (default: follow the phone). Dates, numbers and durations are
   formatted for the chosen language. Assistant replies follow the same
   language.
6. **Private by design.** The phone is paired with the computer by scanning
   a QR code; the pairing secret is kept in the device's hardware-backed key
   store behind the app lock. Everything between phone and computer is
   end-to-end encrypted ([`security.md`](security.md)). Usage and sleep data
   go only to the owner's computer.
7. **Simple first.** A screen shows only what is needed now; things are added
   when the need is real.

## 2. Navigation

*Approved by the owner.* A bottom bar with four destinations, plus a capture button that is always
visible. Less-used screens live under "More" so the bar stays uncluttered.
In visual direction D the bar is slim and shows icons only (each with an
accessibility label), with the capture button in its centre.

```
┌──────────────────────────────────────────┐
│  Screen title                 ● status   │   ● = connection to the computer
│                                          │
│              (screen content)            │
│                                          │
│                                   ( + )  │   capture button, on every tab
├──────────────────────────────────────────┤
│   Today      Chat      Tasks      More   │
└──────────────────────────────────────────┘

More → Sessions · Notes · Projects · Calendar · Courses · Activity · Settings
```

Why this shape:

- **Today** is the start screen: it answers "what should I do now" without typing.
- **Chat** and **Tasks** are the two things used many times a day.
- **Capture** is a button, not a tab, because it is an action available
  everywhere, not a place.
- Sessions, Notes, Projects, Calendar, Courses and Activity are opened a few
  times a day at most; one extra tap is acceptable. Today links directly into each of them.

The connection dot in the top bar opens a small sheet: computer reachable or
not, last successful sync, and a shortcut to settings.

## 3. Screens

Each screen lists its purpose, content, states and the bridge endpoints it
needs. Endpoints marked *(planned)* do not exist yet; their final shape is
decided in the issue that builds them.

### 3.1 Today

*Content and layout set by the owner: as simple as possible, no events,
every section in its own clearly visible card.*

- **Purpose:** what to do today.
- **Content, top to bottom, three cards:**
  1. **Coach card** (gradient ring): one message from the morning's batch
     (what to focus on and why, an observation, a question); a different one
     each time the app is opened, without a new model run. **Tapping it
     opens the coach conversation** (section 3.2).
  2. **Assistant's picks:** the three most important tasks, chosen by the
     assistant, each with a one-line reason.
  3. **Today:** the tasks that belong to today. The card shows three tasks;
     the rest of today's tasks are reached by scrolling **inside the card**,
     so the screen itself stays short.

  Nothing else. Events, deadline lists, sleep and screen-time tiles live on
  their own screens.
- **Actions:** tick a task; tap a task to open it; pull to refresh (reads the
  plan and the agenda again, never starts a model run); **Rerun** on the
  coach card asks the computer for a fresh batch (a model run; refused within
  ten minutes of the last one).
- **States:** loading skeleton · saved copy with "prepared 2 h ago" when the
  computer is unreachable · empty ("Nothing for today") · no plan yet (tasks
  shown in date order, coach card hidden) · error with retry.
- **Endpoints:** the coach's plan ([`coach-design.md`](coach-design.md)),
  tasks.

### 3.2 Chat

- **Purpose:** ask the assistant anything; it runs on the computer.
- **Content:** conversation as message bubbles; assistant replies rendered as
  Markdown (code blocks, lists, tables); a composer with send button.
- **Behaviour:** a conversation continues the same session until the owner
  presses "New chat" in the top bar, also across closing and reopening the
  app (*confirmed by the owner*). While the computer works, a
  progress indicator shows elapsed time, with a cancel button. If streaming is
  available the answer appears as it is written. The owner may leave: a
  push notification arrives when the answer is ready.
- **Coach conversation:** one persistent conversation with the coach,
  **pinned at the top of the Chat tab** and opened by the coach card on
  Today (there is no fifth tab). It keeps itself short by compacting, and
  what it learns about the owner goes to the memory, so it improves over
  time. Its plan usage is shown in its header, separate from the morning
  run.
- **New chat:** starting a conversation asks in which folder it should run:
  the memory folder (default) or one of the allowed folders.
- **History:** opening a conversation loads only the last few exchanges
  (default 4); older ones load when the owner scrolls up. The computer keeps
  the full transcript; the phone does not store whole sessions.
- **While the assistant works:**
  - a second message is **queued** and sent when the run ends; queued
    messages are shown above the composer and can be edited or removed;
  - **Send now** (an explicit action on a queued message) interrupts the
    run and sends that message immediately;
  - **Cancel** stops the run. Both confirmations say plainly: "Changes the
    assistant has already made to files are kept."
- **"/" commands:** typing "/" opens the list of skills and commands of the
  session's folder, with completion; a recognised command is highlighted in
  the composer. Available only where the owner switched them on for that
  session (capable mode, allowed folder).
- **Model:** the top bar shows the session's model and effort; tapping it
  changes them for this session. The default (Opus, high effort) is in
  Settings.
- **Files:** when the assistant offers a file it appears as a **file card**
  (name, type, size) with a download button. Nothing is transferred until
  the button is tapped; the file is then saved to the system Downloads
  folder and the app keeps no copy. Over the size limit the card explains
  why it cannot be sent.
- **Questions from the assistant:** shown as cards with selectable options
  and a free-text answer, in the middle of a run; the run continues when
  the owner answers. (The owner decided to run the assistant through the
  Agent SDK, which supports this natively; until that is built, questions
  arrive as text with lettered options.)
- **Approvals in the middle of a run:** when the assistant is about to do
  something that needs approval (first of all an obvious deletion), the run
  pauses, a card shows exactly what will happen and where, and the run
  continues or is told no after the owner's tap.
- **Skill installs and other approvals:** an approval card shows exactly
  what is asked (for a skill: source, contents, warnings, target folder);
  nothing happens without the owner's tap.
- **Coach questions:** the assistant sometimes asks a short question of its
  own (why a task is waiting, what is hard or easy about it). Such a message
  is marked "Coach" and has "Answer" and "Skip"; at most one is open at a
  time. See [`coach-design.md`](coach-design.md).
- **States:** empty (three example prompts as chips) · sending · working ·
  answer · failed (message kept, "Retry") · computer unreachable (composer
  stays usable; the message is kept as a draft, never sent silently later).
- **Endpoints:** `POST /ask`.

### 3.3 Sessions

- **Purpose:** continue work, including sessions that were started in a
  terminal on the computer.
- **Content:** search field on top, then:
  1. **Pinned** sessions;
  2. the owner's own **groups**, each with its sessions;
  3. **By folder:** every session without a group, grouped automatically by
     its folder.

  A row shows title, folder name, last activity, an "in use" mark when the
  session is open on the computer (it cannot be resumed then), and a bell
  when it has a reminder.
- **Actions:**
  - tap → opens in the Chat layout with the folder in the header; sending a
    message resumes that session in its original folder;
  - long-press or row menu → **rename**, **pin / unpin**, **move to group**
    (or a new group), **remind me**, **reply notifications on / off**;
  - groups: create, rename, delete (the sessions stay).
- **Reminders:** "remind me about this session at <time>", with an optional
  note, once or repeating (daily, weekly). The notification opens the
  session.
- **Reply notifications:** when a run in a session finishes, a push arrives;
  on by default, switchable per session.
- **Rules:** only sessions in allowed folders are listed. Groups, titles and
  pins are kept by the bridge; Claude Code's own session files are never
  changed.
- **States:** loading · empty · in use · error with retry.
- **Endpoints:** `GET /sessions`, `PATCH /sessions/{id}`, `/session-groups`,
  `/reminders`, `POST /sessions/{id}/messages`; history, queue, cancel,
  commands and models as in [`chat-design.md`](chat-design.md).

### 3.4 Capture

- **Purpose:** get a thought out of the head in seconds.
- **Content:** a bottom sheet over the current screen: one text field,
  keyboard already open, a switch between **Note** and **Task**; for a task an
  optional due date.
- **Behaviour:** saving closes the sheet and shows a short confirmation.
  Without a connection the item is stored on the phone, shown as "waiting to
  sync", and sent when the bridge is reachable again.
- **Also reachable from:** a home-screen shortcut and the share menu of other
  apps (share text or a link into LifeOS).
- **Endpoints:** add note, add task *(planned, Phase 3)*.

### 3.5 Tasks

*Prioritisation set by the owner: the list can grow large.*

- **Purpose:** see and tick off what has to be done, most important first.
- **Content:** **Most important:** the first few tasks in the assistant's
  priority order (about five). Below them one full-width button **"Show all
  (n)"**, which opens the complete list in the same order, with filters
  (overdue, today, upcoming, no date, done). Each row: checkbox, title, due
  date, source (e.g. a course name when it came from a deadline).
- **Actions:** tick (the round check), add (through Capture), edit title and
  date (tap the rest of the row; `PATCH /tasks/{id}` through the outbox),
  move a task up or down (the coach learns from it; not built yet).
- **States:** loading · saved copy · empty ("All clear") · no plan yet (date
  order) · error with retry; ticks made offline are queued like captures.
- **Endpoints:** list tasks, complete task, add task *(planned, Phase 3)*;
  priority order from the coach's plan.

### 3.6 Notes

- **Purpose:** find, read and tidy what was captured.
- **Content:** captured notes, newest first, each with its first lines, date
  and where it was filed (inbox, a project, a course, a topic). Search field
  on top; filter chips for the place.
- **Actions:** open a note to read it in full; edit its text; move it to a
  project, course or topic; add a new note (through Capture).
- **States:** loading · saved copy · empty ("Nothing captured yet") · no
  search results · error with retry; edits made offline are queued like
  captures and marked "waiting to sync".
- **Endpoints:** list, search, read and update notes (`GET /notes?query=`,
  `GET /notes/{id}`, `PATCH /notes/{id}`; only inbox captures can be edited,
  and a capture's id changes with its text). Built: list with search, open,
  edit a capture, waiting captures on top. Not built yet: filter chips by
  place, moving a note.

### 3.7 Projects

- **Purpose:** see where each tracked project stands.
- **Content:** list of projects with status (active, paused, done) and the
  date of the last activity. A project page shows: a short summary and
  status; recent activity (latest notes, sessions and changes); open tasks of
  the project; where it lives on the computer (folder name from the context
  map).
- **Actions:** tick a task; open a related note or session; **"Ask about
  this project"** opens Chat with the project as context (and, where the
  folder is allowed, runs in the project's folder).
- **States:** loading · saved copy · empty ("No projects tracked yet") ·
  error with retry.
- **Endpoints:** projects list and project detail from the memory *(planned,
  Phase 3)*; tasks; sessions.

### 3.8 Calendar

- **Purpose:** see the schedule and add events.
- **Content:** agenda list by default (grouped by day), with a month strip on
  top to jump to a date. Deadlines appear as all-day markers.
- **Actions:** add and edit an event (title, start, end, note).
- **States:** loading · saved copy · empty day · error with retry.
- **Endpoints:** list, create, update events *(planned, Phase 4)*.

### 3.9 Courses

*Structure set by the owner. The section is called "Courses", never after
the platform the data comes from.*

- **Purpose:** courses, materials, announcements, assignments and grades
  from the university's learning platform, kept current without asking.
- **Courses screen, top to bottom:**
  1. **Top bar:** the title, the **refresh** action and **"All grades"**
     (an icon button next to refresh).
  2. **Summary card:** the upcoming deadlines of all courses (next two
     weeks, nearest first), and nothing else. *(Opening a deadline's
     assignment detail comes with the assignment detail screen.)*
  3. **Course list:** each course with its next deadline and **a marker
     when it has new content** since the owner last opened it; below it the
     time of the last sync.
- **All grades:** one screen with the grades of all courses, grouped by
  course.
- **Course page, four tabs:**
  - **Materials:** sections and files (name, type, size, date; new ones
    marked), and the course's video and external links.
  - **Announcements:** newest first.
  - **Assignments:** due date and status.
  - **Grades:** the grade items of the course.
- **Downloads:** materials are **listed, not stored or embedded**. Tapping
  download fetches the file without any model run and saves it to the
  phone's Downloads folder; the app keeps no copy. The file comes directly
  from the university site over HTTPS, with a one-time address the computer
  sends through the encrypted channel (no relay quota is used); if that
  fails, the computer's copy is sent through the relay. After a download the
  file shows **Open**, which opens it from Downloads in the phone's app for
  its type (view intent; a clear message when no app can open it).
- **Staying current:** the computer syncs every 6 hours and on the manual
  **refresh** action here; new files, announcements, deadlines or grades
  arrive as a notification and appear in the lists by themselves.
- **Actions:** refresh; download; "Ask about this course" opens Chat in the
  course's folder.
- **States:** loading · saved copy with last sync time · empty · "Not
  connected yet" · sign-in needed (with the renew action) · error with
  retry.
- **Endpoints:** the course endpoints of the bridge (summary, list with
  markers, materials, announcements, assignments, grades, download address,
  refresh).

### 3.10 Activity

- **Purpose:** an honest picture of sleep and phone use.
- **Content:** two tabs.
  - **Sleep:** last night (bed time, wake time, duration); a 7/30-day bar
    chart of duration with a line for the average; bars from the automatic
    fallback (last screen-off → first unlock) are drawn hatched and labelled
    "estimated". Tap a night to enter or correct times by hand.
  - **Screen time:** today's total; a 7/30-day bar chart; top apps of the
    selected day as horizontal bars; number of unlocks.
- **Morning prompt:** when last night's sleep has not been entered, the app
  posts one silent notification early in the morning (default 05:00, time
  configurable, can be switched off), so it is waiting when the phone is
  first picked up. It is scheduled on the phone itself: no polling, no
  always-running service, no network.
- **States:** permission missing (explains why usage access is needed, with a
  button to the system setting) · not enough data yet · normal.
- **Endpoints:** daily upload of usage and sleep *(planned, Phase 6)*. Charts
  are drawn from data stored on the phone, so they always work offline.

### 3.11 Settings

- **Connection:** paired / not paired, last contact, "Pair with computer"
  (scan the QR code the computer shows, compare the six-digit code), "Unpair
  this phone", "Test connection" with a clear result.
- **App lock:** biometric or device credential; lock timeout.
- **Language:** follow phone / English / Türkçe.
- **Appearance:** light (default) / dark / follow phone.
- **Assistant:** default model and effort for new conversations (default:
  Opus, high); number of exchanges loaded when a conversation opens.
- **Coach:** nudge frequency (off / low / normal), quiet hours (default
  22:00–08:00), questions on / off.
- **Notifications:** which kinds the phone shows: answers ready, session
  reminders, coach nudges, questions waiting for the owner, session reports;
  the morning sleep prompt and its time.
- **Activity:** usage-access permission status, daily sync on/off, last sync.
- **About:** app version, bridge version, link to the project.

### 3.12 First run

Three short steps, skippable and repeatable from settings: choose language →
pair with the computer (QR code) → optional usage-access permission with a
plain explanation. The app is usable for offline capture even if pairing is
skipped.

### 3.13 Notifications

All notifications that come from the computer arrive through the push
service and work while the app is closed. A push never contains readable
text: the app is woken, fetches the content through the encrypted channel
and builds the notification itself ([`security.md`](security.md), section
5.1).

| Kind | Opens | Limits |
|---|---|---|
| Answer ready (a run finished) | that conversation or session | per-session switch |
| Session reminder | that session | set by the owner |
| Coach nudge | the task, or Chat for a question | daily cap, quiet hours, frequency setting |
| Question waiting for the owner, session report | the item | — |
| Morning sleep prompt (local, silent) | sleep entry | once per morning |

## 4. Shared behaviour

| Situation | What the owner sees |
|---|---|
| Loading | Skeleton placeholders in the shape of the content; no blocking spinners |
| Computer unreachable | Top-bar dot turns amber; screens show their saved copy with its age; captures queue |
| Not paired, or pairing revoked | A full-width notice with a button to settings; nothing is retried automatically |
| Request failed | Inline message in plain words plus "Retry"; technical detail behind "Details" |
| Long-running answer | Elapsed time and cancel; the owner may leave the screen and is notified when done |
| Empty | One short sentence and the single most useful action |

Accessibility: text follows the system font size; all touch targets are at
least 48 dp; charts have a text summary; colour is never the only signal
(icons or labels accompany status colours); full TalkBack labels.

## 5. Visual direction

The owner chose none of the first three proposals (A, B, C below, kept for
the record) and asked for a new one, **D**, which is now the working
direction and is refined from its mockups in
[`docs/design/mockups/`](design/mockups/) (open `index.html` in a browser;
light and dark, English and Turkish).

### D. Soft tones with colour where it matters *(working direction)*

The first mockup of D was plain black and white; the owner found the
structure right but the look flat and too stark. D is now mocked up in three
variants, **D1, D2, D3**, to be compared side by side (light and dark).

- **Structure (accepted):** layout conventions familiar from mainstream
  photo-sharing apps, structure and proportions only: a slim icon-only bottom
  bar with the capture button in its centre, a compact top bar with a bold
  title and one or two line icons, list rows with a round mark on the left,
  hairline dividers, generous spacing, full-width rounded buttons, the system
  sans-serif.
- **Base:** a slightly soft white in light mode; a deep blue-black or
  charcoal in dark mode; soft grey card surfaces; subtle dividers.
- **Colour where it matters:** a vivid blue for primary buttons and links;
  red for notification badges and counts; a warm multi-colour gradient (own
  tones in the pink / orange / purple range) as a ring or highlight on
  featured elements: the coach card, pinned sessions, the active state.
- **Chosen: D3**, the balanced variant (neutral soft greys in light mode,
  blue-tinted charcoal in dark mode, a gradient from violet through pink to
  orange, medium ring). **Light is the default theme**; the light/dark
  switch stays in settings. The coach card and the colours stay as mocked
  up. Tokens: the mockups' README.
- **Variants that were compared:** D1 cool (blue-black dark mode, blue-led accent, thin ring);
  D2 warm (warm off-white, charcoal, gradient-led accent, thick ring); D3 in
  between. Exact tones and contrast ratios are listed in the mockups'
  README; body text meets the usual contrast recommendation in all six.
- **Own designs only:** icons, buttons and tones are made for this project.
  No third-party logos, assets, gradients, icon sets or product names
  anywhere in the repository.

Earlier proposals, **not chosen**. All four share: Material 3 components,
light and dark themes, generous spacing, large readable type, edge-to-edge
layout, subtle motion (short fades and slides, nothing bouncy).

### A. Calm paper

- **Feel:** a quiet notebook. Warm off-white background in light mode, deep
  warm grey in dark mode; one muted accent colour (deep teal) used sparingly
  for the capture button, links and the current tab.
- **Type:** a humanist sans-serif for the interface, a serif for the Today
  greeting and screen titles; monospace for code in chat.
- **Shapes:** softly rounded cards with hairline borders instead of shadows.
- **Charts:** single accent colour, thin grid lines, direct labels.
- **Why it was proposed:** the app is opened many times a day for short moments;
  a low-contrast-of-attention, text-first look stays pleasant, makes long
  assistant answers easy to read and ages well.

### B. Material You

- **Feel:** native and familiar. Colours come from the phone's wallpaper
  (dynamic colour), tonal surfaces, standard Material 3 shapes and type.
- **Why choose it:** least custom design work, always matches the rest of the
  phone. **Trade-off:** looks like many other apps; charts need extra care to
  stay legible with arbitrary wallpaper colours.

### C. Dark console

- **Feel:** a focused tool. Near-black background, high-contrast text, one
  bright accent (amber or green), monospace for headings and numbers.
- **Why choose it:** fits the "remote control for my computer" idea, great at
  night. **Trade-off:** long reading in chat is more tiring; the light theme
  becomes a second-class citizen; calendar and courses feel less at home.


## 6. Technical notes for the build

- Kotlin, Jetpack Compose, Navigation Compose, single activity.
- All UI text in `values/strings.xml` and `values-tr/strings.xml`.
- Local storage on the phone (Room) for the capture queue, saved copies of
  lists, and usage and sleep history.
- Application id `dev.lifeos.app`.
- Connection: outbound HTTPS to the relay only; messages end-to-end
  encrypted with a libsodium binding; pairing secret protected by the
  Android Keystore with user authentication; no address or token is typed.
- Push: Firebase Cloud Messaging data messages; the Firebase settings file
  is injected at build time from the private config folder and is
  git-ignored.
- Background work (queued captures, daily activity upload) with WorkManager;
  the morning sleep prompt with a single scheduled alarm, not a service.
- One screen per issue where possible; each ships with its states (loading,
  saved copy, empty, error) and both languages.

## 7. Review status

| Point | Status |
|---|---|
| Bottom bar **Today · Chat · Tasks · More** | approved |
| Chat keeps one conversation until "New chat" | confirmed |
| Screen list | Notes and Projects added under More |
| Today: advice card, top three, today's tasks; no events | set by the owner |
| Tasks: prioritised, the rest behind "show all" | set by the owner |
| Sessions: groups, rename, pin, reminders, reply notifications | set by the owner |
| Coach: questions in chat, nudges with limits | set by the owner; design in `coach-design.md` |
| Today in cards, "Assistant's picks", scrolling Today card; Courses with summary, all grades and four tabs; coach conversation pinned in Chat; folder choice for new chats | set by the owner (2026-11-20) |
| Chat: lazy history, queue / send now / cancel, "/" commands, model per session, file cards, questions | set by the owner; design in `chat-design.md` |
| Visual direction | **D3 approved**; light theme by default, switch in settings |
