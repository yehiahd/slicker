package dev.slicker.plugins.websockets

import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import org.json.JSONObject

/**
 * Desktop plugin id "WebSockets". Runs in background and buffers events while the desktop is not
 * connected, so a socket opened at app start still shows up.
 */
class WebSocketsPlugin(private val bufferSize: Int = 1000) : SlickerPlugin {
    override val id = "WebSockets"
    override val runInBackground = true

    private val buffer = ArrayDeque<JSONObject>()
    private var connection: SlickerConnection? = null

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        while (buffer.isNotEmpty()) connection.send("event", buffer.removeFirst())
    }

    @Synchronized
    override fun onDisconnect() {
        connection = null
    }

    @Synchronized
    internal fun send(event: JSONObject) {
        val current = connection
        if (current != null) {
            current.send("event", event)
        } else {
            if (buffer.size >= bufferSize) buffer.removeFirst()
            buffer.addLast(event)
        }
    }
}
