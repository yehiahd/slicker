package dev.slicker.plugins.analytics

import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Desktop plugin id "Analytics". Shows events sent with [dev.slicker.Slicker.logEvent].
 * Runs in background and buffers events while the desktop is not connected.
 */
class AnalyticsPlugin(private val bufferSize: Int = 500) : SlickerPlugin {
    override val id = "Analytics"
    override val runInBackground = true

    private val buffer = ArrayDeque<JSONObject>()
    private var connection: SlickerConnection? = null

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        while (buffer.isNotEmpty()) connection.send("newEvent", buffer.removeFirst())
    }

    @Synchronized
    override fun onDisconnect() {
        connection = null
    }

    fun logEvent(name: String, properties: Map<String, Any?>, source: String?) {
        val event = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("timestamp", System.currentTimeMillis())
            .put("name", name)
            .put("source", source ?: JSONObject.NULL)
            .put("properties", toJson(properties))
        synchronized(this) {
            val current = connection
            if (current != null) {
                current.send("newEvent", event)
            } else {
                if (buffer.size >= bufferSize) buffer.removeFirst()
                buffer.addLast(event)
            }
        }
    }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean, is Int, is Long, is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is android.os.Bundle -> JSONObject().apply {
            value.keySet().forEach { key ->
                @Suppress("DEPRECATION")
                put(key, toJson(value.get(key)))
            }
        }
        else -> value.toString()
    }
}
