package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import kotlinx.serialization.Serializable

/** Per-run metadata only. A saved report never authorizes a new camera session. */
@Serializable
data class GuardCameraCapabilities(
    val cameraId: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val surfaceDeclared: Boolean,
    val fpsLower: Int,
    val fpsUpper: Int,
    val timestampRealtime: Boolean,
    val analysisSize: ProfileSize? = null,
    val analysisError: String? = null,
    val preparedAtEpochMs: Long = 0,
) {
    fun requireMatches(source: SessionSourceSnapshot) {
        check(cameraId == source.cameraId && sourceWidth == source.profile.size.width &&
            sourceHeight == source.profile.size.height) { "PREPARED_CAMERA_SOURCE_MISMATCH" }
        check(surfaceDeclared) { "ENCODER_SURFACE_SIZE_UNDECLARED" }
        check(fpsLower in 1..15 && fpsUpper >= 15 && fpsLower <= fpsUpper) { "NO_15_FPS_CAMERA_RANGE" }
    }
}

internal object GuardAnalysisSizePolicy {
    fun choose(sizes: List<ProfileSize>, source: SessionSourceSnapshot): ProfileSize? {
        val ratio = (source.laneLayout?.originalWidth ?: source.profile.size.width).toDouble() /
            (source.laneLayout?.originalHeight ?: source.profile.size.height)
        val matching = sizes.filter { it.width > 0 && it.height > 0 &&
            kotlin.math.abs(it.width.toDouble() / it.height / ratio - 1) < 0.01 &&
            it.width.toLong() * it.height <= 8_000_000 }
        return matching.filter { it.width.toLong() * it.height <= 2_000_000 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: matching.minByOrNull { it.width.toLong() * it.height }
    }
}

internal object GuardExceptionSummary {
    fun describe(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(3).toList()
        val messages = causes.joinToString("\nCaused by: ") { it.javaClass.name + ": " + it.message.orEmpty().take(220) }
        val frames = causes.flatMap { it.stackTrace.take(3) }.distinct().take(6)
            .joinToString("\n") { it.toString().take(140) }
        return (messages + "\n" + frames).take(1_200)
    }
}
