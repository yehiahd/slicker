<p align="center">
  <img src="assets/slicker-logo.png" alt="Slicker logo" width="120"/>
</p>
<h1 align="center">Slicker</h1>
<p align="center">
  Network, database and preferences inspector for Android apps, in one desktop window.
</p>
<p align="center">
  <a href="https://github.com/yehiahd/slicker/releases/latest"><img src="https://img.shields.io/github/v/release/yehiahd/slicker?label=Mac%20app" alt="Latest Mac app"/></a>
  <a href="https://jitpack.io/#yehiahd/slicker"><img src="https://jitpack.io/v/yehiahd/slicker.svg" alt="JitPack"/></a>
</p>

---

Slicker is a lightweight debugging tool for Android. Your debug build connects to the Slicker
desktop app on your Mac, where you can inspect:

| Plugin | What you see |
|---|---|
| **Network** | Every OkHttp request and response: URL, method, status, headers, bodies, timing |
| **Databases** | All SQLite/Room databases: browse tables, structure, run SQL |
| **Shared Preferences** | All SharedPreferences files: view, edit, delete, live change log |

The Android library is pure Kotlin (no C++ or NDK) and needs no certificate setup. Release builds use
a no-op artifact, so nothing ships to users.

## Screenshots

**Network**: every request and response from OkHttp

![Network plugin](assets/screenshots/network.png)

**Databases**: browse tables and run SQL

![Databases plugin](assets/screenshots/databases.png)

**Shared Preferences**: values and a live change log

![Shared Preferences plugin](assets/screenshots/preferences.png)

---

## Getting started, step by step

Follow these steps in order. They take about 10 minutes the first time.

### What you need

- A Mac with Apple Silicon (M1 or newer), macOS 11 or later.
- Android Studio, or at least the Android SDK platform-tools (`adb`). Slicker looks for `adb` in
  Android Studio's default location (`~/Library/Android/sdk`).
- An Android app that you build yourself and that uses OkHttp (directly or through Retrofit) for its
  network calls.
- An emulator, or a phone connected over USB with USB debugging turned on.

### Step 1: Install the desktop app

1. Open the [latest release](https://github.com/yehiahd/slicker/releases/latest) and download
   `Slicker-<version>-arm64.dmg` from **Assets**.
2. Open the DMG and drag **Slicker** into **Applications**.
3. The first time only: in Applications, right-click **Slicker**, choose **Open**, then **Open**
   again. The app is not notarized by Apple, so a plain double-click is blocked once. If macOS still
   refuses, go to System Settings → Privacy & Security and press **Open Anyway**.
4. A two-step setup wizard runs: pick **Android**, then press **Done** on the health check.

Slicker is a normal Mac app with its own window and Dock icon. Quitting it (Cmd+Q, or closing the
window) also stops its background server. Leave it open while you debug.

### Step 2: Add the JitPack repository

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

If your project lists repositories in the root `build.gradle` instead, add `maven { url "https://jitpack.io" }` there.

### Step 3: Add the library

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    debugImplementation("com.github.yehiahd.slicker:slicker:v0.1.1")
    releaseImplementation("com.github.yehiahd.slicker:slicker-noop:v0.1.1")
}
```

`slicker` is the real library, and `slicker-noop` has the same API but does nothing, so release builds
contain no inspector. If you have other build types (for example `staging`), give each one of the two,
such as `"stagingImplementation"("com.github.yehiahd.slicker:slicker:v0.1.1")`.

Check the [releases](https://github.com/yehiahd/slicker/releases) for a newer version tag.

### Step 4: Start Slicker when the app starts

In your `Application` class (create one and register it in the manifest with
`android:name=".MyApp"` if you don't have one):

```kotlin
import dev.slicker.Slicker

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Slicker.start(this)
    }
}
```

This turns on all three plugins. Databases and Shared Preferences need nothing else: every database
in the app's `databases/` folder and every file in `shared_prefs/` is found automatically.

### Step 5: Hook up Network

Add Slicker's interceptor to every `OkHttpClient` you want to see:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(Slicker.networkInterceptor())
    .build()
```

With Retrofit, pass that client to `Retrofit.Builder().client(client)`. With Hilt or Dagger, add the
interceptor where your network module builds the client:

```kotlin
@Provides @Singleton
fun okHttp(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(authInterceptor)
    .addInterceptor(Slicker.networkInterceptor()) // after auth, so you see the final headers
    .build()
```

