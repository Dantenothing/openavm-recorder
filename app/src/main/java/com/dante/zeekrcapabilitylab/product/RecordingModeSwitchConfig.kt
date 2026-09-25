package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.mirror.MirrorPreviewPolicy
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.service.recorder.*

/** Retains the current physical camera, mapping, dimensions and storage settings. */
object RecordingModeSwitchConfig {
    fun build(current: RecorderConfig, target: RecordingModeChoice, normalQuality: RecordingQuality,
              mirrorEnabled: Boolean, normalSharedInput: Boolean = current.sharedInputRecordingEnabled): RecorderConfig {
        require(target.valid())
        val original = requireNotNull(CameraProfileCatalog.productPreferredProfile(listOf(current.profile.size)))
        val quality = if (target.mode == RecordingMode.NORMAL) normalQuality else RecordingQuality.ORIGINAL
        val next = current.copy(
            source = current.source.copy(profile = current.profile.copy(bitrateBps = quality.bitrate(original.bitrateBps))),
            recordingMode = target.mode, timeLapseMultiplier = target.multiplier,
            requestedFrameRate = quality.fps, strictFrameRate = quality != RecordingQuality.ORIGINAL,
            mirrorPreviewEnabled = false,
            sharedInputRecordingEnabled = normalSharedInput && ProductContinuousPolicy.supportsSource(current.source),
        )
        return next.copy(mirrorPreviewEnabled = mirrorEnabled && MirrorPreviewPolicy.supports(next))
            .also { require(it.validate().isEmpty()) }
    }
}
