package dev.slicker.plugins.leaks

import android.content.Context
import dev.slicker.core.SlickerConnection
import dev.slicker.core.SlickerPlugin
import leakcanary.EventListener
import leakcanary.HeapDumper
import leakcanary.LeakCanary
import org.json.JSONArray
import org.json.JSONObject
import shark.HeapAnalysisSuccess
import shark.LeakTrace
import shark.LeakTraceObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop plugin id "LeakCanary". Forwards LeakCanary's heap analyses to the desktop.
 * LeakCanary is bundled with the debug artifact, so this needs no setup.
 */
class LeaksPlugin(context: Context) : SlickerPlugin {
    override val id = "LeakCanary"
    override val runInBackground = false

    private val leaks = mutableListOf<JSONObject>()
    private val seenSignatures = mutableSetOf<String>()
    private var connection: SlickerConnection? = null

    /** Threshold chosen in the desktop tab, kept so it applies from the next app start too. */
    private val thresholdFile = File(context.filesDir, "slicker/leak_threshold")

    /** Heap dumps go here instead of the public Download folder; see [dumpToPrivateStorage]. */
    private val dumpDir = File(context.cacheDir, "slicker/leakcanary")
    private val privateRoot = context.applicationInfo.dataDir
    private val redirects = ConcurrentHashMap<String, File>()

    /** Hooks into LeakCanary. Call once from Slicker.start. */
    fun install() {
        thresholdFile.takeIf { it.exists() }
            ?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }
            ?.let(::applyThreshold)
        val listener = EventListener { event ->
            if (event is EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded) {
                report(event.heapAnalysis)
            }
        }
        val config = LeakCanary.config
        LeakCanary.config = config.copy(
            heapDumper = dumpToPrivateStorage(config.heapDumper),
            eventListeners = (config.eventListeners + listener).map(::redirectingListener),
        )
    }

    /**
     * LeakCanary writes heap dumps to the public Download folder whenever it can (Android 11+ allows
     * that without a permission). Shared storage goes through FUSE, which throttles writes hard: on
     * some devices a 30 MB dump crawls at a few KB/s while the app is frozen, and never finishes.
     * So dump into the app's own cache folder instead, leave a 1-byte placeholder where LeakCanary
     * expected the file (it checks the size), and point the HeapDump event at the real file.
     */
    private fun dumpToPrivateStorage(original: HeapDumper) = HeapDumper { requested ->
        if (requested.absolutePath.startsWith(privateRoot)) {
            original.dumpHeap(requested)
            return@HeapDumper
        }
        dumpDir.mkdirs()
        redirects.clear() // only the latest dump's events are in flight
        val dump = File(dumpDir, requested.name)
        original.dumpHeap(dump)
        if (dump.length() > 0) {
            redirects[requested.absolutePath] = dump
            runCatching { requested.writeBytes(byteArrayOf(0)) }
        }
        deleteOldDumps()
    }

    private fun redirectingListener(listener: EventListener) = EventListener { event ->
        val dump = (event as? EventListener.Event.HeapDump)?.let { redirects[it.file.absolutePath] }
        if (event is EventListener.Event.HeapDump && dump != null) {
            runCatching { event.file.delete() } // the placeholder
            listener.onEvent(EventListener.Event.HeapDump(event.uniqueId, dump, event.durationMillis, event.reason))
        } else {
            listener.onEvent(event)
        }
    }

    /** Keeps the newest dumps, like LeakCanary's own maxStoredHeapDumps. */
    private fun deleteOldDumps() {
        val max = LeakCanary.config.maxStoredHeapDumps
        dumpDir.listFiles { f -> f.name.endsWith(".hprof") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(max)
            ?.forEach { it.delete() }
    }

    @Synchronized
    override fun onConnect(connection: SlickerConnection) {
        this.connection = connection
        connection.receive("setThreshold") { params, responder ->
            val threshold = params.optInt("threshold", DEFAULT_THRESHOLD).coerceIn(1, 100)
            applyThreshold(threshold)
            runCatching {
                thresholdFile.parentFile?.mkdirs()
                thresholdFile.writeText(threshold.toString())
            }
            responder.success(null)
        }
        connection.receive("clear") { _, responder ->
            synchronized(this) { leaks.clear() }
            responder.success(null)
        }
        sendLeaks()
    }

    private fun applyThreshold(threshold: Int) {
        if (LeakCanary.config.retainedVisibleThreshold != threshold) {
            LeakCanary.config = LeakCanary.config.copy(retainedVisibleThreshold = threshold)
        }
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

    private companion object {
        const val DEFAULT_THRESHOLD = 5
    }
}
