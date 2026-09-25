package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.player.FourLaneTextureLayout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import io.github.dantenothing.avmtransfer.protocol.ContinuousSegmentTimeline
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract
import java.io.File
import kotlin.math.roundToInt

/** Track facts read back from the finalized MP4; nulls mean the device could not extract them. */
@Serializable
data class ActualTrackInfo(
    val width: Int? = null,
    val height: Int? = null,
    val bitrateBps: Long? = null,
    val durationMs: Long? = null,
    /** MediaMetadataRetriever exposes no standard frame-count key; stays null until offline analysis. */
    val frameCount: Long? = null,
)

/** Rolling CaptureCallback statistics for one segment (no per-frame list is kept). */
@Serializable
data class SegmentFrameStats(
    val count: Long = 0,
    val firstTimestampNs: Long? = null,
    val lastTimestampNs: Long? = null,
    val maxGapNs: Long? = null,
)

/**
 * Product-facing four-lane layout metadata saved with every finalized segment.
 * Keeps the original composite file single, the layout type, per-lane crop
 * ranges, labels and rotation so the UI can show 2x2 / single-lane views
 * without re-deriving calibration from the file name.
 */
@Serializable
data class SegmentLaneLayout(
    val originalWidth: Int,
    val originalHeight: Int,
    val layoutType: String = "HORIZONTAL_4X1",
    val lanes: List<SegmentLaneInfo> = emptyList(),
)

@Serializable
data class SegmentLaneInfo(
    val lane: Int,
    val x0: Int,
    val x1: Int,
    val y0: Int,
    val y1: Int,
    val label: String,
    val rotationDegrees: Int = 0,
    val displayOrder: Int = lane,
)

@Serializable
data class SegmentSidecar(
    val schemaVersion: Int = 8,
    val file: String,
    val cameraId: String,
    val profile: CameraFormatProfile,
    /** Stable product meaning captured when the Session started. */
    val sourceRole: RecordingSourceRole = RecordingSourceRole.SURROUND,
    /** Playback contract captured with the file; never re-derived from current settings. */
    val layoutKind: RecordingLayoutKind = RecordingLayoutKind.FOUR_LANE_V1,
    /** Mapping generation used to resolve [sourceRole] to [cameraId]. */
    val mappingRevision: Int = 0,
    val segmentSeconds: Int,
    val segmentNumber: Int,
    /** Stable ZeekrApp.processStartId ("pid-epoch"), never the per-command service startId. */
    val processStartId: String,
    /** Stable identity shared by every segment in one manually started recording Session. */
    val recordingSessionId: String? = null,
    /** Recording behavior frozen at manual Start; old sidecars safely default to NORMAL. */
    val recordingMode: RecordingMode = RecordingMode.NORMAL,
    val timeLapseMultiplier: Int = 1,
    val requestedCaptureRateFps: Double? = null,
    /** Whether the encoder received a continuous request or explicitly paced single frames. */
    val captureSubmissionMode: CaptureSubmissionMode = CaptureSubmissionMode.REPEATING_ENCODER,
    /** Real-time safety boundary. Differs from [segmentSeconds] only for time-lapse. */
    val effectiveSegmentSeconds: Int = segmentSeconds,
    /** When the partial file was requested (filename timestamp), kept as evidence. */
    val requestedAtEpochMs: Long? = null,
    val requestedAtElapsedRealtimeMs: Long? = null,
    val startedAtEpochMs: Long? = null,
    val stoppedAtEpochMs: Long? = null,
    val startedAtElapsedRealtimeMs: Long? = null,
    val stoppedAtElapsedRealtimeMs: Long? = null,
    val gapFromPreviousMs: Long? = null,
    val result: String,
    val error: String? = null,
    val fileBytes: Long = 0,
    val protected: Boolean = false,
    /** Stable ID shared by the pre/current/post files saved by one Save Clip action. */
    val eventId: String? = null,
    val eventRequestedAtEpochMs: Long? = null,
    /** PREVIOUS, CURRENT or NEXT; null for manual/legacy bookmarks. */
    val eventRole: String? = null,
    /** OpenAVM Sentry warnings, relative to this physical MP4; empty on legacy/normal recordings. */
    val triggerMarkers: List<VideoTriggerMarker> = emptyList(),
    /**
     * Temporary pin set while a segment is queued for upload. Distinct from
     * [protected] (explicit user Bookmark): eviction treats both as protected,
     * but only [protected] survives an upload-pin release.
     */
    val uploadPinned: Boolean = false,
    /** True while only the provisional sidecar exists (health/track still pending). */
    val provisional: Boolean = false,
    val actualTrack: ActualTrackInfo? = null,
    val frameStats: SegmentFrameStats? = null,
    val frameHealth: FrameHealthReport? = null,
    /** Frozen four-lane calibration; null for SINGLE_V1 and legacy recordings. */
    val laneLayout: SegmentLaneLayout? = null,
    /** Monotonic elapsed time represented by this physical safety chunk. */
    val realDurationMs: Long? = null,
    /** Real duration divided by finalized MP4 duration. */
    val measuredMultiplier: Double? = null,
    val timeLapseRelativeError: Double? = null,
    val timeLapseAccuracy: TimeLapseAccuracy? = null,
    val finalizeReason: String? = null,
    /** Explicit encoded-pixel arrangement; profile/laneLayout remain camera-source coordinates.
     * Keep unknown descriptors intact so readers reject them rather than fall back to old geometry. */
    val rasterLayout: JsonElement? = null,
    val continuousTimeline: ContinuousSegmentTimeline? = null,
) {
    /** Called by the new writer only after it knows the encoded file's exact source coverage. */
    fun withContinuousRaster(layout: StripRepackContract, timeline: ContinuousSegmentTimeline): SegmentSidecar {
        require(layout.validate().isEmpty() && timeline.validate().isEmpty())
        require(profile.size.width == layout.inputWidth && profile.size.height == layout.inputHeight)
        require(sourceRole == RecordingSourceRole.SURROUND && layoutKind == RecordingLayoutKind.FOUR_LANE_V1)
        require(recordingSessionId == timeline.runId)
        require(if (recordingMode == RecordingMode.NORMAL) timeLapseMultiplier == 1 else timeLapseMultiplier in TimeLapsePolicy.MULTIPLIERS)
        require(laneLayout?.let { it.originalWidth == layout.inputWidth && it.originalHeight == layout.inputHeight } == true)
        actualTrack?.let { require(layout.matchesTrack(it.width ?: 0, it.height ?: 0)) }
        return copy(schemaVersion = 9, rasterLayout = Json.encodeToJsonElement(layout), continuousTimeline = timeline)
    }

    companion object {
        const val RESULT_SUCCESS = "SUCCESS"
        const val RESULT_FAILED = "FAILED"
    }
}

