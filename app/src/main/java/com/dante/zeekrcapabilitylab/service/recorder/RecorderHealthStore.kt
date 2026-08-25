package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context

/** Small durable UI hand-off; it never causes recording to restart. */
object RecorderHealthStore {
    private const val PREFS = "recorder_terminal_health_v1"
    private const val KEY_STATUS = "status"
    private const val KEY_ERROR = "error"
    private const val KEY_MESSAGE = "message"
    private const val KEY_MODE = "mode"
    private const val KEY_UPDATED = "updated"

    fun save(context: Context, state: RecorderState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATUS, state.status)
            .putString(KEY_ERROR, state.lastError)
            .putString(KEY_MESSAGE, state.message)
            .putString(KEY_MODE, state.recordingMode?.name)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): RecorderState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedStatus = prefs.getString(KEY_STATUS, null) ?: return RecorderState()
        val terminalStatus = if (storedStatus in setOf(
                RecorderStatus.ERROR,
                RecorderStatus.STORAGE_BLOCKED,
                RecorderStatus.CAMERA_UNAVAILABLE,
                RecorderStatus.STOPPED,
            )
        ) {
            storedStatus
        } else {
            RecorderStatus.STOPPED
        }
        val mode = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
        return RecorderState(
            status = terminalStatus,
            lastError = prefs.getString(KEY_ERROR, null),
            message = prefs.getString(KEY_MESSAGE, null),
            recordingMode = mode,
        )
    }
}
