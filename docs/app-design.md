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
- **A tab always opens its root screen** (*set by the owner, #166*): tapping
  the selected tab on a sub-page goes back to its root, and switching tabs
  never brings back the sub-page that was open there before. The Chat tab
  therefore opens on its list, also right after the app starts; the
  conversation that was open last is its "current chat".
- **"+" offers note, task and new chat**, in this order (*set by the owner,
  #166*): the capture sheet's switch reads Note · Task · New chat; New chat
  opens a blank chat at once and takes along what was already typed.

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
- **Actions:** tick a task; tap a task (also a pick) to open it, as on Tasks
  (section 3.5); pull to refresh (reads the
  plan and the agenda again, never starts a model run); **Rerun** on the
  coach card asks the computer for a fresh batch (a model run; refused within
  ten minutes of the last one).
- **States:** loading skeleton · saved copy with "prepared 2 h ago" when the
  computer is unreachable · empty ("Nothing for today") · no plan yet (tasks
  shown in date order, coach card hidden) · error with retry.
- **Endpoints:** the coach's plan ([`coach-design.md`](coach-design.md)),
  tasks.

### 3.1a Home: physics tiles (#192, built)

*Chosen by the owner from the home prototypes (prototype C). It replaces the
list of 3.1 as what the Today tab shows; the list stays one tap away and
selectable as the home (Settings → Home style).*

- **Purpose:** every feature as a live tile on a free field, to play with.
- **Tiles, in "Tidy" order** (also the screen reader's order), each with its
  icon, its name and one live line read from the screen behind it:
  Coach (today's message) · Today's tasks (how many are left; opens the list
  of 3.1) · Tasks (open tasks) · Calendar (today's events) · Chat (an answer
  on its way) · Notes (how many) · Courses (days to the next deadline) ·
  Activity (last sleep) · Projects (how many). A line is shown only when the
  computer or its saved copy gave the value: nothing while loading, locked,
  not paired or failed.
- **Play:** drag a tile and let go: it flies on with the finger's speed,
  slows down and bounces off the edges of the field (with a short flash).
  Flying tiles knock into each other softly; tiles may overlap, and the last
  one touched is on top. Two fingers resize a tile (76–210 dp wide; the
  second finger may land anywhere). A tap opens the tile's screen; a touch
  that catches a flying tile only stops it.
- **Tidy up** slides all tiles into a calm grid of equal size; **Reset**
  brings back the first layout. Both can be undone from the message they
  show. The round arrow in the top bar reads the tiles' lines again (there
  is no pull to refresh on a field that is dragged).
- **Kept in sight above the field**, only while they apply: not paired, a
  new release, a memory proposal. The coach's card with Rerun, its open
  question, the assistant's picks and ticking tasks are on the list behind
  "Today's tasks"; a tapped coach notification opens that list directly.
- **Stored on the phone:** each tile's place (as a fraction of the field, so
  it fits a rotation or another screen), size and stacking order, saved when
  the tiles come to rest. A field that shrinks (landscape, split screen)
  moves the tiles inside and gives the layout back when it grows again.
- **Look:** the D3 tokens and the seven tones with a sci-fi layer: a field a
  little darker than the page with faint grid and scan lines, corner marks,
  captions in the monospace font, each tile glowing in its tone's ink, the
  coach in the gradient ring. Tiles are opaque, so text never has another
  tile's text behind it (contrast: `HomeColorsTest`).
- **Motion and battery:** the physics is a pure step function
  (`home/TilePhysics.kt`, tested on the JVM). Frames are requested only
  while something moves; the loop ends when all tiles rest and at once when
  the screen is hidden. With the system's "remove animations" nothing flies
  or slides: a tile stays where it is dropped and Tidy places at once.
- **Accessibility:** each tile is one button for a screen reader ("name.
  live line", action "Open"), with the custom actions "Make larger" and
  "Make smaller" in place of the pinch; "Tidy up" and the classic home stand
  in for dragging. The smallest tile is 76 × 59 dp (above the 48 dp target)
  and grows with the font size; a small tile drops its live line and keeps
  icon and name. A keyboard can focus a tile and open it with Enter.
- **As an action:** the command bar's `set_home_style` (tiles / classic)
  changes the setting; `open_screen today` opens the home.
- **Manual check:**
  1. Open the app: the Today tab shows the field with nine tiles. Throw one:
     it flies, bounces off the edges and comes to rest; throw it harder and
     it goes farther.
  2. Pinch a tile larger and smaller; drop one on another (they overlap, the
     touched one is on top). Tap one: its screen opens; Back returns to the
     field with every tile where it was.
  3. "Tidy up": a 3 × 3 grid; "Undo" in the message puts the tiles back.
     "Reset": the first layout.
  4. Close the app from the recent apps and open it again: places, sizes and
     the order of overlapping tiles are as they were left. Rotate the phone:
     all tiles stay on the field.
  5. Phone settings → Accessibility → "Remove animations" on: a dragged
     tile stays where it is dropped, Tidy places at once. TalkBack on: the
     tiles are read in the Tidy order, double tap opens, the actions menu
     offers larger and smaller.
  6. Settings → Home style → Classic list: Today is the list of 3.1 again;
     "Physics tiles" brings the field back. Check light and dark, and
     English and Turkish.

### 3.2 Chat

- **Purpose:** ask the assistant anything; it runs on the computer.
- **Content:** conversation as message bubbles; assistant replies rendered as
  Markdown (code blocks, lists, tables); a composer with send button.
- **Behaviour:** a conversation continues the same session until the owner
  presses "New chat" in the top bar, also across closing and reopening the
  app (*confirmed by the owner*; it is the Chat tab's "current chat", the
  tab itself opens on its list). While the computer works, a
  progress indicator shows elapsed time, with a cancel button. If streaming is
  available the answer appears as it is written. The owner may leave: a
  push notification arrives when the answer is ready.
- **Coach conversation:** one persistent conversation with the coach,
  **pinned at the top of the Chat tab** and opened by the coach card on
  Today (there is no fifth tab). It keeps itself short by compacting, and
  what it learns about the owner goes to the memory, so it improves over
  time. Its plan usage is shown in its header, separate from the morning
  run.
- **New chat:** opens a blank chat at once; there is no folder picker
  (*set by the owner, #166*). Its first message is sent without a folder and
  the computer gives the chat its own new, empty folder (#163). The owner
  moves to other work by asking; an existing conversation continues in its
  own folder, and "Ask about this project" starts in the project's folder.
  If blank chats are switched off on the computer (its chat folder cannot be
  used), the failure says so with the computer's reason.
- **Moving by asking (#164, #168):** an answer may carry a `switch`. With
  one clear match the app opens that conversation at once; with several it
  shows a short card in the conversation (title, folder, last activity) and
  a tap opens one; a new conversation in a named folder opens empty there,
  with the folder in the header, and its first message names that folder.
  Only the answer to a message sent and received on this phone switches by
  itself: an answer that was followed after a lock or a lost connection
  shows its switch as a card, a replayed one and the history never switch;
  a switch not acted on within a minute (the owner had left) becomes the
  card too.
  The marker lines behind it never show, not even while the answer is
  written. When the owner asked for work in that folder too, the new chat
  starts with that request as its first message, sent at once (offline it
  waits in the composer); the new chat says where it was started from and
  the old answer says "Continued in <folder>", each a tap to the other chat
  (#187).
- **Moving a blank chat's files (#163):** the assistant can propose to move
  the chat's files into a project; an approval card like the skill and MCP
  cards lists the files with their sizes and the target, with **Allow** /
  **Don't allow**. Afterwards it says what was moved and which files stayed;
  a refusal of the computer (a name taken in the target, files changed, no
  audit log) keeps the card waiting with the reason.
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
- **"/" commands:** typing "/" opens the list of skills and commands the
  session can use, with completion; each entry says whether it is the
  folder's own or one of the owner's personal ones, and a recognised command
  is highlighted in the composer. The folder's own are listed only where the
  owner switched them on for that session; the owner's personal ones (#162)
  in every session, once the computer's check for them has passed (capable
  mode, allowed folder). A personal entry whose name the folder also uses is
  completed as `/personal:<name>`.
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
  what is asked (for a skill: source, contents, warnings, target folder; for
  an MCP server: what would start, the outside-the-sandbox warning, and
  whether the approval is for this folder or, for a personal server, for
  every folder);
  nothing happens without the owner's tap. These cards wait for up to a day
  and are shown whenever the conversation is opened; an answer the computer
  refuses keeps the card open with the reason.
- **Answers that are written elsewhere:** the answer to a waiting message,
  and an answer the app stopped receiving (lock, lost connection), are
  followed live again when the conversation is on screen; see
  [`chat-design.md`](chat-design.md), section 7.4.
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
- **Content (#168):** search field on top, then:
  1. **Pinned** sessions;
  2. **By folder:** one coloured tile per folder, in the style of the
     Courses tiles (P3: the first tile wide, the others two by two, each in
     its colour with its icon and geometric ornament). All blank chats are
     one tile, **Chats**; the coach conversation has its own tile once it
     exists; then the memory folder and every allowed folder. A tile shows
     the folder's name, how many conversations it has and when the last one
     was. The colour is the bridge's stable colour number for that folder
     (`colour`), mapped to the app's palette, so a folder keeps its colour.

  Tapping a tile lists that folder's conversations, pinned ones first (back
  returns to the tiles); the coach's tile opens the coach conversation. A
  search lists every match across the folders. A row shows title, folder
  (a blank chat says "Chat" and shows the chat icon; any other shows its
  folder's first letter in the folder's colour), last activity, an "in use"
  mark when the session is open on the computer (it cannot be resumed
  then), and a bell when it has a reminder. Custom session groups were
  removed by the owner's decision; pinning stays.
- **Actions:**
  - tap → opens in the Chat layout with the folder in the header (a blank
    chat: "Chat"; any other: its folder label and a dot in its colour);
    sending a message resumes that session in its original folder;
  - long-press or row menu → **rename**, **pin / unpin**, **remind me**,
    **reply notifications on / off**,
    **use this folder's skills and commands** (per session, off by default)
    and **MCP servers** (the folder's and the owner's personal ones, each
    with its scope; list, approve one by one, revoke; an approved server
    runs outside the sandbox, and the sheet says so first; a personal
    server's approval covers every folder; a personal server the computer
    cannot show in full is "cannot be approved", without a button). The coach
    conversation's session has neither of the last two;
  - **New chat** in the top bar opens a blank chat.
- **Reminders:** "remind me about this session at <time>", with an optional
  note, once or repeating (daily, weekly). The notification opens the
  session.
- **Reply notifications:** when a run in a session finishes, a push arrives;
  on by default, switchable per session.
- **Rules:** only sessions in allowed folders and the bridge's own blank
  chats are listed. Titles and pins are kept by the bridge; Claude Code's
  own session files are never changed. The list holds at most 200 sessions
  (the bridge's limit); a folder with more says how many of how many are
  listed.
- **States:** loading · empty · in use · error with retry.
- **Endpoints:** `GET /sessions` (sessions with `folder_key`,
  `folder_label`, `kind`, `colour`; `folders` for the tiles),
  `PATCH /sessions/{id}`, `/reminders`, `POST /sessions/{id}/messages`;
  history, queue, cancel, commands and models as in
  [`chat-design.md`](chat-design.md).

### 3.4 Capture and the command bar

- **Purpose:** get a thought out of the head in seconds, or ask LifeOS to do
  something by typing or speaking (#185).
- **Content:** a bottom sheet over the current screen: one text field with a
  microphone, keyboard already open, then **Ask** · **Note** · **Task** ·
  **New chat** (#166, #185). Ask is the default (Note when text was shared
  from another app). For a note an optional **Remind me** (day, then time);
  for a task an optional due date and note. Note and Task save with Save;
  New chat closes the sheet and opens a blank chat at once, with what was
  typed in its composer (nothing is sent).
- **Ask (the command bar):** "Do it" sends the text to the computer
  (`POST /command`, docs/bridge.md), which turns it into actions of the
  action registry. Each done action gets one line ("Task added: Buy milk",
  "Note saved, reminder Tue 24 Nov, 10:00: Call the office") with **Undo**
  where it has one. An answer that opens something (a screen, a note, a
  chat, a course, a folder's new chat) closes the sheet, opens it, and shows
  the first line in a snackbar with Undo. An ambiguous request gets **one
  short question**: its options are buttons, and for the model's question an
  answer field. When the computer cannot be reached, the bar offers **Save
  as note** or **Save as task** (the outbox, as before); when an answer got
  lost on the way, **Ask again** repeats the same request id, so nothing is
  done twice; a request the computer could not turn into actions offers
  saving it as a note.
- **Speech:** the microphone starts the phone's own speech recogniser
  (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`) in the app's language
  (`tr-TR` or `en-US`) with `EXTRA_PREFER_OFFLINE`; the app needs no
  microphone permission and never receives audio, only the recognised text,
  which is added to the field for the owner to check before sending.
  **Where the audio goes:** to the phone's recognition service (on most
  phones Google's). "Prefer offline" is a hint: when the offline model for
  the language is installed the audio stays on the phone; otherwise the
  service may send it to its servers under its own terms. LifeOS sends only
  the text, through the relay, to the computer. A phone without a
  recogniser says so and the owner types.
- **Accessibility:** the microphone and every Undo are named for TalkBack
  ("Speak", "Undo: Task added: …"); answers and questions are announced
  (polite live region); every target is at least 48 dp.
- **Behaviour:** saving closes the sheet and shows a short confirmation.
  The item is saved on the phone and shown on every screen at once (Tasks,
  Today, Notes), marked "waiting to sync" until the bridge confirms it, then
  replaced by the stored item without flicker or duplicates (#121). It is
  sent in the background through the outbox, also later when the computer
  was off, with a phone-generated `client_id`, so an unclear send is simply
  repeated. No model run is involved in saving.
- **Also reachable from:** a home-screen shortcut and the share menu of other
  apps (share text or a link into LifeOS).
- **Endpoints:** `POST /notes` (with `client_id`, and `remind_at` for a
  note's reminder), `POST /tasks` (with `client_id`), `POST /command`,
  `POST /command/undo`.
- **A note's reminder (#185):** the computer notifies at that time with a
  fixed text; tapping the notification opens the note. The note screen shows
  the reminder ("Reminder: 24 Nov, 10:00").

### 3.5 Tasks

*Prioritisation set by the owner: the list can grow large.*

- **Purpose:** see and tick off what has to be done, most important first.
- **Content:** **Most important:** the first few tasks in the assistant's
  priority order (about five). Below them one full-width button **"Show all
  (n)"**, which opens the complete list in the same order, with filters
  (overdue, today, upcoming, no date, done). Each row: checkbox, title, due
  date, source (e.g. a course name when it came from a deadline).
- **Actions:** tick (the round check), add (through Capture), open (tap the
  rest of the row, *set by the owner, #167*): a task made from a course
  assignment opens that **assignment's page** (below the same tab); any
  other task opens its **card**, a bottom sheet, not a page: title, due
  date, note (the coach's next step; the memory has no other task note),
  course or project label, **Mark as done** and **Edit**. Edit turns the
  card into the form for title and date (`PATCH /tasks/{id}` through the
  outbox). A task captured on this phone that has not been sent yet opens
  the same card and can be edited and ticked offline. Move a task up or
  down (the coach learns from it; not built yet).
- **States:** loading · saved copy · empty ("All clear") · no plan yet (date
  order) · error with retry; ticks made offline are queued like captures.
- **Endpoints:** list tasks, complete task, add task *(planned, Phase 3)*;
  priority order from the coach's plan.

### 3.6 Notes

- **Purpose:** find, read and tidy what was captured.
- **Content:** captured notes, newest first, each with its first lines, date
  and where it was filed (inbox, a project, a course, a topic). Search field
  on top; filter chips for the place.
- **Actions:** open a note to read it in full; edit its text; delete it
  (#184); move it to a project, course or topic; add a new note (through
  Capture).
- **Deleting (#184):** "Delete" on the note screen, for captures and topic
  notes; a capture still waiting on the phone has a delete button in the
  waiting list. The note leaves the list at once and a snackbar offers
  **Undo** for six seconds (as for a tick); only then is the deletion queued
  in the outbox (kind `NOTE_DELETE`, sent like every other write, also
  later when the computer was off). On the computer the note goes to the
  memory folder's trash and can be restored. What waits on the phone: a
  capture that never left the phone is simply dropped from the outbox; one
  that may have reached the computer is deleted there right after it, by the
  id the computer gave it; an edit of the note that never left is dropped
  and the original note deleted; while an edit is on its way (or may have
  arrived) "Delete" is not offered, as for a second edit. A deletion that
  finds nothing (`404`) counts as done.
- **States:** loading · saved copy · empty ("Nothing captured yet") · no
  search results · error with retry; edits made offline are queued like
  captures and marked "waiting to sync".
- **Endpoints:** list, search, read, update and delete notes (`GET
  /notes?query=`, `GET /notes/{id}`, `PATCH /notes/{id}`, `DELETE
  /notes/{id}`; only inbox captures can be edited, and a capture's id
  changes with its text). Built: list with search, open, edit a capture,
  delete with undo, waiting captures on top. Not built yet: filter chips by
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
- **Course page, five tabs** (Resources since #172):
  - **Materials:** sections and files (name, type, size, date; new ones
    marked), and the course's video and external links.
  - **Announcements:** newest first.
  - **Assignments:** due date and status; each opens the assignment detail.
  - **Grades:** the grade items of the course.
  - **Resources:** what the computer found from the course's syllabus
    (`GET /courses/{id}/resources`, #128, #160), read only: each item's
    title (an HTTPS address opens in the browser) and one line, either
    "downloaded to the course's folder on the computer" or why it is only
    listed, in the app's own words (paid, not a document, too large, not
    downloaded yet, you already have this file, waiting until the resource
    folders are named, …; an unknown reason is "Listed only"). While the
    owner has not named the existing resource folders on the computer
    (`"folders_named": false`), one line above the list says that nothing is
    downloaded until then. Asking for a new run and fetching a downloaded
    resource to the phone are not built.
- **Assignment detail (#129, P3 in the course's colour):** a ticket with
  the due date and time, when it opened and the status, and a stub with the
  days left; the instructions (Markdown); the attached files (download and
  open like materials); the submission (status, closing time, grading
  time) and its task. Every upcoming deadline of the Courses screen opens
  it. Endpoints: `GET /courses/{id}/assignments`,
  `GET /courses/{id}/assignments/{aid}`. Not built: "Open the submission
  page" of the mockups and the submission format and grade rows (the
  bridge does not offer them yet).
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
- **Renewing the sign-in (#100, built):** one tap (the notice on Courses,
  Settings → Courses sign-in, or the "sign-in needs renewing" push) opens
  the site's own sign-in page in a browser tab; the app receives the
  `moodlemobile://token=…` address, checks it against the one-time
  passport and passes it sealed to the computer, which tests and stores
  the token. The app keeps no copy; the page is never automated.
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
- **Endpoints:** daily upload of usage (`POST /activity/usage`, built) and
  sleep (`POST /activity/sleep`, with #36). Charts are drawn from data stored
  on the phone, so they always work offline. The phone measures in the
  background once a day, but sends only while the app is unlocked and
  connected (the device secret is not available otherwise); each finished
  day is sent once. Built: both tabs (Sleep first), manual entry and
  correction, the estimate from screen-off to first unlock (hatched and
  labelled), the charts, and the morning question (one inexact alarm,
  replanned after it rings, after a restart and after a clock or time-zone
  change; a silent notification only while last night has no entry).

### 3.11 Settings

- **Connection:** paired / not paired, last contact, "Pair with computer"
  (scan the QR code the computer shows, compare the six-digit code), "Unpair
  this phone", "Test connection" with a clear result.
- **App lock:** biometric or device credential; lock timeout.
- **Language:** follow phone / English / Türkçe.
- **Appearance:** light (default) / dark / follow phone.
- **Home style (#192):** physics tiles (default) / classic list: what the
  Today tab shows (section 3.1a).
- **Assistant:** default model and effort for new conversations (default:
  Opus, high); number of exchanges loaded when a conversation opens.
- **Models (#124, built):** each model job of the computer with its model,
  effort, advisor and source, and its plan usage today and this week; what
  uses no model. Each job can be changed (model, effort, advisor, or back to
  the default) with `PUT /models/routing`, sent directly, not queued.
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

**Built (#38, owner's choice #136 = A):** Firebase data messages with one
sealed hint, opened on the phone with the push key alone (kept under a
second Keystore key that needs no confirmation, so it works while the app
is locked or closed; it opens hints only). Four Android channels, so a kind
can be muted in the system settings: **chat** (reply ready, question,
approval; one notification per session), **courses** (one notification per
sync, whatever changed), **coach**, **reminders** (reminders and notices).
The notification text is fixed per event kind, lock screen included; the
counts and items in the hint decide only what a tap opens, and a notice's
own title is shown inside the unlocked app. Taps open: a session → the Chat
tab for now (the session screens come with #33), one course → its page,
several → Courses, sign-in → Courses, coach → the coach screen, reminder
and notice → Today.

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

The owner chose none of the first three proposals (A, B, C below, kept here
for the record; no longer drawn in the mockups) and asked for a new one, **D**, which is now the working
direction and is refined from its mockups in
[`docs/design/mockups/`](design/mockups/) (open `index.html` in a browser;
light and dark, English and Turkish).

**Built (#134), the owner's mix from #133:** D3 made warmer with proposal
**P2** for the whole app (each course and project keeps one tone and one
line icon everywhere: tiles, task labels and check rings, pick ranks,
deadlines, the course page, files; section cards stay grey with one faint
watermark icon and a heading icon in the section's tone), except the
**Courses** section, which follows **P3** (tinted tiles of different sizes,
ticket-style deadlines, geometric ornaments) with P2's course colours and
icons. The coach card is unchanged. Tokens: the D3 base tokens plus seven
tones (card tint, second tint, ink; light and dark) in `ui/theme/Tokens.kt`;
a course keeps the colour and icon it was first given for as long as it
exists, a new course takes the next free colour, and no course is ever
re-coloured (#142; the assignment is stored on the phone and only
extended, see `data/Looks.kt` and [`app/README.md`](../app/README.md),
"Conventions"). Not drawn yet: the
assignment detail screen of the mockups.

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
  between. D1 and D2 are dropped and no longer drawn in the mockups.
- **Making D3 warmer (#132, proposals awaiting the owner's choice):** the
  coach card stays; the other cards get soft tints, icons and patterns.
  Three proposals on the D3 base are mocked up side by side: **P1** soft
  tints per section, **P2** a colour and icon per course with faint
  watermark icons, **P3** playful card shapes with small geometric
  illustrations. The mockups' README describes them and lists their tones
  and contrast ratios (all text pairs at least 4.5:1).
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
| Sessions: rename, pin, reminders, reply notifications | set by the owner |
| Coach: questions in chat, nudges with limits | set by the owner; design in `coach-design.md` |
| Today in cards, "Assistant's picks", scrolling Today card; Courses with summary, all grades and four tabs; coach conversation pinned in Chat | set by the owner (2026-11-20) |
| Tabs open their root; "+" offers note, task, new chat; new chat without a folder picker | set by the owner (2026-11-21, #166) |
| Sessions as coloured folder tiles; custom groups removed, pinning stays; moving between conversations by asking (one match switches, several a card) | set by the owner (2026-11-21, #168) |
| An assignment's task opens the assignment; other tasks open a bottom-sheet card | set by the owner (2026-11-21, #167) |
| Chat: lazy history, queue / send now / cancel, "/" commands, model per session, file cards, questions | set by the owner; design in `chat-design.md` |
| Visual direction | **D3 approved**; light theme by default, switch in settings |
