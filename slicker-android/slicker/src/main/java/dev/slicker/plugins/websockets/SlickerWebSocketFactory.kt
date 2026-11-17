package dev.slicker.plugins.websockets

import android.util.Base64
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps a [WebSocket.Factory] (usually your OkHttpClient) and reports every connection event and
 * message to the desktop WebSockets plugin. Works for plain OkHttp WebSockets and for Socket.IO,
 * which takes a WebSocket.Factory in its options.
 */
class SlickerWebSocketFactory(
    private val delegate: WebSocket.Factory,
    private val plugin: WebSocketsPlugin,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) : WebSocket.Factory {

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val connectionId = UUID.randomUUID().toString()
        // OkHttp stores ws:// and wss:// URLs as http:// and https://; show them as the app wrote them.
        val url = request.url.toString().replaceFirst(Regex("^http"), "ws")
        // Set right after newWebSocket returns; callbacks normally run later, on OkHttp's thread.
        val tracked = AtomicReference<WebSocket?>(null)

        fun report(direction: String, type: String, data: String? = null, size: Long? = null, extra: JSONObject.() -> Unit = {}) {
            plugin.send(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("connectionId", connectionId)
                    .put("url", url)
                    .put("timestamp", System.currentTimeMillis())
                    .put("direction", direction)
                    .put("type", type)
                    .put("data", data ?: JSONObject.NULL)
                    .put("size", size ?: JSONObject.NULL)
                    .apply(extra),
            )
        }

        fun text(value: String): String =
            if (value.length > maxPayloadBytes) value.take(maxPayloadBytes) + "… (truncated)" else value

        fun binary(bytes: ByteString): String =
            Base64.encodeToString(bytes.toByteArray().copyOf(minOf(bytes.size, maxPayloadBytes)), Base64.NO_WRAP)

        // The app's listener gets the tracked socket, so sends from inside callbacks are reported too.
        val reportingListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                report("event", "open", "${response.code} ${response.message}".trim()) {
                    put("headers", JSONObject(response.headers.toMultimap().mapValues { it.value.joinToString(", ") }))
                }
                listener.onOpen(tracked.get() ?: webSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                report("received", "text", text(text), text.toByteArray().size.toLong())
                listener.onMessage(tracked.get() ?: webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                report("received", "binary", binary(bytes), bytes.size.toLong())
                listener.onMessage(tracked.get() ?: webSocket, bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                report("event", "closing", "$code $reason".trim())
                listener.onClosing(tracked.get() ?: webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                report("event", "closed", "$code $reason".trim())
                listener.onClosed(tracked.get() ?: webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.let { " (HTTP ${it.code})" } ?: ""
                report("event", "failure", "${t.javaClass.simpleName}: ${t.message}$status")
                listener.onFailure(tracked.get() ?: webSocket, t, response)
            }
        }

        report("event", "connecting", url)
        val socket = delegate.newWebSocket(request, reportingListener)
        val wrapper = object : WebSocket by socket {
            override fun send(text: String): Boolean {
                val queued = socket.send(text)
                report("sent", "text", text(text), text.toByteArray().size.toLong()) { put("queued", queued) }
                return queued
            }

            override fun send(bytes: ByteString): Boolean {
                val queued = socket.send(bytes)
                report("sent", "binary", binary(bytes), bytes.size.toLong()) { put("queued", queued) }
                return queued
            }

            override fun close(code: Int, reason: String?): Boolean {
                report("sent", "close", "$code ${reason.orEmpty()}".trim())
                return socket.close(code, reason)
            }

            override fun cancel() {
                report("sent", "cancel")
                socket.cancel()
            }
        }
        tracked.set(wrapper)
        return wrapper
    }

    companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES = 256 * 1024
    }
}
