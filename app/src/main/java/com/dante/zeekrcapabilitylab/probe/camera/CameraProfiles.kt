package com.dante.zeekrcapabilitylab.probe.camera

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

/**
 * Pure-Kotlin profile/selection/sorting logic for the Camera 2 format lab.
 *
 * Rules enforced here (and covered by JVM tests):
 *  - `5120x1280 @ 28 Mbps` is the preferred four-camera composite experiment.
 *  - `1280x5140` is never filtered out because of an unusual aspect ratio.
 *  - Only sizes the device actually declares are listed.
 *  - There is no implicit fallback: a profile either matches a declared size
 *    exactly or it is not offered at all.
 */
@Serializable
data class ProfileSize(val width: Int, val height: Int) {
    val totalPixels: Long get() = width.toLong() * height
    override fun toString(): String = "${width}x${height}"
}

@Serializable
data class CameraFormatProfile(
    val size: ProfileSize,
    val bitrateBps: Int,
    val source: String = SOURCE_EXPLICIT,
) {
    val label: String get() = "${size.width}x${size.height} @ ${bitrateBps / 1_000_000} Mbps"
    val key: String get() = "${size.width}x${size.height}@$bitrateBps"

    companion object {
        const val SOURCE_EXPLICIT = "EXPLICIT"
        const val SOURCE_DECLARED_AUTO = "DECLARED_AUTO"
    }
}

object CameraProfileCatalog {

    val SIZE_5120X1280 = ProfileSize(5120, 1280)
    val SIZE_1280X5120 = ProfileSize(1280, 5120)
    val SIZE_1280X5140 = ProfileSize(1280, 5140)
    val SIZE_3840X2160 = ProfileSize(3840, 2160)

    /**
     * Explicit test matrix. Order is also preference order: the currently
     * observed 4 x 1280-square horizontal composite at 28 Mbps comes first.
     * Exact HAL declaration is still mandatory, so this never invents support.
     */
    val EXPLICIT_PROFILES: List<CameraFormatProfile> = listOf(
        CameraFormatProfile(SIZE_5120X1280, 28_000_000),
        CameraFormatProfile(SIZE_5120X1280, 14_000_000),
        CameraFormatProfile(SIZE_1280X5120, 28_000_000),
        CameraFormatProfile(SIZE_1280X5120, 14_000_000),
        CameraFormatProfile(SIZE_1280X5140, 28_000_000),
        CameraFormatProfile(SIZE_1280X5140, 14_000_000),
        CameraFormatProfile(SIZE_3840X2160, 14_000_000),
        CameraFormatProfile(SIZE_3840X2160, 28_000_000),
        CameraFormatProfile(SIZE_3840X2160, 40_000_000),
    )

    /**
     * Exact-match filtering against declared sizes. No aspect-ratio heuristics,
     * no nearest-size substitution.
     */
    fun availableProfiles(
        declaredSizes: Collection<ProfileSize>,
        requested: List<CameraFormatProfile> = EXPLICIT_PROFILES,
    ): List<CameraFormatProfile> {
        val declared = declaredSizes.toSet()
        return requested.filter { it.size in declared }
    }

    /** Returns the requested profile only when its size is declared; otherwise null (no fallback). */
    fun resolveExactOrNull(
        declaredSizes: Collection<ProfileSize>,
        requested: CameraFormatProfile,
    ): CameraFormatProfile? = if (requested.size in declaredSizes.toSet()) requested else null

    /** One profile per declared size, bitrate derived by pixel area; used for non-Camera 2 devices. */
    fun declaredAutoProfiles(declaredSizes: Collection<ProfileSize>): List<CameraFormatProfile> =
        declaredSizes
            .filter { it.width > 0 && it.height > 0 }
            .sortedWith(
                compareByDescending<ProfileSize> { it.totalPixels }
                    .thenBy { it.width }
                    .thenBy { it.height },
            )
            .map { size ->
                CameraFormatProfile(size, bitrateForSize(size), CameraFormatProfile.SOURCE_DECLARED_AUTO)
            }

    /** Explicit matrix first (definition order), then auto profiles for other declared sizes. */
    fun labProfiles(declaredSizes: Collection<ProfileSize>): List<CameraFormatProfile> {
        val explicit = availableProfiles(declaredSizes)
        val explicitSizes = explicit.map { it.size }.toSet()
        val auto = declaredAutoProfiles(declaredSizes).filter { it.size !in explicitSizes }
        return explicit + auto
    }