/**
 * Builds the four-lane layout for either known composite orientation:
 * 5120x1280 horizontal or the real-car 1280x5140 vertical stack.
 */
object SegmentLaneLayoutFactory {
    fun forProfile(
        width: Int,
        height: Int,
        labels: List<String>,
        displayOrder: List<Int>,
        rotations: List<Int>,
    ): SegmentLaneLayout? {
        if (width <= 0 || height <= 0) return null
        val horizontal = width.toFloat() / height >= STRONG_FOUR_LANE_RATIO
        val vertical = height.toFloat() / width >= STRONG_FOUR_LANE_RATIO
        if (!horizontal && !vertical) return null
        val safeLabels = (0..3).map { i ->
            labels.getOrElse(i) { "视角${i + 1}" }.ifBlank { "视角${i + 1}" }
        }
        val order = if (displayOrder.size == 4 && displayOrder.toSet() == setOf(1, 2, 3, 4)) {
            displayOrder
        } else {
            listOf(1, 2, 3, 4)
        }
        val lanes = order.mapIndexed { slot, sourceLane ->
            val window = FourLaneTextureLayout.windowForLane(width, height, sourceLane)
            SegmentLaneInfo(
                lane = sourceLane,
                x0 = window.sourceLeftPx.roundToInt(),
                x1 = (window.sourceLeftPx + window.sourceWidthPx).roundToInt(),
                y0 = window.sourceTopPx.roundToInt(),
                y1 = (window.sourceTopPx + window.sourceHeightPx).roundToInt(),
                label = safeLabels.getOrElse(slot) { "视角${sourceLane}" },
                rotationDegrees = rotations.getOrElse(slot) { 0 },
                displayOrder = slot + 1,
            )
        }
        return SegmentLaneLayout(
            originalWidth = width,
            originalHeight = height,
            layoutType = if (horizontal) "HORIZONTAL_4X1" else "VERTICAL_1X4",
            lanes = lanes,
        )
    }

    private const val STRONG_FOUR_LANE_RATIO = 3.2f
}

object SegmentSidecarIO {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun sidecarFileFor(mp4: File): File = SegmentNaming.sidecarFileFor(mp4)

    /** Direct USB bundles use stem.sidecar.json; internal segments use file.mp4.sidecar.json. */
    fun readForMedia(mp4: File): SegmentSidecar? = read(sidecarFileFor(mp4))
        ?: mp4.takeIf { it.extension.equals("mp4", true) }?.let { read(File(it.parentFile, it.nameWithoutExtension + ".sidecar.json")) }

    /** Writes to a `.tmp` sibling and renames over the target so readers never see a torn file. */
    fun writeAtomic(mp4: File, sidecar: SegmentSidecar): File {
        val target = sidecarFileFor(mp4)
        val tmp = File(target.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(SegmentSidecar.serializer(), sidecar))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
        return target
    }

    fun read(file: File): SegmentSidecar? =
        try {
            json.decodeFromString(SegmentSidecar.serializer(), file.readText())
        } catch (t: Throwable) {
            null
        }
}
