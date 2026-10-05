package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize

/** Parallel channels have a bounded input budget; never invent an undeclared camera size. */
internal object CabinRecordingProfile {
    const val MAX_BITRATE = 4_000_000
    fun choose(declared: Collection<ProfileSize>): CameraFormatProfile? {
        val size = declared.filter { it.width in 1..1280 && it.height in 1..720 }
            .maxByOrNull { it.totalPixels } ?: return null
        return CameraFormatProfile(size, if (size.totalPixels > 640L * 480) MAX_BITRATE else 2_000_000)
    }
}
