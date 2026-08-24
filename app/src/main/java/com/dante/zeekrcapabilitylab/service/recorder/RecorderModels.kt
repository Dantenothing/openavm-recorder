package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Explicit configuration for the foreground segment recorder. Validation is pure
 * Kotlin so the JVM tests cover every accepted/rejected combination.
 */
@Serializable
data class RecorderConfig(
    val source: SessionSourceSnapshot,
    val segmentSeconds: Int,
    val storageLimitBytes: Long,
    val minFreeBytes: Long = 20L * 1024L * 1024L * 1024L,
) {
    val cameraId: String get() = source.cameraId
    val profile: CameraFormatProfile get() = source.profile

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (cameraId.isBlank()) errors += "cameraId must not be blank"
        if (profile.size.width <= 0 || profile.size.height <= 0) {
            errors += "profile size must be positive"
        }
        if (profile.bitrateBps <= 0) errors += "bitrateBps must be positive"
        if (segmentSeconds !in SEGMENT_OPTIONS_SECONDS) {
            errors += "segmentSeconds must be one of ${SEGMENT_OPTIONS_SECONDS.sorted()}"
        }
        if (storageLimitBytes !in STORAGE_LIMIT_OPTIONS_BYTES) {
            errors += "storageLimitBytes must be one of ${STORAGE_LIMIT_OPTIONS_BYTES.sorted()}"
        }
        if (minFreeBytes !in MIN_FREE_OPTIONS_BYTES) {
            errors += "minFreeBytes must be one of ${MIN_FREE_OPTIONS_BYTES.sorted()}"
        }
        return errors
    }

    companion object {
        /** Lab options keep 10/30s; the V2 product UI offers 60/120/180s. */
        val SEGMENT_OPTIONS_SECONDS = setOf(10, 30, 60, 120, 180)
        val STORAGE_LIMIT_OPTIONS_BYTES = setOf(5L, 10L, 15L, 30L)
            .map { it * 1024L * 1024L * 1024L }
            .toSet()
        val MIN_FREE_OPTIONS_BYTES = setOf(10L, 20L, 30L)
            .map { it * 1024L * 1024L * 1024L }
            .toSet()

        /** Exact-match check against the HAL-declared MediaRecorder output sizes. */
        fun profileDeclared(profile: CameraFormatProfile, declaredSizes: Collection<ProfileSize>): Boolean =
            CameraProfileCatalog.resolveExactOrNull(declaredSizes, profile) != null
    }
}

object RecorderStatus {
    const val IDLE = "IDLE"
    const val STARTING = "STARTING"
    const val RECORDING = "RECORDING"
    const val FINALIZING = "FINALIZING"
    const val STOPPED = "STOPPED"
    const val CAMERA_UNAVAILABLE = "CAMERA_UNAVAILABLE"
    const val ERROR = "ERROR"
}

/** UI-visible service state. Updated from the camera handler thread. */
data class RecorderState(
    val status: String = RecorderStatus.IDLE,
    val cameraId: String? = null,
    val profile: CameraFormatProfile? = null,
    val sourceRole: RecordingSourceRole? = null,
    val layoutKind: RecordingLayoutKind? = null,
    val segmentSeconds: Int = 60,
    val storageLimitBytes: Long = 15L * 1024L * 1024L * 1024L,
    val segmentNumber: Int = 0,
    val currentFile: String? = null,
    val segmentStartedAtEpochMs: Long? = null,
    val lastError: String? = null,
    val lastSidecarPath: String? = null,
    /** Increments only when a completed successful segment becomes library-visible. */
    val libraryRevision: Long = 0L,
    val message: String? = null,
    val previewRequested: Boolean = false,
    val previewActive: Boolean = false,
    val previewFallbackUsed: Boolean = false,
    /** PARTIAL_WAKE_LOCK held while segments are actively recording. */
    val wakeLockHeld: Boolean = false,
)

