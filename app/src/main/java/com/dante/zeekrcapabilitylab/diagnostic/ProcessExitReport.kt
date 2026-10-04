package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.CrashRecord
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.service.recorder.ContinuousFailureCapture
import com.dante.zeekrcapabilitylab.service.recorder.ContinuousFailureStage
import com.dante.zeekrcapabilitylab.service.recorder.ContinuousFormatFacts
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.*

internal object ProcessExitReport {
    private val symbol = Regex("[A-Za-z_$][A-Za-z0-9_.$-]*")
    fun symbol(value: String?, max: Int = 120): String? = value?.takeIf { it.length <= max && symbol.matches(it) }
    private val eventNames = setOf("UNCAUGHT_EXCEPTION", "PREVIOUS_CRASH", "PROCESS_STARTED",
        "RECORDER_ENCODER_SELECTED", "RECORDER_SEGMENT_START", "RECORDER_CONTINUOUS_FAILED", "RECORDER_CONTINUOUS_FIRST_FAILURE",
        "RECORDER_STOP", "RECORDER_MODE_SWITCH_REQUESTED", "RECORDER_SEGMENT_FINALIZE_BEGIN",
        "RECORDER_MIRROR_HANDOFF_STOPPED", "RECORDER_MIRROR_HANDOFF_RECORDING_STOP_CONFIRMED",
        "RECORDER_MIRROR_HANDOFF_RECORDING_STOP_ACKNOWLEDGED",
        "PRODUCT_HOME_PREVIEW_RESTORED", "PRODUCT_HOME_PREVIEW_RELEASE_TIMEOUT")
    fun relevant(event: ProbeEvent): Boolean = event.eventName in eventNames || event.eventName == ContinuousFormatFacts.EVENT ||
        event.eventName.startsWith("RECORDER_NATIVE_")

    private const val MAX_FAILURE_GROUPS = 6
    private const val MAX_FAILURE_BYTES = 10 * 1024
    private val failureNames = setOf("RECORDER_CONTINUOUS_FIRST_FAILURE", "RECORDER_CONTINUOUS_FAILED")
    private val channels = setOf("SURROUND", "FRONT_CABIN", "REAR_CABIN")
    private val backends = setOf("CONTINUOUS_CODEC", "SHARED_INPUT_CONTINUOUS_CODEC", "CONTINUOUS_MEDIA_RECORDER", "MEDIA_RECORDER")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val stages = ContinuousFailureStage.entries.map { it.name }.toSet()
    private val eventOrder = compareBy<ProbeEvent> { it.epochMs }.thenBy { it.sequence }

