package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.CrashRecord
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import kotlinx.serialization.json.*

internal object ProcessExitReport {
    private val symbol = Regex("[A-Za-z_$][A-Za-z0-9_.$-]*")
    fun symbol(value: String?, max: Int = 120): String? = value?.takeIf { it.length <= max && symbol.matches(it) }
    private val eventNames = setOf("UNCAUGHT_EXCEPTION", "PREVIOUS_CRASH", "PROCESS_STARTED",
        "RECORDER_ENCODER_SELECTED", "RECORDER_SEGMENT_START", "RECORDER_CONTINUOUS_FAILED",
        "RECORDER_STOP", "RECORDER_MODE_SWITCH_REQUESTED", "RECORDER_SEGMENT_FINALIZE_BEGIN")
    fun relevant(event: ProbeEvent): Boolean = event.eventName in eventNames ||
        event.eventName.startsWith("RECORDER_NATIVE_")

    fun events(events: List<ProbeEvent>): JsonArray {
        val rows = events.filter(::relevant).distinctBy { Triple(it.epochMs, it.sequence, it.eventName) }
            .sortedWith(compareBy<ProbeEvent> { it.epochMs }.thenBy { it.sequence }).takeLast(24).map { event ->
                buildJsonObject {
                    put("at", event.epochMs); put("elapsed", event.elapsedRealtimeMs)
                    put("event", symbol(event.eventName)); put("severity", symbol(event.severity))
                    put("exceptionType", symbol(event.errorType ?: event.payload["exception"]))
                    // Free-text error messages can contain media paths, URLs and tokens. Export only an enum prefix.
                    put("errorCode", symbol(event.errorMessage?.takeWhile { it.isLetterOrDigit() || it == '_' }?.take(80)))
                    put("facts", buildJsonObject {
                        listOf("reason", "route", "segment", "switches").forEach { key ->
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
