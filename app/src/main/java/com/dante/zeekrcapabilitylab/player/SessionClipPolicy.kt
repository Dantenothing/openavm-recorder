package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.service.recorder.*
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*

/** Source facts are checked against the frozen selection, never against rounded UI boundaries. */
internal object SessionClipPolicy {
    fun requireBinding(sidecar: SegmentSidecar, expected: SessionVideoSegment, channel: String) {
        val roleChannel = SessionPlaybackTimeline.channelForRole(sidecar.sourceRole.name)
        require(expected.channelId == channel && roleChannel == channel &&
            (sidecar.channelId == null || sidecar.channelId == channel) &&
            sidecar.recordingSessionId == expected.recordingSessionId &&
            sidecar.recordingMode == RecordingMode.NORMAL && sidecar.timeLapseMultiplier == 1 && !sidecar.provisional &&
            sidecar.startedAtElapsedRealtimeMs == expected.startElapsedMs &&
            sidecar.stoppedAtElapsedRealtimeMs == expected.endElapsedMs &&
            sidecar.videoClockAnchor == expected.anchor && sidecar.continuousTimeline == expected.continuousTimeline) {
            "CLIP_SOURCE_CHANGED"
        }
    }

    /** Android duration readers expose integer milliseconds with either floor or ceiling rounding. */
    fun requireDuration(expectedMs: Long, sidecarMs: Long?, containerUs: Long) {
        require(containerUs > 0 && containerUs <= SessionPlaybackTimeline.MAX_SESSION_MS * 1000) { "CLIP_DURATION_UNAVAILABLE" }
        val possibleMs = (containerUs / 1000)..((containerUs + 999) / 1000)
        require(expectedMs in possibleMs && (sidecarMs == null || sidecarMs in possibleMs)) { "CLIP_SOURCE_DURATION_CHANGED" }
    }

    fun raster(sidecar: SegmentSidecar): SessionClipRaster? {
        val raw = SegmentSidecarIO.json.encodeToString(SegmentSidecar.serializer(), sidecar)
        val raster = RecordingRasterMetadata.read(raw)
        require(raster !is RecordingRasterMetadata.Rejected) { "CLIP_LAYOUT_UNSUPPORTED" }
        val contract = (raster as? RecordingRasterMetadata.Repacked)?.contract ?: return null
        val lanes = requireNotNull(sidecar.laneLayout) { "CLIP_LANES_UNAVAILABLE" }
        require(sidecar.sourceRole == RecordingSourceRole.SURROUND && sidecar.layoutKind == RecordingLayoutKind.FOUR_LANE_V1 &&
            lanes.originalWidth == contract.inputWidth && lanes.originalHeight == contract.inputHeight &&
            lanes.layoutType in setOf("HORIZONTAL_4X1", "VERTICAL_1X4") &&
            lanes.lanes.size == 4 && lanes.lanes.map { it.lane }.toSet() == setOf(1, 2, 3, 4) &&
            lanes.lanes.map { it.displayOrder }.toSet() == setOf(1, 2, 3, 4) && lanes.lanes.all {
                it.x0 >= 0 && it.y0 >= 0 && it.x1 > it.x0 && it.y1 > it.y0 &&
                    it.x1 <= contract.inputWidth && it.y1 <= contract.inputHeight &&
                    it.rotationDegrees in setOf(0, 90, 180, 270) && it.label.length in 1..128 && it.label.none(Char::isISOControl)
            }) { "CLIP_LANES_UNSUPPORTED" }
        return SessionClipRaster(contract, lanes.copy(lanes = lanes.lanes.sortedBy { it.lane }))
    }
}

/** Only the display contract is exported. No raw sidecar/JSON, identity, clock, event, or drive data. */
internal data class SessionClipRaster(val contract: StripRepackContract, val lanes: SegmentLaneLayout) {
    fun document(durationMs: Long): String {
        require(durationMs > 0)
        return buildJsonObject {
            put("kind", "OPENAVM_RECORDING_EXPORT")
            put("sourceRole", "SURROUND"); put("layoutKind", "FOUR_LANE_V1")
            put("recordingMode", "NORMAL"); put("timeLapseMultiplier", 1)
            put("rasterLayout", Json.encodeToJsonElement(StripRepackContract.serializer(), contract))
            putJsonObject("actualTrack") {
                put("width", contract.encodedWidth); put("height", contract.encodedHeight); put("durationMs", durationMs)
            }
            put("laneLayout", Json.encodeToJsonElement(SegmentLaneLayout.serializer(), lanes))
        }.toString()
    }
}
