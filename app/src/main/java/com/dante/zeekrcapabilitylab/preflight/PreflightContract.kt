package com.dante.zeekrcapabilitylab.preflight

import kotlinx.serialization.json.*
import java.security.MessageDigest

internal fun j(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> if (value.toDouble().isFinite()) JsonPrimitive(value) else JsonNull
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to j(it.value) })
    is Iterable<*> -> JsonArray(value.map(::j))
    else -> JsonPrimitive(value.toString())
}
internal fun obj(vararg values: Pair<String, Any?>): JsonObject = j(values.toMap()).jsonObject
internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal object PreflightPlan {
    const val VERSION = "p2-real-camera-1"
    const val BASELINE_MS = 25 * 60_000L
    const val MAX_WRITE = 16L * 1024 * 1024 * 1024
    const val MAX_RETAINED = 2L * 1024 * 1024 * 1024
    const val RESERVE = 1024L * 1024 * 1024
    const val SHORT_LIMIT = 96 * 1024
    val cases = listOf("build", "public_capabilities", "egl_small", "usb_fd", "closed_file", "baseline")
    val deferred = listOf("synthetic_encode_decode", "lossless_repack", "candidate_camera_smoke", "candidate_soak", "window_scripts", "pressure")
    fun interruptedCleanup(mode: String?): String = if(mode=="CAPABILITIES_ONLY") "CONFIRMED" else "UNCONFIRMED"
    fun previousRunBlocks(exists: Boolean, previous: JsonObject?, currentBoot: Int): String? {
        if (!exists) return null
        if (previous == null) return "PREVIOUS_JOURNAL_UNREADABLE"
        if (previous["cleanup"] == JsonPrimitive("CONFIRMED")) return null
        val oldBoot = (previous["bootCount"] as? JsonPrimitive)?.intOrNull
        return if (currentBoot >= 0 && oldBoot != null && oldBoot >= 0 && currentBoot != oldBoot) null
            else "PREVIOUS_CLEANUP_UNCONFIRMED_REBOOT_REQUIRED"
    }
    fun baselineStatus(reason: String?, duration: Long, freshSamples: Int, files: Int, failed: Boolean, route: String): String = when {
        reason == "USER_CANCELLED" -> "CANCELLED"
        reason != "PLAN_DURATION_REACHED" || duration < BASELINE_MS || freshSamples < 1 || files < 3 || failed || route != "MEDIA_RECORDER" -> "INCOMPLETE"
        else -> "PASS"
    }
    fun budgetReason(written: Long, retained: Long, incoming: Long, free: Long?): String? = when {
        free == null || free < 0 -> "FREE_SPACE_UNKNOWN"
        incoming < 0 || written < 0 || retained < 0 -> "INVALID_BUDGET"
        incoming > MAX_WRITE - written -> "WRITE_BUDGET_EXHAUSTED"
        incoming > MAX_RETAINED - retained -> "RETENTION_BUDGET_EXHAUSTED"
        free < RESERVE || incoming > (free - RESERVE) / 2 -> "USB_FREE_SPACE_LOW"
        else -> null
    }
}

/** No timeout grants ownership. Stale callbacks cannot finish a newer operation. */
internal class PreflightGate {
    var generation = 0L; private set
    var active: String? = null; private set
    var cleanup = "CONFIRMED"; private set
    var cancelled = false; private set
    fun begin(name: String, checkpoint: () -> Unit = {}): Long {
        check(active == null && cleanup == "CONFIRMED" && !cancelled)
        checkpoint()
        active = name; cleanup = "PENDING"; return ++generation
    }
    fun finish(token: Long, confirmed: Boolean): Boolean {
        if (token != generation || active == null) return false
        cleanup = if (confirmed) "CONFIRMED" else "UNCONFIRMED"
        if (confirmed) active = null
        return confirmed
    }
    fun cancel() { cancelled = true }
    fun accepts(token: Long): Boolean = token == generation && !cancelled && active != null
}

/** A frozen UI can keep reporting a cached tiny age. Observe counter progress on our own clock. */
internal class PreflightProgressClock {
    private var input: Long? = null
    private var display: Long? = null
    private var inputAt = 0L
    private var displayAt = 0L
    fun observe(nowMs: Long, inputFrames: Long, displayFrames: Long): Pair<Long,Long> {
        if(input != inputFrames) { input=inputFrames; inputAt=nowMs }
        if(display != displayFrames) { display=displayFrames; displayAt=nowMs }
        return (nowMs-inputAt).coerceAtLeast(0) to (nowMs-displayAt).coerceAtLeast(0)
    }
}

internal object PreflightReport {
    val statuses = setOf("PASS", "FAIL", "WARN", "SKIP", "BLOCKED", "UNAVAILABLE", "INCOMPLETE", "CANCELLED", "NOT_IMPLEMENTED", "NOT_RUN")
    fun test(id: String, status: String, reason: String? = null, evidence: String = "NONE",
             actualRoute: String? = null, data: JsonObject = obj(), cleanup: String = "CONFIRMED",
             start: Long? = null, end: Long? = null) = obj(
        "id" to id, "testCaseVersion" to 1, "status" to status, "reason" to reason,
        "evidenceLevel" to evidence, "actualRoute" to actualRoute, "cleanup" to cleanup,
        "startElapsedMs" to start, "endElapsedMs" to end,
        "durationMs" to if (start != null && end != null) (end - start).coerceAtLeast(0) else null,
        "evidenceRefs" to listOf("tests.$id.data"), "data" to data)

