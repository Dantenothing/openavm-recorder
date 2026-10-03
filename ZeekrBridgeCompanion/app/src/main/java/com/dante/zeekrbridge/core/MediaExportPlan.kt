package com.dante.zeekrbridge.core

import kotlin.math.max
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline

enum class MediaExportTarget(val fileSuffix: String) {
    ORIGINAL("360"),
    FRONT("Front"),
    REAR("Rear"),
    LEFT("Left"),
    RIGHT("Right"),
    TOP_LEFT("TopLeft"),
    TOP_RIGHT("TopRight"),
    BOTTOM_LEFT("BottomLeft"),
    BOTTOM_RIGHT("BottomRight"),
}

data class PixelCrop(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val x0: Int,
    val x1: Int,
    val y0: Int,
    val y1: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(x0 in 0 until x1 && x1 <= sourceWidth)
        require(y0 in 0 until y1 && y1 <= sourceHeight)
    }

    fun normalizedForMedia3(): NormalizedCrop = NormalizedCrop(
        left = (2f * x0 / sourceWidth) - 1f,
        right = (2f * x1 / sourceWidth) - 1f,
        bottom = 1f - (2f * y1 / sourceHeight),
        top = 1f - (2f * y0 / sourceHeight),
    )
}

data class NormalizedCrop(val left: Float, val right: Float, val bottom: Float, val top: Float)

data class PlannedExportClip(
    val filePath: String,
    val sourceDurationMs: Long,
    val clipStartMs: Long,
    val clipEndMs: Long,
    val crop: PixelCrop?,
    val raster: StripRepackContract? = null,
    val sourceLanes: List<IndexedLane> = emptyList(),
    val sessionBinding: SessionExportBinding? = null,
) {
    val outputDurationMs: Long get() = (clipEndMs - clipStartMs).coerceAtLeast(0L)
}

data class MediaExportPlan(
    val target: MediaExportTarget,
    val sourceRole: IndexedSourceRole,
    val recordingMode: IndexedRecordingMode,
    val timeLapseMultiplier: Int,
    val clips: List<PlannedExportClip>,
    val requestedStartMs: Long,
    val requestedEndMs: Long,
    val missingGapCount: Int,
    val missingGapDurationMs: Long,
    val embeddedMetadata: String? = null,
    val compositeLanes: List<IndexedLane> = emptyList(),
) {
    val outputDurationMs: Long = clips.sumOf { it.outputDurationMs }
    val requiresVideoProcessing: Boolean = target != MediaExportTarget.ORIGINAL
}

object MediaExportPlanner {
    /** Preserve monotonic channel order, with exact selected coverage and no skipped files. */
    fun buildSession(
        segments: List<IndexedMediaSegment>, timeline: SessionPlaybackTimeline, channelId: String,
        target: MediaExportTarget, fromSessionMs: Long, toSessionMs: Long,
    ): MediaExportPlan {
        require(toSessionMs - fromSessionMs in 1..MAX_SESSION_EXPORT_MS) { "Select at most two hours per export" }
        val slices = requireNotNull(timeline.range(channelId, fromSessionMs, toSessionMs)) {
            "The selected camera has a gap or overlapping files in this range. Select a continuous range."
        }
        require(slices.size <= MAX_SESSION_EXPORT_SEGMENTS) { "Select at most 200 segments per export" }
        val byId = segments.associateBy { it.id }
        val windows = timeline.segments.associateBy { it.id }
        val selected = slices.map { slice ->
            val item = requireNotNull(byId[slice.segmentId]) { "A selected file is missing" }
            require(item.file.isFile && item.file.length() > 0L) { "A selected file is missing or incomplete" }
            require(item.recordingMode == IndexedRecordingMode.NORMAL && item.recordingSessionId == timeline.sessionId &&
                item.playbackChannelId == channelId) { "This export requires one normal-recording session and camera" }
            item.copy(durationMs = windows.getValue(item.id).mediaDurationMs)
        }
        val first = selected.first()
        require(selected.all { it.sourceRole == first.sourceRole && it.layoutKind == first.layoutKind &&
            it.raster == first.raster && it.lanes == first.lanes && it.originalWidth == first.originalWidth && it.originalHeight == first.originalHeight }) {
            "The selected files have incompatible camera layouts"
        }
        val plans = selected.zip(slices).map { (segment, slice) -> build(listOf(segment), target, slice.fromMediaMs, slice.toMediaMs) }
        val clips = plans.zip(selected).flatMap { (plan, segment) ->
            val binding = SessionExportBinding.capture(segment, windows.getValue(segment.id))
            plan.clips.map { it.copy(sessionBinding = binding) }
        }
        // The shared timeline proves coverage. A fractional continuous cut can round the
        // global span by 1 ms, but each physical clip must still match its exact media slice.
        require(clips.size == slices.size && clips.zip(slices).all { (clip, slice) ->
            clip.clipStartMs == slice.fromMediaMs && clip.clipEndMs == slice.toMediaMs
        }) { "The export range is incomplete" }
        return plans.first().copy(clips = clips, requestedStartMs = fromSessionMs, requestedEndMs = toSessionMs,
            missingGapCount = 0, missingGapDurationMs = 0,
            embeddedMetadata = if (target == MediaExportTarget.ORIGINAL)
                ContinuousRasterSupport.exportDocument(first, clips.sumOf { it.outputDurationMs }) else null)
    }

