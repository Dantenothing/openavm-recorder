package com.dante.zeekrcapabilitylab.product

/**
 * Timing constants for the product live-preview coordinator.
 *
 * The 1s debounce exists to absorb camera-availability jitter (OEM 360 /
 * reverse-view handovers) and to keep the preview from starting during the
 * first frames of the page. The open watchdog is 6-8s: short enough that a
 * wedged HAL leaves an explicit TimedOut state instead of an infinite "正在打开",
 * long enough for a slow but healthy HAL to finish.
 */
object PreviewTimingPolicy {
    const val DEBOUNCE_MS = 1_000L
    const val OPEN_TIMEOUT_MS = 7_000L
    const val MAX_OPEN_TIMEOUT_MS = 8_000L
}

enum class PreviewPhase {
    IDLE,
    WAITING_FOR_LIFECYCLE,
    WAITING_FOR_AVAILABILITY,
    DEBOUNCING,
    ENUMERATING,
    OPENING,
    PREVIEWING,
    CLOSING,
    UNAVAILABLE,
    TIMED_OUT,
}

data class PreviewUiState(
    val phase: PreviewPhase = PreviewPhase.IDLE,
    val reason: String? = null,
    val generation: Long = 0,
)

/**
 * Pure start gate shared by the state machine and the UI. The first frame must
 * never wait for Camera2: every gate (lifecycle, page visibility, recorder
 * suppression, HAL availability, permission) must be open before the preview is
 * even considered, and enumeration happens only after the debounce elapses.
 */
object PreviewStartPolicy {
    fun shouldAttempt(
        lifecycleResumed: Boolean,
        pageVisible: Boolean,
        recordingActive: Boolean,
        cameraAvailable: Boolean,
        permissionGranted: Boolean,
    ): Boolean = lifecycleResumed &&
        pageVisible &&
        !recordingActive &&
        cameraAvailable &&
        permissionGranted
}

/**
 * The recorder product config (camera enumeration + declared HAL sizes) is
 * strictly a user-triggered background action. It is never computed during
 * Compose composition or app startup, and it carries its own timeout/error
 * state so a wedged HAL can only affect the Start button, never the first frame.
 */
object RecordStartPolicy {
    const val TRIGGER_USER_START = "USER_START"
    const val TRIGGER_COMPOSITION = "COMPOSITION"
    const val TRIGGER_STARTUP = "STARTUP"
    const val CONFIG_TIMEOUT_MS = 8_000L

    fun shouldComputeConfig(trigger: String): Boolean = trigger == TRIGGER_USER_START
}

/**
 * Pure, JVM-testable preview coordinator state machine.
 *
 * The machine owns only decisions and state transitions. The Android adapter
 * ([LivePreviewController]) performs the actual Camera2 work on background
 * threads and feeds outcomes back through the event methods below.
 *
 * Safety properties enforced here:
 * - No enumeration before RESUMED + visible + debounce.
 * - Every attempt allocates a generation token; stale callbacks are rejected
 *   and their camera/session is closed without touching newer state.
 * - STOP, page hide, recording start and availability loss cancel pending work
 *   and release the preview asynchronously.
 * - While recording, no second preview attempt is possible.
 * - Availability jitter can never produce parallel opens or high-frequency
 *   retries: every attempt is serialized through the debounce and in-flight
 *   open states are not torn down by transient availability flicker.
 */