/**
 * Pure-Kotlin command/state policy shared by the UI and the service.
 * Deciding here keeps duplicate START/STOP races out of the camera thread.
 */
object RecorderCommandPolicy {

    val ACTIVE_SERVICE_STATUSES = setOf(
        RecorderStatus.STARTING,
        RecorderStatus.RECORDING,
        RecorderStatus.FINALIZING,
        RecorderStatus.CAMERA_UNAVAILABLE,
        RecorderStatus.ERROR,
    )

    fun isActive(status: String): Boolean = status in ACTIVE_SERVICE_STATUSES

    fun canStart(status: String, serviceRunning: Boolean): Boolean =
        !serviceRunning || status in setOf(RecorderStatus.IDLE, RecorderStatus.STOPPED)

    fun canStop(status: String, serviceRunning: Boolean): Boolean =
        serviceRunning && status != RecorderStatus.IDLE

    /** Camera/OEM takeover ends this session; a later recording requires a fresh manual Start. */
    fun canRetry(status: String, serviceRunning: Boolean): Boolean = false

    fun canBookmark(serviceRunning: Boolean): Boolean = serviceRunning
}

/**
 * A timeout may only finalize the segment it was scheduled for. The token is the
 * segment generation captured when the timeout was posted.
 */
object RecorderTimeoutPolicy {
    fun ownsSegment(
        token: Long,
        currentGeneration: Long,
        recording: Boolean,
        hasCurrentPartial: Boolean,
    ): Boolean = token == currentGeneration && recording && hasCurrentPartial
}

/**
 * Teardown ordering seam (pure logic): the IO executor may only be shut down
 * after the camera-thread teardown has finished AND its finalize task has been
 * submitted, otherwise the last sidecar would be dropped.
 */
object RecorderLifecycleOrder {
    fun canShutdownIo(finalizeCommitted: Boolean, teardownComplete: Boolean): Boolean =
        finalizeCommitted && teardownComplete
}

/** Segment gaps must be measured on the same monotonic clock the service uses. */
object SegmentGapPolicy {
    fun gapMs(previousStoppedElapsedMs: Long?, currentStartedElapsedMs: Long?): Long? =
        if (previousStoppedElapsedMs != null && currentStartedElapsedMs != null) {
            currentStartedElapsedMs - previousStoppedElapsedMs
        } else {
            null
        }
}

/**
 * Classifies a segment finalize outcome so failure evidence is never empty:
 * stop may succeed while the partial is missing/empty or the rename fails.
 */
data class FinalizeOutcome(val success: Boolean, val error: String?)

object FinalizePolicy {
    const val ERROR_MISSING = "FINALIZE_PARTIAL_MISSING"
    const val ERROR_EMPTY = "FINALIZE_PARTIAL_EMPTY"
    const val ERROR_RENAME = "FINALIZE_RENAME_FAILED"

    fun outcome(
        stopError: String?,
        partialExists: Boolean,
        partialBytes: Long,
        renameSucceeded: Boolean,
    ): FinalizeOutcome {
        if (stopError != null) return FinalizeOutcome(success = false, error = stopError)
        if (!partialExists) return FinalizeOutcome(success = false, error = ERROR_MISSING)
        if (partialBytes <= 0L) return FinalizeOutcome(success = false, error = ERROR_EMPTY)
        if (!renameSucceeded) return FinalizeOutcome(success = false, error = ERROR_RENAME)
        return FinalizeOutcome(success = true, error = null)
    }
}

/**
 * Transition decision used by finalize: MediaRecorder.stop() may only be invoked
 * for a segment whose recorder actually started (recording==true). PREPARING
 * segments must skip stop and go straight to reset/release + quarantine.
 */
object RecorderTransitionPolicy {
    fun shouldInvokeStop(wasRecording: Boolean): Boolean = wasRecording
}

/**
 * Single lock serializing in-process storage metadata read/select/delete with
 * upload-pin writes, so "selected for eviction then protected then deleted"
 * cannot happen within this process.
 */