    const val MAX_SESSION_EXPORT_SEGMENTS = 200
    const val MAX_SESSION_EXPORT_MS = 2L * 60 * 60 * 1_000

    fun supportedTargets(sourceRole: IndexedSourceRole): List<MediaExportTarget> =
        if (sourceRole == IndexedSourceRole.SURROUND) DIRECTION_TARGETS
        else listOf(MediaExportTarget.ORIGINAL)

    fun supportedTargets(segments: List<IndexedMediaSegment>): List<MediaExportTarget> {
        val first = segments.firstOrNull() ?: return listOf(MediaExportTarget.ORIGINAL)
        if (first.sourceRole != IndexedSourceRole.SURROUND) return listOf(MediaExportTarget.ORIGINAL)
        return if (first.layoutKind == IndexedLayoutKind.FOUR_LANE_GRID_2X2) {
            QUADRANT_TARGETS
        } else {
            DIRECTION_TARGETS
        }
    }

    fun totalDurationMs(segments: List<IndexedMediaSegment>): Long =
        segments.sumOf { it.durationMs.coerceAtLeast(0L) }

    fun build(
        segments: List<IndexedMediaSegment>,
        target: MediaExportTarget,
        requestedStartMs: Long = 0L,
        requestedEndMs: Long = totalDurationMs(segments),
    ): MediaExportPlan {
        require(segments.isNotEmpty()) { "No media segments" }
        val ordered = segments.sortedWith(
            compareBy<IndexedMediaSegment> { it.startedAtEpochMs }.thenBy { it.segmentNumber ?: 0 },
        )
        val sourceRole = ordered.first().sourceRole
        ordered.forEach { segment -> require(ContinuousRasterSupport.error(segment) == null) {
            ContinuousRasterSupport.error(segment).orEmpty()
        } }
        require(ordered.all { it.sourceRole == sourceRole }) { "A single export cannot mix camera sources" }
        require(ordered.all { it.layoutKind == ordered.first().layoutKind && it.raster == ordered.first().raster }) {
            "A single export cannot mix recording layouts"
        }
        if (ordered.first().raster is RecordingRasterMetadata.Repacked) require(ordered.all {
            it.lanes.sortedBy { lane -> lane.lane } == ordered.first().lanes.sortedBy { lane -> lane.lane }
        }) { "A single export cannot mix view calibrations" }
        require(target in supportedTargets(ordered)) { "This output is not available for the recording layout" }
        val total = totalDurationMs(ordered)
        require(total > 0L) { "Recording duration is unavailable" }
        val start = requestedStartMs.coerceIn(0L, total)
        val end = requestedEndMs.coerceIn(start, total)
        require(end > start) { "Export range is empty" }

        var logicalOffset = 0L
        val clips = buildList {
            ordered.forEach { segment ->
                val duration = segment.durationMs.coerceAtLeast(0L)
                val segmentStart = logicalOffset
                val segmentEnd = logicalOffset + duration
                val overlapStart = max(start, segmentStart)
                val overlapEnd = minOf(end, segmentEnd)
                if (overlapEnd > overlapStart && segment.file.isFile) {
                    add(
                        PlannedExportClip(
                            filePath = segment.filePath,
                            sourceDurationMs = duration,
                            clipStartMs = overlapStart - segmentStart,
                            clipEndMs = overlapEnd - segmentStart,
                            crop = cropFor(segment, target),
                            raster = (segment.raster as? RecordingRasterMetadata.Repacked)?.contract,
                            sourceLanes = segment.lanes.toList(),
                        ),
                    )
                }
                logicalOffset = segmentEnd
            }
        }
        require(clips.isNotEmpty()) { "No available files overlap the export range" }
        val gaps = gapSummary(ordered)
        return MediaExportPlan(
            target = target,
            sourceRole = sourceRole,
            recordingMode = ordered.first().recordingMode,
            timeLapseMultiplier = ordered.first().timeLapseMultiplier.coerceAtLeast(1),
            clips = clips,
            requestedStartMs = start,
            requestedEndMs = end,
            missingGapCount = gaps.first,
            missingGapDurationMs = gaps.second,
            embeddedMetadata = if (target == MediaExportTarget.ORIGINAL)
                ContinuousRasterSupport.exportDocument(ordered.first(), clips.sumOf { it.outputDurationMs }) else null,
            compositeLanes = if (target == MediaExportTarget.ORIGINAL) ordered.first().lanes else emptyList(),
        )
    }