    /** A canonical random recording UUID is hashed; account IDs, VINs and arbitrary strings are rejected. */
    fun sessionCorrelation(value: String?): String? = value?.takeIf(uuid::matches)?.let { id ->
        val bytes = MessageDigest.getInstance("SHA-256").digest(id.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        "sha256:" + bytes.take(8).joinToString("") { byte ->
            val n = byte.toInt() and 255
            "0123456789abcdef"[n ushr 4].toString() + "0123456789abcdef"[n and 15]
        }
    }
    fun channel(value: String?): String = value?.takeIf { it in channels } ?: "UNKNOWN"
    private fun backend(value: String?): String = value?.takeIf { it in backends } ?: "UNKNOWN"
    private fun typedError(event: ProbeEvent): String? {
        val value = event.payload["errorCode"] ?: return null
        if (value in setOf("UNCLASSIFIED_SIGNAL", "UNCLASSIFIED_THROWABLE", "EVIDENCE_UNAVAILABLE")) {
            // Legacy evidence may be unclassified even when its control callback
            // carries a precise application code. Never export the raw message,
            // and do not replace a placeholder with the generic callback wrapper.
            val message = event.errorMessage
            val callbackCode = if (message?.startsWith("CONTINUOUS_ENCODER_FAILED:") == true) {
                // Exactly one known control wrapper; never scan arbitrary text or nested wrappers.
                ContinuousFailureCapture.knownCode(message.removePrefix("CONTINUOUS_ENCODER_FAILED:"))
            } else ContinuousFailureCapture.knownCode(message)
            return callbackCode
                ?.takeUnless { it == "CONTINUOUS_ENCODER_FAILED" } ?: value
        }
        return ContinuousFailureCapture.knownCode(value)?.takeIf { it == value }
    }
    private fun failureKey(event: ProbeEvent): String {
        val correlation = sessionCorrelation(event.payload["recordingSessionId"])
        val channel = channel(event.payload["channelId"])
        if (correlation != null && channel != "UNKNOWN") return "$correlation/$channel"
        // Older events have no channel: never silently merge different camera owners into one run.
        return "$correlation/$channel/" + (event.payload["faultElapsedMs"]?.toLongOrNull() ?: event.elapsedRealtimeMs)
    }
    private fun failureGroups(events: List<ProbeEvent>): List<List<ProbeEvent>> = events.asSequence()
        .filter { it.eventName in failureNames }
        .distinctBy { Triple(it.epochMs, it.sequence, it.eventName) }
        .sortedWith(eventOrder).groupBy(::failureKey).values
        .sortedWith(compareBy<List<ProbeEvent>> { it.last().epochMs }.thenBy { it.last().sequence })
        .takeLast(MAX_FAILURE_GROUPS)

    private fun firstFailure(group: List<ProbeEvent>): ProbeEvent {
        val sample = group.first()
        if (sessionCorrelation(sample.payload["recordingSessionId"]) == null || channel(sample.payload["channelId"]) == "UNKNOWN")
            return sample
        // Typed extraction may finish out of order. The catch-site ordinal belongs to this known owner.
        return group.minWith(compareBy<ProbeEvent> {
            it.payload["faultCaptureOrder"]?.toLongOrNull()?.takeIf { order -> order > 0 } ?: Long.MAX_VALUE
        }.thenBy {
            it.payload["faultElapsedMs"]?.toLongOrNull()?.takeIf { elapsed -> elapsed >= 0 } ?: Long.MAX_VALUE
        }.then(eventOrder))
    }

    /** Protect first/recent typed failures from ordinary STOP/start noise within the same bounded read. */
    fun retainHistory(events: List<ProbeEvent>, limit: Int = 512): List<ProbeEvent> {
        require(limit >= MAX_FAILURE_GROUPS * 2 + CodecFormatReport.MAX_GROUPS * 3)
        if (events.size <= limit) return events
        val formats = CodecFormatReport.retained(events.map(CodecFormatReport::row))
        val protected = (failureGroups(events).flatMap { listOf(firstFailure(it), it.last()) } +
            events.filter { CodecFormatReport.row(it).identity in formats }).toSet()
        return (events.filter { it !in protected }.sortedWith(eventOrder).takeLast(limit - protected.size) + protected)
            .sortedWith(eventOrder)
    }

    /** Independent of the mutable lastFault snapshot. "First available" does not claim complete history. */
    fun continuousFailures(events: List<ProbeEvent>): JsonObject {
        val groups = failureGroups(events).map { group ->
            buildJsonObject {
                put("sessionCorrelation", sessionCorrelation(group.first().payload["recordingSessionId"]))
                put("channelId", channel(group.first().payload["channelId"]))
                put("recordingBackend", group.asReversed().firstNotNullOfOrNull {
                    backend(it.payload["recordingBackend"]).takeUnless { value -> value == "UNKNOWN" }
                } ?: "UNKNOWN")
                put("firstAvailable", failureEdge(firstFailure(group)))
                put("latestAvailable", failureEdge(group.last()))
            }
        }.toMutableList()
        var omitted = 0
        fun render() = buildJsonObject {
            put("scope", "RETAINED_HISTORY_NOT_SELECTED_SESSION")
            put("firstCauseScope", "FIRST_AVAILABLE_PER_KNOWN_SESSION_AND_CHANNEL")
            put("historyMayBeIncomplete", true)
            put("groupsOmittedForSize", omitted)
            put("groups", JsonArray(groups))
        }
        while (groups.isNotEmpty() && render().toString().toByteArray(Charsets.UTF_8).size > MAX_FAILURE_BYTES) {
            groups.removeAt(0); omitted++
        }
        return render()
    }

    private fun failureEdge(event: ProbeEvent): JsonObject = buildJsonObject {
        put("event", event.eventName); put("atEpochMs", event.epochMs); put("atElapsedMs", event.elapsedRealtimeMs)
        put("stage", event.payload["failureStage"]?.takeIf { it in stages } ?: "UNKNOWN")
        // The general callback may wrap the actual code as CONTINUOUS_ENCODER_FAILED; use typed evidence first.
        put("errorCode", typedError(event) ?: ContinuousFailureCapture.knownCode(event.errorMessage) ?: "UNKNOWN")
        put("exceptionType", symbol(event.payload["exceptionType"], 160) ?: "UNKNOWN")
        put("causeType", symbol(event.payload["causeType"], 160) ?: "UNKNOWN")
        CodecFormatReport.format(event.payload)?.let { put("format", it) }
        listOf("codecErrorCode", "errno").forEach { key ->
            put(key, event.payload[key]?.toIntOrNull())
        }
        listOf("codecRecoverable", "codecTransient").forEach { key ->
            put(key, event.payload[key]?.toBooleanStrictOrNull())
        }
        listOf("faultEpochMs", "faultElapsedMs", "faultCaptureOrder", "snapshotAgeMs", "faultEncodedFrames", "faultEncodedBytes", "faultCompletedFiles",
            "faultQueueItems", "faultQueueBytes", "faultSourceAgeAtSampleMs").forEach { key ->
            put(key, event.payload[key]?.toLongOrNull()?.takeIf { it >= 0 })
        }
    }

    fun events(events: List<ProbeEvent>): JsonArray {
        val rows = events.filter(::relevant).distinctBy { Triple(it.epochMs, it.sequence, it.eventName) }
            .sortedWith(compareBy<ProbeEvent> { it.epochMs }.thenBy { it.sequence }).takeLast(24).map { event ->
                buildJsonObject {
                    put("at", event.epochMs); put("elapsed", event.elapsedRealtimeMs)
                    put("event", symbol(event.eventName)); put("severity", symbol(event.severity))
                    put("exceptionType", symbol(event.payload["exceptionType"] ?: event.errorType ?: event.payload["exception"]))
                    // Free-text error messages can contain media paths, URLs and tokens. Export only an enum prefix.
                    put("errorCode", typedError(event) ?: symbol(event.errorMessage?.takeWhile { it.isLetterOrDigit() || it == '_' }?.take(80)))
                    put("facts", buildJsonObject {
                        if (event.eventName in failureNames) {
                            put("sessionCorrelation", sessionCorrelation(event.payload["recordingSessionId"]))
                            put("channelId", channel(event.payload["channelId"]))
                            put("failureStage", event.payload["failureStage"]?.takeIf { it in stages } ?: "UNKNOWN")
                        }
                        listOf("reason", "route", "segment", "switches", "target", "waitedElapsedMs").forEach { key ->
                            event.payload[key]?.takeIf { it.length <= 80 && it.all { c -> c.isLetterOrDigit() || c in "_.-" } }
                                ?.let { put(key, it) }
                        }
                    })
                }
            }.toMutableList()
        while (rows.isNotEmpty() && JsonArray(rows).toString().toByteArray().size > 6000) rows.removeAt(0)
        return JsonArray(rows)
    }

    fun crash(crash: CrashRecord): JsonObject = buildJsonObject {
        put("atEpochMs", crash.epochMs)
        put("exceptionType", symbol(crash.exceptionType))
        put("thread", symbol(crash.thread, 80))
        val lines = crash.stackTrace.take(64 * 1024).lineSequence()
        val framePattern = Regex("at [A-Za-z0-9_.$]+\\([A-Za-z0-9_.$: -]+\\)")
        put("frames", buildJsonArray {
            lines.map(String::trim).filter { it.length <= 180 && framePattern.matches(it) }.take(12).forEach { add(it) }
        })
        put("causes", buildJsonArray {
            lines.filter { it.trimStart().startsWith("Caused by: ") }
                .mapNotNull { symbol(it.trim().removePrefix("Caused by: ").substringBefore(':')) }
                .distinct().take(4).forEach { add(it) }
        })
        put("messageOmitted", true)
    }
}

/** Read only documented tombstone fields; never export registers, memory, FD paths or log buffers.
 * Wire field numbers: Android system/core/debuggerd/proto/tombstone.proto. No reflection or hidden API.
 */
internal object NativeCrashTrace {
    const val MAX_BYTES = 2 * 1024 * 1024
    private data class Field(val tag: Int, val value: Long? = null, val start: Int = 0, val end: Int = 0)

