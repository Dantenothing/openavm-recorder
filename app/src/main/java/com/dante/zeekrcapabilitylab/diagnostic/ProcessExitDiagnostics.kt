package com.dante.zeekrcapabilitylab.diagnostic

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.CrashRecord
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.event.EventLogRotationPolicy
import com.dante.zeekrcapabilitylab.event.EventLogger
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.*

/** On-demand, read-only evidence collection on IO. Never opens a camera or recording output. */
internal object ProcessExitDiagnostics {
    private val json = Json { ignoreUnknownKeys = true }
    private fun crashFile(context: Context) = AtomicFile(File(context.filesDir, "diagnostics/last-process-crash.json"))

    /** Called before the legacy crash.json is cleared during startup. A failed save keeps the original. */
    fun preserveCrash(context: Context, crash: CrashRecord): Boolean = runCatching {
        val file = crashFile(context)
        file.baseFile.parentFile?.mkdirs()
        val out = file.startWrite()
        try { out.write(ProcessExitReport.crash(crash).toString().toByteArray()); file.finishWrite(out) }
        catch (error: Throwable) { file.failWrite(out); throw error }
    }.isSuccess

    fun collect(context: Context): JsonObject {
        val history = recentHistory(File(context.filesDir, "events"))
        var systemStatus = "UNAVAILABLE_API_${Build.VERSION.SDK_INT}"
        val exits = if (Build.VERSION.SDK_INT >= 30) runCatching {
            val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))
            var nativeTraces = 0
            val rows = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8).map { exit ->
                buildJsonObject {
                    put("atEpochMs", exit.timestamp); put("pid", exit.pid)
                    put("process", ProcessExitReport.symbol(exit.processName))
                    put("reason", reasonName(exit.reason)); put("reasonNumber", exit.reason)
                    put("status", exit.status); put("importance", exit.importance)
                    put("pssKiB", exit.pss); put("rssKiB", exit.rss)
                    if (Build.VERSION.SDK_INT >= 31 && exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && nativeTraces++ < 2) {
                        put("nativeTrace", nativeTrace(exit))
                    }
                }
            }
            systemStatus = "AVAILABLE"
            JsonArray(rows)
        }.getOrElse {
            systemStatus = "QUERY_FAILED_${it.javaClass.simpleName}"
            JsonArray(emptyList())
        } else JsonArray(emptyList())
        val crash = runCatching { crashFile(context).openRead().use { input ->
            require(input.channel.size() in 1..8192)
            json.parseToJsonElement(input.bufferedReader().readText()).jsonObject
        } }.getOrNull()
        return buildJsonObject {
            put("currentProcessStartedAtEpochMs", ZeekrApp.processStartEpochMs)
            put("systemExitQuery", systemStatus)
            put("historicalExits", exits)
            put("lastJavaCrash", crash ?: JsonNull)
            put("persistedEvents", ProcessExitReport.events(history.events + EventLogger.events.value))
            put("historyBytesRead", history.bytes)
            put("historyReadErrors", history.errors)
            put("historyByteLimit", HISTORY_BYTES)
            put("rawTraceOrErrorMessagesIncluded", false)
        }
    }

    @androidx.annotation.RequiresApi(31)
    private fun nativeTrace(exit: ApplicationExitInfo): JsonObject = runCatching {
        exit.traceInputStream?.use { input ->
            // Native traces are protobuf on Android 12+, not text. Read at most the explicit budget plus one byte.
            val buffer = ByteArray(NativeCrashTrace.MAX_BYTES + 1)
            var used = 0
            while (used < buffer.size) {
                val count = input.read(buffer, used, buffer.size - used)
                if (count < 0) break
                check(count > 0) { "EMPTY_STREAM_READ" }
                used += count
            }
            NativeCrashTrace.decode(buffer.copyOf(used))
        } ?: buildJsonObject { put("status", "NOT_RETAINED_BY_SYSTEM") }
    }.getOrElse { buildJsonObject { put("status", "READ_FAILED"); put("errorType", ProcessExitReport.symbol(it.javaClass.name)) } }

    internal data class History(val events: List<ProbeEvent>, val bytes: Int, val errors: Int)
    internal fun recentHistory(directory: File, maxBytes: Int = HISTORY_BYTES): History {
        require(maxBytes in 1..HISTORY_BYTES)
        val files = directory.listFiles().orEmpty().filter {
            it.isFile && (it.name == EventLogRotationPolicy.ACTIVE_FILE_NAME || EventLogRotationPolicy.isRotatedName(it.name))
        }.sortedByDescending { it.lastModified() }.take(6)
        val events = ArrayDeque<ProbeEvent>()
        var bytesRead = 0; var errors = 0
        for (file in files) {
            if (bytesRead >= maxBytes) break
            runCatching {
                RandomAccessFile(file, "r").use { input ->
                    val size = minOf(input.length(), (maxBytes - bytesRead).toLong()).toInt()
                    val offset = input.length() - size
                    input.seek(offset)
                    val bytes = ByteArray(size)
                    input.readFully(bytes); bytesRead += size
                    var text = String(bytes, Charsets.UTF_8)
                    if (offset > 0) text = text.substringAfter('\n', "") // Skip a possibly torn first record.
                    text.lineSequence().filter { it.length in 1..16_384 }.forEach { line ->
                        val event = runCatching { json.decodeFromString(ProbeEvent.serializer(), line) }.getOrNull()
                        if (event != null && ProcessExitReport.relevant(event)) {
                            events.addLast(event)
                            if (events.size > 512) {
                                // Files are visited newest first: discard the oldest timestamp, not the newest file.
                                val oldest = events.minBy { it.epochMs }; events.remove(oldest)
                            }
                        }
                    }
                }
            }.onFailure { errors++ }
        }
        return History(events.toList(), bytesRead, errors)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"; 1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "JAVA_CRASH"
        5 -> "NATIVE_CRASH"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"; 14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }
    private const val HISTORY_BYTES = 2 * 1024 * 1024
}
