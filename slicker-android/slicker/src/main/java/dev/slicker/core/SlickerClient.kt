package dev.slicker.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.slicker.SlickerConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Talks to flipper-server's plain WebSocket ("browser") port. No TLS, no certificate exchange.
 * All plugin callbacks run on a single background thread, so plugins need no locking of their own.
 */
class SlickerClient(
    context: Context,
    private val config: SlickerConfig,
) {
    private val appContext = context.applicationContext
    private val plugins = ConcurrentHashMap<String, SlickerPlugin>()
    private val connections = HashMap<String, PluginConnection>()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "slicker").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false

    fun addPlugin(plugin: SlickerPlugin) {
        plugins[plugin.id] = plugin
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : SlickerPlugin> getPlugin(id: String): T? = plugins[id] as T?

    fun start() {
        if (running) return
        running = true
        executor.execute { connect() }
    }

    fun stop() {
        running = false
        executor.execute {
            socket?.close(NORMAL_CLOSURE, null)
            socket = null
            disconnectAll()
        }
    }

    // region connection

    private fun connect() {
        if (!running || socket != null) return
        val request = Request.Builder()
            .url(buildUrl())
            .header("Origin", "http://localhost:${config.port}")
            .build()
        socket = http.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (!running) return
        executor.schedule({ connect() }, config.reconnectDelayMs, TimeUnit.MILLISECONDS)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connected to flipper-server at ${config.host}:${config.port}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            executor.execute { handleMessage(webSocket, text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onLost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "Connection failed: ${t.message}")
            onLost(webSocket)
        }
    }

    private fun onLost(webSocket: WebSocket) {
        executor.execute {
            if (socket !== webSocket) return@execute
            socket = null
            disconnectAll()
            scheduleReconnect()
        }
    }

    private fun buildUrl(): HttpUrl {
        val appInfo = appContext.applicationInfo
        val label = appContext.packageManager.getApplicationLabel(appInfo).toString()
        return HttpUrl.Builder()
            .scheme("http") // OkHttp maps http -> ws for WebSockets
            .host(config.host)
            .port(config.port)
            .addQueryParameter("os", "Android")
            .addQueryParameter("device", "${Build.MODEL} - ${Build.VERSION.RELEASE} - API ${Build.VERSION.SDK_INT}")
            .addQueryParameter("device_id", config.deviceId ?: defaultDeviceId())
            .addQueryParameter("app", label)
            .addQueryParameter("app_id", appContext.packageName)
            .addQueryParameter("sdk_version", "4")
            .addQueryParameter("medium", "1")
            .build()
    }

    @Suppress("HardwareIds")
    private fun defaultDeviceId(): String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "slicker-${Build.MODEL}"

    // endregion

    // region protocol

    private fun handleMessage(webSocket: WebSocket, text: String) {
        if (socket !== webSocket) return
        val message = runCatching { JSONObject(text) }.getOrElse {
            Log.w(TAG, "Ignoring malformed message: $text")
            return
        }
        val id: Int? = if (message.has("id")) message.getInt("id") else null
        val responder = Responder(webSocket, id)
        val params = message.optJSONObject("params") ?: JSONObject()

        when (val method = message.optString("method")) {
            "getPlugins" -> responder.success(JSONObject().put("plugins", JSONArray(plugins.keys.toList())))
            "getBackgroundPlugins" -> responder.success(
                JSONObject().put("plugins", JSONArray(plugins.values.filter { it.runInBackground }.map { it.id })),
            )
            "init" -> {
                val plugin = plugins[params.optString("plugin")]
                if (plugin == null) {
                    responder.error("Plugin ${params.optString("plugin")} not found.", "PluginNotFound")
                } else {
                    connectPlugin(webSocket, plugin)
                }
            }
            "deinit" -> {
                val plugin = plugins[params.optString("plugin")]
                if (plugin != null && !plugin.runInBackground) disconnectPlugin(plugin.id)
            }
            "execute" -> execute(params, responder)
            "isMethodSupported" -> {
                val supported = connections[params.optString("api")]
                    ?.receivers?.containsKey(params.optString("method")) == true
                responder.success(JSONObject().put("isSupported", supported))
            }
            else -> if (id != null) responder.error("Unsupported method: $method")
        }
    }

    private fun execute(params: JSONObject, responder: SlickerResponder) {
        val api = params.optString("api")
        val method = params.optString("method")
        val receiver = connections[api]?.receivers?.get(method)
        if (receiver == null) {
            responder.error("Receiver $method not found.")
            return
        }
        try {
            receiver.onReceive(params.optJSONObject("params") ?: JSONObject(), responder)
        } catch (t: Throwable) {
            Log.w(TAG, "$api.$method failed", t)
            responder.error(t.message ?: t.javaClass.simpleName, t.javaClass.simpleName)
        }
    }

    private fun connectPlugin(webSocket: WebSocket, plugin: SlickerPlugin) {
        if (connections.containsKey(plugin.id)) return
        val connection = PluginConnection(webSocket, plugin.id)
        connections[plugin.id] = connection
        runCatching { plugin.onConnect(connection) }
            .onFailure { Log.w(TAG, "${plugin.id}.onConnect failed", it) }
    }

    private fun disconnectPlugin(id: String) {
        val connection = connections.remove(id) ?: return
        connection.active = false
        runCatching { plugins[id]?.onDisconnect() }
            .onFailure { Log.w(TAG, "$id.onDisconnect failed", it) }
    }

    private fun disconnectAll() {
        connections.keys.toList().forEach(::disconnectPlugin)
    }

    private inner class PluginConnection(
        private val webSocket: WebSocket,
        private val pluginId: String,
    ) : SlickerConnection {
        val receivers = HashMap<String, SlickerReceiver>()
        @Volatile var active = true

        override fun send(method: String, params: JSONObject) {
            if (!active) return
            val event = JSONObject()
                .put("method", "execute")
                .put(
                    "params",
                    JSONObject().put("api", pluginId).put("method", method).put("params", params),
                )
            webSocket.send(event.toString())
        }

        override fun receive(method: String, receiver: SlickerReceiver) {
            receivers[method] = receiver
        }
    }

    private class Responder(private val webSocket: WebSocket, private val id: Int?) : SlickerResponder {
        override fun success(result: Any?) {
            if (id == null) return
            webSocket.send(JSONObject().put("id", id).put("success", result ?: JSONObject()).toString())
        }

        override fun error(message: String, name: String?) {
            val error = JSONObject().put("message", message)
            if (name != null) error.put("name", name)
            val envelope = JSONObject().put("error", error)
            if (id != null) envelope.put("id", id)
            webSocket.send(envelope.toString())
        }
    }

    // endregion

    companion object {
        private const val TAG = "Slicker"
        private const val NORMAL_CLOSURE = 1000

        fun isDebuggable(context: Context): Boolean =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
