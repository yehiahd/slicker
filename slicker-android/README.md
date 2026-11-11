# slicker — Android setup

A lightweight replacement for the retired Flipper Android SDK. One desktop window shows:

| Desktop plugin | What you see |
|---|---|
| **Network** | Every OkHttp request/response: URL, method, status, headers, bodies, timing |
| **Databases** | All SQLite/Room databases: browse tables, structure, run SQL |
| **Shared Preferences Viewer** | All SharedPreferences files: view, edit, delete, live change log |

It is pure Kotlin (no C++/Folly/NDK) and talks to the Slicker desktop (a rebranded fork of the Flipper desktop) over a plain local WebSocket, so
there is no certificate setup.

---

## 1. Start the desktop

**Easiest (macOS, Apple Silicon):** install the Slicker app from the DMG on the
[Releases](https://github.com/yehiahd/slicker/releases) page; see the
[main README](../README.md#quick-start) for install steps.

**From source:** requirements are macOS/Linux, `adb` on your PATH (Android SDK platform-tools), and
Node or `npx` (the script fetches Node 20 automatically; Flipper's dependencies do not run on Node 22+).

```bash
./scripts/slicker-desktop.sh
```

- First run builds the desktop (a few minutes) and installs it to `~/.slicker/server`.
- Later runs start in a couple of seconds.
- Opens **http://localhost:52342** in your browser.
- After changing desktop code, rebuild with `./scripts/slicker-desktop.sh --rebuild`.

The desktop detects every adb device/emulator and runs `adb reverse tcp:8333 tcp:8333` on it, so the app
can reach it at `localhost:8333`. No manual adb step is needed.

---

## 2. Get the Android library

### Option A: JitPack (recommended)

Nothing to build. Releases are published from git tags at
`https://jitpack.io/#yehiahd/slicker`.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

App module `build.gradle.kts`:

```kotlin
dependencies {
    debugImplementation("com.github.yehiahd.slicker:slicker:v0.1.0")
    releaseImplementation("com.github.yehiahd.slicker:slicker-noop:v0.1.0")
}
```

Use the latest release tag instead of `v0.1.0` when newer ones exist.

### Option B: local build (mavenLocal)

From `slicker-android/`:

```bash
./gradlew publishToMavenLocal
```

> Gradle needs JDK 17+. If `java -version` is older, point `JAVA_HOME` at Android Studio's JDK:
> `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`

Then add `mavenLocal()` to your repositories and depend on:

```kotlin
dependencies {
    debugImplementation("dev.slicker:slicker:0.1.0")
    releaseImplementation("dev.slicker:slicker-noop:0.1.0")
}
```

---

## 3. Add it to any Android project

### 3.1 Dependencies

Add the repository and dependencies from step 2 (JitPack or mavenLocal). The examples below use the
local coordinates; with JitPack use `com.github.yehiahd.slicker:slicker:v0.1.0` instead.

Using other build types or flavors (e.g. `staging`)? Every variant needs exactly one of the two:

```kotlin
"stagingImplementation"("dev.slicker:slicker:0.1.0")
```

The real artifact also does nothing on its own when the app is not debuggable, so using it in a
debuggable `staging` build is safe.

### 3.2 Start it

In your `Application`:

```kotlin
import dev.slicker.Slicker

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Slicker.start(this)
    }
}
```

### 3.3 Hook up Network

Add the interceptor to every `OkHttpClient` you want to see (Retrofit uses OkHttp underneath):

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(Slicker.networkInterceptor())
    .build()
```

With Hilt/Dagger, add it where you build the client in your network module.

Order matters: add it **last** (after auth/header interceptors) to see the final request as sent,
or use `addNetworkInterceptor(...)` to see what goes over the wire after redirects and compression.

Databases and Preferences need no code: every database in `databases/` and every file in
`shared_prefs/` is picked up automatically.

### 3.4 Run

1. Start the desktop (step 1).
2. Run the debug app on an emulator or USB device.
3. In the desktop, pick your app at the top left, then click each plugin under **Disabled** and press
   **Enable Plugin** (first time only, it is remembered).

---

## Configuration (optional)

```kotlin
Slicker.start(
    this,
    SlickerConfig(
        host = "localhost",          // desktop host as seen from the device
        port = 8333,                 // flipper-server browser port
        deviceId = null,             // name in the desktop device list; default ANDROID_ID
        reconnectDelayMs = 3_000,
        preferenceFiles = null,      // e.g. listOf("settings", "session"); null = all files
    ),
)
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| App not shown in the desktop | Check `adb reverse --list` shows `tcp:8333`. If not, re-plug the device or run `adb reverse tcp:8333 tcp:8333`. Check logcat tag `Slicker` for `Connected to flipper-server`. |
| `CLEARTEXT communication to localhost not permitted` in logcat (seen on recent Android versions) | Your app's network security config blocks plain HTTP. Allow `localhost` in a **debug-only** config (`src/debug/res/xml/network_security_config.xml`) with `<domain includeSubdomains="false">localhost</domain>` and `cleartextTrafficPermitted="true"`. |
| Two entries for the same phone in the desktop | Expected: one is the adb device, the other is the app connection (named by `deviceId`). Select the one that lists your app. |
| Nothing happens in a release build | By design: the no-op artifact (or a non-debuggable app) disables everything. |
| Desktop fails with `SlowBuffer` errors | You are on Node 22+. Use the script, which uses Node 20. |
| Slicker window stays on "Starting Slicker…" | Check `~/Library/Logs/Slicker/server.log`. Another Slicker or Flipper may already be using port 52342; quit it and reopen Slicker. |
| Setup wizard asks about iOS or shows iOS errors | You are on an old build. Slicker is Android only; install the latest DMG. |
| Network body missing | Bodies are captured up to 1 MB. One-shot/duplex request bodies (e.g. streaming uploads) are not read. |

## Limitations

- Network mocking (Flipper's "Mock" tab) is not supported.
- Network bodies are capped at 1 MB.
- Desktop and device must be connected over adb (USB or emulator). Wi-Fi-only devices need
  `adb connect` first.

## Releasing to JitPack

JitPack builds the library from `jitpack.yml` at the repo root (JDK 17, `slicker-android` only).

```bash
git tag v0.1.0
git push origin v0.1.0
```

Then open `https://jitpack.io/#yehiahd/slicker`, pick the tag and press **Get it** (the first
request triggers the build). The repository must be public, or connected to a paid JitPack plan.

## Project layout

```
slicker-android/
  slicker/        library: client + Network/Databases/Preferences plugins
  slicker-noop/   release no-op with the same API
  sample/              demo app (seeded DB + prefs, GET/POST buttons)
scripts/slicker-desktop.sh   build + start the desktop
SLICKER_NOTES.md                     protocol notes
```
