# slicker — spike notes (Android)

## Building the desktop
- Node 26 fails (`SlowBuffer` removed, used by `buffer-equal-constant-time`). Use **Node 20**.
- Yarn 1: `npx -y yarn@1.22.22 ...` (no global install needed).
- `yarn flipper-server` (dev) needs **watchman**. The release build does not:
  `cd desktop && yarn install && yarn build:flipper-server --start`
- UI: http://localhost:52342

## Ports (flipper-server)
| Port | Purpose |
|---|---|
| 8089 / 9089 | insecure — cert exchange only (`signCertificate`) |
| 8088 / 9088 | secure — mutual TLS, plugin traffic |
| 8333 | browser ws — plain, full plugin traffic, needs `Origin: http://localhost:` |
| 52342 | web UI |

Desktop auto-runs `adb reverse` for 8088/8089/9088/9089, **not** 8333.

## Client connection
Query: `os=Android&device=<model - release - API n>&device_id=<id>&app=<label>&app_id=<pkg>&sdk_version=4&medium=1`

- **Option A (full):** RSA2048 CSR with CN=package -> write `filesDir/sonar/app.csr` -> insecure
  `{"method":"signCertificate","csr","destination","medium":1,"timestamp"}` -> desktop writes
  `sonarCA.crt`/`device.crt` via `adb run-as` (app must be debuggable) -> reply `{"deviceId"}` ->
  `signCertificateAck` -> reconnect `wss://localhost:9088` with client cert.
- **Option B (simple):** `ws://localhost:8333` + Origin header, `device_id` = adb serial,
  manual `adb reverse tcp:8333 tcp:8333`. No TLS.

## Envelope
- Desktop -> device: `getPlugins`, `getBackgroundPlugins`, `init`/`deinit` (no id),
  `execute {api, method, params}`, `isMethodSupported {api, method}`.
- Responses: `{"id":N,"success":...}` / `{"id":N,"error":{"message","name"?}}`.
- Device events: `{"method":"execute","params":{"api":plugin,"method":event,"params":{...}}}`.

## Plugins
- **Network** (background): events `newRequest {id,timestamp,method,url,headers:[{key,value}],data:b64}`,
  `newResponse {id,timestamp,status,reason,headers,isMock,data:b64,totalChunks:1,index:0}`,
  `partialResponse` (>1MiB chunks). Optional desktop call `mockResponses`.
- **Databases**: `databaseList`, `getTableData`, `getTableStructure`, `getTableInfo`, `execute`.
  Values `{type:null|integer|float|string|blob|boolean, value}`. Errors `{code:1|2|3,message}`.
- **Preferences**: `getAllSharedPreferences`, `getSharedPreferences {name}`,
  `setSharedPreference {sharedPreferencesName,preferenceName,preferenceValue}`,
  `deleteSharedPreference`; event `sharedPreferencesChange {preferences,name,deleted,time,value}`.

Sources: android/.../NetworkFlipperPlugin.java, DatabasesManager.java, SharedPreferencesFlipperPlugin.java,
desktop/flipper-server/src/app-connectivity/*, xplat/Flipper/FlipperConnectionManagerImpl.cpp.

## Android client (slicker-android/) — setup guide: slicker-android/README.md
Modules: `slicker` (debug), `slicker-noop` (release, same API), `sample`.

```kotlin
// Application.onCreate
Slicker.start(this)
// OkHttp
OkHttpClient.Builder().addInterceptor(Slicker.networkInterceptor())
```
```kotlin
debugImplementation(project(":slicker"))
releaseImplementation(project(":slicker-noop"))
```
Desktop patch: `androidDeviceManager.tsx` also `adb reverse`s the browser port (8333), so no manual step.
Build: `JAVA_HOME=<Android Studio jbr> ./gradlew :sample:assembleDebug` (Gradle 9.8, AGP 9.4.1, built-in Kotlin).
Verified on emulator API 37: Databases, Network (GET/POST), Preferences (read + live change events).
