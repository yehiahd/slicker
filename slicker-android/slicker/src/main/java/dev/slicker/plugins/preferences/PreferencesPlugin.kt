package dev.slicker.plugins.preferences

import android.content.Context
import android.content.SharedPreferences
import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Desktop plugin id "Preferences". Shows the given SharedPreferences files, or every file in
 * shared_prefs/ when [names] is null, and streams changes while the desktop is connected.
 */
class PreferencesPlugin(
    context: Context,
    private val names: List<String>? = null,
) : SlickerPlugin {
    override val id = "Preferences"

    private val appContext = context.applicationContext
    @Volatile
    private var connection: SlickerConnection? = null
    private val listeners = mutableMapOf<String, Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        registerListeners()

        connection.receive("getAllSharedPreferences") { _, r ->
            val all = JSONObject()
            prefsNames().forEach { all.put(it, toJson(prefs(it))) }
            r.success(all)
        }
        connection.receive("getSharedPreferences") { p, r -> r.success(toJson(prefs(p.getString("name")))) }
        connection.receive("setSharedPreference") { p, r ->
            val name = p.getString("sharedPreferencesName")
            val prefs = prefs(name)
            val key = p.getString("preferenceName")
            val current = prefs.all[key] ?: error("Preference $key not found in $name")
            val raw = p.get("preferenceValue")
            prefs.edit().apply {
                when (current) {
                    is Boolean -> putBoolean(key, raw.toString().toBooleanStrict())
                    is Int -> putInt(key, raw.toString().toInt())
                    is Long -> putLong(key, raw.toString().toLong())
                    is Float -> putFloat(key, raw.toString().toFloat())
                    is Set<*> -> putStringSet(key, toStringSet(raw))
                    else -> putString(key, raw.toString())
                }
            }.commit()
            r.success(toJson(prefs))
        }
        connection.receive("deleteSharedPreference") { p, r ->
            val prefs = prefs(p.getString("sharedPreferencesName"))
            prefs.edit().remove(p.getString("preferenceName")).commit()
            r.success(toJson(prefs))
        }
    }

    override fun onDisconnect() {
        connection = null
        listeners.values.forEach { (prefs, listener) -> prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        listeners.clear()
    }

    private fun registerListeners() {
        prefsNames().forEach { name ->
            if (listeners.containsKey(name)) return@forEach
            val prefs = prefs(name)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                if (key == null) return@OnSharedPreferenceChangeListener // clear() on API 30+
                val value = sp.all[key]
                connection?.send(
                    "sharedPreferencesChange",
                    JSONObject()
                        .put("preferences", name)
                        .put("name", key)
                        .put("deleted", value == null)
                        .put("time", System.currentTimeMillis())
                        .put("value", jsonValue(value)),
                )
            }
            // SharedPreferences keeps listeners weakly; the map holds the strong reference.
            prefs.registerOnSharedPreferenceChangeListener(listener)
            listeners[name] = prefs to listener
        }
    }

    private fun prefsNames(): List<String> = names ?: File(appContext.applicationInfo.dataDir, "shared_prefs")
        .listFiles { f -> f.name.endsWith(".xml") }
        ?.map { it.name.removeSuffix(".xml") }
        ?.sorted()
        .orEmpty()

    private fun prefs(name: String): SharedPreferences = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun toJson(prefs: SharedPreferences): JSONObject {
        val json = JSONObject()
        prefs.all.toSortedMap().forEach { (key, value) -> json.put(key, jsonValue(value)) }
        return json
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray(value.toList())
        else -> value
    }

    private fun toStringSet(raw: Any): Set<String> = when (raw) {
        is JSONArray -> (0 until raw.length()).map { raw.getString(it) }.toSet()
        else -> runCatching { toStringSet(JSONArray(raw.toString())) }.getOrElse { setOf(raw.toString()) }
    }
}
