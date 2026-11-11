package dev.slicker.plugins.network

import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import org.json.JSONObject

/**
 * Desktop plugin id "Network". Runs in background and buffers events while the desktop
 * is not connected, so requests made at app start still show up.
 */
class NetworkPlugin(private val bufferSize: Int = 500) : SlickerPlugin {
    override val id = "Network"
    override val runInBackground = true

    private val buffer = ArrayDeque<Pair<String, JSONObject>>()
    private var connection: SlickerConnection? = null

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        while (buffer.isNotEmpty()) {
            val (method, params) = buffer.removeFirst()
            connection.send(method, params)
        }
    }

    @Synchronized
    override fun onDisconnect() {
        connection = null
    }

    @Synchronized
    internal fun send(method: String, params: JSONObject) {
        val current = connection
        if (current != null) {
            current.send(method, params)
        } else {
            if (buffer.size >= bufferSize) buffer.removeFirst()
            buffer.addLast(method to params)
        }
    }
}
