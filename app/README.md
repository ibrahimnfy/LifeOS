# LifeOS Android app

The Android client of LifeOS: Kotlin, Jetpack Compose, a single activity,
English and Turkish. The concept is in
[`docs/app-design.md`](../docs/app-design.md); the look is visual direction
D3 from [`docs/design/mockups/`](../docs/design/mockups/).

What exists now: the **app skeleton** (navigation, D3 theme, language and
appearance), and the **connection to the computer**: pairing by QR code, the
end-to-end layer of [`docs/security.md`](../docs/security.md), the relay
client and the app lock. On top of that, **Today, Tasks, Capture, Calendar,
Notes and Courses work on the owner's real data** through that connection,
with saved copies for when the computer is unreachable and an outbox for
writes made offline. Chat and the other screens under More are still
placeholders.

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
        │   ├── lock/                lock timeout, system confirmation dialog
        │   ├── settings/            theme, language, lock timeout (DataStore)
        │   └── ui/
        │       ├── theme/           D3 colour tokens, Material 3 mapping, type
        │       ├── icons/           the app's own line icons
        │       ├── components/      top bar, bottom bar, rows, cards, QR scanner
        │       ├── nav/             routes and the navigation host
        │       └── screens/         Today, Chat, Tasks, Calendar, Notes, Courses, More,
        │                            Settings, Pair, Lock
        ├── main/res/values/         English strings (default)
        ├── main/res/values-tr/      Turkish strings
        ├── debug/                   test aids of debug builds (see below)
        ├── release/                 their empty twin for release builds
        └── test/                    JVM unit tests and the shared test vectors
```

Conventions:

- Every user-visible text is a string resource, in both languages. No text
  is hard-coded in Kotlin.
- Colours come only from the D3 tokens in `ui/theme/Tokens.kt`; every
  Material 3 colour role is mapped from them and dynamic colour is off.
  (`warn`, the amber of "computer not reachable", was added with the
  connection dot.)
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
  inside the card. Pulling down asks the computer for a fresh plan (a model
  run there; it quietly keeps the old plan when one was made in the last ten
  minutes).
- **Tasks:** with a plan, "Most important" (the first five of the coach's
  order) and "Show all (n)", which opens the whole ordered list with filters
  (overdue, today, upcoming, no date). Without a plan, the open tasks by
  date. A tick hides the task at once and can be undone for a few seconds;
  then it is sent.
- **Capture:** a note or a task with an optional due date. Also reachable
  from the home-screen shortcut (long-press on the app icon) and from other
  apps' "share" (text or a link).
- **Calendar** (under More): the agenda by day from a week ago to two months
  ahead, a month strip to jump, adding and changing events (all day, timed,
  several days). Course deadlines are ordinary events here.
- **Notes** (under More): only the notes captured on this phone that still
  wait to be sent, and a line saying that the others live on the computer.
- **Courses** (under More): summary card with the deadlines of the next two
  weeks and "All grades", the course list with markers for new content, the
  refresh action (starts a sync on the computer and waits for it), and a
  course page with Materials, Announcements, Assignments and Grades. Files
  are listed, not stored; "Download" saves one to the phone's Downloads
  folder.
- **More:** Sessions, Projects and Activity are placeholder screens.
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
- **Outbox** (`data/Outbox.kt`): new notes and tasks, ticks, new and changed
  events are stored on the phone first and sent in order as soon as the
  computer answers. "Attempt started" is stored before an item is sent.
  If nothing left the phone, the item keeps waiting. If it left but no
  answer came, a tick or an event change is repeated later (harmless; a tick
  answered with "task not found" counts as done), while a new note, task or
  event is marked **"Please check"** and is sent again only when the owner
  says so, and not before the first attempt can no longer arrive (six
  minutes). A refusal by the computer is shown as "Not accepted". Unsent
  items are deleted on unpair.
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
  "Saved to Downloads" is shown until the app is closed. Direct saving needs
  Android 10 or newer; on Android 8 and 9 the system's "save as" screen asks
  where to put the file.

What the bridge does not offer yet, and the app therefore does not do:
listing or editing notes, editing, reopening or deleting a task, deleting an
event, and listing finished tasks (no "done" filter).

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

Not built yet: push, and Chat through the connection.

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
- **Release builds do not contain any of this.** They compile the empty
  twin in `src/release/`; a pairing left behind by a test aid is deleted
  when a build without the aid starts.

The pairing text is the key. Never put it into a file, a log or a report.

## What is not in it yet

- No push notifications.
- Chat does not send; the coach conversation, Sessions, Projects and
  Activity are placeholders.
- Tasks cannot be edited or moved, notes cannot be browsed (see above).
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
   coach has a plan). Tap it: it disappears and "Marked as done · Undo"
   appears. Tap **Undo**: it is back. Tap it again and wait: on the computer
   the task has moved to `tasks-done.md`.
3. **Tasks:** with open tasks and a plan, "Most important" and "Show all
   (n)" with the four filters; without a plan, sections by date.
4. **Calendar** (More → Calendar): the agenda with a month strip. **+** adds
   an event; tapping an event changes it (switch "All day" off to give it a
   time). The computer's `calendar/events.md` has the line.
5. **Offline:** switch the phone to flight mode. After a moment the dot
   turns amber and the screens show "Saved copy · updated … ago". Capture a
   note: "Waiting to sync"; More → Notes lists it. Switch flight mode off:
   within seconds the note is in `inbox.md`, once, and the list is empty.
6. **Shortcut and share:** long-press the app icon → "Capture"; in another
   app, share a piece of text → "Capture in LifeOS": the sheet opens with it.
7. **Courses** (More → Courses; needs the course sync on the computer and a
   bridge that has the `/courses` endpoints): the summary card, "All grades",
   the course list; a course's four tabs; the download arrow of a file →
   "Saved to Downloads", and the file is in the phone's Downloads folder.
   The refresh arrow in the top bar starts a sync.
8. **Unpair:** Today and Tasks show "Not connected yet" again; saved copies
   and waiting items are gone.
