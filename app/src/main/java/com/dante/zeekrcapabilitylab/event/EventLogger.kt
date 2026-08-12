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
import java.util.concurrent.atomic.AtomicLong

object EventLogger {
    private const val MAX_MEMORY_EVENTS = 5000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var appContext: Context
    private val sequence = AtomicLong(0)
    private var activeFile: File? = null
    private val fileLock = Any()

    private val _events = MutableStateFlow<List<ProbeEvent>>(emptyList())
    val events: StateFlow<List<ProbeEvent>> = _events.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun startSessionFile(sessionId: String) {
        synchronized(fileLock) {
            activeFile = File(appContext.filesDir, "events/$sessionId.jsonl").apply {
                parentFile?.mkdirs()
            }
        }
    }

    fun endSessionFile() {
        synchronized(fileLock) {
            activeFile = null
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
        appendToFile(event)
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
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) null else json.decodeFromString(ProbeEvent.serializer(), line)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun appendToFile(event: ProbeEvent) {
        val file = synchronized(fileLock) {
            activeFile ?: File(appContext.filesDir, "events/no-session.jsonl").apply {
                parentFile?.mkdirs()
            }
        }
        synchronized(fileLock) {
            try {
                file.appendText(json.encodeToString(ProbeEvent.serializer(), event) + "\n")
            } catch (t: Throwable) {
                // Storage failures must never crash the probe.
            }
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