    fun validate(report: JsonObject) {
        require(report["schemaVersion"] == JsonPrimitive(1))
        require(report["exampleOnly"] == JsonPrimitive(false))
        require(report["productionSwitchAllowed"] == JsonPrimitive(false))
        require(report["format"] == JsonPrimitive("OPENAVM_PREFLIGHT"))
        require(report["runId"]?.jsonPrimitive?.content?.isNotBlank() == true)
        report["tests"]!!.jsonArray.forEach { item ->
            val t = item.jsonObject
            require(t["status"]!!.jsonPrimitive.content in statuses)
            if (t["status"] == JsonPrimitive("PASS")) {
                require(t["cleanup"] == JsonPrimitive("CONFIRMED"))
                require(t["evidenceLevel"] != JsonPrimitive("NONE"))
                val requested = t["requestedRoute"]
                if (requested != null && requested != JsonNull) require(requested == t["actualRoute"])
            }
        }
    }

    fun syntheticSummary(value: JsonObject) = obj("status" to value["status"],"reason" to value["reason"],"failure" to value["failure"],
        "health" to value["healthSequence"],"full" to value["fullSequence"],"scope" to value["scope"],
        "healthFiles" to value["health"],"fullFiles" to value["fullRaster"],
        "healthCleanup" to value["healthFileCleanup"],"fullCleanup" to value["fullFileCleanup"],
        "fullEncodedLoad" to value["fullEncodedLoad"],"loadGroups" to value["loadGroups"],
        "retainedDiagnostics" to value["retainedDiagnostics"],
        "healthFailureSnapshot" to value["healthFailureSnapshot"],"healthFinalSnapshot" to value["healthFinalSnapshot"])

    /** Hash the persisted full bytes, never this envelope. Always emit a whole JSON document. */
    fun short(full: ByteArray, persisted: Boolean = true): String {
        val source = Json.parseToJsonElement(full.toString(Charsets.UTF_8)).jsonObject
        validate(source)
        val fields = source.toMutableMap()
        val omitted = mutableListOf<String>()
        // The unconfirmed-cleanup branch has no successful service callback to create a summary.
        // Derive the same bounded summary before the final fallback can drop interrupted details.
        if(fields["p1Summary"] !is JsonObject) (fields["p1InterruptedDetail"] as? JsonObject)?.let {
            fields["p1Summary"]=JsonObject(syntheticSummary(it)+("summarySource" to JsonPrimitive("p1InterruptedDetail")))
        }
        fun envelope(): String {
            fields["fullReportSha256"] = if(persisted) JsonPrimitive(sha(full)) else JsonNull
            fields["fullReportPersistence"] = JsonPrimitive(if(persisted) "SAVED" else "MEMORY_ONLY_SAVE_UNAVAILABLE")
            if(!persisted) fields["memoryReportSha256"] = JsonPrimitive(sha(full))
            fields["fullReportBytes"] = JsonPrimitive(full.size)
            fields["omittedSections"] = j(omitted)
            fields["exportBytes"] = JsonPrimitive(0)
            repeat(5) { fields["exportBytes"] = JsonPrimitive(JsonObject(fields).toString().toByteArray().size) }
            return JsonObject(fields).toString()
        }
        var text = envelope()
        for (key in listOf("samples", "events", "cameraCapabilities", "codecCapabilities", "sourceManifest", "historicalExits")) {
            if (text.toByteArray().size <= PreflightPlan.SHORT_LIMIT) break
            if (fields.remove(key) != null) omitted += key
            text = envelope()
        }
        if (text.toByteArray().size > PreflightPlan.SHORT_LIMIT) {
            // Preserve measured statuses/reasons/counts. Only remove known bulky per-frame arrays.
            // The persisted full report remains byte-for-byte unchanged and available as parts.
            val arrays = setOf("submitElapsedNs", "imageFrameIds", "filePtsUs", "frameIds", "sourceTimestampsNs")
            fun compact(value: JsonElement, path: String): JsonElement = when(value) {
                is JsonArray -> JsonArray(value.mapIndexed { i,v -> compact(v,"$path[$i]") })
                is JsonObject -> {
                    val result = linkedMapOf<String,JsonElement>()
                    value.forEach { (key,v) ->
                        if(key in arrays && v is JsonArray) {
                            omitted += "$path.$key"
                            result["${key}Summary"] = obj("count" to v.size,"first" to v.firstOrNull(),"last" to v.lastOrNull(),
                                "sha256" to sha(v.toString().toByteArray()))
                        } else result[key] = compact(v,"$path.$key")
                    }
                    JsonObject(result)
                }
                else -> value
            }
            fields["tests"] = compact(source.getValue("tests"),"tests")
            fields["p1InterruptedDetail"]?.let { fields["p1InterruptedDetail"] = compact(it,"p1InterruptedDetail") }
            text = envelope()
        }
        if (text.toByteArray().size > PreflightPlan.SHORT_LIMIT) {
            val core = setOf("schemaVersion", "exampleOnly", "format", "version", "startEpochMs", "runId", "reportId", "build", "planVersion", "mode", "phase", "reason", "cleanup", "productionSwitchAllowed", "untested", "findings", "p1Summary", "p2Summary", "actualCoverage")
            fields.keys.toList().filter { it !in core }.forEach { fields.remove(it); omitted += it }
            fields["tests"] = JsonArray(source["tests"]!!.jsonArray.map { JsonObject(it.jsonObject.filterKeys { key -> key != "data" }) })
            omitted += "tests.data"
            text = envelope()
        }
        require(text.toByteArray().size <= PreflightPlan.SHORT_LIMIT) { "SHORT_REPORT_OVER_BUDGET" }
        return text
    }
}
