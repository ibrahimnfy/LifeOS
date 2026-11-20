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
coach conversation and Projects are still placeholders.

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
  the rank of a pick, a deadline, its page and its files. The mapping is in
  `data/Looks.kt` and depends only on the course ids: courses in id order
  take the tone at `id mod 7` and the icon at `id mod 6`, or the next free
  one when a course with a smaller id has it; projects and unknown course
  names get a tone from a fixed hash of their name and the folder icon.
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
  bar under the active one, and the capture (+) button in the centre.
- Top bar: bold title and the connection dot: grey (not paired), hollow
  (connecting), green (the computer answered), amber (not reachable). It
  opens Settings.
- **Today:** three cards from the coach's plan and the agenda. The coach
  card shows one message of the plan, another one each time the app is
  opened (tapping it opens a "Coach conversation" placeholder);
  "Assistant's picks" is the plan's top three with their reasons; "Today"
  lists overdue and today's tasks, three visible and the rest by scrolling
  inside the card. Pulling down only reads the plan and the agenda again; it
  never starts a model run. The coach card's **Rerun** button asks the
  computer for a new plan (a model run of a few minutes there); the card
  shows the progress and, when a plan was made in the last ten minutes, the
  computer's refusal. Without a plan the card offers **Prepare a plan**.
- **Tasks:** with a plan, "Most important" (the first five of the coach's
  order) and "Show all (n)", which opens the whole ordered list with filters
  (overdue, today, upcoming, no date). Without a plan, the open tasks by
  date. The round check ticks a task: it is hidden at once and the tick can
  be undone for a few seconds; then it is sent. Tapping the rest of the row
  (here and on Today) opens the task to change its title and due date (or
  remove the date); the change is shown at once with "Waiting to sync" until
  the computer confirmed it.
- **Chat** (the tab): the coach's conversation pinned on top (a placeholder
  for now), the chat in progress, the three most recent sessions with **All
  sessions**, and **New chat**. New chat asks where it should run: the
  memory folder (the default) or one of the allowed folders, as the computer
  lists them. A conversation shows the owner's messages on the right and the
  assistant's answers on the left (paragraphs, headings, lists, code blocks,
  bold and inline code are rendered; the text can be selected), "Working…"
  with the time since the message was sent, and the answer as it is written.
  **The same conversation is continued until New chat** (or until another
  session is opened), also after the app was closed: the Chat tab then opens
  it directly, once per start, with its last few messages read from the
  computer. The folder is in the header.
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
  - **Questions:** when the assistant asks with its question tool, a card
    shows each question with its choices (one or several) and a field for
    the owner's own words, with **Send** and **Skip**.
- **Sessions** (the clock in Chat, or under More): a field that filters by
  title and folder, pinned sessions, then every folder with its sessions,
  newest first; "Working" marks a session the computer is answering right
  now. Tapping one opens it in Chat; the next message continues it in its own
  folder. A session that is open in a terminal on the computer is refused
  there when the message is sent, and the app says so and offers **Retry**.