    internal fun cropFor(segment: IndexedMediaSegment, target: MediaExportTarget): PixelCrop? {
        if (target == MediaExportTarget.ORIGINAL) return null
        require(segment.sourceRole == IndexedSourceRole.SURROUND) { "Direction export requires 360° input" }
        require(target in supportedTargets(listOf(segment))) {
            "This output is not available for the recording layout"
        }
        val targetIndex = when (target) {
            MediaExportTarget.FRONT -> 0
            MediaExportTarget.REAR -> 1
            MediaExportTarget.LEFT -> 2
            MediaExportTarget.RIGHT -> 3
            MediaExportTarget.TOP_LEFT -> 0
            MediaExportTarget.TOP_RIGHT -> 1
            MediaExportTarget.BOTTOM_LEFT -> 2
            MediaExportTarget.BOTTOM_RIGHT -> 3
            MediaExportTarget.ORIGINAL -> error("unreachable")
        }
        val sourceWidth = segment.originalWidth ?: 1280
        val sourceHeight = segment.originalHeight ?: 5140
        val lanes = segment.lanes.takeIf { it.size == 4 }
            ?: FourLaneLayoutClassifier.classify(sourceWidth, sourceHeight)?.lanes?.takeIf { it.size == 4 }
            ?: emptyList()
        val lane = lanes.sortedBy { it.displayOrder }.getOrNull(targetIndex)
        if (lane != null) {
            return PixelCrop(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                x0 = lane.x0,
                x1 = lane.x1,
                y0 = lane.y0,
                y1 = lane.y1,
            )
        }
        require(sourceHeight >= sourceWidth * 4) { "Unknown 360° lane layout" }
        val separator = ((sourceHeight - sourceWidth * 4) / 4).coerceAtLeast(0)
        val y0 = targetIndex * (sourceWidth + separator)
        return PixelCrop(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            x0 = 0,
            x1 = sourceWidth,
            y0 = y0,
            y1 = (y0 + sourceWidth).coerceAtMost(sourceHeight),
        )
    }

    internal fun validateFrozenInput(clip: PlannedExportClip, current: IndexedMediaSegment, measuredDurationMs: Long? = null) {
        clip.sessionBinding?.let { binding ->
            require(current.file.absolutePath == java.io.File(clip.filePath).absolutePath) { "SESSION_EXPORT_SOURCE_CHANGED" }
            binding.requireMatches(current)
            require(measuredDurationMs == clip.sourceDurationMs && current.raster ==
                (clip.raster?.let { RecordingRasterMetadata.Repacked(it) } ?: RecordingRasterMetadata.Original) &&
                current.lanes == clip.sourceLanes && ContinuousRasterSupport.error(current) == null) { "SESSION_EXPORT_SOURCE_CHANGED" }
        }
        val raster = clip.raster ?: return
        require(current.raster == RecordingRasterMetadata.Repacked(raster) &&
            current.lanes.sortedBy { it.lane } == clip.sourceLanes.sortedBy { it.lane } &&
            ContinuousRasterSupport.error(current) == null) { "RECORDING_LAYOUT_CHANGED" }
    }

    internal fun gapSummary(segments: List<IndexedMediaSegment>): Pair<Int, Long> {
        var count = 0
        var duration = 0L
        segments.sortedBy { it.startedAtEpochMs }.zipWithNext().forEach { (previous, next) ->
            val previousEnd = previous.stoppedAtEpochMs
                ?: (previous.startedAtEpochMs + previous.durationMs.coerceAtLeast(0L))
            val gap = next.startedAtEpochMs - previousEnd
            if (gap > 5_000L) {
                count++
                duration += gap
            }
        }
        return count to duration
    }

    private val DIRECTION_TARGETS = listOf(
        MediaExportTarget.ORIGINAL,
        MediaExportTarget.FRONT,
        MediaExportTarget.REAR,
        MediaExportTarget.LEFT,
        MediaExportTarget.RIGHT,
    )
    private val QUADRANT_TARGETS = listOf(
        MediaExportTarget.ORIGINAL,
        MediaExportTarget.TOP_LEFT,
        MediaExportTarget.TOP_RIGHT,
        MediaExportTarget.BOTTOM_LEFT,
        MediaExportTarget.BOTTOM_RIGHT,
    )
}
