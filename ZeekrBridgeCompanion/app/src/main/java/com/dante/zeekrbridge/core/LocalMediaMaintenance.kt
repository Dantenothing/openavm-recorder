package com.dante.zeekrbridge.core

import android.content.Context
import kotlin.concurrent.thread

object LocalMediaCleanupPolicy {
    fun expiredUnprotected(
        segments: Collection<IndexedMediaSegment>,
        cutoffEpochMs: Long,
    ): List<IndexedMediaSegment> = segments.filter { segment ->
        val timestamp = segment.stoppedAtEpochMs
            ?: segment.startedAtEpochMs.takeIf { it > 0L }
            ?: segment.file.lastModified().takeIf { it > 0L }
            ?: Long.MAX_VALUE
        !segment.protected && timestamp < cutoffEpochMs
    }
}

object LocalMediaMaintenance {
    private const val PREFS = "phone_product_settings"
    private const val KEY_ENABLED = "auto_cleanup_local"
    private const val KEY_LAST_RUN = "auto_cleanup_last_run"
    private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    private const val MIN_RUN_INTERVAL_MS = 20L * 60L * 60L * 1_000L

    fun schedule(context: Context, nowEpochMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        if (nowEpochMs - prefs.getLong(KEY_LAST_RUN, 0L) < MIN_RUN_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_RUN, nowEpochMs).apply()
        thread(name = "openavm-local-cleanup", isDaemon = true) {
            runCatching {
                val snapshot = MediaIndexScanner.scan(ReceivedFiles.visibleFiles(ReceivedStore.receivedDir()))
                val expired = LocalMediaCleanupPolicy.expiredUnprotected(
                    snapshot.segments,
                    nowEpochMs - RETENTION_MS,
                )
                if (expired.isNotEmpty()) {
                    TrashStore.moveToTrash(expired.map { it.file })
                    MediaIndexStore.invalidate()
                }
            }.onFailure { ServerLog.log("LOCAL_MEDIA_CLEANUP_FAILED ${it.message}") }
        }
    }
}
