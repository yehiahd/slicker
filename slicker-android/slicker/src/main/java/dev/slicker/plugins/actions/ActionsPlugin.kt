package dev.slicker.plugins.actions

import android.os.Handler
import android.os.Looper
import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Desktop plugin id "Actions". Buttons the app registers with Slicker.addAction() appear on the
 * desktop; pressing one runs the code on the app's main thread.
 */
class ActionsPlugin : SlickerPlugin {
    override val id = "Actions"

    /** [input] is the text field's initial value, or null for a plain button. */
    private class Action(
        val name: String,
        val group: String,
        val description: String?,
        val input: String?,
        val run: (String) -> Unit,
    )

    private val actions = LinkedHashMap<String, Action>()
    private var connection: SlickerConnection? = null
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun add(name: String, group: String?, description: String?, input: String?, run: (String) -> Unit) {
        actions[name] = Action(name, group ?: "Actions", description, input, run)
        sendActions()
    }

    @Synchronized
    fun remove(name: String) {
        if (actions.remove(name) != null) sendActions()
    }

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        connection.receive("run") { params, responder ->
            val action = synchronized(this) { actions[params.getString("name")] }
            if (action == null) {
                responder.error("Action \"${params.getString("name")}\" is not registered")
                return@receive
            }
            var error: Throwable? = null
            val done = CountDownLatch(1)
            main.post {
                try {
                    action.run(params.optString("value", action.input ?: ""))
                } catch (t: Throwable) {
                    error = t
                } finally {
                    done.countDown()
                }
            }
            when {
                !done.await(10, TimeUnit.SECONDS) -> responder.success(JSONObject().put("pending", true))
                error != null -> responder.error(error?.message ?: error!!.javaClass.simpleName)
                else -> responder.success(null)
            }
        }
        sendActions()
    }

    @Synchronized
    override fun onDisconnect() {
        connection = null
    }

    @Synchronized
    private fun sendActions() {
        val current = connection ?: return
        val list = JSONArray()
        actions.values.forEach {
            list.put(
                JSONObject()
                    .put("name", it.name)
                    .put("group", it.group)
                    .put("description", it.description ?: JSONObject.NULL)
                    .put("input", it.input ?: JSONObject.NULL),
            )
        }
        current.send("actions", JSONObject().put("actions", list))
    }
}
