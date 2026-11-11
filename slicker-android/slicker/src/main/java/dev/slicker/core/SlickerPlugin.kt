package dev.slicker.core

import org.json.JSONObject

/** A device-side plugin. [id] must match the desktop plugin id (e.g. "Network"). */
interface SlickerPlugin {
    val id: String

    /** Background plugins are initialised by the desktop as soon as the app connects. */
    val runInBackground: Boolean get() = false

    fun onConnect(connection: SlickerConnection)

    fun onDisconnect()
}

interface SlickerConnection {
    /** Sends a device -> desktop event for this plugin. */
    fun send(method: String, params: JSONObject)

    /** Registers a handler for a desktop -> device call on this plugin. */
    fun receive(method: String, receiver: SlickerReceiver)
}

fun interface SlickerReceiver {
    fun onReceive(params: JSONObject, responder: SlickerResponder)
}

interface SlickerResponder {
    /** [result] may be a JSONObject, JSONArray, primitive or null. */
    fun success(result: Any? = null)

    fun error(message: String, name: String? = null)
}
