package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context

/** Metadata shared by all one-minute files protected by one Save Clip action. */
data class IncidentTag(
    val eventId: String,
    val requestedAtEpochMs: Long,
    val role: String,
)

/**
 * Small persistent hand-off for the one segment that must be protected after
 * the user presses Save Clip. Persistence matters when the recorder service is
 * recreated between the current and following one-minute segment.
 */
class IncidentProtectionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun pending(nowEpochMs: Long = System.currentTimeMillis()): IncidentTag? {
        val eventId = preferences.getString(KEY_EVENT_ID, null) ?: return null
        val requestedAt = preferences.getLong(KEY_REQUESTED_AT, 0L)
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        if (requestedAt <= 0L || expiresAt <= nowEpochMs) {
            clear()
            return null
        }
        return IncidentTag(eventId, requestedAt, ROLE_NEXT)
    }

    @Synchronized
    fun saveNext(eventId: String, requestedAtEpochMs: Long) {
        preferences.edit()
            .putString(KEY_EVENT_ID, eventId)
            .putLong(KEY_REQUESTED_AT, requestedAtEpochMs)
            .putLong(KEY_EXPIRES_AT, requestedAtEpochMs + PENDING_EXPIRY_MS)
            .apply()
    }

    @Synchronized
    fun consume(eventId: String) {
        if (preferences.getString(KEY_EVENT_ID, null) == eventId) clear()
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        const val ROLE_PREVIOUS = "PREVIOUS"
        const val ROLE_CURRENT = "CURRENT"
        const val ROLE_NEXT = "NEXT"
        const val PENDING_EXPIRY_MS = 10 * 60_000L

        private const val PREFS_NAME = "recorder_incident_protection"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_REQUESTED_AT = "requested_at"
        private const val KEY_EXPIRES_AT = "expires_at"

        fun eventId(requestedAtEpochMs: Long): String = "event-$requestedAtEpochMs"
    }
}