- **Capture:** a note or a task with an optional due date. Also reachable
  from the home-screen shortcut (long-press on the app icon) and from other
  apps' "share" (text or a link).
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
  with the next deadline, and Materials, Announcements, Assignments and
  Grades; files and links carry the course's colour. Files
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
- **More:** Projects is a placeholder screen.
- **Settings:** language, appearance, **connection** (pair, state, name,
  last contact, "Test connection", "Unpair this phone"), **app lock**
  (immediately / 1 minute / 5 minutes), app version.
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
  left but no answer came, a tick, a task edit or an event change is
  repeated later (harmless; a tick answered with "task not found" counts as
  done), while a new note, task or event and a note edit are marked
  **"Please check"** and are sent again only when the owner says so, and
  not before the first attempt can no longer arrive (six minutes). A note
  edit sent again that is answered with "note not found" counts as done (the
  first attempt arrived and gave the note its new id). A second edit of a
  note replaces the first one while that is still waiting; once the first is
  on its way, the note cannot be edited until it is confirmed. A refusal by
  the computer is shown as "Not accepted". Unsent items are deleted on
  unpair.
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
    that a run may stay quiet for up to 11 minutes.
  - **Whenever the app stops receiving an answer the computer is still
    writing** (the app was locked, the connection dropped, a long silence),
    the run goes on there. The conversation then shows "The computer is still
    working on this", asks every five seconds while it is on screen (`GET
    /sessions/{id}/queue`), and reads the last messages (`GET
    /sessions/{id}/history`) when the run has ended. If the conversation
    there does not end with the message, the app says that it probably did
    not arrive and offers **Retry**; it never repeats a message on its own. A first message that
    got no answer at all cannot be followed up (its session is not known):
    the app says that it may have arrived and points to Sessions.
  - A refusal (session open on the computer, too many runs, folder gone,
    message too long) keeps the message with **Retry**; a run that breaks off
    keeps what was written and says why.
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
    clock, so the two wall clocks need not agree. `404 not_pending` is shown
    as "no longer waiting"; an answer that could not be sent can be given
    again. While the app is not receiving the answer (locked, connection
    lost) it reads `GET /sessions/{id}/interactions` with every look at the
    run, so a waiting card appears again and can still be answered. Cards
    are kept in memory only, until the next message.

What the bridge does not offer yet, and the app therefore does not do:
editing topic notes or moving notes, reopening or deleting a task, deleting
an event, and listing finished tasks (no "done" filter).

## Connection to the computer

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

Not built yet: push.

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
  computer), `fail` (the run breaks off), `offline` (nothing is sent, the
  text comes back), `silent` (the answer stops arriving and the message is
  not found on the computer), `delete` or `push` (the run waits for an
  approval; the first is a deletion), `choose` (the run waits for the answer
  to two questions). With `hurry` the wait lasts 20 seconds instead of ten
  minutes, to see what running out of time looks like.

  ```sh
  adb -s emulator-5554 shell am start -n dev.lifeos.app/.MainActivity --ez lifeos.test.sample_data true
  ```
- **Release builds do not contain any of this.** They compile the empty
  twin in `src/release/`; a pairing left behind by a test aid is deleted
  when a build without the aid starts.

The pairing text is the key. Never put it into a file, a log or a report.

## What is not in it yet

- No push notifications.
- Chat: older messages than the last few (scrolling up), messages while a
  run is in progress (queue, send now) and cancelling a run, "/" commands,
  the model picker, file cards (a file the assistant offers is only named in
  its answer) and tables in answers (shown as text). Sessions: groups,
  renaming, pinning and reminders. The coach conversation and Projects are
  placeholders.
- Tasks cannot be moved up or down; topic notes cannot be edited (see above).
- Courses: "Ask about this course" and renewing the sign-in from the phone.
- No instrumented (on-device) tests; only JVM unit tests.

## Manual check

Without pairing:

1. Install and open the app. A welcome screen offers pairing; choose
   **Later**. Today shows "Not connected yet" with the pair button.
2. Tap the four bottom-bar icons in turn: Today, Chat, Tasks, More. The
   gradient bar moves to the active icon.
3. More → Settings → Language → **Türkçe**: every text switches to Turkish
   at once and the Settings screen stays open. Switch back as you like.
4. Settings → Appearance → **Dark**: the app turns dark, including
   the status-bar icons. Close and reopen the app: the choice is kept.
5. With a larger system font size the text grows and nothing is cut off.

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
   sync". Switch flight mode off: within seconds the note is in `inbox.md`,
   once, and on the Notes screen it moves into the computer's list.
6. **Notes and task edits:** capture a note; More → Notes shows it at the
   top of the list (newest first) together with the older captures and the
   topic notes. Type a word of it into the search field: only matching notes
   remain. Tap the note → **Edit**, change the text, Save: "Saved", the
   screen closes and the list shows the new text; on the computer the line in
   `inbox.md` has the new text and its original time. A topic note opens
   read-only. On Tasks, tap a task's title (not the check): change the title,
   pick another date (or **Remove** it), Save; the row changes at once and
   `tasks.md` has the new title and date. In flight mode the same edits show
   "Waiting to sync" and reach the computer once it is off.
7. **Shortcut and share:** long-press the app icon → "Capture"; in another
   app, share a piece of text → "Capture in LifeOS": the sheet opens with it.
8. **Courses** (More → Courses; needs the course sync on the computer and a
   bridge that has the `/courses` endpoints): the top bar shows "Courses"
   with "Last sync …" under it, "All grades" and the refresh arrow. The rose
   deadline card shows only "Upcoming deadlines" as tickets; the course tiles
   follow. A course's colour and icon are the same on its tile, its page,
   its files, its deadlines and the labels of its tasks. A course's four tabs;
   the download arrow of a file → "Saved to Downloads", and the file is in
   the phone's Downloads folder. Tap **Open** next to it: a PDF opens in the
   phone's PDF viewer (or the system asks which app to use); back in LifeOS
   nothing changed. Delete the file in the Files app and tap **Open** again:
   "This file is no longer in Downloads", and the download arrow is back.
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

### Chat and sessions

Paired, with the bridge running (each message is a model run on the plan;
keep them small). On an emulator without a computer, the `sample_data` test
aid shows the same screens with invented answers.

1. **New chat:** open the Chat tab and tap **New chat**. "Where should this
   chat run?" lists **Memory (default)** and the allowed folders. Choose one,
   **Start**: an empty conversation with three example prompts; the header
   shows the folder.
2. **Send:** type "Say hello in one sentence" and send. The message appears
   on the right, then "Working… 0:03" counts up, then the answer is written
   on the left. Send a second message that refers to the first ("Say it in
   Turkish"): the answer shows that it is the same conversation. On the
   computer, `claude --resume` in that folder lists the session.
3. **Continues after a restart:** close the app completely, open it, unlock
   and tap the Chat tab: the same conversation opens with its last messages.
   Back leads to the Chat list, where it is under "Current chat".
4. **Sessions:** tap the clock (or More → Sessions): sessions grouped by
   folder, newest first, also the ones started in a terminal. Type a word
   into the search field: only matching sessions remain. Tap a session: its
   last messages appear; send a message: it continues that session in its
   folder.
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

### Approvals and questions

On an emulator with the `sample_data` test aid (no computer, nothing runs):

1. Chat → **New chat** → Start. Tap the pill **Read-only**: it becomes
   **Changes allowed** with a line of explanation.
2. Send "delete the old drafts": a card with a red frame, "The assistant
   wants to delete files permanently", the warning, the whole `rm` command,
   the folder, **Allow**, **Don't allow** and "Counts as “no” in 9:5x". Tap
   **Don't allow**: "You did not allow this." and the answer says that
   nothing was changed. Send it again and tap **Allow**: "You allowed this."
3. Send "hurry push it" and wait 20 seconds without tapping: the buttons go
   away and the card says that it was not answered in time.
4. Send "choose for me": two questions with choices. Send stays grey until
   both have an answer; choose one for the first, two for the second (or
   type your own words), **Send**: "Answered." and the reply repeats the
   choices. **Skip** declines instead.

With the computer (the bridge with `LIFEOS_RUNNER=sdk` and the capable mode
switched on by its guardrail check; each step is a short run on the plan):

5. In a conversation in the memory folder switch **Changes allowed** on and
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
8. Send "Use the question tool to ask me Red or Blue, then say what I
   chose": a question card; choose one, **Send**: the reply names it.
9. With "Changes allowed" on and the capable mode switched off on the
   computer, a message is refused with "Making changes is switched off on
   the computer right now…" and **Retry**.

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