    fun decode(bytes: ByteArray): JsonObject {
        if (bytes.size > MAX_BYTES) return status("TOO_LARGE")
        return runCatching {
            val root = fields(bytes, 0, bytes.size)
            val tid = root.lastOrNull { it.tag == 6 }?.value
            val thread = root.filter { it.tag == 16 && it.value == null }.mapNotNull { entry ->
                val map = fields(bytes, entry.start, entry.end)
                map.lastOrNull { it.tag == 2 && it.value == null }?.takeIf {
                    tid != null && map.lastOrNull { field -> field.tag == 1 }?.value == tid
                }
            }.lastOrNull() ?: return@runCatching status("CRASH_THREAD_NOT_FOUND")
            val signal = root.lastOrNull { it.tag == 10 && it.value == null }?.let { fields(bytes, it.start, it.end) }
            buildJsonObject {
                put("status", "AVAILABLE")
                put("tid", tid)
                put("signal", signal?.lastOrNull { it.tag == 2 }?.let { ProcessExitReport.symbol(string(bytes, it)) })
                put("signalNumber", signal?.lastOrNull { it.tag == 1 }?.value)
                put("frames", buildJsonArray {
                    fields(bytes, thread.start, thread.end).filter { it.tag == 4 && it.value == null }.take(8).forEach { frame ->
                        val values = fields(bytes, frame.start, frame.end)
                        add(buildJsonObject {
                            val path = values.lastOrNull { it.tag == 6 }?.let { string(bytes, it) }
                            val library = path?.replace('\\', '/')?.substringAfterLast('/')
                                ?.takeIf { it.length <= 120 && it.matches(Regex("[A-Za-z0-9_.+-]+")) }
                            val function = values.lastOrNull { it.tag == 4 }?.let { string(bytes, it) }
                                ?.takeIf { it.length <= 240 && it.matches(Regex("[A-Za-z0-9_:~<>,() *&.+=\\[\\]$-]+")) }
                            put("library", library); put("function", function)
                            put("relativePc", values.lastOrNull { it.tag == 1 }?.value?.takeIf { it >= 0 }?.toString(16))
                        })
                    }
                })
            }
        }.getOrElse { status("INVALID_PROTO") }
    }

