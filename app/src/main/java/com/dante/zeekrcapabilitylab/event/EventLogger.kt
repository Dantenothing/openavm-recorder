package com.dante.zeekrcapabilitylab.event

import android.content.Context
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.data.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory event stream plus a bounded JSONL disk log.
 *
 * Disk contract (the camera-thread / flash-wear fix):
 *  - [logEvent] never performs disk I/O on the caller's thread. Events are
 *    queued (bounded; overflow drops the OLDEST queued events and writes an
 *    explicit EVENT_LOG_OVERFLOW_DROPPED marker) and appended in batches by one
 *    dedicated writer thread, so a slow flush can never stall the camera
 *    handler and bursts collapse into fewer, larger writes.
 *  - The default product stream is size-bounded by [EventLogRotationPolicy]:
 *    events/app.jsonl rotates at MAX_ACTIVE_FILE_BYTES and rotated files are
 *    retained newest-first within MAX_ROTATED_TOTAL_BYTES. The pre-rotation
 *    no-session.jsonl from earlier builds is adopted as a rotated file so
 *    retention ages it out.
 *  - An explicit lab session file ([startSessionFile]) bypasses rotation; lab
 *    sessions are short-lived and owned by the caller.
 *  - Any storage failure degrades to nothing: logging must never crash the app.
 *    The in-memory [events] stream is updated synchronously either way.
 */
object EventLogger {
    private const val MAX_MEMORY_EVENTS = 5000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var appContext: Context
    private val sequence = AtomicLong(0)

    /** Explicit lab session target; null routes to the rotated product stream. */
    private var sessionFile: File? = null
    private val fileLock = Any()
    private var legacyMigrated = false

