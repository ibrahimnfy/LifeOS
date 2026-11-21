# LifeOS Android app

The Android client of LifeOS: Kotlin, Jetpack Compose, a single activity,
English and Turkish. The concept is in
[`docs/app-design.md`](../docs/app-design.md); the look is visual direction
D3 made warmer, as the owner chose in #133: proposal **P2** everywhere and
**P3** for the Courses section, from
[`docs/design/mockups/`](../docs/design/mockups/).

What exists now: the **app skeleton** (navigation, D3 theme, language and
appearance), and the **connection to the computer**: pairing by QR code, the
end-to-end layer of [`docs/security.md`](../docs/security.md), the relay
client and the app lock. On top of that, **Today, Tasks, Capture, Calendar,
Notes and Courses work on the owner's real data** through that connection,
with saved copies for when the computer is unreachable and an outbox for
writes made offline. **Activity** shows the phone's screen time (usage
access) and sends it to the computer once a day. **Chat** sends a message
to the assistant on the computer and shows the answer as it is written, and
**Sessions** lists the Claude Code sessions there and continues one. The
coach conversation is still a placeholder.

## Requirements

- A full **JDK 21 or newer** (with `javac`; a runtime-only Java package is
  not enough). The JDK bundled with Android Studio works. Point `JAVA_HOME`
  at it if `javac` is not on the `PATH`.
- The **Android SDK** with platform `android-36` and build-tools `36.1.0`,
  found through `ANDROID_HOME`. `local.properties` is not used and must not
  be committed. The build never downloads SDK components
  (`android.builder.sdkDownload=false`); install missing ones yourself.
- No system Gradle: the committed wrapper downloads Gradle and verifies its
  checksum (`distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`).

## Build

From this folder:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug            # the debug APK
./gradlew testDebugUnitTest lint   # unit tests and lint
```

The unit tests load libsodium for the computer's own platform from the
lazysodium-java test dependency; nothing has to be installed for them.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Lint reports go to `app/build/reports/`; warnings do not fail the build,
errors do.

To keep every build file inside the repository (nothing written to the home
folder), set two more variables; `.tools/` at the repository root is
git-ignored:

```sh
export GRADLE_USER_HOME="$(git rev-parse --show-toplevel)/.tools/gradle"
export ANDROID_USER_HOME="$(git rev-parse --show-toplevel)/.tools/android-user"
```

`ANDROID_USER_HOME` is where the Android Gradle plugin keeps the debug
signing key. An APK signed with a different debug key cannot be installed
over an existing one (`adb install -r` reports a signature mismatch), so use
the same setting for every build, or uninstall the app first.

## Install

With USB debugging enabled and the phone connected:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.lifeos.app/.MainActivity
```

## Release build (daily use)

Daily use runs the release build: the test aids of debug builds (see below)
are not in it, and it is not debuggable. It is signed with the owner's
release key, which lives only in the private settings folder:

```
~/.config/lifeos/release/
├── release.keystore         the key (PKCS12)
└── keystore.properties      storeFile, storePassword, keyAlias, keyPassword
```

Both files are mode 600 in a mode 700 folder and never enter the
repository. There is no backup by the owner's choice: if the key is lost, the
app is uninstalled, the new build installed and the phone paired again (all
data stays on the computer). Without `keystore.properties` a release build is
produced unsigned and cannot be installed.

The version code is the number of commits, so every newer build installs
over an older one.

```sh
./gradlew --no-daemon assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Switching from a debug build to the release build once:** the two are
signed with different keys, so Android refuses to install one over the
other. Uninstall the debug build first (`adb uninstall dev.lifeos.app`), then
install the release build and pair again (on the computer
`lifeos-bridge pair "<name>"`, then revoke the old pairing with
`lifeos-bridge devices revoke <id>`, the id shown by `lifeos-bridge devices`). After that, updates install over each other and
keep the pairing.

**Later updates come to the phone (#138):** on the computer
`uv run lifeos-bridge release --build` (in `bridge/`) builds, signs and
registers the next release and wakes the phone. The app asks for the newest
release whenever it connects and when that push arrives; Today (and
Settings) then show **Update ready** with version and size. One tap fetches
the file through the encrypted file transfer into the app's cache folder,
checks its size and SHA-256 and that it is signed with the certificate the
computer announced and with this app's own, and only then hands it to
Android's installer (a PackageInstaller session; Android shows its own
confirmation). The file is deleted as soon as the installer has it, and any
leftover at the next start. Android asks once to allow LifeOS to install
apps ("Install unknown apps"); the card explains why and opens that setting.
A debug build, signed with another key, says "This build cannot update
itself" instead of downloading anything; so do phones older than Android 9,
which cannot read the signer of the file.

Check that a build has no test aids: the strings of the debug twin
(`lifeos.test.*`) must not occur in the APK's code, and the manifest must not
be debuggable:

```sh
unzip -p app/build/outputs/apk/release/app-release.apk 'classes*.dex' | grep -c -a 'lifeos.test.'   # 0
$ANDROID_HOME/build-tools/36.1.0/aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/release/app-release.apk | grep -c debuggable                          # 0
```

## Project layout

```
app/                         Gradle root (this folder)
├── gradle/libs.versions.toml    all versions, in one place
├── gradle/wrapper/              Gradle wrapper
├── settings.gradle.kts, build.gradle.kts, gradle.properties
└── app/                         the application module (dev.lifeos.app)
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/kotlin/dev/lifeos/app/
        │   ├── MainActivity.kt      the single activity
        │   ├── LifeOsCore.kt        pairing, app lock and connection state
        │   ├── crypto/              libsodium interface and the sealed-message format
        │   ├── pairing/             QR text, pairing flow, storage, Keystore vault
        │   ├── relay/               mailbox (REST + stream), relay account, requests
        │   ├── data/                API calls and models, saved copies, outbox, downloads
        │   ├── sync/                sending as soon as the phone is online again (network callback, background job)
        │   ├── activity/            screen time: usage events, per-day store, daily measurement, upload
        │   ├── lock/                lock timeout, system confirmation dialog
        │   ├── settings/            theme, language, lock timeout (DataStore)
        │   └── ui/
        │       ├── theme/           D3 colour tokens and the seven tones, Material 3 mapping, type
        │       ├── icons/           the app's own line icons
        │       ├── components/      top bar, bottom bar, rows, cards, QR scanner
        │       ├── nav/             routes and the navigation host
        │       └── screens/         Today, Chat, Tasks, Calendar, Notes, Courses, More,
        │                            Sessions, Settings, Pair, Lock
        ├── main/res/values/         English strings (default)
        ├── main/res/values-tr/      Turkish strings
        ├── debug/                   test aids of debug builds (see below)
        ├── release/                 their empty twin for release builds
        └── test/                    JVM unit tests and the shared test vectors
