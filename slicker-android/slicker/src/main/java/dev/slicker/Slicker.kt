package dev.slicker

import android.content.Context
import dev.slicker.core.SlickerClient
import dev.slicker.plugins.databases.DatabasesPlugin
import dev.slicker.plugins.network.SlickerInterceptor
import dev.slicker.plugins.network.NetworkPlugin
import dev.slicker.plugins.preferences.PreferencesPlugin
import okhttp3.Interceptor

/**
 * Entry point. Call [start] from Application.onCreate and add [networkInterceptor] to your OkHttpClient.
 * Does nothing in non-debuggable builds.
 */
object Slicker {
    private val networkPlugin = NetworkPlugin()
    private val interceptor = SlickerInterceptor(networkPlugin)

    @Volatile var client: SlickerClient? = null
        private set

    @Synchronized
    fun start(context: Context, config: SlickerConfig = SlickerConfig()) {
        if (client != null || !SlickerClient.isDebuggable(context)) return
        client = SlickerClient(context, config).apply {
            addPlugin(networkPlugin)
            addPlugin(DatabasesPlugin(context))
            addPlugin(PreferencesPlugin(context, config.preferenceFiles))
            start()
        }
    }

    @Synchronized
    fun stop() {
        client?.stop()
        client = null
    }

    fun networkInterceptor(): Interceptor = interceptor
}