class PreviewStateMachine(
    private val listener: Listener,
) {
    interface Listener {
        fun onStateChanged(state: PreviewUiState)
        fun onEnumerateRequested()
        fun onOpenRequested()
        fun onCloseRequested()
        fun onLateCameraClosed()
    }

    private var phase: PreviewPhase = PreviewPhase.IDLE
    private var reason: String? = null
    private var generation: Long = 0L
    private var closeTarget: Pair<PreviewPhase, String?>? = null

    private var lifecycleResumed = false
    private var pageVisible = false
    private var recordingActive = false
    private var cameraAvailable = false
    private var permissionGranted = false

    /** Token to be captured by the adapter when it starts async HAL work. */
    val currentGeneration: Long
        get() = generation

    fun setLifecycleResumed(value: Boolean) {
        if (lifecycleResumed == value) return
        lifecycleResumed = value
        recompute(allowRevive = value)
    }

    fun setPageVisible(value: Boolean) {
        if (pageVisible == value) return
        pageVisible = value
        recompute(allowRevive = value)
    }

    fun setRecordingActive(value: Boolean) {
        if (recordingActive == value) return
        recordingActive = value
        recompute(allowRevive = !value)
    }

    fun setCameraAvailable(value: Boolean) {
        if (cameraAvailable == value) return
        cameraAvailable = value
        recompute(allowRevive = value)
    }

    fun setPermissionGranted(value: Boolean) {
        if (permissionGranted == value) return
        permissionGranted = value
        recompute(allowRevive = value)
    }

    /** Manual user retry from an explicit failure state. */
    fun retry() {
        if (phase != PreviewPhase.UNAVAILABLE && phase != PreviewPhase.TIMED_OUT) return
        if (!gatesOpen()) {
            recompute(allowRevive = false)
            return
        }
        transition(PreviewPhase.DEBOUNCING)
    }

    fun onDebounceElapsed() {
        if (phase != PreviewPhase.DEBOUNCING) return
        if (!gatesOpen()) {
            recompute(allowRevive = false)
            return
        }
        generation++
        transition(PreviewPhase.ENUMERATING)
        listener.onEnumerateRequested()
    }

    fun onEnumerationComplete(generation: Long) {
        if (generation != this.generation || phase != PreviewPhase.ENUMERATING) return
        transition(PreviewPhase.OPENING)
        listener.onOpenRequested()
    }

    fun onEnumerationFailed(generation: Long, reason: String) {
        if (generation != this.generation || phase != PreviewPhase.ENUMERATING) return
        fail(reason)
    }

    /** Synchronous open failure (missing surface, openCamera throw, etc.). */
    fun onStartFailed(generation: Long, reason: String) {
        if (generation != this.generation || phase != PreviewPhase.OPENING) return
        fail(reason)
    }

    /** Returns true only when the callback belongs to the current attempt. */
    fun onOpened(generation: Long): Boolean {
        if (generation != this.generation || phase != PreviewPhase.OPENING) {
            listener.onLateCameraClosed()
            return false
        }
        return true
    }

    /** Returns true only when the session belongs to the current attempt. */
    fun onSessionConfigured(generation: Long): Boolean {
        if (generation != this.generation || phase != PreviewPhase.OPENING) {
            listener.onLateCameraClosed()
            return false
        }
        transition(PreviewPhase.PREVIEWING)
        return true
    }

    fun onSessionConfigureFailed(generation: Long, reason: String) {
        if (generation != this.generation || phase != PreviewPhase.OPENING) return
        fail(reason)
    }

    fun onOpenTimeout() {
        if (phase != PreviewPhase.OPENING) return
        generation++
        requestClose(PreviewPhase.TIMED_OUT to "open timeout")
    }

    fun onCameraDisconnected(generation: Long): Boolean {
        if (generation != this.generation || phase !in OPEN_OR_PREVIEW) {
            listener.onLateCameraClosed()
            return false
        }
        fail("CAMERA_DISCONNECTED")
        return true
    }

    fun onCameraError(generation: Long, code: Int): Boolean {
        if (generation != this.generation || phase !in OPEN_OR_PREVIEW) {
            listener.onLateCameraClosed()
            return false
        }
        fail("CAMERA_ERROR code=$code")
        return true
    }

    /** The adapter has issued the async close; move to the waiting target. */
    fun onCloseComplete() {
        if (phase != PreviewPhase.CLOSING) return
        val target = closeTarget ?: waitingTarget()
        closeTarget = null
        transition(target.first, target.second)
    }

    private fun recompute(allowRevive: Boolean) {
        when {
            !lifecycleResumed || !pageVisible -> {
                when (phase) {
                    PreviewPhase.IDLE -> transition(PreviewPhase.WAITING_FOR_LIFECYCLE)
                    PreviewPhase.WAITING_FOR_LIFECYCLE, PreviewPhase.CLOSING -> Unit
                    else -> requestClose(PreviewPhase.WAITING_FOR_LIFECYCLE to null)
                }
            }

            recordingActive -> {
                when (phase) {
                    PreviewPhase.IDLE, PreviewPhase.WAITING_FOR_LIFECYCLE,
                    PreviewPhase.UNAVAILABLE, PreviewPhase.TIMED_OUT ->
                        transition(PreviewPhase.WAITING_FOR_AVAILABILITY, REASON_RECORDING)
                    PreviewPhase.WAITING_FOR_AVAILABILITY, PreviewPhase.CLOSING -> Unit
                    else -> requestClose(PreviewPhase.WAITING_FOR_AVAILABILITY to REASON_RECORDING)
                }
            }

            !permissionGranted -> {
                when (phase) {
                    PreviewPhase.IDLE, PreviewPhase.WAITING_FOR_LIFECYCLE,
                    PreviewPhase.WAITING_FOR_AVAILABILITY, PreviewPhase.DEBOUNCING,
                    PreviewPhase.UNAVAILABLE ->
                        transition(PreviewPhase.UNAVAILABLE, REASON_PERMISSION)
                    PreviewPhase.ENUMERATING, PreviewPhase.OPENING, PreviewPhase.PREVIEWING ->
                        requestClose(PreviewPhase.UNAVAILABLE to REASON_PERMISSION)
                    PreviewPhase.CLOSING, PreviewPhase.TIMED_OUT -> Unit
                }
            }

            !cameraAvailable -> {
                when (phase) {
                    PreviewPhase.IDLE, PreviewPhase.WAITING_FOR_LIFECYCLE,
                    PreviewPhase.UNAVAILABLE, PreviewPhase.TIMED_OUT ->
                        transition(PreviewPhase.WAITING_FOR_AVAILABILITY)
                    PreviewPhase.WAITING_FOR_AVAILABILITY, PreviewPhase.CLOSING -> Unit
                    PreviewPhase.DEBOUNCING -> transition(PreviewPhase.WAITING_FOR_AVAILABILITY)
                    PreviewPhase.ENUMERATING, PreviewPhase.OPENING, PreviewPhase.PREVIEWING -> Unit
                }
            }

            else -> {
                when (phase) {
                    PreviewPhase.IDLE, PreviewPhase.WAITING_FOR_LIFECYCLE,
                    PreviewPhase.WAITING_FOR_AVAILABILITY -> transition(PreviewPhase.DEBOUNCING)
                    PreviewPhase.UNAVAILABLE, PreviewPhase.TIMED_OUT -> {
                        if (allowRevive) transition(PreviewPhase.DEBOUNCING)
                    }
                    PreviewPhase.DEBOUNCING, PreviewPhase.ENUMERATING, PreviewPhase.OPENING,
                    PreviewPhase.PREVIEWING, PreviewPhase.CLOSING -> Unit
                }
            }
        }
    }

    private fun fail(reason: String) {
        generation++
        requestClose(PreviewPhase.UNAVAILABLE to reason)
    }

    private fun requestClose(target: Pair<PreviewPhase, String?>) {
        if (phase == PreviewPhase.IDLE) return
        closeTarget = target
        transition(PreviewPhase.CLOSING)
        listener.onCloseRequested()
    }

    private fun waitingTarget(): Pair<PreviewPhase, String?> = when {
        !lifecycleResumed || !pageVisible -> PreviewPhase.WAITING_FOR_LIFECYCLE to null
        recordingActive -> PreviewPhase.WAITING_FOR_AVAILABILITY to REASON_RECORDING
        !permissionGranted -> PreviewPhase.UNAVAILABLE to REASON_PERMISSION
        !cameraAvailable -> PreviewPhase.WAITING_FOR_AVAILABILITY to null
        else -> PreviewPhase.DEBOUNCING to null
    }

    private fun gatesOpen(): Boolean = PreviewStartPolicy.shouldAttempt(
        lifecycleResumed = lifecycleResumed,
        pageVisible = pageVisible,
        recordingActive = recordingActive,
        cameraAvailable = cameraAvailable,
        permissionGranted = permissionGranted,
    )

    private fun transition(newPhase: PreviewPhase, newReason: String? = null) {
        if (phase == newPhase && reason == newReason) return
        phase = newPhase
        reason = newReason
        listener.onStateChanged(PreviewUiState(phase = phase, reason = reason, generation = generation))
    }

    companion object {
        const val REASON_RECORDING = "RECORDING_ACTIVE"
        const val REASON_PERMISSION = "CAMERA_PERMISSION_DENIED"
        const val REASON_TIMEOUT = "open timeout"

        private val OPEN_OR_PREVIEW = setOf(PreviewPhase.OPENING, PreviewPhase.PREVIEWING)
    }
}
