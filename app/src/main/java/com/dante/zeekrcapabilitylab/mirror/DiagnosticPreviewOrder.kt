package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.TerminalCloseTrial

internal enum class PreviewInitializationOrder { CAMERA_FIRST, SURFACE_FIRST }
internal data class PreviewOrderTrial(val id: String, val cameraId: String, val order: PreviewInitializationOrder,
                                     val sourceRole: RecordingSourceRole = RecordingSourceRole.SURROUND,
                                     val closeTrial: TerminalCloseTrial? = null, val recordingConfig: RecorderConfig? = null) {
    fun admitsRecording(config: RecorderConfig?): Boolean = closeTrial != null && closeTrial.failure == null &&
        config != null && config == recordingConfig
}

/** Single-use, process-local admission. No preference, native owner or retry permission. */
internal class PreviewOrderRegistry {
    private var trial: PreviewOrderTrial? = null
    private var consumed = false
    @Synchronized fun arm(value: PreviewOrderTrial) {
        check(trial == null) { "PREVIEW_ORDER_TRIAL_BUSY" }
        require(value.sourceRole in setOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN))
        require((value.closeTrial == null) == (value.recordingConfig == null))
        value.recordingConfig?.let { config ->
            require(value.closeTrial?.id == value.id && config.cameraId == value.cameraId &&
                config.source.sourceRole == value.sourceRole && config.validate().isEmpty())
        }
        trial = value; consumed = false
    }
    @Synchronized fun take(id: String): PreviewOrderTrial? = trial?.takeIf { it.id == id && !consumed }
        ?.also { consumed = true }
    @Synchronized fun finish(id: String) { if (trial?.id == id) { trial = null; consumed = false } }
}

internal object DiagnosticPreviewOrder {
    private val registry = PreviewOrderRegistry()
    fun arm(trial: PreviewOrderTrial) { check(BuildConfig.RECORDER_SUPPORT_ENABLED); registry.arm(trial) }
    fun take(id: String): PreviewOrderTrial? = if (BuildConfig.RECORDER_SUPPORT_ENABLED) registry.take(id) else null
    fun finish(id: String) = registry.finish(id)
}