```

Conventions:

- Every user-visible text is a string resource, in both languages. No text
  is hard-coded in Kotlin.
- Colours come only from the tokens in `ui/theme/Tokens.kt`: the D3 base
  tokens (every Material 3 colour role is mapped from them and dynamic
  colour is off; `warn`, the amber of "computer not reachable", was added
  with the connection dot) and the seven soft **tones** of the warmer
  design (sky, teal, violet, peach, sand, mint, rose; each a card tint, a
  second tint and an ink, light and dark, contrast checked in the mockups
  README), plus the fixed opacities of `ToneAlpha` for watermarks and
  ornaments.
- **Colour carries meaning (P2).** A course or project keeps one tone and
  one line icon everywhere: its tile, the label and check ring of a task,
  the rank of a pick, a deadline, its page and its files. The rule is the
  owner's (`data/Looks.kt`): a course keeps the colour and icon it was first
  given for as long as it exists; a new course (several at once: in id
  order) takes the first free colour and icon in palette order (the least
  used once all seven are taken); a course that is gone frees its colour;
  no other course is ever re-coloured. The assignment is stored on the phone
  (`looks/looks.json`, only extended, deleted on unpair) and updated from
  each course list (an empty list changes nothing). Projects and unknown
  course names get a tone from a fixed hash of their name and the folder
  icon.
  Section cards (picks, Today, tasks) stay grey with one faint watermark
  icon and a heading icon in their section's tone (picks violet, Today
  peach, tasks mint, deadlines rose). The coach card is unchanged.
- **Courses (P3):** tinted cards with geometric ornaments drawn in code
  (`ui/components/Tones.kt`: waves, grid, dots, arcs, triangles, circles;
  each course has the one that goes with its icon), deadline tickets, tiles
  of different sizes and a tight corner on some cards.
- Icons are drawn for this project (`ui/icons/LifeIcons.kt`). No icon
  library is used.
- Touch targets are at least 48 dp, icon-only controls have content
  descriptions, text sizes are in `sp`; results that appear later (comparison
  code, test result, errors) are announced to screen readers.
- `crypto/`, `relay/` (except the two HTTP classes), the pairing flow and
  `data/` (except `Downloads.kt`) use no Android class, so they are covered
  by JVM unit tests.

### Versions

The newest stable versions were taken that work with the installed SDK
(platform 36). Compose, Lifecycle, Navigation and Core are therefore one
release line behind their newest versions, which need platform 37; lint
reports this as `GradleDependency` warnings. Raise `compileSdk` and those
versions together once platform 37 is installed. For the same reason OkHttp
is 5.4.0 (5.5 needs platform 37).

| Library | Version | Used for |
|---|---|---|
| lazysodium-android (+ JNA 5.17.0 as `aar`) | 5.2.0 | libsodium in the app |
| lazysodium-java (tests only) | 5.2.0 | the same libsodium calls in JVM unit tests |
| OkHttp | 5.4.0 | HTTPS to the relay |
| kotlinx-serialization-json (+ compiler plugin) | 1.13.0 | JSON and the typed models of the bridge's answers |
| lifecycle-viewmodel-compose | 2.10.0 | one loader per endpoint and screen |
| kotlinx-coroutines | 1.11.0 | background work |
| androidx.biometric | 1.1.0 | fingerprint / screen-lock confirmation |
| CameraX | 1.7.0 | camera preview and frames for the QR code |
| ZXing core | 3.5.4 | reading the QR code on the phone |

There is no Firebase SDK and no Google services plugin: the relay is reached
with plain HTTPS requests.

## What is in the app

- Bottom bar: Today, Chat, Tasks, More as icon-only items with a gradient
  bar under the active one, and the capture (+) button in the centre. A
  tab always opens its root screen (#166): tapping it again on a sub-page
  goes back to the root, and coming from another tab never brings back the
  sub-page that was open there before.
- Top bar: bold title and the connection dot: grey (not paired), hollow
  (connecting), green (the computer answered), amber (not reachable). It
  opens Settings.
- **Today:** three cards from the coach's plan and the agenda. The coach
  card shows one message of the plan, another one each time the app is
  opened (tapping it opens the coach conversation, see Chat);
  "Assistant's picks" is the plan's top three with their reasons; "Today"
  lists overdue and today's tasks, three visible and the rest by scrolling
  inside the card. Pulling down only reads the plan and the agenda again; it
  never starts a model run. The coach card's **Rerun** button asks the
  computer for a new plan (a model run of a few minutes there); the card
  shows the progress and, when a plan was made in the last ten minutes, the
  computer's refusal. Without a plan the card offers **Prepare a plan**.
  The coach's open question (#76) has its own card under it, marked
  "Coach · Question": the owner answers in their own words (**Send
  answer**; the computer logs it, then files it in a run of a minute or
  two) or **Skip**s it (three skips in a row pause questions for a while).
  The question is left out of the rotating card. Tapping a coach
  notification (the daily note or a nudge) opens Today with the message it
  named on the coach card.
- **Tasks:** with a plan, "Most important" (the first five of the coach's
  order) and "Show all (n)", which opens the whole ordered list with filters
  (overdue, today, upcoming, no date). Without a plan, the open tasks by
  date. The round check ticks a task: it is hidden at once and the tick can
  be undone for a few seconds; then it is sent. Tapping the rest of the row
  (here, on Show all and on Today, also a pick) opens the task (#167): a
  task the course sync made from an assignment (`assignment: moodle-<id>`,
  its course folder ending in the course id) opens that assignment's page
  below the same tab; any other task opens its card, a bottom sheet with
  its title, due date, the owner's own note (#170, under "Note"), the
  coach's next step (a separate block, under "Next step"), its course or
  project, **Mark as done** and **Edit**. Edit turns the card into the form
  for the title, the due date (or no date) and the note (one paragraph, up
  to 500 characters, empty for none); the change is shown at once with
  "Waiting to sync" until the computer confirmed it. The note is sent only
  when it was changed, so a title or date edit never touches it. A task
  captured on this phone that has not been sent yet opens the same card and
  can be edited (it then leaves with the new title, date and note) and
  ticked (the tick waits behind it and names the computer's id once the
  task arrived). Capturing a task under "+" can include a note.
- **Chat** (the tab): the coach's conversation pinned on top, the chat in
  progress, the three most recent sessions with **All
  sessions**, and **New chat**. New chat (here, in a conversation's and the
  Sessions top bar, and under "+") opens a blank chat at once, with no
  folder to pick (#166): its first message is sent without a folder, so the
  computer gives the chat a folder of its own (a bridge without that answers
  in its default folder). "Ask about this project" still starts the chat in
  the project's folder. A conversation shows the owner's messages on the right and the
  assistant's answers on the left (paragraphs, headings, lists, code blocks,
  bold, italic, inline code and web links are rendered by the app's one
  Markdown reader, which also shows assignment descriptions; the text can be
  selected), "Working…"
  with the time since the message was sent, and the answer as it is written.
  **The same conversation is continued until New chat** (or until another
  session is opened), also after the app was closed: it is the "Current
  chat" on the Chat tab, and opening it reads its last few messages from
  the computer. The folder is in the header (a blank chat says "Chat";
  another conversation names its folder with a dot in the folder's colour).
  - **Moving by asking (#164, #168):** asked to open another conversation,
    the answer carries a switch: one clear match opens that conversation at
    once, in its own folder (the coach's on the coach screen); several give a
    short card "Which conversation?" (title, folder, last activity) to tap
    one; "start a chat in <folder>" opens an empty conversation there, with
    the folder in the header. Only an answer sent and received on this phone
    switches by itself; an answer followed after a lock shows its switch as
    the card, and reading a conversation again never switches. The marker
    lines never show, not even while the answer is written.
  - **Moving a blank chat's files (#163):** when the assistant proposes to
    move the chat's files into a project, a card **Move files** lists every
    file with its size and the target, with **Allow** / **Don't allow**; it
    waits up to a day, like a skill install. Afterwards it says how many
    files went where, and which stayed. A refusal of the computer (a name
    taken in the target, files changed since the card, no audit log) keeps
    the card open with the reason. When blank chats are switched off on the
    computer, a new chat's first message says so, with the computer's reason.
  - **A silent run and a lost answer (#148):** a run that says nothing for a
    while sends keep-alives, so the app listens only 75 seconds without any
    sign before it asks the computer instead (the run goes on there). A new
    conversation goes out under an id the app chose (`new_session_id`): when
    the first answer is lost, the app asks under that id whether the message
    arrived, follows the run if it did, and otherwise offers **Retry**, which
    the computer recognises as the same start and never runs twice. A card
    closes when the computer says that it no longer waits for it (answered
    on another device, out of time, run ended).
  - **Notifications:** a notification about a session (answer ready,
    question, approval) opens that conversation, read afresh, so a waiting
    card is on screen at once; the coach's session opens the coach's screen.
  - **Earlier messages:** a conversation opens with its last few exchanges.
    Scrolling to the top (or tapping "Earlier messages") reads the page
    before, and so on; the screen keeps its place.
  - **While the assistant works** the composer stays usable: a message sent
    then waits on the computer and is sent when the run has ended. The
    waiting messages are listed above the composer ("Waiting to be sent");
    each can be **edited**, **removed** or **sent now**, which interrupts the
    run and sends that message at once. **Stop** next to "Working…" ends the
    run. Both ask first and say: changes the assistant has already made to
    files are kept. After a stop the waiting messages are on hold until the
    owner's next message, "Send them", or **Send now** on one of them (then
    nothing is interrupted, so it does not ask). **The answer to a waiting
    message is written on screen like any other** (#154): the message moves
    from the line into the conversation, with "Working…", Stop, and question
    and approval cards.
  - **Files:** a file the assistant offers appears as a card under its
    answer (name, kind, size) with a download button. Nothing is transferred
    before the tap; the file then goes into the phone's Downloads folder,
    with the progress on the card, and **Open** hands it to the phone's app
    for its kind, as for course files. A file the computer does not send (too
    large, outside the allowed folders, hidden) has a card that says why. An
    offer is good for 15 minutes, also after the conversation was read again
    (a lock, another session and back): the card then keeps its download for
    the time that is left. After that the card asks to request the file again.
  - **Model and effort:** the header shows the folder, the model and the
    effort of the conversation; the pill above the composer ("opus · high")
    opens the choice: the default for chat sessions, or one of the models and
    effort levels the computer offers. It applies from the next message of
    that conversation. **The default** for all chat sessions is changed under
    Settings → Models ("Chat sessions").
  - **"/" commands:** a message that starts with "/" lists the skills and
    commands the session can use above the composer, narrowed while the
    name is typed, each with its description, argument hint, warnings and
    whether it is the folder's or the owner's personal one; a tap completes
    the name. A recognised command is coloured in the composer. The
    folder's own appear only where they are switched on for the session and
    usable; the owner's personal ones (#162, #172) whenever the computer
    offers them (`personal.enabled`), also with the switch off. A personal
    one whose name the folder also uses is completed as
    `/personal:<name>`. Otherwise one line says why nothing is listed (the
    computer's check has not passed, the folder's switched off and no
    personal ones, none at all, not one this conversation can use, needs
    "Changes allowed").
  - **The skills switch (#97):** the session's sheet (the dots next to the
    pills, or a long press on a session) has **Use this folder's skills and
    commands**, off unless switched on, per session, with what it means in
    plain words: only in messages with "Changes allowed", only the skills
    and commands of the folder's own `.claude` folder (the owner's personal
    ones are offered in every such message without the switch, #162), and
    a skill cannot widen what the assistant may do. Under it the sheet says
    how many the folder has, how many personal ones the owner has (or that
    they cannot be used right now), and when the computer's check for
    skills has not passed, that the folder's cannot be used right now.
  - **Skill installs (#97):** when the assistant proposes a skill, a card
    shows **where it comes from, what is in it and where it would go**
    before anything can be approved: the source address, what the skill says
    about itself, every file with its size, warnings in plain words (shell
    lines, tools it asks for, files that are not notes) and the target
    folder. While the computer still downloads it into its quarantine there
    is no "Install", only "Don't install"; a skill that could not be fetched
    says why and can only be put away; a source that is not HTTPS is shown as
    refused. **Nothing is installed without the tap on Install.** The card
    waits for up to a day, also across messages, runs and restarts, and is
    shown whenever the conversation is opened.
  - **MCP servers (#153, #172):** the session's sheet has **MCP servers**:
    the servers the folder's `.mcp.json` declares, then the owner's personal
    (user-scope) servers, each with its scope ("This folder's server", "Your
    personal server: one approval covers every folder"), its state (not
    approved, approved, changed since the approval, or for a personal one
    "cannot be approved", without any button) and what would start:
    the command with its arguments or the address, and only the *names* of
    environment variables and headers. **The warning that an approved server
    runs outside the sandbox** (an ordinary program with the network and the
    whole home folder) stands first, in an amber frame, in the list and on
    every approval card; an approved server has the amber frame too.
    **Review and approve** asks the computer for the approval card of exactly
    that definition and shows it at once (**Approve**, **Don't approve**);
    left unanswered, the card waits for a day in the conversation like a
    skill install. A personal server's card asks for every folder and says
    so. **Revoke approval** takes an approval back. An answer that
    used servers says which under its text, and that they run outside the
    sandbox.
  - **Read-only or "Changes allowed"** (the pill above the composer): every
    conversation starts read-only. Switched on, its next messages ask the
    computer for the capable mode, in which the assistant may change files
    and run commands inside the computer's guardrails. It is off again after
    a new chat, another session or a lock of the app.
  - **Approvals:** when the assistant is about to do something that needs
    the owner's yes (delete files, a force or history-rewriting operation,
    push, install, send a message, use the network), the run waits and a
    card shows what kind of action it is, **exactly the command that will
    run and the folder it runs in**, with **Allow** and **Don't allow** and
    the time left. A deletion has a red frame and a warning. Nothing runs
    without the tap on Allow; when the time is over the card says that it
    counted as "no".
  - **Questions (option cards):** when the assistant asks with its question
    tool, the run waits and a card shows each question (up to four) with its
    choices as rows to tap (one choice, or several where the question allows
    it) and a field for the owner's own words, with **Send** and **Skip**
    and the time left. Send is possible once every question has an answer.
    Afterwards the card keeps what was answered. Without an answer in time
    the assistant goes on without one. The cards use only the bridge's
    interactions (the Agent SDK's documented question callback); a client
    that could not show them would get the question as text instead.
  - **The coach conversation (#114):** one conversation with the coach that
    goes on, opened from the coach card on Today and from the pinned entry
    of the Chat tab (the same conversation; there is no fifth tab). It looks
    and works like any other conversation (earlier messages, waiting
    messages, stop, questions), but it is read-only and always on the
    coach's model, so it has neither the model pill nor "Changes allowed";
    the top bar says "Read-only" and the model. **Its plan usage** stands
    under the top bar in plain numbers, today's runs, tokens and estimated
    cost of this conversation and, apart from it, of the morning run; a tap
    (or the bars icon) opens the same for the week, how full the
    conversation is, the summary of what came before and **Tidy up now**.
    The computer tidies a long conversation up by itself before the next
    message (what matters goes to the memory, a summary stays, a new
    session continues): the screen then says that it tidies up first, and
    marks the place with one line that says what was done (summarised, and
    how many files of the memory got something). Both come from the answer's
    own stream (#156): signs of life while the tidying up runs, then
    `compacted` with the files, the summary and the new session; nothing is
    guessed from a changed session id while the answer is heard. **Stop**
    stays there meanwhile; the computer never stops the tidying up, and the
    app then says so ("…is being tidied up right now, and that is never
    stopped. Your message is answered as soon as it is done."). Which session the conversation is, the
    computer says each time (`GET /coach/chat`); the phone stores nothing
    about it. In the lists of sessions the coach's session opens this
    screen, and it is left out of "Recent".
- **Sessions** (the clock in Chat, or under More, #168): a field that
  filters by title and folder (a search lists every match across the
  folders), the pinned sessions, then **By folder**: one coloured tile per
  folder, in the style of the Courses tiles (the first wide, the others two
  by two, each in its folder's colour with its icon and ornament). All blank
  chats are one tile, **Chats**; the coach conversation has its own tile
  (it opens the coach conversation); then the memory folder and every
  allowed folder. A tile shows how many conversations the folder has and
  when the last one was. The colour is the bridge's `colour` number of that
  folder, so a folder keeps its colour. Tapping a tile lists that folder's
  conversations, pinned ones first; back returns to the tiles. "Working"
  marks a session the computer is answering right now, "Open on the
  computer" one that a terminal or the desktop app holds there
  (`running_here` and `open_elsewhere` of `GET /sessions`). A row's mark is
  the chat icon for a blank chat (which says "Chat" instead of a folder) and
  the folder's first letter in its colour for any other. Tapping one opens
  it in Chat; the next message continues it in its own folder. A session
  that is open on the computer is refused there when the message is sent,
  and the app says so and offers **Retry**. **New chat** stays in the top
  bar.
  - **Titles and pins (#74):** a long press on a session, or the dots at the
    end of its row (also on the Chat tab's recent sessions), opens what can
    be done with it: **Rename** (and back to the title the computer found),
    **Pin / Unpin**. Custom groups were removed (#168). All of it is kept on
    the computer (`PATCH /sessions/{id}`); Claude Code's own session files
    are not touched. A change is sent directly: without a connection
    nothing changes and a line says so.
  - **Reminders and reply notifications (#75):** the same sheet has
    **Remind me**: the session's reminders to come, and a new one with its
    day, time, **Once / Every day / Every week** and an optional note. The
    computer keeps and fires them (`/reminders`); a session with a reminder
    to come has a small bell in its row. The notification says only
    "Reminder"; a tap opens that session (the reminder is looked up on the
    computer, also after it has fired) and shows the note in one line.
    **Notify me when an answer is ready** is the per-session switch for the
    notification after a finished run (`notify_replies`, on unless switched
    off); questions and approvals are announced either way. In a
    conversation the same sheet opens from the dots next to the pills above
    the composer. The coach conversation's session has neither the skills
    switch nor the MCP servers in its sheet (it is always read-only).
- **Capture** ("+"): one text field and, in this order, **Note**, **Task**
  (with an optional due date) and **New chat** (#166). New chat closes the
  sheet and opens a blank chat at once; what was typed goes into its
  composer. Also reachable from the home-screen shortcut (long-press on the
  app icon) and from other apps' "share" (text or a link).
- **Calendar** (under More): the agenda by day from a week ago to two months
  ahead, a month strip to jump, adding and changing events (all day, timed,
  several days). Course deadlines are ordinary events here.
- **Notes** (under More): the computer's notes, captures newest first and
  then the topic notes, each with its first lines and date, and a search
  field (the computer searches; while it cannot be reached, the saved copy is
  searched). Notes captured on this phone that are not sent yet are on top.
  Tapping a note opens it in full; a capture can be edited there (topic notes
  are read-only for now). The computer gives an edited capture a new id, so
  the note screen closes after saving and the list shows the new text.
- **Courses** (under More): the top bar has the title with the time of the
  last sync under it, **All grades** (a text button) and the refresh action
  (starts a sync on the computer and waits for it). Below: the deadline
  card with the upcoming deadlines of the next two weeks and nothing else
  (the nearest three as tickets with the days left, further ones as rows; a
  deadline that has passed since the saved copy was made is left out), then
  "All courses" as tiles (the first one wide, the others two by two, each in
  its course's colour with its icon, ornament, next deadline and the marker
  for new content). A course page has a header card in the course's colour
  with the next deadline, and Materials, Announcements, Assignments,
  Grades and Resources (what the computer found from the syllabus, each
  downloaded or with the reason it is only listed, #172); files and links
  carry the course's colour. Every deadline ticket
  or row and every assignment of the Assignments tab opens the
  **assignment detail**: a ticket in the course's colour with the due date,
  the days left on its stub, when it opened and the status (a dot and the
  words); the instructions (the site's Markdown: headings, lists, bold,
  italic, web links that open in the browser); the attached files, which
  download and open like other materials; the submission (status, when it
  closes, when it is graded) and whether it is in the task list. A deadline
  that belongs to an assignment carries its id (`assignment_id`, #144) and
  opens it directly, and so does the course page's header card for the
  course's next deadline; without the id (an older bridge) the app finds the
  assignment in the course's list by name and due time. Files
  are listed, not stored; "Download" saves one to the phone's Downloads
  folder.
- **Activity** (under More), two tabs:
  - **Sleep:** last night (length, bed and wake time, "Estimated" when it
    was guessed) with **Enter last night** or **Correct**; a 7 or 30 night
    chart (entered nights solid, estimated ones hatched, the average dashed;
    tapping a night shows it above, where it can be entered or corrected);
    the **morning question** (on by default at 05:00, the time can be
    changed, it can be switched off; on Android 13 and newer it needs the
    notification permission, asked for with a button). Without usage access
    a short line explains that nights could be estimated.
  - **Screen time:** without usage access, an explanation of what is read
    and where it goes, and a button to the system setting (this app's own
    page where the phone has one). With it: today's screen time and unlocks,
    a 7 or 30 day bar chart (average as a dashed line; tapping a bar chooses
    the day), the chosen day's apps (a minute or more, the home screen and
    LifeOS left out) as bars.
  - Below both: the switch "Send screen time and sleep once a day" with the
    state of sending and "Send now".
- **More → Projects** (#152, `GET /projects`): the projects from the
  memory's project files and the allowed folders, newest activity first.
  Each has its own colour, folder icon and ornament (the same look as its
  tasks), its status (active / paused / done), a short summary, the number
  of open tasks and its last activity. A project's page shows its state,
  open tasks and recent chats; **Ask about this project** starts a new chat
  in the project's folder (through the chat's "New chat" entry point), and
  a recent chat continues where it left off. A project without an existing
  folder says why it cannot start a chat.
- **Memory review** (#151, `GET /memory/consolidation`): when the nightly
  consolidation proposes changes, Today shows "Memory changes to review";
  its screen lists each change (file a capture, merge two facts, add a fact)
  with the file, the text and why, and **Approve** / **Reject** per change
  or for all. Approved changes are written in one commit on the computer.
  With nothing waiting, **Look for changes now** asks for a proposal. The
  proposal's push (Coach channel) opens this screen.
- **Settings:** language, appearance, **connection** (pair, state, name,
  last contact, "Test connection", "Unpair this phone"), **app lock**
  (immediately / 1 minute / 5 minutes), **Models** (see below),
  **Coach** (#76, kept on the computer: no coach notifications / once a day
  / up to three a day, quiet hours start and end, coach questions on or
  off), app version and, while there is one, the "Update ready" prompt.
- **Settings → Models** (`GET /models/routing`): each model job of the
  computer (chat sessions, coach run and conversation, consolidation, course
  resources, summaries, context map) with its model, effort and advisor and
  where that comes from (the job's default, the routing setting, the
  background model; chat sessions can choose per session), and the plan
  usage of its runs today and this week (runs, tokens with cache, Claude
  Code's cost estimate); then what uses no model at all. **Change** opens a
  sheet with the job's model, effort and advisor (the choices of
  `GET /models`; "Default" returns a field to what lies underneath, the
  advisor can be switched off); **Back to the default** appears when the
  app chose something. A change is a setting, sent directly with
  `PUT /models/routing` (not through the outbox): without a connection
  nothing changes and the screen says so; a refusal or an audit log that
  cannot be written is reported too. It applies from the job's next run.
- **First run:** one welcome screen that leads to pairing or is skipped.

Nothing on these screens is sample content any more. Not paired, they show
"Not connected yet" with the pair button.

## Data: saved copies, outbox, downloads

- **One loader per endpoint** (`ui/components/States.kt`, `data/UiState.kt`):
  loading skeleton → content, or "empty", "not connected yet", or an error
  with "Retry". Requests go to the computer one at a time.
- **Saved copies** (`data/ScreenCache.kt`): the last good answer per
  endpoint, as a small JSON file in the app's private files. When the
  computer does not answer, the screen shows it with "Saved copy · updated
  … ago". Nothing is read while the app is locked; everything is deleted on
  unpair; the files are excluded from backup and device transfer.
- **Outbox** (`data/Outbox.kt`): new notes and tasks, ticks, task and note
  edits, new and changed events are stored on the phone first and sent in
  order as soon as the computer answers. "Attempt started" is stored before
  an item is sent. If nothing left the phone, the item keeps waiting. If it
  left but no answer came, a tick, a task edit, an event change and a new
  note or task are repeated later (harmless: a tick answered with "task not
  found" counts as done, and a new note or task carries the phone's own id
  as `client_id`, so the computer answers a repeat with the item it already
  stored and never saves it twice), while a new event and a note edit are
  marked **"Please check"** and are sent again only when the owner says so,
  and not before the first attempt can no longer arrive (six minutes). A note
  edit sent again that is answered with "note not found" counts as done (the
  first attempt arrived and gave the note its new id). A second edit of a
  note replaces the first one while that is still waiting; once the first is
  on its way, the note cannot be edited until it is confirmed. A refusal by
  the computer is shown as "Not accepted". Unsent items are deleted on
  unpair.
- **Online again (#165)** (`sync/`): the app follows the phone's default
  network with Android's network callback (`NetworkWatch`). When a
  validated network appears where there was none, `SyncTrigger` starts one
  run at once: connections of the old network are dropped, the relay stream
  is opened again without waiting out its pause between attempts, the
  computer is asked (up to three times, two seconds apart) and what waits
  is sent; a successful check also reloads the screens. Runs never overlap:
  changes during a run become one follow-up run three seconds after it, so
  a flapping connection does not start parallel sends, and a run is never
  cut off half-way. Duplicates are still ruled out by the outbox itself
  (one sender at a time, `client_id` on new notes and tasks). Before this,
  nothing reacted to the network itself: the phone waited for the relay
  stream's own pause between attempts, for the check that runs once a
  minute when no "link down" had been seen, and nothing at all happened
  while Android kept the app's process paused or stopped (there was no
  background job for the outbox). **In the background**, whenever
  something waits while the computer cannot be reached, a unique
  WorkManager job (`OutboxWorker`, expedited from Android 12 on) is planned
  with "network connected" as its condition; Android starts it (waking a paused process) when the phone is
  online again, and it asks the running core for a run. **Limit:** sending
  needs the device secret, which is behind the app lock. When the app has
  locked itself (after the chosen lock time out of sight) or its process
  was stopped (it always starts locked), the job ends without sending, and
  what waits is sent right after the next unlock.
- **Offline-first notes and tasks** (`data/Local.kt`): a new note or task
  is on every screen at once. While it waits it is shown where it belongs
  (Tasks in its date section or under "Just added", Today when it is due
  today or late, Show all, Notes on top) with "Waiting to sync"; when the
  computer confirms it, its answer (the stored task, the note's id and time)
  takes the item's place before it leaves the outbox, so nothing flickers
  or appears twice; once a list from the computer that was asked for after
  the confirmation arrives, the list itself carries it (kept in
  `outbox/confirmed.json`, deleted on unpair). No model run is involved in
  saving.
- **Downloads** (`data/Downloads.kt`, `data/Download.kt`): first the direct
  way: the computer sends the file's address through the sealed channel and
  the phone fetches it over HTTPS straight into the Downloads collection
  (hidden until complete). The address contains a secret of the owner's
  account: it lives in one local variable, is never logged, stored or shown,
  and redirects are followed only to the same host over HTTPS. If that
  fails, the computer's own copy comes through the relay in sealed chunks,
  which must arrive in sequence and match the announced size and SHA-256;
  after a dropped connection the transfer continues once at the missing
  chunk. An incomplete file is removed. The app keeps no copy and no record;
  "Saved to Downloads" and an **Open** button are shown until the app is
  closed. Open hands the file's entry in Downloads to the phone's app for
  its type (a view intent that only lends that app read access; the type is
  the site's, else Android's, else the one of the file name's extension). If
  no app can open it, or the file was removed from Downloads meanwhile, a
  short message says so. Direct saving needs
  Android 10 or newer; on Android 8 and 9 the system's "save as" screen asks
  where to put the file.
- **Screen time** (`activity/`): with the owner's usage access
  (`PACKAGE_USAGE_STATS`, granted in the system settings), the system's
  usage events of the last seven days are turned into minutes per local day
  and app and the number of unlocks (`UsageMath`; a stretch across midnight
  is split, a day without any event is left out, not counted as zero). The
  days are kept in `activity/activity.json` in the app's private files (400
  days at most, excluded from backup like everything else, deleted on
  unpair) so the charts work offline. App names come from the package
  manager; `<queries>` asks only for apps with a home-screen icon and the
  home screen itself, not for all packages. **Measuring** happens when
  Activity is shown and once a day in the background (WorkManager,
  `UsageWorker`), so nothing is lost when the app is not opened for some
  days (the system forgets its events after about a week). **Sending**
  (`ActivitySync` → `POST /activity/usage`, each finished day once, with the
  total, the unlocks and the 20 most used apps' names and minutes) needs the
  device secret, which is available only while the app is unlocked: it
  happens when the app connects to the computer (at most once an hour) or
  on "Send now", never in the background. A day the computer has confirmed
  is not sent again unless its numbers change.
- **Sleep** (`activity/`): a night is the owner's entry (bed and wake time,
  `HH:MM`, for the morning it ended on) or, with usage access, a guess
  (`SleepEstimate`: the longest stretch from the screen going off to the
  next unlock that ends between 03:00 and 13:00 and lasts 3 to 14 hours).
  A guess never replaces an entry; an entry or correction is sent again
  (`POST /activity/sleep`, with `estimated`). The **morning question**
  (`SleepPrompt`) is one inexact alarm of the phone's own alarm service at
  the chosen time; when it rings and last night has no entry (a guess does
  not count), one silent notification ("Good morning · How did you sleep?",
  no data in it) opens the Sleep tab. The next morning is planned right
  after, and again after a restart of the phone or a change of clock or
  time zone (a receiver that is not exported). No polling, no service that
  keeps running, nothing from the computer. Entering last night removes the
  notification.

- **Chat** (`data/Chat.kt`, owned by `LifeOsCore`, so an answer keeps
  arriving while another screen is shown): a message goes out as `POST /ask`
  (a new conversation, with the chosen folder) or `POST
  /sessions/{id}/messages`, both with `"stream": true`; the answer comes back
  through the relay as events (`session`, `delta`…, then `done` or `error`).
  **The computer keeps the conversation; the phone keeps only which one is
  open** (`chat/current.json`: the session's id and its folder, never a
  message; deleted on unpair) and what is on screen. Rules:
  - Before sending, the computer must have answered (the dot is green, or it
    answers a quick question now). If not, **nothing is sent**: the text goes
    back into the composer and a notice says so. It is never sent later by
    itself.
  - The computer takes a message within seconds (it names the session or
    refuses), so no first answer within 45 seconds counts as "no answer". After
    that a run may stay quiet for up to 11 minutes. The same holds for the
    coach conversation: while it is tidied up first, the stream sends a
    keep-alive every 20 seconds (#156).
  - **The coach's stream** (`POST /coach/chat/messages`) may begin with
    `keepalive` events and `compacted {"facts", "summary", "session_id"}`
    before the message's own `session`: a keep-alive before `session` is
    shown as "tidies it up first", `compacted` moves the conversation to the
    new session and marks the place. A `queued {…}` event (the message had
    to wait after all) puts it in line like a `202`; an `error` event before
    the run ends the wait with its reason and **Retry**. `409
    compaction_running` (Stop, or Send now, on the session that is being
    tidied up) has its own text. When the answer is not heard (a lock, a
    lost stream), the new session is still learned from `GET /coach/chat`.
  - **Whenever the app stops receiving an answer the computer is still
    writing** (the app was locked, the connection dropped, a long silence),
    the run goes on there, and the app **attaches to it again** (`GET
    /sessions/{id}/live`, #154): a stream that only reads, first what the run
    said so far (it replaces the unfinished answer on screen, so nothing is
    doubled), then the rest as it is written, with its question and approval
    cards. The same stream carries the answers to waiting messages, which the
    computer sends by itself. Following never starts or continues a run.
    Where a run cannot be followed (the computer says `"followable": false`,
    or the stream cannot be reached or breaks off), it is as before: the
    conversation shows "The computer is still
    working on this", asks every five seconds while it is on screen (`GET
    /sessions/{id}/queue`, trying to attach again each time), and reads the
    last messages (`GET
    /sessions/{id}/history`) when the run has ended. If the conversation
    there does not end with the message, the app says that it probably did
    not arrive and offers **Retry**; it never repeats a message on its own. A first message that
    got no answer at all cannot be followed up (its session is not known):
    the app says that it may have arrived and points to Sessions.
  - A refusal (session open on the computer, too many runs, folder gone,
    message too long) keeps the message with **Retry**; a run that breaks off
    keeps what was written and says why.
  - **Model and effort** are the session's own on the computer (`PATCH
    /sessions/{id}` with `model` and `effort`, `null` for the default); a new
    conversation sends them with its first message. The choices come from
    `GET /models`. **Commands** come from `GET /sessions/{id}/commands`, asked
    for when a message starts with "/"; the app only completes and colours
    names, the computer checks the message (`command_not_allowed`,
    `skills_unavailable`).
  - **Offered files** (`files` and `files_refused` of a finished answer):
    the card holds only the offer's id, name, kind and size. The download is
    `Downloads.startOffer`: the computer's copy in sealed chunks straight
    into the Downloads collection (`RelayDownload`, the same code as the
    fallback route of course files: chunks in sequence, announced size and
    SHA-256 checked, an incomplete file removed, hidden until complete). The
    app keeps no copy and no record; "Saved to Downloads" lives until the app
    is closed. An answer read from the conversation later (after a lock, or
    an earlier page) has only the marker line, so its card is rebuilt from
    the file's name; the app then asks for the session's live offers (`GET
    /sessions/{id}/files`, only when such a card is on screen) and puts each
    offer back on the card of its name, the newest offer on the newest
    answer, each once. The card counts the time that is *left* of the 15
    minutes (the computer's end time), not a new window.
  - **Earlier messages** are asked for one page at a time (`GET
    /sessions/{id}/history?before=<cursor>`) and held only while the
    conversation is on screen; nothing of a session is written to the phone.
  - **The queue is the computer's** (`GET /sessions/{id}/queue`, at most ten
    messages): a message sent during a run is the same request as any
    message and is answered with its place in the line (`202`). Editing and
    removing are `PATCH` and `DELETE …/queue/{qid}`. **Send now** is one
    request, `POST …/queue/{qid}/send-now` (#154): the message becomes the
    next one, a run in progress is interrupted for it, a hold ends; nothing
    is sent twice or deleted. `404 not_queued` means that the computer sent
    it meanwhile and is no failure. **Stop** is `POST /sessions/{id}/cancel`.
    "Send them" (`POST …/queue/resume`) is answered at once; if it does not
    get through, the line is on hold as before and a notice says so. The
    computer sends waiting messages by itself; the app follows their answers
    through `GET /sessions/{id}/live` (see above), so they are written on
    screen and can ask for approvals and questions.
  - **One session, read fresh** (`GET /sessions/{id}`, #154), instead of
    looking it up in the list of the newest 200: when a conversation is
    opened (its model and effort; a choice the owner makes meanwhile wins),
    for a tapped notification or reminder (`SessionLookup`), and for the
    session's sheet (title, pin, switches).
  - While the app is locked nothing of the conversation is in memory; it is
    read again after unlocking.
  - **Approvals and questions** (`data/Interactions.kt`, the bridge's
    "Questions and approvals"): every message is sent with `"interactive":
    true`, and with `"mode": "capable"` only while "Changes allowed" is on.
    An `interaction` event of the answer becomes a card; the answer goes out
    as `POST /sessions/{id}/interactions/{iid}` (`{"approve": true|false}`,
    `{"answers": {...}}` or `{"decline": true}`). **The app never answers
    by itself:** when the time is over it only closes the card (the computer
    counts the silence as "no"), and a tap after that sends nothing. The
    time left is the computer's waiting time counted on the phone's running
    clock, so the two wall clocks need not agree. (A card that is read from
    the computer's list, or that is part of a followed run's replay (before
    `replay_end`, #157), has only its end time: its time left is that against
    the phone's wall clock, so a clock that is off shortens or lengthens what
    the card shows. A card that arrives after `replay_end` is new and has its
    whole wait. The computer alone decides when the time is over, and a card
    it still lists stays answerable.) `404 not_pending` is shown
    as "no longer waiting"; an answer that could not be sent can be given
    again. **One list for everything that waits** (`GET
    /sessions/{id}/interactions`): the app reads it whenever a conversation
    is opened or read again (not only while a run is in progress), so a card
    of a run it does not hear appears again and can still be answered, and so
    does a card that waits across runs. Cards are kept in memory only; those
    of a run until the next message.
  - **Skill installs** (`kind: "skill_install"` in that list and in
    `skill_installs` of a capable answer's `done`): they wait up to 24 hours
    on the computer, so the app neither clears them with the next message nor
    closes them when a run ends, and counts their time in hours. A card that
    is still `downloading` is asked about every three seconds while it is on
    screen. The answer is the same `{"approve": true|false}`; a yes is sent
    only for a `ready` card, and what the computer answers decides what the
    card says. **A yes the computer refuses leaves the card waiting** (#157:
    `409 still_downloading`, `409 skill_exists`, `503 audit_unavailable`,
    `500 install_failed`): the card stays open with its files, says why in
    the app's words, looks at the computer's list again, and can be answered
    again; a no is always taken. `404 not_pending` closes it. A failed or
    refused proposal carries `error_code`, which the app maps to its own
    EN/TR texts (the bridge's English sentence is not read; an unknown word
    gets a general text). The
    marker line `[[skill-install: …]]` is left out of the text while an
    answer is written and in earlier answers. The switch is `PATCH
    /sessions/{id}` with `{"skills": true|false}`; what the folder has and
    whether it is usable comes from `GET /sessions/{id}/commands`.
  - **Moves of a chat's files** (`kind: "move_files"` in that list and in
    `file_moves` of an answer's `done`, #163, #168): cards that wait across
    runs like skill installs. A yes's answer (`moved`, `files`, `failed`)
    decides what the card says; `409 file_exists`, `409 files_changed`, the
    bridge's other `409` checks and `503 audit_unavailable` leave it
    waiting with a worded reason. A proposal refused at once (no `id`,
    `state: "refused"`) is a card with its `error_code` worded and nothing
    to answer. `[[move-files: …]]` lines are left out while an answer is
    written and in the history, which keeps them.
  - **Switches** (`data/Switch.kt`, #164, #168): `switch` of a `done`
    event is parsed only for an answer that happens now; for the app's own
    message `open` and `new` become a one-time `switchTo` that the
    conversation screen hands to the navigation, `choose` (and any switch of
    a followed answer) a card in the state until the next message. A lock,
    another conversation and a re-read drop both; the history never carries
    one. `[[switch…]]` lines are hidden while written, and a last line that
    is becoming any marker line is held back until it is whole.
  - **Folders** (`data/ChatModels.kt`): `GET /sessions` (`limit=200`) gives
    each session's `folder_key`, `folder_label`, `kind`, `colour` and the
    `folders` for the tiles; `Looks.folder(colour)` maps the colour number
    to the palette's tone and ornament. A conversation remembers its
    folder's kind and colour in its pointer; one opened by a switch (which
    names no path) learns its folder from `GET /sessions/{id}`. A new
    conversation sends its folder's path, the memory folder included; a
    blank chat none.
  - **MCP servers** (`data/McpServers.kt`): the list is `GET
    /sessions/{id}/mcp-servers`, read each time the sheet's page is opened
    and after every change, never kept. "Review and approve" is `POST
    …/mcp-servers/{name}/approval` (the computer keeps one card per
    definition and answers with the one that waits, `"already_waiting":
    true`, #157); the card
    (`kind: "mcp_server"`) is answered with `{"approve": true|false}` like
    every approval, from the sheet or from the conversation, and the
    computer's `"approved"` decides what is shown (`false` after a yes: the
    definition changed meanwhile; `503 audit_unavailable`: nothing was
    approved and the card can be answered again). Revoking is `DELETE
    …/mcp-servers/{name}`. Both requests always send `?scope=folder` or
    `?scope=personal` (#162); `409 mcp_server_unsupported` gets its own
    line. The card's top-level `"scope"` decides its wording. `mcp_servers` of a finished answer becomes the
    line under it, and each exchange of `GET /sessions/{id}/history` carries
    it too (#157), so an answer read again later says the same. A message
    refused with
    `503 mcp_unavailable` or `503 audit_unavailable` says why in the app's
    own words and can be retried. The app uses its own EN/TR warning text,
    not the bridge's English `note`.

What the bridge does not offer yet, and the app therefore does not do:
editing topic notes or moving notes, reopening or deleting a task, deleting
an event, and listing finished tasks (no "done" filter).

## Connection to the computer

**Streams the app stops reading (#157).** A stream (a chat answer, a followed
run) holds one of the relay's four workers on the computer while it runs.
`RelayClient` therefore tells the bridge whenever a stream's reader stops
before the stream has said its end (`done`, `error`, `queued`, or its last
message): `POST /relay/detach` with `{"r": "<request id>"}`, sent like any
request, once, best effort. That covers another conversation or a new chat,
a stream that gave no sign in time, and a followed run that broke off. **On a
lock** the secret is forgotten at once, so the detach messages for every
open stream are sealed first (`detachAll`) and written right after; if a
request is being written at that very moment nothing is sent, and the bridge
lets the stream go by itself after about 80 seconds. A chat message's run is
never cut by this: it goes on, and the app attaches to it again with `GET
/sessions/{id}/live` after the unlock. `stream_abandoned` (the relay's last
word for a stream it let go by itself) is not a failure: the run is asked
about and followed again.

Design and threat model: [`docs/security.md`](../docs/security.md). The
phone side mirrors the bridge's `e2e.py`, `devices.py`, `mailbox.py` and
`relay.py`.

- **Cryptography** (`crypto/`): only libsodium, through the small `Sodium`
  interface with exactly four operations: `crypto_kdf_derive_from_key`,
  `crypto_aead_xchacha20poly1305_ietf` encrypt and decrypt, keyed
  `crypto_generichash`, plus `randombytes`. Nothing home-made.
- **Pairing** (`pairing/`): the QR text is read with the camera on the
  phone, the owner confirms with fingerprint or screen lock, the app creates
  an anonymous relay account, sends one sealed hello and shows the six-digit
  comparison code. It stores the pairing only after the computer's first
  sealed message arrived; after two minutes without confirmation everything
  is forgotten and the relay account is removed again.
- **Storage:** the device secret is encrypted with an AES-GCM key in the
  Android Keystore that can be used only for five minutes after a strong
  biometric or the screen lock was confirmed. The phone therefore needs a
  screen lock; without one, pairing stops with an explanation. Files are
  app-private and excluded from backup and device transfer. The message
  counters are written and forced to storage before a message is sent or
  acted on. "Unpair this phone" deletes the files and the Keystore key.
- **App lock** (`lock/`): when paired, the app asks for fingerprint or
  screen lock on every cold start and after it was out of sight for the
  chosen time. While locked the secret is not in memory and the relay
  connection is closed.
- **Relay client** (`relay/`): Realtime Database over REST with a streaming
  GET and polling as the fallback; every received message passes the
  receiver checks (version, device, direction, authentication, counter) or
  is dropped silently, and is deleted from the mailbox as soon as it was
  taken. A request is retried at most once, always as a newly sealed
  message; requests other than GET are retried only when the first try
  certainly did not leave the phone.

**Moodle sign-in from the phone** (`signin/`, `data/MoodleSignIn.kt`, #100):
the renewal of the course site's sign-in, in one tap from the "sign-in ran
out" notification, from the Courses screen (its notice has the button) or
from Settings → Courses sign-in.

- The app asks the computer for the site's sign-in link
  (`POST /moodle/signin/start`, a direct request; nothing is saved): the
  official mobile app's flow, with a one-time passport that the computer
  keeps for ten minutes.
- The link opens in a **browser tab** (a Custom Tab where the browser offers
  one, through the documented session extra; no extra library, never a
  WebView). The owner signs in on the site's own page; the app never sees
  or fills in that page and never sees the password.
- The page ends on `moodlemobile://token=…`, the address scheme the site
  uses for its official app. `MoodleRedirectActivity` receives it (no
  screen), removes it from the intent and brings the app back. If the
  official Moodle app is installed too, Android may ask which app opens it:
  choose LifeOS.
