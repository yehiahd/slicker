package dev.slicker.plugins.network

import android.util.Base64
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Reports every request/response passing through OkHttp to the desktop Network plugin. */
class SlickerInterceptor(
    private val plugin: NetworkPlugin,
    private val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val id = UUID.randomUUID().toString()
        plugin.send("newRequest", requestJson(id, request))

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            plugin.send("newResponse", failureJson(id, e))
            throw e
        }
        plugin.send("newResponse", responseJson(id, response))
        return response
    }

    private fun requestJson(id: String, request: Request): JSONObject {
        val body = request.body
        val data = if (body == null || body.isOneShot() || body.isDuplex()) {
            null
        } else {
            runCatching {
                val buffer = Buffer()
                body.writeTo(buffer)
                encode(buffer.readByteArray(minOf(buffer.size, maxBodyBytes)))
            }.getOrNull()
        }
        return JSONObject()
            .put("id", id)
            .put("timestamp", System.currentTimeMillis())
            .put("method", request.method)
            .put("url", request.url.toString())
            .put("headers", headersJson(request.headers, body?.contentType()?.toString()))
            .put("data", data ?: JSONObject.NULL)
    }

    private fun responseJson(id: String, response: Response): JSONObject {
        val data = runCatching { encode(response.peekBody(maxBodyBytes).bytes()) }.getOrNull()
        return JSONObject()
            .put("id", id)
            .put("timestamp", System.currentTimeMillis())
            .put("status", response.code)
            .put("reason", response.message)
            .put("headers", headersJson(response.headers, null))
            .put("isMock", false)
            .put("data", data ?: JSONObject.NULL)
            .put("totalChunks", 1)
            .put("index", 0)
    }

    private fun failureJson(id: String, e: Exception): JSONObject = JSONObject()
        .put("id", id)
        .put("timestamp", System.currentTimeMillis())
        .put("status", 0)
        .put("reason", "${e.javaClass.simpleName}: ${e.message}")
        .put("headers", JSONArray())
        .put("isMock", false)
        .put("data", JSONObject.NULL)
        .put("totalChunks", 1)
        .put("index", 0)

    private fun headersJson(headers: Headers, contentType: String?): JSONArray {
        val array = JSONArray()
        headers.forEach { (name, value) -> array.put(JSONObject().put("key", name).put("value", value)) }
        // OkHttp adds Content-Type from the body later in the chain; show it on the request too.
        if (contentType != null && headers["Content-Type"] == null) {
            array.put(JSONObject().put("key", "Content-Type").put("value", contentType))
        }
        return array
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        const val DEFAULT_MAX_BODY_BYTES = 1024L * 1024L
    }
}
