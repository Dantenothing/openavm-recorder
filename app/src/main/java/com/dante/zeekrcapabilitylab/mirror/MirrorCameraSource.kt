package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.view.Surface
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*

/** Presentation can use an existing producer; it cannot create recording ownership. */
interface MirrorCameraSource {
    val state: RecorderState
    val previewOnly: Boolean get() = false
    /** Private ordered-preview trial only; ordinary producers require an opened device. */
    val acceptsPreviewOutputBeforeOpen: Boolean get() = false
    fun enable(enabled: Boolean, runId: String, generation: Long)
    fun replace(surface: Surface, runId: String, generation: Long, released: () -> Unit)
    /** A new frame displayed by this run, not CameraDevice.onOpened or a capture receipt. */
    fun displayedFrame(runId: String, generation: Long) = Unit
    /** Cached request/result counters only; reading these must never query or reconfigure the camera. */
    fun captureFacts(): Map<String, String> = emptyMap()
    fun stop()
    fun bookmark()
}

class RecordingMirrorSource(private val context: Context, private val recorder: RecorderSession) : MirrorCameraSource {
    override val state get() = CameraRecordingService.state.value
    override fun enable(enabled: Boolean, runId: String, generation: Long) = recorder.setPreviewOutputEnabled(enabled, runId, generation)
    override fun replace(surface: Surface, runId: String, generation: Long, released: () -> Unit) =
        recorder.replacePreviewSurface(surface, runId, generation, released)
    override fun captureFacts(): Map<String, String> {
        val current = state
        val capture = RecorderCaptureEvidence.latest
        if (!capture.matches(current) || capture.cameraGeneration != current.cameraGeneration) return emptyMap()
        return mapOf("captureFrames" to capture.frames.count.toString(),
            "captureFailures" to capture.preview.captureFailures.toString(),
            "captureFailureReason" to (capture.preview.lastFailureReason?.toString() ?: "UNKNOWN"),
            "previewBuffersLost" to capture.preview.previewBuffersLost.toString(),
            "encoderBuffersLost" to capture.preview.encoderBuffersLost.toString(),
            "encodedFrames" to (capture.encoder?.frames?.toString() ?: "UNKNOWN"))
    }
    override fun stop() = CameraRecordingService.stop(context)
    override fun bookmark() = CameraRecordingService.bookmark(context)
}
