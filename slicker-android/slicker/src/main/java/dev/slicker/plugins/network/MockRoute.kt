package dev.slicker.plugins.network

import okhttp3.Request
import org.json.JSONArray

/**
 * A mocked response set up in the desktop Network plugin.
 *
 * [url] matches the request URL exactly, or ignoring the query string when the route has none.
 * A `*` in the route URL matches any text, so a route ending in `*` matches every URL that starts with it.
 * An empty [method] matches every method.
 */
internal class MockRoute(
    val url: String,
    val method: String,
    val status: Int,
    val body: String,
    val headers: List<Pair<String, String>>,
) {
    private val pattern: Regex? =
        if ('*' in url) Regex(url.split('*').joinToString(".*") { Regex.escape(it) }) else null

    fun matches(request: Request): Boolean {
        if (method.isNotEmpty() && !method.equals(request.method, ignoreCase = true)) return false
        val requestUrl = request.url.toString()
        if (pattern != null) return pattern.matches(requestUrl)
        if (url == requestUrl) return true
        val withoutQuery = requestUrl.substringBefore('?')
        return '?' !in url && url.trimEnd('/') == withoutQuery.trimEnd('/')
    }

    companion object {
        private val DROPPED_HEADERS = setOf("content-length", "content-encoding", "transfer-encoding")

        fun parseAll(routes: JSONArray): List<MockRoute> = (0 until routes.length()).mapNotNull { i ->
            val route = routes.optJSONObject(i) ?: return@mapNotNull null
            if (!route.optBoolean("enabled", true)) return@mapNotNull null
            val url = route.optString("requestUrl").trim()
            if (url.isEmpty()) return@mapNotNull null
            val headersJson = route.optJSONArray("headers") ?: JSONArray()
            val headers = (0 until headersJson.length()).mapNotNull { h ->
                val header = headersJson.optJSONObject(h) ?: return@mapNotNull null
                val key = header.optString("key").trim()
                // Copied calls carry the original body's length/encoding, which no longer apply.
                if (key.isEmpty() || key.lowercase() in DROPPED_HEADERS) null else key to header.optString("value")
            }
            MockRoute(
                url = url,
                method = route.optString("method").trim(),
                status = route.optString("status").trim().toIntOrNull() ?: 200,
                body = route.optString("data"),
                headers = headers,
            )
        }
    }
}