    private val pendingLock = Any()
    private val pending = ArrayDeque<ProbeEvent>()
    private var pendingDropped = 0
    private var flushScheduled = false

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-log-writer").apply { isDaemon = true }
    }

    private val _events = MutableStateFlow<List<ProbeEvent>>(emptyList())
    val events: StateFlow<List<ProbeEvent>> = _events.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun startSessionFile(sessionId: String) {
        synchronized(fileLock) {
            sessionFile = File(appContext.filesDir, "events/$sessionId.jsonl").apply {
                parentFile?.mkdirs()
            }
        }
    }

    fun endSessionFile() {
        synchronized(fileLock) {
            sessionFile = null
        }
    }

    fun logEvent(
        category: String,
        eventName: String,
        severity: String = Severity.INFO,
        sourcePackage: String? = null,
        payload: Map<String, String> = emptyMap(),
        errorType: String? = null,
        errorMessage: String? = null,
    ) {
        val event = ProbeEvent(
            sequence = sequence.incrementAndGet(),
            sessionId = PRODUCT_SESSION_ID,
            epochMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            category = category,
            eventName = eventName,
            severity = severity,
            sourcePackage = sourcePackage,
            payload = payload,
            errorType = errorType,
            errorMessage = errorMessage,
        )
        enqueueForDisk(event)
        _events.value = (_events.value + event).takeLast(MAX_MEMORY_EVENTS)
        if (severity == Severity.ERROR || errorType != null) {
            _lastError.value = "${event.category}/${event.eventName}: ${errorMessage ?: event.errorMessage ?: "unknown error"}"
        }
    }

    fun markError(
        category: String,
        eventName: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        logEvent(
            category = category,
            eventName = eventName,
            severity = Severity.ERROR,
            errorType = throwable?.javaClass?.name,
            errorMessage = message,
        )
    }

    /**
     * Drains queued events to disk and waits up to [timeoutMs]. Best effort:
     * used by the crash handler so the final events usually survive process
     * death, and safe to call from any thread (a wedged writer only costs the
     * timeout, never a deadlock).
     */
    fun flushBlocking(timeoutMs: Long) {
        if (!::appContext.isInitialized) return
        val latch = CountDownLatch(1)
        try {
            writeExecutor.execute {
                flushPending()
                latch.countDown()
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            // Best effort only.
        }
    }

    fun readSessionEvents(sessionId: String): List<ProbeEvent> =
        readJsonl(File(appContext.filesDir, "events/$sessionId.jsonl"))

    fun readAllEvents(): List<ProbeEvent> {
        val dir = File(appContext.filesDir, "events")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.sortedBy { it.name }
            ?.flatMap { file -> readJsonl(file) }
            ?.sortedWith(compareBy<ProbeEvent> { it.epochMs }.thenBy { it.sequence })
            ?: emptyList()
    }

    private fun readJsonl(file: File): List<ProbeEvent> {
        if (!file.exists()) return emptyList()
        return try {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    // Per-line recovery: one torn line (e.g. a power cut during an
                    // append) must not discard the rest of the file.
                    if (line.isBlank()) {
                        null
                    } else {
                        runCatching { json.decodeFromString(ProbeEvent.serializer(), line) }.getOrNull()
                    }
                }.toList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun enqueueForDisk(event: ProbeEvent) {
        if (!::appContext.isInitialized) return
        synchronized(pendingLock) {
            while (pending.size >= EventLogRotationPolicy.MAX_PENDING_EVENTS) {
                pending.removeFirst()
                pendingDropped++
            }
            pending.addLast(event)
            if (!flushScheduled) {
                flushScheduled = true
                try {
                    writeExecutor.execute(::flushPending)
                } catch (t: RejectedExecutionException) {
                    flushScheduled = false
                }
            }
        }
    }

    /** Writer-thread only: drain the queue, then append one batch under [fileLock]. */
    private fun flushPending() {
        val batch: List<ProbeEvent>
        val dropped: Int
        synchronized(pendingLock) {
            batch = pending.toList()
            pending.clear()
            dropped = pendingDropped
            pendingDropped = 0
            flushScheduled = false
        }
        if (batch.isEmpty() && dropped == 0) return
        val text = try {
            buildString {
                if (dropped > 0) {
                    append(json.encodeToString(ProbeEvent.serializer(), overflowMarker(dropped)))
                    append('\n')
                }
                batch.forEach { event ->
                    append(json.encodeToString(ProbeEvent.serializer(), event))
                    append('\n')
                }
            }
        } catch (t: Throwable) {
            return
        }
        synchronized(fileLock) {
            try {
                targetFileLocked().appendText(text)
            } catch (t: Throwable) {
                // Storage failures must never crash the app; the memory stream keeps the events.
            }
        }
    }

    private fun overflowMarker(count: Int): ProbeEvent = ProbeEvent(
        sequence = sequence.incrementAndGet(),
        sessionId = PRODUCT_SESSION_ID,
        epochMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        category = Categories.SYSTEM,
        eventName = "EVENT_LOG_OVERFLOW_DROPPED",
        severity = Severity.WARN,
        payload = mapOf("count" to count.toString()),
    )

    /** Caller holds [fileLock]. */
    private fun targetFileLocked(): File {
        sessionFile?.let { return it }
        val dir = File(appContext.filesDir, "events").apply { mkdirs() }
        migrateLegacyLocked(dir)
        val active = File(dir, EventLogRotationPolicy.ACTIVE_FILE_NAME)
        if (active.exists() && EventLogRotationPolicy.shouldRotate(active.length())) {
            active.renameTo(File(dir, uniqueRotatedNameLocked(dir)))
            applyRetentionLocked(dir)
        }
        return File(dir, EventLogRotationPolicy.ACTIVE_FILE_NAME)
    }

    /** One-time adoption of the pre-rotation stream so retention can age it out. Caller holds [fileLock]. */
    private fun migrateLegacyLocked(dir: File) {
        if (legacyMigrated) return
        legacyMigrated = true
        val legacy = File(dir, EventLogRotationPolicy.LEGACY_FILE_NAME)
        if (!legacy.exists()) return
        val stamp = legacy.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        legacy.renameTo(File(dir, uniqueRotatedNameLocked(dir, stamp)))
        applyRetentionLocked(dir)
    }

    /** Caller holds [fileLock]. */
    private fun uniqueRotatedNameLocked(dir: File, epochMs: Long = System.currentTimeMillis()): String {
        var stamp = epochMs
        while (File(dir, EventLogRotationPolicy.rotatedName(stamp)).exists()) stamp++
        return EventLogRotationPolicy.rotatedName(stamp)
    }

    /** Caller holds [fileLock]. */
    private fun applyRetentionLocked(dir: File) {
        val rotated = dir.listFiles()
            ?.filter { EventLogRotationPolicy.isRotatedName(it.name) }
            ?.map { RotatedLogFile(name = it.name, bytes = it.length()) }
            .orEmpty()
        EventLogRotationPolicy.selectRotatedDeletions(rotated).forEach { name ->
            File(dir, name).delete()
        }
    }

    fun currentSessionIdOrNoSession(): String = PRODUCT_SESSION_ID

    fun debugContextAvailable(): Boolean = ::appContext.isInitialized

    private const val PRODUCT_SESSION_ID = "app"
}

object LogUtil {
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        EventLogger.logEvent(
            category = Categories.ERROR,
            eventName = tag,
            severity = Severity.ERROR,
            errorType = throwable?.javaClass?.name,
            errorMessage = message,
        )
    }
}
