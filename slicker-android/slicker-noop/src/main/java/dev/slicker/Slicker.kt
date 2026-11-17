package dev.slicker

import android.content.Context
import android.os.Bundle
import okhttp3.Interceptor
import okhttp3.WebSocket

/** Release no-op: same API as the debug artifact, does nothing. */
object Slicker {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, config: SlickerConfig = SlickerConfig()) = Unit

    fun stop() = Unit

    fun networkInterceptor(): Interceptor = Interceptor { it.proceed(it.request()) }

    @JvmStatic
    fun webSocketFactory(factory: WebSocket.Factory): WebSocket.Factory = factory

    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun logEvent(name: String, properties: Map<String, Any?> = emptyMap(), source: String? = null) = Unit

    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun logEvent(name: String, params: Bundle?, source: String? = null) = Unit

    @JvmStatic fun flag(key: String, value: Boolean): Boolean = value
    @JvmStatic fun flag(key: String, value: String): String = value
    @JvmStatic fun flag(key: String, value: Int): Int = value
    @JvmStatic fun flag(key: String, value: Long): Long = value
    @JvmStatic fun flag(key: String, value: Double): Double = value

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun onFlagChanged(listener: (key: String) -> Unit) = Unit

    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun addAction(name: String, group: String? = null, description: String? = null, action: () -> Unit) = Unit

    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun addAction(
        name: String,
        group: String? = null,
        description: String? = null,
        input: String,
        action: (String) -> Unit,
    ) = Unit

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun removeAction(name: String) = Unit
}