object RecorderStorageLock {
    val lock: Any = Any()
}

/** Pure segment guard decisions shared by the session and its tests. */
object SegmentGuardPolicy {
    fun shouldFinalizeOnLoss(hasCurrentPartial: Boolean): Boolean = hasCurrentPartial

    fun ownsSetup(
        generation: Long,
        currentGeneration: Long,
        currentPartial: File?,
        partial: File,
    ): Boolean = generation == currentGeneration && currentPartial === partial
}

/** A new session uses the UI preview only while it both exists and is still desired. */
object SegmentPreviewPolicy {
    fun includeInNewSession(previewConfigured: Boolean, previewDesired: Boolean): Boolean =
        previewConfigured && previewDesired
}

/** Guards an in-flight preview swap from ever taking ownership of a newer encoder segment. */
object ActivePreviewReplacementPolicy {
    fun canRebuild(
        replacementValid: Boolean,
        recording: Boolean,
        cameraReady: Boolean,
        encoderReady: Boolean,
    ): Boolean = replacementValid && recording && cameraReady && encoderReady

    fun shouldQueueForNextSegment(status: String, stopping: Boolean, releasing: Boolean): Boolean =
        !stopping && !releasing && status in setOf(RecorderStatus.STARTING, RecorderStatus.FINALIZING)

    fun ownsCallback(
        token: Long,
        currentToken: Long,
        segmentGeneration: Long,
        currentSegmentGeneration: Long,
        recording: Boolean,
        encoderMatches: Boolean,
    ): Boolean = token == currentToken &&
        segmentGeneration == currentSegmentGeneration &&
        recording && encoderMatches
}

/** The gallery must never publish provisional or failed recording evidence. */
object LibraryPublicationPolicy {
    fun shouldPublish(result: String, provisional: Boolean): Boolean =
        result == SegmentSidecar.RESULT_SUCCESS && !provisional
}

/** Bookmark protection must survive sidecar enrichment: merge existing + snapshot flags. */
object SidecarProtectionPolicy {
    fun effectiveProtected(existingProtected: Boolean?, snapshotProtected: Boolean): Boolean =
        (existingProtected == true) || snapshotProtected
}

enum class WakeLockAction { ACQUIRE, RELEASE, NONE }

/**
 * Pure PARTIAL_WAKE_LOCK policy: hold only while recording is actively
 * STARTING/RECORDING/FINALIZING; release for every inactive state; never
 * double-acquire; renew before the platform timeout silently drops the lock.
 */
object RecorderWakeLockPolicy {
    const val TIMEOUT_MS = 15 * 60 * 1000L
    const val RENEW_MARGIN_MS = 30 * 1000L

    val HOLDING_STATUSES = setOf(
        RecorderStatus.STARTING,
        RecorderStatus.RECORDING,
        RecorderStatus.FINALIZING,
    )

    fun shouldHold(status: String): Boolean = status in HOLDING_STATUSES

    fun decide(held: Boolean, status: String): WakeLockAction = when {
        !held && shouldHold(status) -> WakeLockAction.ACQUIRE
        held && !shouldHold(status) -> WakeLockAction.RELEASE
        else -> WakeLockAction.NONE
    }

    fun shouldRenew(heldForMs: Long): Boolean = heldForMs >= TIMEOUT_MS - RENEW_MARGIN_MS
}

/**
 * Pure watchdog policy: a camera open or capture-session setup that the HAL never
 * completes must fire (and leave explicit evidence) well before the wake-lock
 * timeout, and a stale watchdog must never mutate newer state.
 */
object WatchdogPolicy {
    const val OPEN_TIMEOUT_MS = 12_000L
    const val SETUP_TIMEOUT_MS = 12_000L

    fun shouldFire(elapsedMs: Long, timeoutMs: Long): Boolean = elapsedMs >= timeoutMs

    fun isCurrent(token: Long, currentToken: Long): Boolean = token == currentToken
}
