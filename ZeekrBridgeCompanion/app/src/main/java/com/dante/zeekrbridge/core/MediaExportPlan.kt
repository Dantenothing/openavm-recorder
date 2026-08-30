package com.dante.zeekrbridge.core

import kotlin.math.max

enum class MediaExportTarget(val fileSuffix: String) {
    ORIGINAL("360"),
    FRONT("Front"),
    REAR("Rear"),
    LEFT("Left"),
    RIGHT("Right"),
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
) {
    val outputDurationMs: Long = clips.sumOf { it.outputDurationMs }
    val requiresVideoProcessing: Boolean = target != MediaExportTarget.ORIGINAL
}

object MediaExportPlanner {
    fun supportedTargets(sourceRole: IndexedSourceRole): List<MediaExportTarget> =
        if (sourceRole == IndexedSourceRole.SURROUND) MediaExportTarget.entries
        else listOf(MediaExportTarget.ORIGINAL)

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
        require(ordered.all { it.sourceRole == sourceRole }) { "A single export cannot mix camera sources" }
        require(target in supportedTargets(sourceRole)) { "Direction export requires a 360° recording" }
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
        )
    }

    internal fun cropFor(segment: IndexedMediaSegment, target: MediaExportTarget): PixelCrop? {
        if (target == MediaExportTarget.ORIGINAL) return null
        require(segment.sourceRole == IndexedSourceRole.SURROUND) { "Direction export requires 360° input" }
        val targetIndex = when (target) {
            MediaExportTarget.FRONT -> 0
            MediaExportTarget.REAR -> 1
            MediaExportTarget.LEFT -> 2
            MediaExportTarget.RIGHT -> 3
            MediaExportTarget.ORIGINAL -> error("unreachable")
        }
        val sourceWidth = segment.originalWidth ?: 1280
        val sourceHeight = segment.originalHeight ?: 5140
        val lane = segment.lanes.sortedBy { it.displayOrder }.getOrNull(targetIndex)
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
}
