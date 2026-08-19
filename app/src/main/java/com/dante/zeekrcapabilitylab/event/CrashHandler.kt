package com.dante.zeekrcapabilitylab.event

import android.content.Context
import com.dante.zeekrcapabilitylab.data.CrashRecord
import kotlinx.serialization.json.Json
import java.io.File

object CrashHandler {
    private const val FILE_NAME = "crash.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val record = CrashRecord(
                    epochMs = System.currentTimeMillis(),
                    thread = thread.name,
                    exceptionType = throwable.javaClass.name,
                    message = throwable.message,
                    stackTrace = throwable.stackTraceToString(),
                )
                write(context, record)
                EventLogger.markError(
                    com.dante.zeekrcapabilitylab.data.Categories.ERROR,
                    "UNCAUGHT_EXCEPTION",
                    throwable.message ?: throwable.javaClass.name,
                    throwable,
                )
                // Event writes are asynchronous; give the writer a bounded
                // chance to persist the final crash evidence.
                EventLogger.flushBlocking(500L)
            } catch (t: Throwable) {
                // Never interfere with the crash flow.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun readLast(context: Context): CrashRecord? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(CrashRecord.serializer(), file.readText())
        } catch (t: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }

    private fun write(context: Context, record: CrashRecord) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json.encodeToString(CrashRecord.serializer(), record))
        } catch (t: Throwable) {
            // Best effort.
        }
    }
}
