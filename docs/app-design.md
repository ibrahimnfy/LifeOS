# App concept

Status: **proposal, waiting for the owner's review.** No app code is written
before this is approved. Examples below are generic placeholders.

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
   The computer may be asleep or off the private network; that is normal, not
   an error to hide.
4. **Calm.** No feeds, no badges competing for attention, no streak pressure
   on the activity screens. Numbers are shown plainly.
5. **Two languages, fully.** English and Turkish for every string, chosen in
   settings (default: follow the phone). Dates, numbers and durations are
   formatted for the chosen language. Assistant replies follow the same
   language.
6. **Private by design.** The bridge token is stored encrypted on the device.
   Usage and sleep data go only to the owner's computer.

## 2. Navigation

A bottom bar with four destinations, plus a capture button that is always
visible. Less-used screens live under "More" so the bar stays uncluttered.

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

More → Sessions · Calendar · Courses · Activity · Settings
```

Why this shape:

- **Today** is the start screen: it answers "what matters now" without typing.
- **Chat** and **Tasks** are the two things used many times a day.
- **Capture** is a button, not a tab, because it is an action available
  everywhere, not a place.
- Sessions, Calendar, Courses and Activity are opened a few times a day at
  most; one extra tap is acceptable. Today links directly into each of them.

The connection dot in the top bar opens a small sheet: bridge reachable or
not, last successful sync, and a shortcut to settings.

## 3. Screens

Each screen lists its purpose, content, states and the bridge endpoints it
needs. Endpoints marked *(planned)* do not exist yet; their final shape is
decided in the issue that builds them.

### 3.1 Today

- **Purpose:** the day at a glance.
- **Content, top to bottom:** greeting with date · next events today · tasks
  due today and overdue · upcoming deadlines (next 7 days, courses included) ·
  last night's sleep and today's screen time so far (tap → Activity).
- **Actions:** tick a task; tap anything to open its screen; pull to refresh.
- **States:** loading skeleton · saved copy with "updated 2 h ago" when the
  computer is unreachable · empty ("Nothing scheduled today") · error with retry.
- **Endpoints:** daily agenda and deadlines *(planned, Phase 4)*, tasks
  *(planned, Phase 3)*; sleep and usage come from the phone itself.

### 3.2 Chat

- **Purpose:** ask the assistant anything; it runs on the computer.
- **Content:** conversation as message bubbles; assistant replies rendered as
  Markdown (code blocks, lists, tables); a composer with send button.
- **Behaviour:** a conversation continues the same session until the owner
  starts a new one ("New chat" in the top bar). While the computer works, a
  progress indicator shows elapsed time, with a cancel button. If streaming is
  available the answer appears as it is written.
- **States:** empty (three example prompts as chips) · sending · working ·
  answer · failed (message kept, "Retry") · computer unreachable (composer
  stays usable; the message is kept as a draft, never sent silently later).
- **Endpoints:** `POST /ask`.

### 3.3 Sessions

- **Purpose:** continue work that was started on the computer.
- **Content:** list of Claude Code sessions, newest first: title, folder
  name, last activity. Search field on top. Sessions currently open on the
  computer are marked "in use" and cannot be resumed.
- **Actions:** tap a session → it opens in the Chat layout with a header
  showing the folder; sending a message resumes that session in its original
  folder.
- **States:** loading · empty · in use · error with retry.
- **Endpoints:** `GET /sessions`, `POST /sessions/{id}/messages`.

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

- **Purpose:** see and tick off what has to be done.
- **Content:** sections Overdue · Today · Upcoming · No date · Done
  (collapsed). Each row: checkbox, title, due date, source (e.g. a course
  name when it came from a deadline).
- **Actions:** tick, add (through Capture), edit title and date, filter.
- **States:** loading · saved copy · empty ("All clear") · error with retry;
  ticks made offline are queued like captures.
- **Endpoints:** list tasks, complete task, add task *(planned, Phase 3)*.

### 3.6 Calendar

- **Purpose:** see the schedule and add events.
- **Content:** agenda list by default (grouped by day), with a month strip on
  top to jump to a date. Deadlines appear as all-day markers.
- **Actions:** add and edit an event (title, start, end, note).
- **States:** loading · saved copy · empty day · error with retry.
- **Endpoints:** list, create, update events *(planned, Phase 4)*.

### 3.7 Courses

- **Purpose:** courses, materials and deadlines from the university Moodle.
- **Content:** list of courses with the next deadline of each; a course page
  with sections, files and assignments; an assignment shows its due date and
  status.
- **Actions:** open a file (downloaded through the bridge); "Ask about this
  course" jumps to Chat with the course as context.
- **States:** loading · saved copy with last sync time · empty · "Moodle not
  connected yet" · error with retry.
- **Endpoints:** courses, sections, files, assignments, deadlines
  *(planned, Phase 5)*.

### 3.8 Activity

- **Purpose:** an honest picture of sleep and phone use.
- **Content:** two tabs.
  - **Sleep:** last night (bed time, wake time, duration); a 7/30-day bar
    chart of duration with a line for the average; bars from the automatic
    fallback (last screen-off → first unlock) are drawn hatched and labelled
    "estimated". Tap a night to enter or correct times by hand.
  - **Screen time:** today's total; a 7/30-day bar chart; top apps of the
    selected day as horizontal bars; number of unlocks.
- **States:** permission missing (explains why usage access is needed, with a
  button to the system setting) · not enough data yet · normal.
- **Endpoints:** daily upload of usage and sleep *(planned, Phase 6)*. Charts
  are drawn from data stored on the phone, so they always work offline.

### 3.9 Settings

- **Connection:** bridge address and token (pasted or scanned from a QR code
  shown on the computer), "Test connection" button with a clear result.
- **Language:** follow phone / English / Türkçe.
- **Appearance:** follow phone / light / dark.
- **Activity:** usage-access permission status, daily sync on/off, last sync.
- **Notifications:** which kinds the phone shows (questions waiting for the
  owner, session reports, reminders).
- **About:** app version, bridge version, link to the project.

### 3.10 First run

Three short steps, skippable and repeatable from settings: choose language →
connect to the computer (address + token, test) → optional usage-access
permission with a plain explanation. The app is usable for offline capture
even if the connection step is skipped.

## 4. Shared behaviour

| Situation | What the owner sees |
|---|---|
| Loading | Skeleton placeholders in the shape of the content; no blocking spinners |
| Computer unreachable | Top-bar dot turns amber; screens show their saved copy with its age; captures queue |
| Wrong or missing token | A full-width notice with a button to settings; nothing is retried automatically |
| Request failed | Inline message in plain words plus "Retry"; technical detail behind "Details" |
| Long-running answer | Elapsed time and cancel; the owner may leave the screen and is notified when done |
| Empty | One short sentence and the single most useful action |

Accessibility: text follows the system font size; all touch targets are at
least 48 dp; charts have a text summary; colour is never the only signal
(icons or labels accompany status colours); full TalkBack labels.

## 5. Visual direction

Three directions to choose from. All share: Material 3 components, light and
dark themes, generous spacing, large readable type, edge-to-edge layout,
subtle motion (short fades and slides, nothing bouncy).

### A. Calm paper *(recommended)*

- **Feel:** a quiet notebook. Warm off-white background in light mode, deep
  warm grey in dark mode; one muted accent colour (deep teal) used sparingly
  for the capture button, links and the current tab.
- **Type:** a humanist sans-serif for the interface, a serif for the Today
  greeting and screen titles; monospace for code in chat.
- **Shapes:** softly rounded cards with hairline borders instead of shadows.
- **Charts:** single accent colour, thin grid lines, direct labels.
- **Why recommended:** the app is opened many times a day for short moments;
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

The owner may also mix: for example direction A with the dynamic colours of B.

## 6. Technical notes for the build

- Kotlin, Jetpack Compose, Navigation Compose, single activity.
- All UI text in `values/strings.xml` and `values-tr/strings.xml`.
- Local storage on the phone (Room) for the capture queue, saved copies of
  lists, and usage and sleep history; token in encrypted storage.
- Background work (queued captures, daily activity upload) with WorkManager.
- One screen per issue where possible; each ships with its states (loading,
  saved copy, empty, error) and both languages.

## 7. Open points for the review

1. Is the bottom bar right: **Today · Chat · Tasks · More**? (Alternative:
   Calendar instead of Tasks on the bar.)
2. Which visual direction: **A**, **B**, **C**, or a mix?
3. Should Chat keep one continuing conversation by default, or start a new
   one each time the app is opened?
4. Anything missing from the screen list, or anything to drop for a first
   version?
