package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize

enum class MirrorDestination { NONE, HOME, OVERLAY }

data class MirrorCameraKey(val sessionId: String, val cameraGeneration: Long)

data class MirrorEvidence(val active: Boolean = false, val destination: String = "NONE",
    val reason: String = "OFF", val frames: Long = 0, val lastFrameAgeMs: Long? = null,
    val rearLane: Int = 0, val rotation: Int = 0, val mirrored: Boolean = false,
    val requestedWidth: Int? = null, val requestedHeight: Int? = null,
    val bufferWidth: Int? = null, val bufferHeight: Int? = null,
    val viewWidth: Int? = null, val viewHeight: Int? = null,
    val lensMode: String? = null, val zoom: Float = 1f, val cameraGeneration: Long = 0,
    val textureCallbacks: Long = 0, val duplicateTimestampCallbacks: Long = 0,
    val invalidTimestampCallbacks: Long = 0, val lastTextureCallbackAgeMs: Long? = null,
    val lastTextureTimestampNs: Long? = null, val drawPasses: Long = 0, val lastDrawAgeMs: Long? = null,
    val textureAttached: Boolean? = null, val textureAvailable: Boolean? = null,
    val textureShown: Boolean? = null, val hardwareAccelerated: Boolean? = null,
    val redrawAttempts: Int = 0, val redrawFramesResumed: Int = 0,
    val displayRecoveryAttempts: Int = 0, val gl: MirrorGlEvidence = MirrorGlEvidence())

/** No camera opens, background starts or retries are authorized by presentation state. */
object MirrorPreviewPolicy {
    fun cameraKey(state: RecorderState): MirrorCameraKey? =
        state.recordingSessionId?.takeIf { state.mirrorPreviewManaged && state.cameraGeneration > 0 &&
            state.status in setOf(RecorderStatus.STARTING, RecorderStatus.RECORDING, RecorderStatus.FINALIZING) }
            ?.let { MirrorCameraKey(it, state.cameraGeneration) }

    fun commandMatches(expectedSession: String?, expectedCamera: Long?, state: RecorderState): Boolean =
        (expectedSession == null || expectedSession == state.recordingSessionId) &&
            (expectedCamera == null || expectedCamera == state.cameraGeneration)

    /** Splitting a 640x480 preview into four lanes discards most of the lane detail. */
    fun previewSize(declared: Collection<ProfileSize>, recordingSize: ProfileSize): ProfileSize? =
        declared.firstOrNull { it == recordingSize && it.width > 0 && it.height > 0 }

    private fun surround(config: RecorderConfig): Boolean =
        config.source.sourceRole == RecordingSourceRole.SURROUND &&
            config.source.layoutKind == RecordingLayoutKind.FOUR_LANE_V1

    fun supports(config: RecorderConfig): Boolean = surround(config) ||
        config.source.sourceRole == RecordingSourceRole.CABIN && config.source.layoutKind == RecordingLayoutKind.SINGLE_V1

    fun supportsPreview(config: RecorderConfig): Boolean = supports(config)

    fun destination(active: Boolean, foreground: Boolean, homeAttached: Boolean,
                    permission: Boolean, dismissed: Boolean, displayUsable: Boolean = true): MirrorDestination = when {
        !active || !displayUsable -> MirrorDestination.NONE
        foreground && homeAttached -> MirrorDestination.HOME
        !foreground && permission && !dismissed -> MirrorDestination.OVERLAY
        else -> MirrorDestination.NONE
    }
}

/** A UI update is not a new camera frame. Re-showing a window needs a new timestamp. */
class MirrorFrameFreshness(private val maximumAgeMs: Long = 2_000, private val stallTimeoutMs: Long = 8_000) {
    var callbacks: Long = 0; private set
    var duplicateTimestampCallbacks: Long = 0; private set
    var invalidTimestampCallbacks: Long = 0; private set
    var lastTextureTimestampNs: Long? = null; private set
    private var lastCallbackAt: Long? = null
    private var timestamp: Long? = null
    private var lastFrameAt: Long? = null
    private var presented = false
    var frames: Long = 0; private set
    private var presentationAt = 0L
    private var recordingSegment: Int? = null
    private var recording = false
    fun presentationChanged(nowMs: Long = 0) { presented = false; presentationAt = nowMs }
    /** Segment identity survives a FINALIZING state too brief for the UI tick to see. */
    fun recorderProgress(segment: Int, active: Boolean, nowMs: Long, continuousInput: Boolean = false) {
        if (active && (!recording || !continuousInput && recordingSegment != segment)) presentationChanged(nowMs)
        recordingSegment = segment
        recording = active
    }
    fun frame(timestampNs: Long, nowMs: Long) {
        callbacks++
        lastCallbackAt = nowMs
        lastTextureTimestampNs = timestampNs
        if (timestampNs <= 0) { invalidTimestampCallbacks++; return }
        if (timestamp == timestampNs) { duplicateTimestampCallbacks++; return }
        timestamp = timestampNs
        lastFrameAt = nowMs
        presented = true
        frames++
    }
    fun age(nowMs: Long): Long? = lastFrameAt?.takeIf { it <= nowMs }?.let { nowMs - it }
    fun callbackAge(nowMs: Long): Long? = lastCallbackAt?.takeIf { it <= nowMs }?.let { nowMs - it }
    fun isFresh(nowMs: Long): Boolean = presented && age(nowMs)?.let { it <= maximumAgeMs } == true
    fun outputTimedOut(nowMs: Long): Boolean = if (presented) {
        age(nowMs)?.let { it > stallTimeoutMs } == true
    } else nowMs >= presentationAt && nowMs - presentationAt > stallTimeoutMs
}

/** Main-thread state: view removal alone can never destroy a producer-owned texture. */
class RetainedPreviewLease(private val releaseTexture: () -> Unit) {
    private var attached = false
    private var producer = false
    private var retired = false
    var released = false; private set
    fun attach() { check(!retired && !released); attached = true }
    fun acquireProducer() { check(!retired && !released && !producer); producer = true }
    fun detach() { attached = false; maybeRelease() }
    fun producerReleased() { producer = false; maybeRelease() }
    fun retire() { retired = true; maybeRelease() }
    private fun maybeRelease() {
        if (retired && !attached && !producer && !released) { released = true; releaseTexture() }
    }
}

/** Geometry after lane cropping. Rotation/mirroring are display-only; preserve source aspect. */
object MirrorGeometry {
    fun corners(width: Float, height: Float, sourceAspect: Float, rotation: Int, mirrored: Boolean): FloatArray {
        require(width > 0 && height > 0 && sourceAspect > 0 && sourceAspect.isFinite())
        require(rotation in setOf(0, 90, 180, 270))
        val aspect = if (rotation % 180 == 0) sourceAspect else 1f / sourceAspect
        val w = minOf(width, height * aspect)
        val h = w / aspect
        val left = (width - w) / 2; val top = (height - h) / 2
        return listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).flatMap { (u, v) ->
            val (x, y) = when (rotation) { 90 -> 1f - v to u; 180 -> 1f - u to 1f - v; 270 -> v to 1f - u; else -> u to v }
            listOf(left + (if (mirrored) 1f - x else x) * w, top + y * h)
        }.toFloatArray()
    }
}
