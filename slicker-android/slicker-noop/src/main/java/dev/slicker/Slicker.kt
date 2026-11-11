package dev.slicker

import android.content.Context
import okhttp3.Interceptor

/** Release no-op: same API as the debug artifact, does nothing. */
object Slicker {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, config: SlickerConfig = SlickerConfig()) = Unit

    fun stop() = Unit

    fun networkInterceptor(): Interceptor = Interceptor { it.proceed(it.request()) }
}
