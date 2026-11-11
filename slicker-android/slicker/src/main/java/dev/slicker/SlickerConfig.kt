package dev.slicker

/**
 * @param host Desktop host as seen from the device. "localhost" works for emulators and USB devices
 *   because flipper-server runs `adb reverse` for [port].
 * @param port flipper-server browser WebSocket port (default 8333).
 * @param deviceId Id shown in the desktop device list. Defaults to ANDROID_ID.
 * @param preferenceFiles SharedPreferences file names to expose; null = all files.
 */
data class SlickerConfig(
    val host: String = "localhost",
    val port: Int = 8333,
    val deviceId: String? = null,
    val reconnectDelayMs: Long = 3_000,
    val preferenceFiles: List<String>? = null,
)