    /**
     * Entry point for raw HAL MediaRecorder candidates.
     *
     * Contract: the result contains only sizes the HAL actually declared. An empty
     * candidate list stays empty — no fallback size is injected here; callers must
     * surface "no profiles" to the user instead of inventing an undeclared size.
     */
    fun labProfilesForRecordCandidates(candidates: Collection<ProfileSize>): List<CameraFormatProfile> =
        labProfiles(candidates)

    /**
     * Product recording preference learned from the real car: the native feed
     * is four 1280-square views stacked vertically. A HAL-declared vertical
     * four-lane size therefore wins over a horizontal alternative and records
     * at 28 Mbps. Bitrate is an encoder setting; only the dimensions need an
     * exact HAL declaration.
     */
    fun productPreferredProfile(declaredSizes: Collection<ProfileSize>): CameraFormatProfile? {
        val declared = declaredSizes.filter { it.width > 0 && it.height > 0 }.distinct()
        val composite = declared
            .filter(::isFourLaneComposite)
            .sortedWith(
                compareBy<ProfileSize> { if (it.height > it.width) 0 else 1 }
                    .thenByDescending { it.totalPixels },
            )
            .firstOrNull()
        if (composite != null) {
            val explicitSize = EXPLICIT_PROFILES.any { it.size == composite }
            return CameraFormatProfile(
                size = composite,
                bitrateBps = 28_000_000,
                source = if (explicitSize) {
                    CameraFormatProfile.SOURCE_EXPLICIT
                } else {
                    CameraFormatProfile.SOURCE_DECLARED_AUTO
                },
            )
        }
        return availableProfiles(declared).firstOrNull()
            ?: labProfilesForRecordCandidates(declared).firstOrNull()
    }

    fun isFourLaneComposite(size: ProfileSize): Boolean {
        if (size.width <= 0 || size.height <= 0) return false
        val longSide = maxOf(size.width, size.height).toDouble()
        val shortSide = minOf(size.width, size.height).toDouble()
        val aspect = longSide / shortSide
        return abs(aspect - 4.0) / 4.0 <= 0.02
    }

    fun sortProfiles(profiles: List<CameraFormatProfile>): List<CameraFormatProfile> =
        profiles.sortedWith(
            compareByDescending<CameraFormatProfile> { it.size.totalPixels }
                .thenByDescending { it.bitrateBps }
                .thenBy { it.size.height }
                .thenBy { it.size.width },
        )

    fun bitrateForSize(size: ProfileSize): Int = when {
        size.totalPixels >= 8_000_000L -> 14_000_000
        size.totalPixels >= 1_920L * 1_080 -> 8_000_000
        size.totalPixels >= 1_280L * 720 -> 6_000_000
        else -> 4_000_000
    }
}

/** What the experiment requested, before the device had a chance to accept/reject it. */
@Serializable
data class RequestedRecording(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val bitrateBps: Int,
    val durationMs: Long,
)

/** Track facts read from the produced file; nulls mean "pending offline verification". */
@Serializable
data class ActualRecording(
    val cameraId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrateBps: Long? = null,
    val durationMs: Long? = null,
    val source: String = "MEDIA_METADATA_RETRIEVER",
)

@Serializable
data class RecordingSidecar(
    val schemaVersion: Int = 1,
    val file: String,
    val requested: RequestedRecording,
    val actual: ActualRecording? = null,
    val startedAtEpochMs: Long? = null,
    val stoppedAtEpochMs: Long? = null,
    val result: String,
    val error: String? = null,
    val fileBytes: Long,
    val trackVerification: String = TRACK_PENDING_OFFLINE,
) {
    companion object {
        const val RESULT_SUCCESS = "SUCCESS"
        const val RESULT_FAILED = "FAILED"
        const val TRACK_VERIFIED = "VERIFIED"
        const val TRACK_PENDING_OFFLINE = "PENDING_OFFLINE"
    }
}

object RecordingSidecarFactory {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun sidecarFileFor(mediaFile: File): File =
        File(mediaFile.absolutePath + ".sidecar.json")

    fun write(mediaFile: File, sidecar: RecordingSidecar): File {
        val file = sidecarFileFor(mediaFile)
        file.writeText(json.encodeToString(RecordingSidecar.serializer(), sidecar))
        return file
    }

    fun fromJson(text: String): RecordingSidecar =
        json.decodeFromString(RecordingSidecar.serializer(), text)
}

/**
 * Pure-Kotlin decision used by every recording path: an actual track counts as
 * verified only when both width and height are known and positive. Duration or
 * bitrate alone is not proof of the track dimensions, so such metadata must be
 * reported as pending offline verification instead.
 */
object TrackVerification {
    fun isVerified(actual: ActualRecording?): Boolean =
        actual != null && (actual.width ?: 0) > 0 && (actual.height ?: 0) > 0
}
