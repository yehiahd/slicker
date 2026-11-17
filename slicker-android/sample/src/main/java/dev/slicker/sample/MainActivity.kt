package dev.slicker.sample

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.slicker.Slicker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val http = OkHttpClient.Builder().addInterceptor(Slicker.networkInterceptor()).build()
    private var counter = 0
    private var socket: WebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seed()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        val flagsView = TextView(this).apply { setPadding(32, 24, 32, 24); textSize = 16f }
        fun showFlags() {
            val newCheckout = Slicker.flag("new_checkout", false)
            val welcome = Slicker.flag("welcome_message", "Hello!")
            val maxItems = Slicker.flag("max_cart_items", 10)
            flagsView.text = "Checkout: ${if (newCheckout) "new" else "old"}\n" +
                "Welcome message: $welcome\nMax cart items: $maxItems"
        }
        showFlags()
        Slicker.onFlagChanged { showFlags() }
        layout.addView(flagsView)
        layout.addView(button("GET request") { call(Request.Builder().url("https://httpbin.org/get").build()) })
        layout.addView(button("POST request") {
            val body = """{"hello":"slicker"}""".toRequestBody("application/json".toMediaType())
            call(Request.Builder().url("https://httpbin.org/post").post(body).build())
        })
        layout.addView(button("GET user (try mocking it)") {
            call(Request.Builder().url("https://httpbin.org/anything/user/42").build())
        })
        layout.addView(button("Track: add to cart") {
            Slicker.logEvent(
                "add_to_cart",
                mapOf("item_id" to "sku_1093", "price" to 12.5, "quantity" to ++counter, "tags" to listOf("sale", "new")),
                source = "Firebase",
            )
        })
        layout.addView(button("Track: screen view") {
            Slicker.logEvent("screen_view", Bundle().apply { putString("screen_name", "Home"); putLong("load_ms", 184) }, "Amplitude")
        })
        val socketView = TextView(this).apply { setPadding(32, 8, 32, 8); text = "WebSocket: not connected" }
        fun showSocket(text: String) = runOnUiThread { socketView.text = "WebSocket: $text" }
        layout.addView(socketView)
        layout.addView(button("Open WebSocket") {
            socket?.cancel()
            showSocket("connecting…")
            // Public echo server: sends back every message it receives.
            val request = Request.Builder().url("wss://ws.postman-echo.com/raw").build()
            socket = Slicker.webSocketFactory(http).newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    showSocket("open")
                    webSocket.send("""{"type":"hello","client":"slicker-sample"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) = showSocket("open, last echo: $text")

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    showSocket("closed ($code $reason)")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) socket = null
                    showSocket("failed: ${t.javaClass.simpleName}")
                }
            })
        })
        layout.addView(button("Send WebSocket message") {
            val current = socket
            if (current == null) {
                Toast.makeText(this, "Open the WebSocket first", Toast.LENGTH_SHORT).show()
            } else {
                current.send("""{"type":"ping","count":${++counter},"items":["a","b"]}""")
            }
        })
        layout.addView(button("Close WebSocket") {
            val current = socket
            if (current == null) {
                Toast.makeText(this, "No open WebSocket", Toast.LENGTH_SHORT).show()
            } else {
                showSocket("closing…")
                current.close(1000, "Done")
            }
        })
        layout.addView(button("Leak this screen") {
            // Deliberate leak for the LeakCanary plugin: keeps this activity alive after it closes.
            leaked += this
            recreate()
        })
        layout.addView(button("Change a preference") {
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("counter", ++counter).apply()
        })
        setContentView(layout)
        registerActions()
    }

    private fun registerActions() {
        Slicker.addAction("Log out", group = "Account", description = "Clears the saved session") {
            getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().apply()
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
        }
        Slicker.addAction("Switch to staging", group = "Environment") {
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("environment", "staging").apply()
            Toast.makeText(this, "Using staging", Toast.LENGTH_SHORT).show()
        }
        Slicker.addAction("Switch to production", group = "Environment") {
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("environment", "production").apply()
            Toast.makeText(this, "Using production", Toast.LENGTH_SHORT).show()
        }
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        Slicker.addAction(
            "Set base URL",
            group = "Environment",
            description = "Saved as base_url in the settings preferences",
            input = settings.getString("base_url", "https://api.example.com/")!!,
        ) { url ->
            settings.edit().putString("base_url", url).apply()
            Toast.makeText(this, "Base URL: $url", Toast.LENGTH_SHORT).show()
        }
        Slicker.addAction("Clear cache", group = "Storage", description = "Deletes the app's cache folder") {
            cacheDir.deleteRecursively()
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun button(text: String, onClick: () -> Unit) =
        Button(this).apply { this.text = text; setOnClickListener { onClick() } }

    private fun call(request: Request) = thread { runCatching { http.newCall(request).execute().close() } }

    private fun seed() {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("username", "driver42").putBoolean("darkMode", true)
            .putInt("counter", 0).putStringSet("tags", setOf("a", "b")).apply()
        getSharedPreferences("session", Context.MODE_PRIVATE).edit().putLong("loginAt", System.currentTimeMillis()).apply()
        object : SQLiteOpenHelper(this, "sample.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE rides (id INTEGER PRIMARY KEY, rider TEXT, fare REAL, done INTEGER)")
                db.execSQL("CREATE INDEX idx_rider ON rides(rider)")
                repeat(25) { db.execSQL("INSERT INTO rides(rider, fare, done) VALUES ('rider$it', ${it * 1.5}, ${it % 2})") }
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }.writableDatabase.close()
    }

    companion object {
        private val leaked = mutableListOf<Any>()
    }
}
