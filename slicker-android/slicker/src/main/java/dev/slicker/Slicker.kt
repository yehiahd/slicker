package dev.slicker

import android.content.Context
import dev.slicker.core.SlickerClient
import dev.slicker.plugins.actions.ActionsPlugin
import dev.slicker.plugins.analytics.AnalyticsPlugin
import dev.slicker.plugins.flags.FlagsPlugin
import dev.slicker.plugins.websockets.SlickerWebSocketFactory
import dev.slicker.plugins.websockets.WebSocketsPlugin
import dev.slicker.plugins.databases.DatabasesPlugin
import dev.slicker.plugins.network.SlickerInterceptor
import dev.slicker.plugins.network.NetworkPlugin
import dev.slicker.plugins.preferences.PreferencesPlugin
import android.os.Bundle
import okhttp3.Interceptor
import okhttp3.WebSocket

/**
 * Entry point. Call [start] from Application.onCreate and add [networkInterceptor] to your OkHttpClient.
 * Does nothing in non-debuggable builds.
 */
object Slicker {
    private val networkPlugin = NetworkPlugin()
    private val interceptor = SlickerInterceptor(networkPlugin)
    private val analyticsPlugin = AnalyticsPlugin()
    private val flagsPlugin = FlagsPlugin()
    private val actionsPlugin = ActionsPlugin()
    private val webSocketsPlugin = WebSocketsPlugin()

    @Volatile internal var client: SlickerClient? = null
        private set

    @Synchronized
    fun start(context: Context, config: SlickerConfig = SlickerConfig()) {
        if (client != null || !SlickerClient.isDebuggable(context)) return
        client = SlickerClient(context, config).apply {
            addPlugin(networkPlugin)
            addPlugin(analyticsPlugin)
            flagsPlugin.init(context)
            addPlugin(flagsPlugin)
            addPlugin(actionsPlugin)
            addPlugin(webSocketsPlugin)
            addPlugin(DatabasesPlugin(context))
            addPlugin(PreferencesPlugin(context, config.preferenceFiles))
            leaksPlugin(context)?.let(::addPlugin)
            start()
        }
    }

    @Synchronized
    fun stop() {
        client?.stop()
        client = null
    }

    fun networkInterceptor(): Interceptor = interceptor

    /**
     * Wraps [factory] (usually your OkHttpClient) so its WebSockets show up in the desktop
     * WebSockets plugin: connection events and every message sent and received.
     *
     * ```
     * val socket = Slicker.webSocketFactory(client).newWebSocket(request, listener)
     *
     * // Socket.IO
     * options.webSocketFactory = Slicker.webSocketFactory(client)
     * ```
     */
    @JvmStatic
    fun webSocketFactory(factory: WebSocket.Factory): WebSocket.Factory =
        SlickerWebSocketFactory(factory, webSocketsPlugin)

    /**
     * Shows an analytics event in the desktop Analytics plugin. Call it next to your real tracking
     * call (or from your analytics wrapper). [source] is an optional label such as "Firebase".
     * Values can be strings, numbers, booleans, lists, maps or Bundles; anything else uses toString().
     */
    @JvmStatic
    @JvmOverloads
    fun logEvent(name: String, properties: Map<String, Any?> = emptyMap(), source: String? = null) {
        if (client == null) return
        analyticsPlugin.logEvent(name, properties, source)
    }

    /** Same as [logEvent], for Firebase-style Bundle parameters. */
    @JvmStatic
    @JvmOverloads
    fun logEvent(name: String, params: Bundle?, source: String? = null) {
        if (client == null) return
        val properties = params?.keySet()?.associateWith {
            @Suppress("DEPRECATION")
            params.get(it)
        } ?: emptyMap()
        analyticsPlugin.logEvent(name, properties, source)
    }

    /**
     * Reads a feature flag. Pass the value your app would use (a default, or what Remote Config
     * returned); you get it back unless an override is set in the desktop Flags plugin.
     *
     * ```
     * val newCheckout = Slicker.flag("new_checkout", remoteConfig.getBoolean("new_checkout"))
     * ```
     * The flag shows up on the desktop the first time it is read.
     */
    @JvmStatic
    fun flag(key: String, value: Boolean): Boolean = flagsPlugin.read(key, value)

    @JvmStatic
    fun flag(key: String, value: String): String = flagsPlugin.read(key, value)

    @JvmStatic
    fun flag(key: String, value: Int): Int = flagsPlugin.read(key, value)

    @JvmStatic
    fun flag(key: String, value: Long): Long = flagsPlugin.read(key, value)

    @JvmStatic
    fun flag(key: String, value: Double): Double = flagsPlugin.read(key, value)

    /** Called on the main thread with the flag's key when an override is set or cleared. */
    @JvmStatic
    fun onFlagChanged(listener: (key: String) -> Unit) = flagsPlugin.addListener(listener)

    /**
     * Adds a button to the desktop Actions plugin. Pressing it runs [action] on the main thread.
     * Adding an action with the same [name] again replaces it.
     *
     * ```
     * Slicker.addAction("Log out", group = "Account") { session.logout() }
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun addAction(name: String, group: String? = null, description: String? = null, action: () -> Unit) =
        actionsPlugin.add(name, group, description, null) { action() }

    /**
     * Adds a button with a text field to the desktop Actions plugin. The field starts with [input];
     * pressing the button runs [action] on the main thread with the text that was entered.
     *
     * ```
     * Slicker.addAction("Set base URL", group = "Environment", input = config.baseUrl) { url ->
     *     config.setBaseUrl(url)
     * }
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun addAction(
        name: String,
        group: String? = null,
        description: String? = null,
        input: String,
        action: (String) -> Unit,
    ) = actionsPlugin.add(name, group, description, input, action)

    @JvmStatic
    fun removeAction(name: String) = actionsPlugin.remove(name)

    /** LeakCanary is optional: apps can exclude it, so only touch it when it's on the classpath. */
    private fun leaksPlugin(context: Context): dev.slicker.plugins.leaks.LeaksPlugin? = runCatching {
        Class.forName("leakcanary.LeakCanary")
        dev.slicker.plugins.leaks.LeaksPlugin(context).apply { install() }
    }.getOrNull()
}