- The app checks the address against the passport (`md5(site + passport)`,
  as the official app does) and only then sends it sealed to the computer
  (`POST /moodle/signin/finish`); locked or offline, it waits in memory
  until the app is unlocked and connected. The computer checks it again,
  tests the token at the site and only then stores it. The app keeps no
  copy: the address lives in memory until it is sent, and an attempt is
  forgotten on unpair.

**Push** (`push/`, #38; docs/security.md 5.1):

- **Firebase without its Gradle plugin:** the four values of the owner's
  Firebase app are compiled in from `~/.config/lifeos/firebase/` (see
  `app/build.gradle.kts`); the app starts Firebase itself (`PushSetup`).
  A build without them, or a phone without Google Play services, simply has
  no push (Settings → Notifications says so).
- **Data messages only** (`PushService`, also while the app is closed): one
  field `h`, a hint sealed with the push key. It is opened with that key
  alone; the checks are those of every message (this device, direction
  "push", kind "push hint", authentication, a known event, a counter
  greater than every earlier one, stored first). A bare wake-up only makes
  a running app ask the computer again.
- **The push key** (sub-key 3) is kept under a second Keystore key **without
  user confirmation** (owner's choice #136 = A, `Vaults.push`,
  `PushKeyStore`), so a hint opens while the app is locked; it opens hints
  only. The device secret stays under the confirmation-bound key. Deleted on
  unpair (with its Keystore key and the push counter).
- **The push address** is handed to the computer through the sealed channel
  (`POST /push/token`, answered by the bridge's relay code) when the app
  connects and whenever Firebase gives a new one; an unchanged address is
  not sent again.
- **Notifications:** four channels (chat, courses, coach, reminders) that
  the owner can mute in the system settings; the text is fixed per event
  kind and content-free, lock screen included (`PushNotifier`). Replies of
  one session share one notification, all course changes of a sync share
  one. Counts and items stay inside the app: they decide what a tap opens;
  a notice's title is shown as a message in the unlocked app.


### Test vectors

`app/src/test/resources/e2e-vectors.json` is written by the bridge's own
code (synthetic keys only):

```sh
cd ../bridge && .venv/bin/python tests/make_e2e_vectors.py
```

The unit tests check that the app derives the same four sub-keys, opens
every message the bridge sealed, produces byte-for-byte the same sealed
message from the same inputs (so the bridge opens what the app seals), and
shows the same comparison code.

### Test aids of debug builds

Debug builds contain `src/debug/kotlin/dev/lifeos/app/TestAids.kt`, so an
automated check can pair without the camera and without a fingerprint:

```sh
adb shell am start -n dev.lifeos.app/.MainActivity \
    --es lifeos.test.pairing_text '<pairing text>'
```

- A pairing made this way is stored under a Keystore key that needs no
  confirmation, and the app lock is skipped for that pairing only.
- With `--ez lifeos.test.real_lock true` only the camera is replaced: the
  app asks for confirmation and stores the pairing like a scanned one.
- A pairing made with the camera is never affected by these extras: its key
  demands the owner's confirmation whatever an intent says.
- `--ez lifeos.test.sample_courses true` makes the Courses screens (and only
  them) show the synthetic courses of `src/debug/assets/sample-courses.json`
  until the app is closed, including a small sample file that is "downloaded"
  the relay way. Use it for screenshots; the owner's courses are not asked
  for or changed in that mode. (The sample answers are also kept as saved
  copies; unpair or clear the app's data afterwards.)
- `--es lifeos.test.push <event>` shows the notification a push of that kind
  would show (fixed text, channel, what a tap opens), without Firebase.
- `--ez lifeos.test.sample_data true` is for screenshots on an emulator
  without a computer: on a phone that is **not paired**, the app acts as
  connected and every screen shows the invented answers of
  `src/debug/assets/sample-data.json` (Today, Tasks, Notes, Courses with five
  generic courses and a project; dates are counted from the current day).
  Writes are simply accepted and go nowhere; nothing is kept as a saved
  copy. A paired phone ignores it. Close the app to leave the mode.
  **Chat and Sessions** come from `src/debug/assets/sample-chat.json`: three
  folders, five sessions, one conversation, and an invented answer that
  arrives piece by piece for any message. A message that contains one of
  these words shows another state: `in use` (the session is open on the
  computer), `fail` (the run breaks off), `file` (the answer offers a small file and names one that is
  too large), `slow` (the answer takes half a
  minute: time to send more messages, which wait in line, to send one now
  and to stop), `offline` (nothing is sent, the text comes back), `silent` (the answer stops arriving and the message is
  not found on the computer), `lost` as a first message (it arrives and is
  answered, but nothing reaches the phone), `delete` or `push` (the run waits for an
  approval; the first is a deletion), `choose` (the run waits for the answer
  to two questions). With `hurry` the wait lasts 20 seconds instead of ten
  minutes, to see what running out of time looks like. `install` (the answer
  proposes a skill: its card is "downloading" for six seconds, then lists
  three files and two warnings; with `broken` the download fails, with
  `plain` the source is not HTTPS and is refused at once; with `taken` a
  tap on Install is refused because a skill of that name exists, and the
  card stays). `mcp` (the answer
  names the approved MCP servers it used), `mcp unchecked` (the message is
  refused because the computer has not checked the folder's approved
  servers). Every sample session's folder declares three MCP servers, one of
  each state; approvals and revocations are kept until the app is closed.
  The answer to a waiting message can be followed (`/live`) and is written
  over a few seconds; `file` offers are listed for 15 minutes (`/files`),
  and each sample session can be read by itself.
  The session "Milestone summary draft" counts as open on the computer, and
  "Summarise the lecture notes of week 3" waits for an approval from the
  start: `--es lifeos.test.push approval --es lifeos.test.push_ref
  sample-session-4` shows the notification whose tap opens that
  conversation with the card. A `slow` answer sends keep-alives, and a new
  conversation runs under the id the app chose.
  **Reminders** are kept while the app runs; one has fired already, so
  `--es lifeos.test.push reminder --es lifeos.test.push_ref sample-reminder-1`
  shows a reminder notification whose tap opens its session.
  **The coach conversation** starts empty there and counts as long after
  five messages, or at once for a message that contains `tidy`: the next
  answer then begins only after nine seconds of keep-alives and a
  `compacted` event (two files, a summary), in a renewed session; a stop
  during those seconds is refused with `compaction_running`.

  ```sh
  adb -s emulator-5554 shell am start -n dev.lifeos.app/.MainActivity --ez lifeos.test.sample_data true
  ```
- **Release builds do not contain any of this.** They compile the empty
  twin in `src/release/`; a pairing left behind by a test aid is deleted
  when a build without the aid starts.

The pairing text is the key. Never put it into a file, a log or a report.

## What is not in it yet

- Chat: tables in answers (shown as text). The coach conversation shows only its current session: the
  messages from before it was tidied up are under Sessions ("Coach
  (earlier)").
- Tasks cannot be moved up or down; topic notes cannot be edited (see above).
- Courses: "Ask about this course".
- No instrumented (on-device) tests; only JVM unit tests.

## Manual check

Without pairing:

1. Install and open the app. A welcome screen offers pairing; choose
   **Later**. Today shows "Not connected yet" with the pair button.
2. Tap the four bottom-bar icons in turn: Today, Chat, Tasks, More. The
   gradient bar moves to the active icon.
3. **Tabs open their root (#166):** More → Settings, then tap **More**
   again: the More list. More → Settings, tap **Chat**, then **More**: the
   More list, not Settings. The same for Tasks → Show all (tap Tasks), Chat
   → Sessions (tap Chat) and Today → the coach conversation (tap Today).
4. **"+" (#166):** tap **+**: the sheet offers **Note**, **Task** and
   **New chat**, in this order. **New chat** closes the sheet and opens an
   empty conversation at once.
5. More → Settings → Language → **Türkçe**: every text switches to Turkish
   at once and the Settings screen stays open. Switch back as you like
   (with Turkish, the three options of "+" still fit on one line each).
6. Settings → Appearance → **Dark**: the app turns dark, including
   the status-bar icons. Close and reopen the app: the choice is kept.
7. With a larger system font size the text grows and nothing is cut off.

### Pairing and connection

The phone needs a screen lock and an internet connection; the bridge must be
running on the computer with the relay set up.

1. On the computer: `lifeos-bridge pair "My phone"`. A QR code appears in
   the terminal (valid for two minutes, for one phone).
2. In the app: Settings → Connection → **Pair with computer** → **Scan the
   code**. Allow the camera, point it at the QR code, then confirm with
   fingerprint or screen lock.
3. Both screens show a six-digit code (`123 456`). If they are equal, type
   `yes` on the computer. The app shows **Paired** and the name the computer
   uses for this phone; the dot in the top bar turns green.
4. Settings → Connection → **Test connection**: "The computer answered in
   … ms. Bridge version …".
5. Close the app completely and open it again: it asks for fingerprint or
   screen lock, then connects by itself. Settings → App lock chooses how long
   the app may be out of sight before it asks again.
6. Stop the bridge and tap **Test connection**: after about 40 seconds the
   app says that the computer did not answer, and the dot turns amber.
7. **Unpair this phone** → confirm: the connection shows "Not paired". On
   the computer, `lifeos-bridge devices revoke <id>` removes the phone there
   (`lifeos-bridge devices` lists the ids).

### The screens on real data (after pairing)

1. **Capture:** tap **+**, type a note, Save: "Saved". On the computer the
   line is at the end of `inbox.md` in the memory folder. Choose **Task**,
   pick a due date of today, Save.
2. **Today:** the task is in the "Today" card (and among the picks if the
   coach has a plan). Tap its round check: it disappears and "Marked as done
   · Undo" appears. Tap **Undo**: it is back. Tap the check again and wait:
   on the computer the task has moved to `tasks-done.md`. Pull Today down:
   the cards reload within seconds and no plan run starts (the "prepared …
   ago" line of the coach card stays as it was). Tap **Rerun** on the
   coach card: "Preparing a new plan…", and a few minutes later the card
   shows a message of the new plan. Tap **Rerun** again at once: "A plan was
   made less than ten minutes ago".
3. **Tasks:** with open tasks and a plan, "Most important" and "Show all
   (n)" with the four filters; without a plan, sections by date.
4. **Calendar** (More → Calendar): the agenda with a month strip. **+** adds
   an event; tapping an event changes it (switch "All day" off to give it a
   time). The computer's `calendar/events.md` has the line.
5. **Offline:** switch the phone to flight mode. After a moment the dot
   turns amber and the screens show "Saved copy · updated … ago". Capture a
   note: "Waiting to sync"; More → Notes lists it on top under "Waiting to
   sync". Capture a task due today: it is on Tasks (in "Today", or under
   "Just added" when the coach has a plan) and on Today at once, marked
   "Waiting to sync", without pulling down. Switch flight mode off: within
   seconds the note is in `inbox.md` and the task in `tasks.md`, each once;
   on the screens they lose the mark, with no flicker and no duplicate (the
   task could be ticked and edited while it waited too, see "Tapping a
   task"). (If the connection drops in the middle of a
   send, the item is simply sent again later; the computer recognises it.)
6. **Notes and task edits:** capture a note; More → Notes shows it at the
   top of the list (newest first) together with the older captures and the
   topic notes. Type a word of it into the search field: only matching notes
   remain. Tap the note → **Edit**, change the text, Save: "Saved", the
   screen closes and the list shows the new text; on the computer the line in
   `inbox.md` has the new text and its original time. A topic note opens
   read-only. On Tasks, tap a task's title (not the check): its card opens
   (#167); tap **Edit**, change the title, pick another date (or **Remove**
   it), Save; the row changes at once and `tasks.md` has the new title and
   date. In flight mode the same edits show "Waiting to sync" and reach the
   computer once it is off.
7. **Shortcut and share:** long-press the app icon → "Capture"; in another
   app, share a piece of text → "Capture in LifeOS": the sheet opens with it.
8. **Courses** (More → Courses; needs the course sync on the computer and a
   bridge that has the `/courses` endpoints): the top bar shows "Courses"
   with "Last sync …" under it, "All grades" and the refresh arrow. The rose
   deadline card shows only "Upcoming deadlines" as tickets; the course tiles
   follow. A course's colour and icon are the same on its tile, its page,
   its files, its deadlines and the labels of its tasks. A course's five tabs (Resources: see "Course resources" below);
   the download arrow of a file → "Saved to Downloads", and the file is in
   the phone's Downloads folder. Tap **Open** next to it: a PDF opens in the
   phone's PDF viewer (or the system asks which app to use); back in LifeOS
   nothing changed. Delete the file in the Files app and tap **Open** again:
   "This file is no longer in Downloads", and the download arrow is back.
   Tap a deadline ticket: the assignment opens with its due ticket in the
   course's colour (days left on the stub), the instructions, its files
   (download one) and the submission status. A course's Assignments tab:
   tapping an assignment opens the same screen.
   The refresh arrow starts a sync.
9. **Screen time** (More → Activity): "Usage access is needed" with an
   explanation. Tap **Open usage access settings**: the system page for
   LifeOS opens (on some phones the list of apps; choose LifeOS). Switch
   access on and go back: Activity shows today's screen time and unlocks,
   the bar chart and the apps of today. Use a few apps for a couple of
   minutes and open Activity again: the numbers grew. **30 days** widens the
   chart; tapping a bar shows that day's apps. The next day, open the app
   (unlocked, the dot green): "Last sent … ago", and on the computer the
   memory's `activity/` file of the month has a `screen` line for yesterday
   with the total, the unlocks and the apps. Switch "Send screen time once a
   day" off: nothing more is sent. Revoking usage access in the system
   settings brings back the explanation.
10. **Sleep** (More → Activity → Sleep): "Last night · Not entered yet".
   Tap **Enter last night**, pick bed and wake times, Save: the card shows
   the length and the times, the chart a solid bar for this morning. With
   usage access, nights you did not enter appear hatched and marked
   "Estimated" (after a night with the phone lying unused). Tap a bar: that
   night shows above with "Enter" or "Correct". Under "Morning question",
   allow notifications (Android 13+), set the time two minutes ahead and do
   not enter the night (or correct the time of a test night on another
   day): at that time a silent notification "Good morning · How did you
   sleep?" appears without sound; tapping it opens the Sleep tab. Entered
   nights reach the computer like screen time: a `sleep` line in the
   memory's `activity/` file of the month. Restart the phone: the question
   is still planned (`adb shell dumpsys alarm | grep SLEEP_PROMPT`).
11. **Unpair:** Today and Tasks show "Not connected yet" again; saved copies,
   waiting items and the screen time stored on the phone are gone.

### Sending when the phone is online again (#165)

The bridge runs on the computer; the app is paired and unlocked.

1. Switch the phone to flight mode (Wi-Fi off too). Capture a note and a
   task: both show "Waiting to sync".
2. Switch flight mode off, keep the app open and watch the computer: the
   note is in `inbox.md` and the task in `tasks.md` within a few seconds of
   the phone showing its network, each once; on the phone both lose
   "Waiting to sync".
3. Repeat with the app in the background: capture in flight mode, go to
   the home screen, switch flight mode off within the lock time (Settings →
   App lock; five minutes is easiest to test with). Both reach the computer
   within a few seconds without opening the app.
4. Flapping: in flight mode capture a note, then switch flight mode off,
   on, off quickly. The note arrives once.
5. Locked: capture in flight mode, leave the app until it locks (or close
   it from the recent apps), switch flight mode off. Nothing is sent yet;
   open the app and confirm with fingerprint or screen lock: the note
   arrives within seconds.

### Tapping a task (#167)

Paired, with the bridge running; for step 1 the course sync must have made a
task from an assignment (its line in `tasks.md` has `(assignment: moodle-…)`).

1. **An assignment's task:** on Tasks (also Show all, the Today card and the
   picks) tap such a task's title (not the check): the assignment's page
   opens with its due ticket and description, and the Tasks tab stays
   selected. Back returns to the list. The same from Today keeps Today
   selected.
2. **Any other task:** tap a task you captured yourself: a card slides up
   from the bottom (not a new page) with its title, its due date ("No due
   date" without one, "Overdue" when late), its course or project label in
   its colour, and **Mark as done** and **Edit**. A task the coach gave a
   next step shows it under "Next step". Swipe the card down: nothing
   changed.
3. **Edit in the card:** **Edit** → the same sheet shows the title and the
   date; change both, **Save**: "Saved" and the row shows the change;
   `tasks.md` has it. **Cancel** goes back to the card.
4. **Mark as done in the card:** the card closes, the task leaves the list
   and "Marked as done · Undo" appears; **Undo** brings it back. Without
   Undo, `tasks-done.md` has it a few seconds later.
5. **A task still waiting (flight mode):** switch flight mode on, capture a
   task with **+** → **Task**; on Tasks it shows "Waiting to sync". Tap it:
   the card opens with "Waiting to sync". **Edit** it (new title and date),
   Save: "Waiting to sync" and the row shows the change. Capture a second
   task and tap **Mark as done** in its card (or its check), let the undo
   time pass. Switch flight mode off: within seconds `tasks.md` has the first
   task with the new title and date (once), and the second task is in
   `tasks-done.md` (added and finished), not left open.
6. Turn the phone while a card is open: it stays open. Lock the app with a
   card open: after unlocking the card is closed.
7. In Turkish and in dark mode the card is fully translated and readable.

### A task's note (#170)

Paired, with the bridge running.

1. **Capture with a note:** **+** → type a title → **Task**: under the date
   a "Note (optional)" field appears. Type two lines (Enter gives a space),
   for example `Bring the (old) notes`, **Save**: "Saved". Tap the task: the
   card shows the note under "Note". `tasks.md` has the task on one line
   ending in `(note: Bring the [old] notes) ^t-…`. A task saved without a
   note has no "Note" block and no `(note:` on its line.
2. **Edit the note:** in the card **Edit** → change only the note, **Save**:
   "Saved", the card shows the new note when tapped again, and the line in
   `tasks.md` has it (title and date unchanged). Edit only the title: the
   note stays. Empty the note field and Save: the "Note" block is gone and
   so is `(note:` on the line.
3. **Next step stays apart:** on a task the coach gave a next step, add a
   note: the card shows "Note" and "Next step" as two blocks, and the line
   keeps both `(next: …)` and `(note: …)`.
4. **Offline:** flight mode on; capture a task with a note: it shows
   "Waiting to sync" and its card shows the note. **Edit** its note, Save.
   Edit the note of a task already on the computer too. Flight mode off:
   within seconds `tasks.md` has the new task once, with the edited note,
   and the other task's new note.
5. **The course sync keeps it:** add a note to a task made from an
   assignment (on the computer, `PATCH /tasks/{id}` with `{"note": "…"}`, see
   `docs/bridge.md`); after the next sync that moves its date or completes
   it, the line (in `tasks.md` or `tasks-done.md`) still has the note.
6. The 500-character limit: pasting a longer text into the note field
   keeps the first 500 characters. In Turkish the labels read "Not" and
   "Not (isteğe bağlı)".

### Renewing the Moodle sign-in on the phone

Needs a paired phone, the computer running, and the course site set up.

1. Settings → **Courses sign-in** → **Renew the sign-in** (or the button on
   the Courses screen when its notice says the sign-in ran out, or tap the
   "sign-in needs renewing" notification): "Asking your computer…", then
   the site's sign-in page opens in a browser tab.
2. Sign in on the site's page (the institution's button if the site offers
   one). At the end the browser comes back to LifeOS by itself; some
   browsers show a last page with a link to tap. If the official Moodle app
   is installed, choose LifeOS when Android asks.
3. Courses shows "Passing the new sign-in to your computer…", then
   "Renewed". On the computer, `scripts/moodle-login --check` says the
   token works, and the next course sync (the refresh arrow) succeeds.
4. Start again and come back without signing in, or wait more than ten
   minutes: "This sign-in attempt has run out". Nothing changed on the
   computer.

### Memory review (#151)

Needs a paired phone and the computer running.

1. Wait for the nightly consolidation, or start one on the computer
   (`POST /memory/consolidation/run`; after a decided proposal, the review
   screen's **Look for changes now** does the same). When a proposal is
   ready, a Coach notification appears; tap it: "Memory review" opens.
   Today shows "Memory changes to review" with the count.
2. Reject one change: it shows "Rejected". Approve one: "Applied"; the
   memory folder has one new commit with that change.
3. **Approve all (n)** applies the rest in one commit; the Today card
   disappears.
4. Edit the named topic file on the computer between the proposal and the
   approval: the screen says the memory changed meanwhile and nothing was
   written.

On an emulator, the `sample_data` test aid shows a sample proposal with
three changes; approving and rejecting change it in memory until the app
closes.

### Projects (#152)

Needs a paired phone, the computer running, and project files or allowed
folders.

1. More → **Projects**: every project with its colour and folder icon,
   status, summary, open tasks and last activity, newest first; a task of
   the project on the Tasks screen has the same colour.
2. Open a project with a folder: state, open tasks, recent chats. Tap
   **Ask about this project**: the Chat tab opens a new chat whose folder
   is the project's; ask "What is the state of this project?" and the
   answer reads that folder.
3. Tap a recent chat: it continues in its folder.
4. A project with only a memory file (no folder) says it has no folder on
   the computer and offers no chat.

### Coach questions, nudges and settings (#76)

Needs a paired phone and the computer running, with a coach plan.

1. Settings → **Coach** shows the computer's settings (by default "Once a
   day", 22:00–08:00, questions on). Choose **Up to three a day**, set quiet
   hours, switch questions off and on: each change is saved at once; on the
   computer `GET /coach/settings` (or the next nudge) follows it. With the
   computer off, the group says it is shown when the computer is connected.
2. When the morning plan has a question, Today shows the card "Coach ·
   Question" under the coach card. Write an answer and tap **Send answer**:
   "Sending…", then "Answer saved." and the card disappears; the answer is
   in the coach log in the memory folder.
3. On another day, tap **Skip** instead: "Skipped." (after three skips in a
   row: "The coach will not ask questions for a while").
4. With frequency "Up to three a day", wait for a nudge (at least two hours
   after the daily note, never in quiet hours): a notification "The coach
   has something for you"; tap it: Today opens with that message on the
   coach card.

On an emulator, the `sample_data` test aid shows a sample question and
keeps settings changes in memory until the app closes;
`--es lifeos.test.push coach --es lifeos.test.push_ref m-1` shows a nudge.

### Updating the app from the computer (#138)

Needs the release build on the phone (see "Release build"), paired, and the
computer running.

1. Make a change (or just a new commit) and run
   `uv run lifeos-bridge release --build` in `bridge/`. It reports the
   version and that the phone was told.
2. Within seconds the notification "An app update is ready" appears
   (Reminders channel). Tap it: Today opens with **Update ready**, "Version
   0.1.N · size". Without the push, opening the app shows the same card.
3. Tap **Download and install**. The first time, the card explains the
   permission and **Open the setting** goes to "Install unknown apps" for
   LifeOS; allow it, come back, tap **Download and install** again.
4. "Downloading… x of y", then Android's own screen asks to update LifeOS;
   confirm. The app restarts as the new version (Settings → About → App
   version) and stays paired.
5. No copy remains: Android's Settings → Apps → LifeOS → Storage shows a
   cache far smaller than the update (the file is deleted as soon as the
   installer has it, and any leftover at the next start).
6. Cancel Android's screen once: the card says Android did not install it
   and offers **Try again**.
7. With a debug build installed, steps 1–2 show the card with "This build
   cannot update itself" and no download button.

On an emulator without a computer, the `sample_data` test aid shows that
debug-build card on Today (its sample release has a newer version and
another key); `--es lifeos.test.push update` shows the notification.

### Push notifications (app closed, phone idle)

Needs a build made with the owner's Firebase settings and a paired phone.

1. Open the app once, unlocked and connected (the dot green): it hands its
   push address to the computer. Settings → Notifications says "Your
   computer can reach this phone…"; on Android 13+ allow notifications.
2. Close the app completely (swipe it away) and lock the phone; wait a few
   minutes so it is idle.
3. On the computer, start a chat run from the terminal or with
   `scripts/notify.sh "Test" "Test" ""`: within seconds a notification
   appears with a fixed text only ("A reply is ready", "A notice from your
   computer"), the same on the lock screen; no title, name or number.
4. After a course sync that found something: one notification "Your courses
   have news", however many things changed. Tap it: after unlocking, the
   course (one changed) or Courses opens.
5. Tap a reply notification: the Chat tab opens (the session screens come
   with #33). A notice shows its title as a message after unlocking.
6. Settings → Notifications → **Notification settings**: the four channels
   Chat, Courses, Coach and Reminders; switch Courses off and run a sync:
   nothing appears, the others still do.
7. Unpair: no more notifications reach this phone.

On an emulator without the computer, a debug build shows the notification
of a kind directly (nothing is received):
`adb -s emulator-5554 shell am start -n dev.lifeos.app/.MainActivity --es lifeos.test.push courses`
(also `answer`, `coach`, `reminder`, `notice`, …).

### Models and plan usage

Settings → **Models and plan usage**: one card per job with its model
(and effort and advisor) and where it comes from; under it "Today" and
"This week" with the runs, tokens and estimated cost (or "No runs"). Run a
chat or rerun the coach on the computer, pull down: the numbers of that job
grow. Set `LIFEOS_MODEL_ROUTING` on the computer (for example
`summaries=haiku`), restart the bridge, pull down: the job shows the new
model and "Set in the computer's routing setting". Tap **Change** on
"Summaries", pick another model and effort, Save: "Saved. Used from the next
run."; the card shows the new model and "Chosen in the app", and the
computer's audit log has a `ModelRouting` entry. **Back to the default**:
the card shows what the computer is set up with again. In flight mode,
Save says the computer cannot be reached and nothing changes.

### Chat and sessions

Paired, with the bridge running (each message is a model run on the plan;
keep them small). On an emulator without a computer, the `sample_data` test
aid shows the same screens with invented answers.

1. **New chat (#166):** open the Chat tab and tap **New chat**: an empty
   conversation with three example prompts opens at once, with no folder to
   choose and no folder in the header. The same from the pen icon in a
   conversation's top bar and on Sessions, and from **+** → **New chat**
   (type a few words first: they are in the composer, nothing is sent).
   Send a message: the first message goes out without a folder, so on
   Sessions the new session is listed under the chat's own new folder (a
   bridge with #163) or under the default folder (an older bridge).
2. **Send:** type "Say hello in one sentence" and send. The message appears
   on the right, then "Working… 0:03" counts up, then the answer is written
   on the left. Send a second message that refers to the first ("Say it in
   Turkish"): the answer shows that it is the same conversation. On the
   computer, `claude --resume` in that folder lists the session.
3. **Continues after a restart:** close the app completely, open it, unlock
   and tap the Chat tab: the Chat list opens (not the conversation), with
   the conversation under "Current chat"; tapping it shows its last
   messages, and a new message continues it.
4. **Sessions:** tap the clock (or More → Sessions): the pinned sessions,
   then one coloured tile per folder (all blank chats one "Chats" tile),
   also for sessions started in a terminal. Tap a tile: that folder's
   sessions, newest first. Type a word into the search field on the tiles
   screen: only matching sessions remain, from every folder. Tap a session:
   its last messages appear; send a message: it continues that session in
   its folder.
5. **Open on the computer:** run `claude --resume` on the computer, choose a
   session and leave it open. Open the same session in the app and send a
   message: "This session is open on the computer right now…" with
   **Retry**. Close it on the computer and tap Retry: the answer arrives.
6. **Computer not reachable:** stop the bridge, wait until the dot is amber,
   send a message: "Not sent: the computer cannot be reached…", and the text
   is back in the composer. Start the bridge, wait for the green dot, send it.
7. **Leaving during an answer:** send "Count slowly from 1 to 30, one number
   per line", and lock the phone at once (app lock on "Immediately"). Unlock
   after a few seconds and open the conversation: "The computer is still
   working on this…", and a little later the whole answer, once.
8. Settings → Language → **Türkçe** and Appearance → **Dark**: the Chat
   list, a conversation and Sessions are fully in Turkish and readable.

### The coach conversation

Paired (each message is a run on the plan, on the coach's model), or on an
emulator with the `sample_data` test aid:

1. Today → tap the coach card: "Coach conversation", "Read-only · model ·
   effort" under the title, "Plan usage today" with one line for this
   conversation and one for the morning run. Back, Chat tab → the pinned
   entry on top: the same screen with the same messages.
2. Send "What should I do first today?": the answer arrives as in any
   chat. There is no model pill and no "Read-only / Changes allowed" pill.
   On the computer, `GET /coach/chat` shows the session and the usage of
   `coach_chat` one run higher; the numbers under the top bar follow.
3. Tap the usage block: today and this week for the conversation and for
   the morning run, "x% of the length at which it is tidied up", and
   **Tidy up now**.
4. **Tidy up now** → confirm: "Tidying up…", then the conversation is
   empty ("…was tidied up and starts short again") and the sheet shows
   "Tidied up once so far" and the summary. On the computer the earlier
   session is listed as "Coach (earlier)" and `coach/conversation.md` in
   the memory has the summary.
5. A long conversation (sample mode: a message with "tidy"; on the
   computer: a transcript over the limit of `GET /coach/chat`): after
   sending, "The conversation had grown long: the computer tidies it up
   first…" with **Stop** next to it, then the answer, with the line "Tidied
   up here: what stands above was summarised, and what matters was written
   to 2 files of the memory." above the message. Tap **Stop** → confirm
   while it tidies up: "The conversation is being tidied up right now, and
   that is never stopped…"; the answer arrives all the same. On the
   computer the conversation's summary and facts are complete
   (`coach/conversation.md` in the memory).
6. Sessions: the session "Coach" opens the coach screen, not an ordinary
   conversation. Lock and unlock the app: the conversation is read again.

### Sessions by folder, rename and pin (#74, #168)

Paired (no model run is started by any step), or on an emulator with the
`sample_data` test aid, where the changes are kept until the app is closed:

1. Sessions: under "Pinned" the pinned sessions, then **By folder**: a wide
   **Chats** tile first (all blank chats; with the sample data two, one of
   them pinned), then the coach's tile (once the coach conversation has a
   message), the memory folder, and one tile per allowed folder, each in its
   own colour with its icon and ornament, with "N conversations · <when>".
   Pull to refresh and switch to Dark: every folder keeps its colour. A
   folder without conversations says "No conversations yet". On the
   computer, `GET /sessions` shows the same `colour` per folder.
2. Tap **Chats**: the top bar says "Chats", the blank chats are listed with
   the pinned one first; each row has the chat icon and no folder name.
   Back (the arrow or the system back) returns to the tiles, not to the
   screen before. Tap the project folder's tile: its sessions, each mark the
   folder's letter in the folder's colour. Tap the coach's tile: the coach
   conversation opens.
3. A session → long-press (or the dots at the end of its row) →
   **Rename**, type a title, **Save**: the row shows it at once, also under
   "Recent" on the Chat tab. On the computer, `GET /sessions` shows the new
   `title` and the old one as `detected_title`; the session's own file is
   unchanged. Rename again → **Use the title the computer found**: the old
   title is back. There is no "Move to a group" any more.
4. The same sheet → **Pin**: the session is listed under "Pinned" with the
   ring around its mark, and first in its folder's list; **Unpin** puts it
   back.
5. Type into the search field: the tiles give way to the matching sessions
   from every folder; clear it and the tiles are back.
6. Open a blank chat from the Chats tile: the Chat header says "Chat" (no
   folder name). Open a project session: the header names the folder with a
   dot in its colour.
7. Flight mode, then **Pin**: "Not changed: the computer cannot be reached
   right now." and the list is as before.

### Moving between conversations by asking, and a chat's files (#163, #164, #168)

Paired with a bridge that has #163 and #164, in a new blank chat (each step
is one short model run), or on an emulator with the `sample_data` test aid
(the words in brackets):

1. **One match** ("jump"): ask "Open my chat about the packing list" (name
   one that exists). The answer's marker line (`[[switch: …]]`) never shows,
   not even while the answer is written, and the app opens that
   conversation at once, in its own folder: its last messages appear and the
   header names its folder (a blank chat: "Chat"). The next message
   continues it there.
2. **Several** ("pick"): ask "Open one of my earlier chats about numbers or
   files, I am not sure which". A card "Which conversation?" lists them
   with title, folder and last activity; nothing switches by itself. Tap
   one: it opens. Go back to the first chat and send another message: the
   card is gone.
3. **A new one in a folder** ("begin"): ask "Start a new chat in my
   <project> folder". An empty conversation opens with the folder in the
   header (and its colour dot); its first message runs in that folder (on
   the computer, the new session is listed under that folder's tile).
4. **Never by itself:** after step 1, lock and unlock the app, and open the
   first chat again from Sessions: it shows its messages and stays there.
   Reading it again never switches.
5. **Moving files** ("move"; with "taken" the first Allow finds a name
   taken, with "broken" one file stays): in a blank chat ask the assistant
   to write a short file, then "Move that file into my <project>/notes
   folder". The marker line never shows (also not after reopening the chat);
   a card **Move files** lists every file with its size, the target's label
   and path, and **Allow** / **Don't allow**, with the hours it waits. The
   sample data adds a second card that the computer refused at once ("the
   target is not one of your allowed folders"), with no buttons.
6. **Allow**: "Moved 1 file into <project>/notes." On the computer the
   file is in the target and gone from the chat's folder. With "taken": the
   card stays open with "Not moved: something of the same name is in the
   target folder by now…"; Allow again moves it. With "broken": "Moved 1
   file…" and "1 file could not be moved and stays in the chat: …".
   **Don't allow** on another card: "Not moved." and nothing moved.
7. **Blank chats off** ("root off" as the first message of a new chat): on
   the computer, put a `CLAUDE.md` into the chat root (see `docs/bridge.md`,
   "Blank chats") and send a first message in a new chat: "Blank chats are
   switched off on the computer…" with "The computer says: …" and
   **Retry**. Remove the file and tap Retry: the answer arrives.
8. Settings → Language → **Türkçe**: the tiles ("Sohbetler", "Koç"), the
   card ("Hangi konuşma?") and the move card ("Dosyaları taşı") are in
   Turkish.

### Session reminders and reply notifications

Paired (no model run is needed for the reminder itself), or on an emulator
with the `sample_data` test aid (see "Test aids" for its notification):

1. Sessions → the dots of a session → **Remind me** → **New reminder**:
   choose today, a time two minutes ahead, **Once**, a note, **Save**. The
   row gets a small bell. On the computer, `GET /reminders` lists it with
   the time in UTC and the phone's time zone.
2. Lock the phone. When the time has come (the computer checks every 30
   seconds) a notification "Reminder" arrives, without the note. Tap it and
   unlock: the session's conversation opens and one line shows "Reminder:
   <note>". The bell is gone from its row.
3. **Remind me** → **New reminder** with a time that has passed: Save is
   grey and "This time has passed." With **Every day** the same time can be
   saved; the sheet then shows "Every day, next …".
4. Tap a reminder in the list: change its time or note, Save; open it
   again → **Delete the reminder**: it is gone, and so is the bell.
5. **Notify me when an answer is ready** off for a session; send it a
   message and close the app: no notification when the answer is ready
   (`GET /sessions` shows `notify_replies: false`). Switched on again, the
   next answer is announced. Another session is not affected.
6. In a conversation, the dots at the right of the pills above the composer
   open the same sheet for that session.

### A silent run, a lost first answer, and notifications that open the conversation

On an emulator with the `sample_data` test aid:

1. New chat → send "lost hello" (in the sample mode the message
   arrives but no answer reaches the phone): after a moment "The computer is
   still working on this…", and a few seconds later the answer. The app
   found the conversation under the id it had chosen; nothing was sent
   twice.
2. Send "slow count": the answer arrives over half a minute with
   keep-alives in between; "Working…" keeps counting and nothing breaks off.
3. Send "push it" with **Changes allowed** on: the approval card waits.
   Tap **Allow**: the card says "You allowed this." and stays so when the
   computer's own "closed" arrives.
4. Sessions: "Milestone summary draft" is marked **Open on the computer**,
   "Summarise the lecture notes of week 3" **Working**.
5. `adb shell am start -n dev.lifeos.app/.MainActivity --es lifeos.test.push
   approval --es lifeos.test.push_ref sample-session-4`, then tap the
   notification: the conversation of "Summarise the lecture notes of week
   3" opens with the approval card (`git push origin main`) waiting. Answer
   it: the run ends and the answer appears.

With the computer (each step is a short run on the plan):

6. Send a message, close the app, wait for the "answer ready"
   notification, tap it and unlock: that conversation opens with the
   answer. With "Changes allowed" on, ask for a deletion, lock the phone,
   tap the "approval" notification: the conversation opens with the card.
7. Start a new chat and switch flight mode on right after tapping send
   (before "Working…" counts up); switch it off after a minute: the app
   says whether the message arrived (the run is followed) or offers
   **Retry**; on the computer the conversation exists once at most.
8. Ask for something that takes a minute without output ("run `sleep 70`
   and say done" with "Changes allowed"): the answer arrives as usual, with
   no "The computer is still working on this…" in between.

### Earlier messages, waiting messages, send now and stop

Paired (each message is a run on the plan), or on an emulator with the
`sample_data` test aid, where "slow" in a message makes its answer take half a
minute:

1. Open a session with more than four exchanges (Sessions → a longer one).
   Its last messages are shown. Scroll to the very top: "Loading earlier
   messages…", then older messages appear above and the message you were
   looking at stays in place. Repeat until nothing is added.
2. Send "Count slowly from 1 to 40, one number per line". While the answer
   is written, send "Then say done" and "And say it in Turkish": both appear
   under "Waiting to be sent · 2", not in the conversation. On the computer,
   `GET /sessions/<id>/queue` lists them.
3. Tap **Edit** on the first, change it, Save: the row shows the new text.
   Tap **Remove** on the second: it is gone.
4. When the count is finished the waiting message moves from "Waiting to be
   sent" into the conversation and its answer is written as it arrives, with
   "Working…" and **Stop** (for a moment before that: "The computer is still
   working on this…").
5. Start a long answer again, send a second message, tap **Send now** on it:
   a question says that changes made to files are kept. Confirm: the count
   breaks off ("Stopped. Changes … are kept."), and the second message is
   answered next, written as it arrives. On the computer the queue never
   held the message twice (`GET /sessions/<id>/queue`).
6. Start a long answer, send a second message and tap **Stop** → confirm:
   the answer breaks off, the second message stays under "Waiting to be
   sent" with "On hold since the run was stopped". **Send them** lets it go
   on; so does sending another message, or **Send now** on the message
   (without a question: nothing is running).
7. With the computer: send "Count slowly from 1 to 60, one number per line",
   lock the phone while it counts (app lock on "Immediately"), unlock and
   open the conversation: the numbers so far are there once, and the count
   goes on being written, with **Stop**. Switch flight mode on for a few
   seconds during another count: after "The computer is still working on
   this…" the answer continues where it was.
8. With the computer (#157): start a long answer ("Count slowly from 1 to
   120, one number per line"), then lock and unlock the phone five times
   during the same run. Each time the count is on screen once and goes on;
   the other screens (Today, Notes) still load at once, and the whole answer
   is there at the end (without the detach the relay's four workers would
   be taken after the fourth lock, until the run ends).

### Model, effort and "/" commands

1. Open a conversation: the header shows "folder · model · effort" (the
   defaults until something is chosen). Tap the model pill above the
   composer, choose another model and effort, **Save**: header and pill
   change. Send a message; on the computer `GET /sessions` shows the
   session's `model` and `effort`. Choose "Default (…)" for both again.
2. Settings → Models → "Chat sessions" → **Change** sets the default: a
   conversation without its own choice shows it in its header afterwards.
3. In a session of a folder that has `.claude/skills` or `.claude/commands`,
   with the folder's skills switched on for the session (its sheet → **Use
   this folder's skills and commands**),
   type "/": the folder's skills and commands are listed. Type two letters:
   the list narrows. Tap one: its name is completed, coloured in the
   composer, and the line says that commands run only with "Changes
   allowed". Switch that on and send: the skill runs.
4. Type "/nothing-like-this x": "This is not one of the skills or commands
   this conversation can use"; sent with "Changes allowed", the computer
   refuses it and the message can be corrected.
5. **Personal skills (#172).** On the computer, have one personal skill in
   `~/.claude/skills/<name>/SKILL.md` (and the guardrail check passed with
   the personal-skill probes). In a session **without** the folder's switch,
   type "/": the personal skill is listed as "Your personal skill", the
   folder's own are not. Choose it, switch **Changes allowed** on and send:
   the skill runs. Switch the folder's skills on: both kinds are listed, the
   folder's marked "Skill of this folder"; when both have the same name, the
   personal one is completed as `/personal:<name>`. Without any personal
   skill and with the switch off, "/" says that the folder's own are
   switched off and there are no personal ones; nothing claims that a "/"
   message is sent as plain text. The session sheet says how many personal
   skills there are, and that they are offered without the switch.

On an emulator with `sample_data`: a session without the switch lists
"standup" and "summarise" as personal skills on "/"; with the switch on, the
three folder entries come first and the personal "summarise" is completed as
`/personal:summarise `.

### Files in chat

1. In a conversation in a folder with a small text file (there is no folder
   picker since #166: open a session of that folder from Sessions, or use
   **Ask about this project**), send "Give me the
   file notes.md" (name a file that exists). The answer is followed by a card
   with the file's name, kind and size. On the computer, nothing was read
   yet: the audit log (`tail -n 2 ~/.config/lifeos/audit/$(date -u
   +%Y-%m).jsonl`) has a `FileOffer` line and no `FileDownload`.
2. Tap the download arrow: "Downloading… %", then "Saved to Downloads" and
   **Open**. The file is in the phone's Downloads folder with the same size;
   the audit log now has `FileDownload` (name and size only). Tap **Open**:
   the phone's app for that kind of file shows it.
3. Ask for a file larger than the limit (50 MB unless changed), or one
   outside the allowed folders: the card says that it is not sent and why.
4. Lock and unlock the app (or open another session and come back) within
   15 minutes: the card still has its download arrow, and the download
   works. Wait until the 15 minutes are over (or restart the bridge) and
   open the conversation again: the card shows the file's name with "Ask for
   this file again"; a card that was on screen all the time says "The
   computer no longer sends this file…" when tapped.
5. Start a larger download and switch flight mode on: the card says that the
   computer could not be reached, and no partial file is in Downloads.

On an emulator with `sample_data`, "file" in a message gives an answer with a
20-byte file to download and a refused one.

### Approvals and questions

On an emulator with the `sample_data` test aid (no computer, nothing runs):

1. Chat → **New chat**. Tap the pill **Read-only**: it becomes
   **Changes allowed** with a line of explanation.
2. Send "delete the old drafts": a card with a red frame, "The assistant
   wants to delete files permanently", the warning, the whole `rm` command,
   the folder, **Allow**, **Don't allow** and "Counts as “no” in 9:5x". Tap
   **Don't allow**: "You did not allow this." and the answer says that
   nothing was changed. Send it again and tap **Allow**: "You allowed this."
3. Send "hurry push it" and wait 20 seconds without tapping: the buttons go
   away and the card says that it was not answered in time.
4. **Option cards:** send "choose for me": two questions with choices.
   Send stays grey until both have an answer; choose one for the first, two
   for the second (or type your own words), **Send**: the card shows "Your
   answer: …" under each question and "Answered.", and the reply repeats
   the choices. **Skip** declines instead. With "hurry choose" and no tap,
   the card says after 20 seconds that the assistant went on without an
   answer.

With the computer (the bridge with `LIFEOS_RUNNER=sdk` and the capable mode
switched on by its guardrail check; each step is a short run on the plan):

5. In a conversation in the memory folder (an existing session of that
   folder, opened from Sessions) switch **Changes allowed** on and
   send "Create the file approval-test.txt here with one line of text, then
   delete it again with rm". The card shows the `rm` command and the memory
   folder. **Don't allow**: the assistant says it was not allowed, the file
   is still there, and `tail -n 4 ~/.config/lifeos/audit/$(date -u
   +%Y-%m).jsonl` on the computer shows the request and `refused` by the
   owner. Ask it to delete the file again and tap **Allow**: the file is
   gone and the log shows `approved`.
6. Ask for a deletion once more and do not answer: after ten minutes the
   card says that it counted as "no", the file is still there, and the log
   shows `refused` with `timeout`.
7. Lock the phone while a card is waiting, unlock and open the conversation:
   "The computer is still working on this" and the same card, which can
   still be answered.
8. **Option cards:** send "Use the question tool to ask me Red or Blue,
   then say what I chose": a question card with the two choices; choose one,
   **Send**: the reply names it. Send it again and type "Green" into "Or
   write your own answer" instead: the reply names Green. Ask "Use the
   question tool to ask me which of Mon, Tue, Wed suit me, several can be
   chosen": the rows have check boxes and the reply lists all chosen days.
9. With "Changes allowed" on and the capable mode switched off on the
   computer, a message is refused with "Making changes is switched off on
   the computer right now…" and **Retry**.

### Skill installs and the skills switch (#97)

On an emulator with the `sample_data` test aid (nothing is installed
anywhere):

1. Open a session, tap the dots next to the pills: the sheet has **Use this
   folder's skills and commands** with its explanation, "This folder has
   3 skills and commands." and "You have 2 personal skills or commands…".
   Switch it off, close the sheet and type "/": only the two personal ones
   are listed. Switch it on again: "/" lists all five.
2. Send "install a skill": under the answer a card "Skill install" with the
   source address and "The computer is fetching it…", and only **Don't
   install**. After a few seconds, without a tap, it shows what the skill
   says about itself, "Contains 3 files, 4.8 kB" with each file and size,
   two warnings in amber, "Would be installed into …/.claude/skills/tidy-notes",
   **Install**, **Don't install** and "It waits 23 h 59 min more."
3. Send another message without answering: the card is still there, below
   the new answer. Go back, open another session and return (or lock and
   unlock): the card is there again.
4. Tap **Install**: "Installed." Send "install a skill" again and tap
   **Don't install**: "Not installed."
5. Send "install broken": after a few seconds the card says that there is
   not a usable skill, with **Put away** only. Send "install plain": the
   card says at once that only an HTTPS address is accepted, without buttons.
   Send "install taken", wait for the contents and tap **Install**: "Not
   installed: a skill of this name is in the folder by now…", and the card
   is still there with its files; **Don't install** puts it away.

With the computer (capable mode on; each step is a short run on the plan):

6. In a session of an allowed folder switch the skills on, switch **Changes
   allowed** on and send "Propose installing the skill at <an HTTPS address
   of a SKILL.md> with the install line". The card appears; on the computer
   `GET /sessions/<id>/interactions` lists it and nothing is in
   `<folder>/.claude/skills/`. Close the app, open it and the conversation
   again: the card is back. **Install**: the folder now has
   `.claude/skills/<name>/SKILL.md`, and the audit log (`tail -n 3
   ~/.config/lifeos/audit/$(date -u +%Y-%m).jsonl`) shows the proposal and
   `skill_install_approved`. "/" then lists the skill.
7. Propose the same skill again: the card says that a skill of this name
   exists in the folder already and that nothing is replaced, with **Put
   away** only.

### A session's MCP servers (#153, personal ones #172)

On an emulator with the `sample_data` test aid (nothing is started
anywhere):

1. Open a session → the dots next to the pills → **MCP servers**. The
   warning in the amber frame stands first; below it three servers of the
   folder, each marked "This folder's server":
   "calendar" (approved, amber frame, an address and the header name
   `Authorization`), "issue-tracker" (changed since the approval) and
   "notes-index" (not approved, `npx -y notes-index-server --root notes`,
   the variable name `NOTES_TOKEN`); then two personal ones, marked "Your
   personal server: one approval covers every folder": "web-search" (not
   approved) and "team-wiki" ("Cannot be approved…", no button at all). No
   value of a variable or header is shown anywhere.
1a. **Review and approve** on "web-search": the card asks "Approve your
   personal MCP server “web-search” for every folder?" and says that one
   approval covers every folder (no folder path). **Approve**: "Approved.
   Messages with “Changes allowed” in every folder…"; back in the list it
   is "Approved for every folder". **Revoke approval** takes it back.
2. **Review and approve** on "issue-tracker": the approval card with the
   same warning, the command and the folder. **Approve**: "Approved.
   Messages with “Changes allowed”…"; **Back to the servers**: it is listed
   as approved, with the amber frame.
3. **Revoke approval** on "calendar": "The approval was revoked." and it is
   listed as not approved.
4. **Review and approve** on "notes-index", then close the sheet without
   answering: the same card waits in the conversation ("It waits 23 h 59 min
   more."), also after another message and after reopening the conversation.
   Answer it there with **Don't approve**: "Not approved."
5. Send "mcp please": under the answer, "This answer used the MCP server
   issue-tracker, which runs outside the sandbox." Send "mcp unchecked":
   "Not run: this folder has approved MCP servers…" with **Retry**.

With the computer (capable mode on; an allowed folder with a `.mcp.json`
that declares a harmless server, for example `{"mcpServers": {"clock":
{"command": "python3", "args": ["tools/clock_server.py"]}}}`):

6. The sheet lists "clock" as not approved, and `GET
   /sessions/<id>/mcp-servers` on the computer shows the same. **Review and
   approve** → **Approve**: the list shows it approved; `tail -n 2
   ~/.config/lifeos/audit/$(date -u +%Y-%m).jsonl` has the request and
   `mcp_server_approved`.
7. Send a message with **Changes allowed** that needs the server: the answer
   names it under its text, and the audit log has `mcp_servers_used`.
8. Change an argument in `.mcp.json` on the computer and open the sheet
   again: "Changed since you approved it: not used". **Revoke approval**: it
   is not approved, and the log has `mcp_server_revoked`.
9. **A personal server (#172).** Add a harmless user-scope server on the
   computer (`claude mcp add --scope user clock -- python3
   /path/to/clock_server.py`) and restart the bridge (its check reruns with
   the new probes). The sheet of any session lists "clock" twice if the
   folder also has one, each with its scope. Approve the personal one: the
   card says it is for every folder; open a session of **another** allowed
   folder: its sheet shows the personal "clock" approved as well. On the
   computer `GET /sessions/<id>/mcp-servers` shows `"scope": "personal"`,
   and the audit log has the request and the approval with the scope.
   Revoke it from either session: it is not approved in both. A personal
   server whose definition uses `headersHelper` is listed as "Cannot be
   approved" without buttons.

### Course resources (#172)

On an emulator with `sample_data`: open a course → **Resources**. Above the
list: "Nothing is downloaded yet: first name, on the computer, the folders
where you already keep course resources." Three items: the open textbook
("Waiting: nothing is downloaded until you have named your resource folders
on the computer"), the lecture notes ("Not downloaded: you already have this
file") and the handbook ("Listed only: a paid book"). Tapping a title opens
its HTTPS address in the browser. In Turkish the same lines are in Turkish.

With the computer: before `~/.config/lifeos/resource_folders` exists, a
course with a syllabus shows the same top line and its items as waiting
(`GET /courses/<id>/resources` has `"folders_named": false`). Create the
file (an empty one is enough), wait for the next resources run (or ask for
one with `POST /courses/<id>/resources/refresh` on the computer) and pull to
refresh: the line is gone; items are downloaded or listed with their reason,
and a book already in one of the named folders says "you already have this
file".

### The warmer design (P2, Courses in P3)

With real data (or, on an emulator, with the `sample_data` test aid):

1. **Today:** the coach card looks as before. "Assistant's picks" and
   "Today" are grey cards with a faint star or sun in the top corner and a
   small coloured icon before the heading. Each pick's number and each
   task's check ring have the colour of the task's course or project, and
   the task shows a small label with that course's icon and name.
2. **Tasks:** "Most important" with a faint check mark watermark; the same
   labels and coloured rings.
3. **Courses:** a rose deadline card with dots and up to three tickets
   (coloured stripe on top, the days left in large); the course tiles in
   their colours, the first one wide, each with its own ornament. Open a
   course: its header card has the same colour, icon and ornament as its
   tile; its files and links carry that colour. "All grades" shows each
   course with its icon tile.
4. A course has the same colour and icon everywhere: compare its tile, its
   page, a deadline ticket and the label of one of its tasks.
5. Settings → Appearance → **Dark**: every tint switches to its dark step;
   all text stays readable (text, muted text and course names on the tints).
