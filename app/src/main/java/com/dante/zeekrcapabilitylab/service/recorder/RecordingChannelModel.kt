package com.dante.zeekrcapabilitylab.service.recorder

import kotlinx.serialization.Serializable

object RecordingChannelId {
    const val SURROUND = "SURROUND"
    const val FRONT_CABIN = "FRONT_CABIN"
    const val REAR_CABIN = "REAR_CABIN"
    val all = setOf(SURROUND, FRONT_CABIN, REAR_CABIN)
    fun forRole(role: RecordingSourceRole): String = when (role) {
        RecordingSourceRole.SURROUND -> SURROUND
        RecordingSourceRole.CABIN -> FRONT_CABIN
        RecordingSourceRole.IR -> REAR_CABIN
    }
}

/** Shared identity is passed in memory by the authoritative recorder, never a persisted start permit. */
data class RecordingChannelIdentity(val sessionId: String, val sessionStartedElapsedMs: Long, val channelId: String) {
    init {
        require(sessionId.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")))
        require(sessionStartedElapsedMs >= 0 && channelId in RecordingChannelId.all)
    }
}

@Serializable
data class RecordingChannelHealth(
    val recordingSessionId: String,
    val channelId: String,
    val status: String = "STARTING",
    val capturedFrames: Long = 0,
    val encodedFrames: Long = 0,
    val writtenFrames: Long = 0,
    val completedFiles: Long = 0,
    val lastFrameElapsedMs: Long? = null,
    val lastWriteElapsedMs: Long? = null,
    val cleanupPending: Boolean = false,
    val cleanupUnconfirmed: Boolean = false,
    val errorCode: String? = null,
    val storageKind: String = "INTERNAL",
)

object RecordingChannelPolicy {
    const val WIDTH = 640
    const val HEIGHT = 480
    const val FPS = 15
    const val BITRATE = 2_000_000
    const val QUEUE_BYTES = 4L * 1024 * 1024
    const val QUEUE_ITEMS = 90
    const val START_TIMEOUT_MS = 20_000L
    const val STALE_FRAME_MS = 5_000L

    fun configuration(primary: RecorderConfig, source: SessionSourceSnapshot,
                      profile: com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile,
                      primaryStorage: RecordingStorageKind): RecorderConfig =
        primary.copy(source = source.copy(profile = profile, layoutKind = RecordingLayoutKind.SINGLE_V1, laneLayout = null),
            requestedFrameRate = FPS, strictFrameRate = false,
            sharedInputRecordingEnabled = false, mirrorPreviewEnabled = false, auxiliaryChannels = emptyList(),
            storagePreference = if (primaryStorage == RecordingStorageKind.USB_MEDIASTORE)
                RecordingStoragePreference.USB_PREFERRED else RecordingStoragePreference.INTERNAL_ONLY)

    fun roles(config: RecorderConfig): List<RecordingSourceRole> = if (
        config.recordingMode == RecordingMode.NORMAL && config.source.sourceRole == RecordingSourceRole.SURROUND
    ) config.auxiliaryChannels.filter { it != RecordingSourceRole.SURROUND }.distinct().take(2) else emptyList()

    fun health(status: String, captured: Long, written: Long, lastFrame: Long?, now: Long, error: String?): String = when {
        status == RecorderStatus.FINALIZING -> "STOPPING"
        error != null || status in setOf(RecorderStatus.ERROR, RecorderStatus.CAMERA_UNAVAILABLE) -> if (captured > 0 || written > 0) "INTERRUPTED" else "UNAVAILABLE"
        status in setOf(RecorderStatus.STOPPED, RecorderStatus.IDLE) -> "STOPPED"
        status == RecorderStatus.WAITING_CAMERA || lastFrame != null && (lastFrame > now || now - lastFrame > STALE_FRAME_MS) -> "INTERRUPTED"
        written > 0 -> "WRITING"
        captured > 0 -> "RECEIVING"
        else -> "STARTING"
    }

    /** A capture result/encoder start is not proof of an MP4 write. */
    fun update(previous: RecordingChannelHealth, value: RecorderState, evidence: CaptureEvidence?, now: Long): RecordingChannelHealth {
        val encoder = evidence?.encoder
        val captured = evidence?.frames?.count ?: previous.capturedFrames
        val written = encoder?.writtenFrames ?: previous.writtenFrames
        val last = evidence?.lastReceivedElapsedMs ?: previous.lastFrameElapsedMs
        val writeAt = encoder?.lastWriteElapsedMs ?: previous.lastWriteElapsedMs
        val phase = health(value.status, captured, written, last, now, value.lastError)
        val currentPhase = if (phase == "WRITING" && writeAt != null && (writeAt > now || now - writeAt > STALE_FRAME_MS))
            "INTERRUPTED" else phase
        return previous.copy(status = currentPhase,
            capturedFrames = captured, encodedFrames = encoder?.frames ?: previous.encodedFrames,
            writtenFrames = written, completedFiles = maxOf(previous.completedFiles, encoder?.completedFiles ?: 0),
            lastFrameElapsedMs = last, lastWriteElapsedMs = writeAt,
            cleanupPending = value.cleanupPending, cleanupUnconfirmed = value.cleanupUnconfirmed,
            errorCode = value.lastError?.substringBefore(':')?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }
                ?: value.lastError?.let { "RECORDER_ERROR" }, storageKind = value.activeStorageKind.name)
    }
}

/** A timed-out native owner keeps its slot. Only acknowledgement from that exact owner frees it. */
internal class RecordingChannelAdmission(private val maximum: Int = 2) {
    private val owners = linkedMapOf<String, Any>()
    init { require(maximum in 1..2) }
    @Synchronized fun admit(cameraId: String, token: Any): Boolean {
        if (cameraId.isBlank() || owners.size >= maximum || cameraId in owners) return false
        owners[cameraId] = token
        return true
    }
    @Synchronized fun release(cameraId: String, token: Any, confirmed: Boolean): Boolean {
        if (!confirmed || owners[cameraId] !== token) return false
        owners.remove(cameraId)
        return true
    }
    @Synchronized fun count(): Int = owners.size
}

/** Presentation keeps cached counters after stop, not the Service-owning RecorderSession. */
internal object RecordingChannelRetention {
    fun retainPrimary(revoked: Boolean, status: String, cleanupPending: Boolean, cleanupUnconfirmed: Boolean,
                      settleUntilMs: Long, nowMs: Long): Boolean {
        if (status in setOf(RecorderStatus.STOPPED, RecorderStatus.IDLE) && !cleanupPending && !cleanupUnconfirmed) return false
        return !revoked || nowMs < settleUntilMs
    }

    // An unconfirmed native owner remains fenced, but only its eventual callbacks
    // may advance it. Repeatedly sampling the same cached state has no benefit.
    fun pollAuxiliary(closing: Boolean, cleanupUnconfirmed: Boolean): Boolean = !closing || !cleanupUnconfirmed
}
