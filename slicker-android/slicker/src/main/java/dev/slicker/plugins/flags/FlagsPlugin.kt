package dev.slicker.plugins.flags

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Desktop plugin id "Flags". Every flag the app reads through Slicker.flag() is listed on the
 * desktop with the value the app passed in; the desktop can override it. Overrides are kept in
 * files/slicker/flags.json, so they survive app restarts until cleared.
 */
class FlagsPlugin : SlickerPlugin {
    override val id = "Flags"

    private class Flag(val key: String, val type: String, var appValue: Any)

    private val flags = LinkedHashMap<String, Flag>()
    private val overrides = LinkedHashMap<String, Any>()
    private val listeners = mutableListOf<(String) -> Unit>()
    private var file: File? = null
    private var connection: SlickerConnection? = null
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun init(context: Context) {
        val f = File(context.filesDir, "slicker/flags.json").also { file = it }
        if (!f.exists()) return
        runCatching {
            val json = JSONObject(f.readText())
            json.keys().forEach { overrides[it] = json.get(it) }
        }.onFailure { Log.w(TAG, "Could not read flag overrides", it) }
    }

    /** Returns the override for [key] if one is set, otherwise [appValue]. */
    @Synchronized
    fun <T : Any> read(key: String, appValue: T): T {
        val type = typeOf(appValue)
        val existing = flags[key]
        if (existing == null || existing.appValue != appValue || existing.type != type) {
            flags[key] = Flag(key, type, appValue)
            sendFlags()
        }
        val override = overrides[key] ?: return appValue
        val value = when (val converted = convert(override, type)) {
            null -> return appValue
            is Number -> when (appValue) { // keep the exact type the app asked for
                is Int -> converted.toInt()
                is Long -> converted.toLong()
                is Float -> converted.toFloat()
                is Double -> converted.toDouble()
                else -> converted
            }
            else -> converted
        }
        @Suppress("UNCHECKED_CAST")
        return if (appValue.javaClass.isInstance(value)) value as T else appValue
    }

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        connection.receive("setOverride") { params, responder ->
            val key = params.getString("key")
            val type = synchronized(this) { flags[key]?.type } ?: "string"
            val value = convert(params.get("value"), type)
            if (value == null) {
                responder.error("\"${params.get("value")}\" is not a valid $type")
            } else {
                update(key) { overrides[key] = value }
                responder.success(null)
            }
        }
        connection.receive("clearOverride") { params, responder ->
            val key = params.getString("key")
            update(key) { overrides.remove(key) }
            responder.success(null)
        }
        connection.receive("clearAll") { _, responder ->
            val keys = synchronized(this) { overrides.keys.toList() }
            synchronized(this) { overrides.clear() }
            save()
            sendFlags()
            keys.forEach(::notifyChanged)
            responder.success(null)
        }
        sendFlags()
    }

    @Synchronized
    override fun onDisconnect() {
        connection = null
    }

    private fun update(key: String, change: () -> Unit) {
        synchronized(this) { change() }
        save()
        sendFlags()
        notifyChanged(key)
    }

    private fun notifyChanged(key: String) {
        val current = synchronized(this) { listeners.toList() }
        main.post { current.forEach { runCatching { it(key) } } }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(JSONObject(overrides as Map<*, *>).toString())
        }.onFailure { Log.w(TAG, "Could not save flag overrides", it) }
    }

    @Synchronized
    private fun sendFlags() {
        val current = connection ?: return
        val list = JSONArray()
        // Overrides for flags the app hasn't read yet this run are listed too, so they can be cleared.
        (flags.keys + overrides.keys).distinct().forEach { key ->
            val flag = flags[key]
            list.put(
                JSONObject()
                    .put("key", key)
                    .put("type", flag?.type ?: typeOf(overrides.getValue(key)))
                    .put("appValue", flag?.appValue ?: JSONObject.NULL)
                    .put("override", overrides[key] ?: JSONObject.NULL)
                    .put("seen", flag != null),
            )
        }
        current.send("flags", JSONObject().put("flags", list))
    }

    private fun typeOf(value: Any): String = when (value) {
        is Boolean -> "boolean"
        is Int, is Long -> "number"
        is Float, is Double -> "decimal"
        else -> "string"
    }

    /** Converts a desktop/stored value to the flag's type, or null if it doesn't fit. */
    private fun convert(value: Any?, type: String): Any? = when (type) {
        "boolean" -> when (value) {
            is Boolean -> value
            is String -> value.lowercase().toBooleanStrictOrNull()
            else -> null
        }
        "number" -> when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
        "decimal" -> when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        else -> value?.toString()
    }

    private companion object {
        const val TAG = "Slicker"
    }
}
