package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import java.io.File

/**
 * Pure-Kotlin segment naming. Every in-flight segment is written as
 * `seg-<n>-<epoch>-<W>x<H>-<M>M.mp4.partial` and only renamed to `.mp4`
 * after MediaRecorder.stop() succeeds and the file is non-empty.
 */
object SegmentNaming {
    const val PARTIAL_SUFFIX = ".partial"
    const val MP4_SUFFIX = ".mp4"
    const val SIDECAR_SUFFIX = ".sidecar.json"

    fun partialFile(
        dir: File,
        segmentNumber: Int,
        profile: CameraFormatProfile,
        startedAtEpochMs: Long,
    ): File = File(
        dir,
        "seg-%04d-%d-%dx%d-%dM.mp4.partial".format(
            segmentNumber,
            startedAtEpochMs,
            profile.size.width,
            profile.size.height,
            profile.bitrateBps / 1_000_000,
        ),
    )

    fun finalFileFor(partial: File): File =
        File(partial.parentFile, partial.name.removeSuffix(PARTIAL_SUFFIX))

    fun isPartial(name: String): Boolean = name.endsWith(PARTIAL_SUFFIX)

    fun isFinalMp4(name: String): Boolean = name.endsWith(MP4_SUFFIX) && !isPartial(name)

    fun sidecarFileFor(mp4: File): File = File(mp4.absolutePath + SIDECAR_SUFFIX)
}
