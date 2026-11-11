package dev.slicker.sample

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import dev.slicker.Slicker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val http = OkHttpClient.Builder().addInterceptor(Slicker.networkInterceptor()).build()
    private var counter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seed()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        layout.addView(button("GET request") { call(Request.Builder().url("https://httpbin.org/get").build()) })
        layout.addView(button("POST request") {
            val body = """{"hello":"slicker"}""".toRequestBody("application/json".toMediaType())
            call(Request.Builder().url("https://httpbin.org/post").post(body).build())
        })
        layout.addView(button("Change a preference") {
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("counter", ++counter).apply()
        })
        setContentView(layout)
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
}
