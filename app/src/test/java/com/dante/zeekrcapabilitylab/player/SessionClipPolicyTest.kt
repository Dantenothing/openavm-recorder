package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionClipPolicyTest {
    private val anchor = VideoClockAnchor(elapsedRealtimeNs = 1_000_000_000, source = "CALLBACK_OBSERVED")
    private val cut = ContinuousSegmentTimeline(runId = "session", firstPtsUs = 0, lastPtsUs = 900_000,
        endExclusivePtsUs = 1_000_000, frames = 10, startsWithKeyFrame = true)
    private val contract = StripRepackContract(inputWidth = 64, inputHeight = 256, stripHeight = 128, encodedWidth = 128, encodedHeight = 128)
    private fun source() = SegmentSidecar(file = "/private/file.mp4", cameraId = "private-camera", processStartId = "private-process",
        profile = CameraFormatProfile(ProfileSize(64, 256), 128_000), segmentSeconds = 1, segmentNumber = 1,
        recordingSessionId = "session", channelId = "SURROUND", startedAtElapsedRealtimeMs = 1_000,
        stoppedAtElapsedRealtimeMs = 2_000, videoClockAnchor = anchor, continuousTimeline = cut, result = "SUCCESS",
        actualTrack = ActualTrackInfo(width = 128, height = 128, durationMs = 1_000),
        laneLayout = SegmentLaneLayout(64, 256, "VERTICAL_1X4", (1..4).map {
            SegmentLaneInfo(it, 0, 64, (it - 1) * 64, it * 64, "View $it", rotationDegrees = 90, displayOrder = 5 - it)
        }), rasterLayout = Json.encodeToJsonElement(StripRepackContract.serializer(), contract))
    private fun frozen() = SessionVideoSegment("file", "SURROUND", "session", 1_000, 2_000, 1_000, anchor, cut)

    @Test fun normalizedDocumentPreservesCompleteDisplayContractWithoutPrivateOrUnknownFields() {
        val sidecar = source().copy(rasterLayout = JsonObject(source().rasterLayout!!.jsonObject +
            ("unknownPrivateGps" to buildJsonObject { put("latitude", 12.345); put("file", "PRIVATE_SENTINEL") })))
        val document = SessionClipPolicy.raster(sidecar)!!.document(800)
        val obj = Json.parseToJsonElement(document).jsonObject
        assertEquals(RecordingRasterMetadata.Repacked(contract), RecordingRasterMetadata.read(document))
        assertEquals(setOf("kind", "sourceRole", "layoutKind", "recordingMode", "timeLapseMultiplier", "rasterLayout", "actualTrack", "laneLayout"), obj.keys)
        assertEquals(Json.encodeToJsonElement(SegmentLaneLayout.serializer(), source().laneLayout!!), obj["laneLayout"])
        assertEquals(800L, obj["actualTrack"]!!.jsonObject["durationMs"]!!.jsonPrimitive.long)
        listOf("PRIVATE_SENTINEL", "private-camera", "private-process", "/private/", "latitude", "recordingSessionId", "videoClockAnchor")
            .forEach { assertFalse(it, document.contains(it)) }
    }

    @Test fun missingOrInvalidLaneCalibrationCannotCreateApparentlyPlayableRepackedExport() {
        val value = source()
        val layout = value.laneLayout!!
        listOf(value.copy(laneLayout = null), value.copy(laneLayout = layout.copy(lanes = layout.lanes.take(3))),
            value.copy(laneLayout = layout.copy(lanes = layout.lanes.map { it.copy(displayOrder = 1) })),
            value.copy(laneLayout = layout.copy(lanes = layout.lanes.map { it.copy(x1 = 65) })),
            value.copy(laneLayout = layout.copy(lanes = layout.lanes.map { it.copy(rotationDegrees = 45) })))
            .forEach { assertThrows(IllegalArgumentException::class.java) { SessionClipPolicy.raster(it) } }
    }

    @Test fun frozenChannelTimeAnchorAndContinuousCutMustAllStillMatch() {
        SessionClipPolicy.requireBinding(source(), frozen(), "SURROUND")
        val value = source()
        listOf(value.copy(channelId = "FRONT_CABIN"), value.copy(sourceRole = RecordingSourceRole.CABIN),
            value.copy(recordingSessionId = "other"), value.copy(startedAtElapsedRealtimeMs = 1_001),
            value.copy(stoppedAtElapsedRealtimeMs = 2_001), value.copy(videoClockAnchor = anchor.copy(elapsedRealtimeNs = 1_001_000_000)),
            value.copy(continuousTimeline = cut.copy(endExclusivePtsUs = 1_001_000)), value.copy(provisional = true))
            .forEach { assertThrows(IllegalArgumentException::class.java) { SessionClipPolicy.requireBinding(it, frozen(), "SURROUND") } }
    }

    @Test fun legacyChannelFallbackIsAllowedOnlyForMatchingRole() {
        SessionClipPolicy.requireBinding(source().copy(channelId = null), frozen(), "SURROUND")
        assertThrows(IllegalArgumentException::class.java) { SessionClipPolicy.requireBinding(source().copy(channelId = null), frozen(), "FRONT_CABIN") }
    }

    @Test fun containerBindingAllowsOnlyItsFloorOrCeilingMilliseconds() {
        SessionClipPolicy.requireDuration(1_000, 1_001, 1_000_900)
        SessionClipPolicy.requireDuration(1_001, null, 1_000_900)
        assertThrows(IllegalArgumentException::class.java) { SessionClipPolicy.requireDuration(1_002, 1_001, 1_000_900) }
        assertThrows(IllegalArgumentException::class.java) { SessionClipPolicy.requireDuration(1_000, 999, 1_000_900) }
        assertThrows(IllegalArgumentException::class.java) { SessionClipPolicy.requireDuration(1_001, null, 1_000_000) }
    }

    @Test fun geometryOrCalibrationChangeChangesCompatibility() {
        val value = source()
        assertNotEquals(SessionClipPolicy.raster(value), SessionClipPolicy.raster(value.copy(laneLayout = value.laneLayout!!.copy(
            lanes = value.laneLayout!!.lanes.map { it.copy(rotationDegrees = 180) }))))
    }
}
