package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.player.FourLaneTextureLayout
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class RecordingMode {
    FRONT_ONLY,
    SURROUND_360,
}

@Serializable
enum class RecordingSourceKind {
    DIRECT_FRONT,
    COMPOSITE_CROP,
    COMPOSITE,
}

@Serializable
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun validate(): List<String> = buildList {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
            add("crop coordinates must be finite")
        }
        if (left !in 0f..1f || top !in 0f..1f || right !in 0f..1f || bottom !in 0f..1f) {
            add("crop coordinates must be within 0..1")
        }
        if (right <= left || bottom <= top) add("crop rectangle must have positive area")
    }
}

@Serializable
data class FrontCalibration(
    val sourceFingerprint: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val frontLane: Int,
    val crop: NormalizedCropRect,
    val rotationDegrees: Int,
    val calibrationVersion: Int = CURRENT_VERSION,
) {
    fun validate(): List<String> = buildList {
        if (sourceFingerprint.isBlank()) add("calibration source fingerprint is missing")
        if (sourceWidth <= 0 || sourceHeight <= 0) add("calibration source geometry is invalid")
        if (frontLane !in 1..4) add("front lane must be within 1..4")
        addAll(crop.validate())
        if (rotationDegrees !in setOf(0, 90, 180, 270)) add("rotation must be 0, 90, 180, or 270")
        if (calibrationVersion != CURRENT_VERSION) add("calibration version is stale")
    }

    fun matches(fingerprint: String, size: ProfileSize): Boolean =
        validate().isEmpty() &&
            sourceFingerprint == fingerprint &&
            sourceWidth == size.width &&
            sourceHeight == size.height

    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class EncoderProfile(
    val codecMime: String = "video/avc",
    val codecName: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val requestedBitrateBps: Int,
    val profile: Int? = null,
    val level: Int? = null,
)

/** Stable, non-identifying Camera2 facts used to detect OTA/source changes. */
data class CameraSourceFacts(
    val cameraId: String,
    val lensFacing: Int?,
    val hardwareLevel: Int?,
    val sensorOrientation: Int?,
    val activeArray: String?,
    val capabilities: List<Int>,
    val recordSizes: List<ProfileSize>,
    val surfaceTextureSizes: List<ProfileSize> = emptyList(),
    val mediaCodecSizes: List<ProfileSize> = emptyList(),
)

object CameraSourceFingerprint {
    fun create(facts: CameraSourceFacts): String {
        val canonical = buildString {
            append("camera=").append(facts.cameraId).append('\n')
            append("facing=").append(facts.lensFacing).append('\n')
            append("level=").append(facts.hardwareLevel).append('\n')
            append("orientation=").append(facts.sensorOrientation).append('\n')
            append("active=").append(facts.activeArray ?: "-").append('\n')
            append("capabilities=").append(facts.capabilities.sorted().joinToString(",")).append('\n')
            append("recordSizes=").append(
                facts.recordSizes
                    .filter { it.width > 0 && it.height > 0 }
                    .distinct()
                    .sortedWith(compareBy<ProfileSize> { it.width }.thenBy { it.height })
                    .joinToString(",") { "${it.width}x${it.height}" },
            )
            append('\n')
            append("surfaceTextureSizes=").append(canonicalSizes(facts.surfaceTextureSizes))
            append('\n')
            append("mediaCodecSizes=").append(canonicalSizes(facts.mediaCodecSizes))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalSizes(sizes: List<ProfileSize>): String = sizes
        .filter { it.width > 0 && it.height > 0 }
        .distinct()
        .sortedWith(compareBy<ProfileSize> { it.width }.thenBy { it.height })
        .joinToString(",") { "${it.width}x${it.height}" }
}

object FrontCropPolicy {
    const val FIXED_FRONT_LANE = 1
    const val FIXED_FRONT_ROTATION_DEGREES = 0

    /** Equal-lane crop for the fixed composite ordering: front, rear, left, right. */
    fun laneCrop(size: ProfileSize, lane: Int): NormalizedCropRect? {
        if (size.width <= 0 || size.height <= 0 || lane !in 1..4) return null
        val vertical = size.height.toDouble() / size.width >= 3.2
        val horizontal = size.width.toDouble() / size.height >= 3.2
        if (!vertical && !horizontal) return null
        val window = FourLaneTextureLayout.windowForLane(size.width, size.height, lane)
        return NormalizedCropRect(
            left = window.u,
            top = window.v,
            right = window.u + window.width,
            bottom = window.v + window.height,
        )
    }

    fun calibration(
        fingerprint: String,
        size: ProfileSize,
        lane: Int,
        rotationDegrees: Int,
    ): FrontCalibration? = laneCrop(size, lane)?.let { crop ->
        FrontCalibration(
            sourceFingerprint = fingerprint,
            sourceWidth = size.width,
            sourceHeight = size.height,
            frontLane = lane,
            crop = crop,
            rotationDegrees = ((rotationDegrees % 360) + 360) % 360,
        ).takeIf { it.validate().isEmpty() }
    }

    /** Builds the deterministic front selection used by front-only recording. */
    fun fixedFrontSelection(
        fingerprint: String,
        size: ProfileSize,
    ): FrontCalibration? = calibration(
        fingerprint = fingerprint,
        size = size,
        lane = FIXED_FRONT_LANE,
        rotationDegrees = FIXED_FRONT_ROTATION_DEGREES,
    )
}

/** A front calibration is durable only after the selected live crop produced a frame. */
object FrontCalibrationConfirmationPolicy {
    fun canSave(
        previewActive: Boolean,
        firstFrameVisible: Boolean,
        sourceUnchanged: Boolean,
        previewMatchesSource: Boolean,
    ): Boolean = previewActive && firstFrameVisible && sourceUnchanged && previewMatchesSource
}

data class VideoEncoderCapability(
    val codecName: String,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val widthRange: IntRange,
    val heightRange: IntRange,
    val frameRateRange: IntRange,
    val bitrateRange: IntRange,
    val surfaceInput: Boolean,
    val hardwareAccelerated: Boolean = true,
)

/** Pure, fail-closed selector. It never changes mode or invents an unvalidated fallback. */
object FrontEncoderProfilePolicy {
    const val TARGET_SIZE = 1280
    const val TARGET_FRAME_RATE = 30
    const val TARGET_BITRATE_BPS = 8_000_000

    fun select(capabilities: List<VideoEncoderCapability>): EncoderProfile? = capabilities
        .asSequence()
        .filter { it.surfaceInput }
        .filter { it.hardwareAccelerated }
        .filter { TARGET_SIZE in it.widthRange && TARGET_SIZE in it.heightRange }
        .filter { TARGET_FRAME_RATE in it.frameRateRange }
        .filter { TARGET_BITRATE_BPS in it.bitrateRange }
        .filter {
            TARGET_SIZE % it.widthAlignment.coerceAtLeast(1) == 0 &&
                TARGET_SIZE % it.heightAlignment.coerceAtLeast(1) == 0
        }
        .map {
            EncoderProfile(
                codecName = it.codecName,
                width = TARGET_SIZE,
                height = TARGET_SIZE,
                frameRate = TARGET_FRAME_RATE,
                requestedBitrateBps = TARGET_BITRATE_BPS,
            )
        }
        .firstOrNull()
}
