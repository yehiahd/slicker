package dev.slicker.plugins.leaks

import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import leakcanary.EventListener
import leakcanary.LeakCanary
import org.json.JSONArray
import org.json.JSONObject
import shark.HeapAnalysisSuccess
import shark.LeakTrace
import shark.LeakTraceObject
import java.util.UUID

/**
 * Desktop plugin id "LeakCanary". Forwards LeakCanary's heap analyses to the desktop.
 * LeakCanary is bundled with the debug artifact, so this needs no setup.
 */
class LeaksPlugin : SlickerPlugin {
    override val id = "LeakCanary"
    override val runInBackground = false

    private val leaks = mutableListOf<JSONObject>()
    private val seenSignatures = mutableSetOf<String>()
    private var connection: SlickerConnection? = null

    /** Hooks into LeakCanary. Call once from Slicker.start. */
    fun install() {
        val listener = EventListener { event ->
            if (event is EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded) {
                report(event.heapAnalysis)
            }
        }
        LeakCanary.config = LeakCanary.config.copy(
            eventListeners = LeakCanary.config.eventListeners + listener,
        )
    }

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        connection.receive("clear") { _, responder ->
            synchronized(this) { leaks.clear() }
            responder.success(null)
        }
        sendLeaks()
    }

    @Synchronized
    override fun onDisconnect() {
        connection = null
    }

    @Synchronized
    private fun report(analysis: HeapAnalysisSuccess) {
        for (leak in analysis.allLeaks) {
            val trace = leak.leakTraces.firstOrNull() ?: continue
            if (seenSignatures.add(trace.signature)) leaks.add(trace.toJson(leak.shortDescription))
        }
        sendLeaks()
    }

    private fun sendLeaks() {
        connection?.send("reportLeak2", JSONObject().put("leaks", JSONArray(leaks)))
    }

    private fun LeakTrace.toJson(title: String): JSONObject {
        val objects = referencePath.map { it.originObject } + leakingObject
        val ids = objects.map { UUID.randomUUID().toString() }
        val elements = JSONObject()
        objects.forEachIndexed { i, obj ->
            elements.put(ids[i], JSONObject()
                .put("id", ids[i])
                .put("name", "${obj.className} (${obj.typeName})")
                .put("expanded", true)
                .put("children", JSONArray(listOfNotNull(ids.getOrNull(i + 1))))
                .put("attributes", JSONArray()
                    .put(JSONObject().put("name", "leaking").put("value", obj.leakingStatus.shortName))
                    .put(JSONObject().put("name", "retaining").put("value", obj.retaining)))
                .put("data", JSONObject())
                .put("decoration", "")
                .put("extraInfo", JSONObject()))
        }
        return JSONObject()
            .put("title", title)
            .put("root", ids.first())
            .put("elements", elements)
            .put("retainedSize", retainedHeapByteSize?.let { "$it bytes" } ?: "unknown size")
            .put("details", toString())
    }

    private val LeakTraceObject.LeakingStatus.shortName
        get() = when (this) {
            LeakTraceObject.LeakingStatus.NOT_LEAKING -> "N"
            LeakTraceObject.LeakingStatus.LEAKING -> "Y"
            LeakTraceObject.LeakingStatus.UNKNOWN -> "?"
        }

    private val LeakTraceObject.retaining
        get() = retainedHeapByteSize?.let { "$it bytes ($retainedObjectCount objects)" } ?: "unknown"
}
