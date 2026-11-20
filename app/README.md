# LifeOS Android app

The Android client of LifeOS: Kotlin, Jetpack Compose, a single activity,
English and Turkish. The concept is in
[`docs/app-design.md`](../docs/app-design.md); the look is visual direction
D3 from [`docs/design/mockups/`](../docs/design/mockups/).

This folder currently holds the **app skeleton**: it builds, installs and
runs, shows the approved navigation and the D3 theme, and switches language
and appearance. It has no network code and stores nothing but the appearance
setting.

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
        │   ├── settings/            theme and language settings, DataStore
        │   └── ui/
        │       ├── theme/           D3 colour tokens, Material 3 mapping, type
        │       ├── icons/           the app's own line icons
        │       ├── components/      top bar, bottom bar, rows, capture sheet
        │       ├── nav/             routes and the navigation host
        │       └── screens/         Today, Chat, Tasks, More, Settings
        ├── main/res/values/         English strings (default)
        ├── main/res/values-tr/      Turkish strings
        └── test/                    JVM unit tests
```

Conventions:

- Every user-visible text is a string resource, in both languages. No text
  is hard-coded in Kotlin.
- Colours come only from the D3 tokens in `ui/theme/Tokens.kt`; every
  Material 3 colour role is mapped from them and dynamic colour is off.
- Icons are drawn for this project (`ui/icons/LifeIcons.kt`). No icon
  library is used.
- Touch targets are at least 48 dp, icon-only controls have content
  descriptions, text sizes are in `sp`.

### Versions

The newest stable versions were taken that work with the installed SDK
(platform 36). Compose, Lifecycle, Navigation and Core are therefore one
release line behind their newest versions, which need platform 37; lint
reports this as `GradleDependency` warnings. Raise `compileSdk` and those
versions together once platform 37 is installed.

## What is in the skeleton

- Bottom bar: Today, Chat, Tasks, More as icon-only items with a gradient
  bar under the active one, and the capture (+) button in the centre.
- Top bar: bold title and the connection dot (grey: not paired; it opens
  Settings).
- **Today:** coach card in the gradient ring, "Top three", "Today".
- **Chat:** empty state with three example prompts and the composer.
- **Tasks:** "Most important" and the "Show all (n)" button.
- **More:** Sessions, Notes, Projects, Calendar, Courses, Activity (each a
  placeholder screen) and Settings.
- **Settings:** language (follow phone / English / Türkçe, switched at
  runtime with per-app locales), appearance (light / dark / follow phone,
  kept in DataStore), connection ("Not paired"), app version.
- **Capture sheet:** text field, Note/Task switch, Save.

All tasks and the coach text are invented sample content and are marked as
such on screen.

## What is not in it yet

- No network code and no permissions: no relay connection, no encryption,
  no pairing (the "Pair with computer" button is disabled), no push.
- No app lock.
- No storage: a capture is confirmed but not kept; ticks on sample tasks are
  forgotten when the screen is left.
- Chat does not send; "Show all" and the screens under More are placeholders.
- No instrumented (on-device) tests; only JVM unit tests.

## Manual check

1. Install and open the app. Today appears in the light theme, in the
   phone's language (English unless the phone is set to Turkish).
2. Tap the four bottom-bar icons in turn: Today, Chat, Tasks, More. The
   gradient bar moves to the active icon.
3. More → Settings → Language → **Türkçe**: every text switches to Turkish
   at once and the Settings screen stays open. Switch back as you like.
4. Settings → Appearance → **Dark**: the app turns dark, including
   the status-bar icons. Close and reopen the app: the choice is kept.
5. Tap **+** in the bottom bar: the capture sheet opens with the keyboard.
   Type something, choose Note or Task, tap Save: the sheet closes and
   "Saved on this phone" is shown briefly.
6. More → any of the first six rows: a placeholder with the title and
   "Coming with a later step"; the back arrow returns to More.
7. With a larger system font size the text grows and nothing is cut off.