    private fun status(value: String) = buildJsonObject { put("status", value) }
    private fun string(bytes: ByteArray, field: Field): String? = if (field.value == null && field.end - field.start <= 1024)
        String(bytes, field.start, field.end - field.start, Charsets.UTF_8) else null

    private fun fields(bytes: ByteArray, start: Int, end: Int): List<Field> {
        var position = start
        fun varint(): Long {
            var result = 0L
            for (index in 0..9) {
                require(position < end)
                val byte = bytes[position++].toInt() and 255
                if (index == 9) require(byte and 254 == 0)
                result = result or ((byte and 127).toLong() shl (index * 7))
                if (byte and 128 == 0) return result
            }
            error("VARINT_OVERFLOW")
        }
        val result = mutableListOf<Field>()
        var fieldCount = 0
        while (position < end) {
            require(++fieldCount <= 8192)
            val key = varint()
            require(key > 0 && key ushr 3 in 1..0x1fffffffL)
            val tag = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> result += Field(tag, value = varint())
                1 -> { require(end - position >= 8); position += 8 }
                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= end - position)
                    result += Field(tag, start = position, end = position + length.toInt())
                    position += length.toInt()
                }
                5 -> { require(end - position >= 4); position += 4 }
                else -> error("UNSUPPORTED_WIRE_TYPE")
            }
        }
        return result
    }
}
