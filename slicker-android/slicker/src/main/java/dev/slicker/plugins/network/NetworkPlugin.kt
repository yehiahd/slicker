package dev.slicker.plugins.network

import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop plugin id "Network". Runs in background and buffers events while the desktop
 * is not connected, so requests made at app start still show up.
 *
 * Also receives mock routes from the desktop ("Mock" button in the Network plugin).
 */
class NetworkPlugin(private val bufferSize: Int = 500) : SlickerPlugin {
    override val id = "Network"
    override val runInBackground = true

    private val buffer = ArrayDeque<Pair<String, JSONObject>>()
    private var connection: SlickerConnection? = null

    @Volatile
    internal var mocks: List<MockRoute> = emptyList()
        private set

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        connection.receive("mockResponses") { params, responder ->
            mocks = MockRoute.parseAll(params.optJSONArray("routes") ?: JSONArray())
            responder.success()
        }
        while (buffer.isNotEmpty()) {
            val (method, params) = buffer.removeFirst()
            connection.send(method, params)
        }
    }

    @Synchronized
    override fun onDisconnect() {
        connection = null
        // Without the desktop there is no way to turn mocks off, so drop them.
        mocks = emptyList()
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
