package com.dante.zeekrcapabilitylab.service.recorder

import java.util.UUID

/** Owns the stable product identity for one manually started recording Session. */
internal class RecordingSessionIdentity(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    var currentId: String? = null
        private set

    fun beginNewSession(): String {
        val id = idFactory().trim()
        require(id.isNotEmpty()) { "recordingSessionId must not be blank" }
        currentId = id
        return id
    }

    fun requireCurrentId(): String = checkNotNull(currentId) {
        "No active manually started recording Session"
    }

    fun endSession() {
        currentId = null
    }
}
