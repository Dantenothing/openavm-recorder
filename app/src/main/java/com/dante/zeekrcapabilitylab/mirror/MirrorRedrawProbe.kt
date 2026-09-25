package com.dante.zeekrcapabilitylab.mirror

/** A bounded display-only experiment. It cannot authorize camera or Surface replacement. */
class MirrorRedrawProbe {
    var attempts = 0; private set
    var framesResumed = 0; private set
    private var activeSegment: Int? = null
    private var activeSince = 0L
    private var wasRecording = false
    private var attemptedSegment: Int? = null
    private var waitingForFrames: Long? = null

    fun tick(segment: Int, nowMs: Long, recording: Boolean, visible: Boolean,
             frames: Long, frameAgeMs: Long?, previewResultAgeMs: Long?): Boolean {
        if (!recording || !visible || activeSegment != segment) waitingForFrames = null
        if (recording && (!wasRecording || activeSegment != segment)) activeSince = nowMs
        wasRecording = recording
        activeSegment = segment
        val freshCapture = previewResultAgeMs != null && previewResultAgeMs in 0..750
        waitingForFrames?.let { before ->
            if (recording && visible && freshCapture && frames >= before + 3 &&
                frameAgeMs != null && frameAgeMs in 0..500) {
                framesResumed++
                waitingForFrames = null
            }
        }
        if (!recording || !visible || !freshCapture || frameAgeMs == null || frameAgeMs < 2_000 ||
            nowMs < activeSince || nowMs - activeSince < 2_000 ||
            attemptedSegment == segment || attempts >= 3) return false
        attemptedSegment = segment
        attempts++
        waitingForFrames = frames
        return true
    }
}