Requests sent through other HTTP stacks (HttpURLConnection, Ktor's non-OkHttp engines, image loaders
with their own client) do not show up. Most image loaders (Coil, Glide) can be given your OkHttp
client.

### Step 6: Allow the connection to the desktop

The app talks to Slicker over a plain (unencrypted) local connection to `localhost`. Android 9 and
later block that by default. Add a **debug-only** exception so release builds stay unchanged.

`app/src/debug/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">localhost</domain>
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

`app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:networkSecurityConfig="@xml/network_security_config" />
</manifest>
```

Skip this step if your app already sets `android:usesCleartextTraffic="true"`. If your main manifest
already has a `networkSecurityConfig`, add the `localhost` domain to a debug copy of that file instead.

### Step 7: Run and enable the plugins

1. Make sure Slicker is open on your Mac.
2. Run the **debug** build on the emulator or USB phone. Slicker sets up the adb connection itself.
3. In logcat, filter by the tag `Slicker`. You should see `Connected to flipper-server at localhost:8333`.
4. In Slicker, click the device and app picker at the top left and choose your app **under your
   device's name**.
5. In the left sidebar, open **Disabled**, click **Network**, and press **Enable Plugin**. Do the
   same for **Databases** and **Shared Preferences Viewer**.

Plugins are enabled per app and per device, and Slicker remembers your choice. A new app, or the
same app on another device, starts with everything disabled.

### Step 8: Use it

- **Network:** use your app; every request appears as it happens. Click one to see headers and
  bodies. Requests made while Network was disabled for that app are not shown, so enable it first,
  then do the thing you want to inspect.
- **Databases:** pick a database and a table to browse rows, see the structure, or run SQL in the
  query tab.
- **Shared Preferences Viewer:** pick a file to see its values. Click a value to edit it; changes the
  app makes appear in the change log below.

Slicker shows everything in full, including `Authorization` headers and cookies. That only happens in
debug builds, but keep it in mind when sharing screenshots.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| App not in the picker | Check logcat (tag `Slicker`). Make sure Slicker is open, then relaunch the app. `adb reverse --list` should show `tcp:8333`. |
| `CLEARTEXT communication to localhost not permitted` in logcat | Do step 6. |
| App is listed but Network is empty | Network is disabled for that app on that device (step 7), or the app made no requests yet. Many apps load from cache on launch; do something that fetches new data. |
| Some requests are missing | That client doesn't have `Slicker.networkInterceptor()` (step 5), or the call doesn't use OkHttp. |
| Two entries for the same phone | One is the adb device, the other is the app connection. Choose the one that lists your app. |
| Can't install the debug build over a Play Store build | Signatures differ. Uninstall the Play Store build first (this deletes the app's data on that device). |
| Nothing in a release build | By design: release uses `slicker-noop`. |
| Slicker window stays on "Starting Slicker…" | Check `~/Library/Logs/Slicker/server.log`. Another Slicker or Flipper may be using port 52342; quit it and reopen Slicker. |

More detail, configuration options and a local build of the library:
[slicker-android/README.md](slicker-android/README.md).

---

## Building from source

**Desktop, run from source** (needs Node or `npx`): `./scripts/slicker-desktop.sh`. It opens the UI in
your browser at http://localhost:52342.

**Mac app (DMG):**

```bash
./scripts/slicker-dmg.sh
```

Produces `dist/Slicker-<version>-arm64.dmg` (about 115 MB, includes its own Node runtime). The app is
ad-hoc signed, not notarized. Needs the Xcode command line tools (`xcode-select --install`): the build
compiles the native window app from `desktop/static/slicker-mac/main.swift` with `swiftc`.

**Releasing:** push a `v*` tag, which JitPack uses for the library, then create a GitHub release for
that tag and attach the DMG.

## Repository layout

```
slicker-android/   Android library (slicker, slicker-noop) and a sample app
desktop/           Desktop app: server, web UI, native Mac app (static/slicker-mac)
scripts/           slicker-desktop.sh (run from source), slicker-dmg.sh (build the Mac app)
SLICKER_NOTES.md   Protocol notes
```

The `android/`, `iOS/`, `xplat/`, `react-native/` and `docs/` folders come from Flipper and are not
used by Slicker.

## Credits and license

Slicker is a fork of [Flipper](https://github.com/facebook/flipper) by Meta, which was archived. The
desktop is Flipper's, rebranded for Slicker; the Android client is a new
implementation of Flipper's protocol.

Released under the MIT license, the same as Flipper. See [LICENSE](LICENSE).
