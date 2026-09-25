package com.dante.zeekrbridge.core

import io.github.dantenothing.avmtransfer.protocol.PixelRectangle
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract
import kotlinx.serialization.json.*

/** Only explicit layout plus frozen logical lane coordinates may enable repacked playback/export. */
internal object ContinuousRasterSupport {
    fun trackError(raster: RecordingRasterMetadata, layout: IndexedLayoutKind,
        width: Int, height: Int, rotation: Int = 0): String? {
        raster.trackError(width, height)?.let { return it }
        if (raster is RecordingRasterMetadata.Repacked && rotation % 360 != 0)
            return "RASTER_TRACK_ROTATION_UNSUPPORTED"
        if (raster == RecordingRasterMetadata.Original && layout.isFourLane &&
            FourLaneLayoutClassifier.classify(width, height)?.kind != layout)
            return "FOUR_LANE_TRACK_LAYOUT_UNSUPPORTED"
        return null
    }

    fun error(segment: IndexedMediaSegment): String? = error(
        segment.raster, segment.sourceRole, segment.layoutKind, segment.lanes,
        segment.originalWidth, segment.originalHeight,
    )

    fun error(
        raster: RecordingRasterMetadata,
        source: IndexedSourceRole,
        layout: IndexedLayoutKind,
        lanes: List<IndexedLane>,
        width: Int?, height: Int?,
    ): String? {
        if (raster is RecordingRasterMetadata.Rejected) return raster.reason
        val contract = (raster as? RecordingRasterMetadata.Repacked)?.contract ?: return null
        contract.validate().firstOrNull()?.let { return it }
        if (source != IndexedSourceRole.SURROUND || layout != IndexedLayoutKind.FOUR_LANE_V1)
            return "RASTER_CAMERA_LAYOUT_MISMATCH"
        if (width != contract.inputWidth || height != contract.inputHeight) return "RASTER_SOURCE_SIZE_MISMATCH"
        if (lanes.size != 4 || lanes.map { it.lane }.toSet() != setOf(1, 2, 3, 4) ||
            lanes.map { it.displayOrder }.toSet() != setOf(1, 2, 3, 4)) return "RASTER_LANES_UNAVAILABLE"
        if (lanes.any { !it.rectangle().within(contract.inputWidth, contract.inputHeight) })
            return "RASTER_LANE_OUT_OF_BOUNDS"
        return null
    }

    fun exportDocument(segment: IndexedMediaSegment, durationMs: Long): String? {
        val contract = (segment.raster as? RecordingRasterMetadata.Repacked)?.contract ?: return null
        require(error(segment) == null) { error(segment).orEmpty() }
        return buildJsonObject {
            put("kind", "OPENAVM_RECORDING_EXPORT")
            put("sourceRole", segment.sourceRole.name)
            put("layoutKind", segment.layoutKind.name)
            put("recordingMode", segment.recordingMode.name)
            put("sourceStartedAtEpochMs", segment.startedAtEpochMs)
            put("rasterLayout", Json.encodeToJsonElement(StripRepackContract.serializer(), contract))
            putJsonObject("actualTrack") {
                put("width", contract.encodedWidth); put("height", contract.encodedHeight)
                put("durationMs", durationMs)
            }
            putJsonObject("laneLayout") {
                put("originalWidth", contract.inputWidth); put("originalHeight", contract.inputHeight)
                putJsonArray("lanes") {
                    segment.lanes.forEach { lane ->
                        addJsonObject {
                            put("lane", lane.lane); put("label", lane.label); put("displayOrder", lane.displayOrder)
                            put("x0", lane.x0); put("x1", lane.x1); put("y0", lane.y0); put("y1", lane.y1)
                        }
                    }
                }
            }
        }.toString()
    }
}

internal fun IndexedLane.rectangle() = PixelRectangle(x0, y0, x1 - x0, y1 - y0)
