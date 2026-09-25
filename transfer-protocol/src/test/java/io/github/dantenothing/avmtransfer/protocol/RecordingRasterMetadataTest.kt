package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecordingRasterMetadataTest {
    private val layout = StripRepackContract(inputWidth = 1280, inputHeight = 5140,
        stripHeight = 1728, encodedWidth = 3840, encodedHeight = 1728)
    private fun sidecar(value: JsonElement = Json.encodeToJsonElement(layout)): String = buildJsonObject {
        put("rasterLayout", value)
        put("profile", buildJsonObject { put("size", buildJsonObject { put("width", 1280); put("height", 5140) }) })
    }.toString()

    @Test fun legacyFilesDoNotAcquireNewGeometryFromDimensions() {
        assertEquals(RecordingRasterMetadata.Original, RecordingRasterMetadata.read(null))
        assertEquals(RecordingRasterMetadata.Original, RecordingRasterMetadata.read("{\"actualTrack\":{\"width\":3840,\"height\":1728}}"))
        assertEquals(RecordingRasterMetadata.Original, RecordingRasterMetadata.read("{\"rasterLayout\":null}"))
    }

    @Test fun explicitLayoutRequiresAnActualMatchingTrack() {
        val metadata = RecordingRasterMetadata.read(sidecar())
        assertEquals(RecordingRasterMetadata.Repacked(layout), metadata)
        assertNull(metadata.trackError(3840, 1728))
        assertEquals("RASTER_TRACK_SIZE_MISMATCH", metadata.trackError(1280, 5140))
        assertEquals("VIDEO_TRACK_SIZE_UNAVAILABLE", metadata.trackError(0, 0))
    }

    @Test fun malformedOrUnknownLayoutNeverFallsBackToLegacy() {
        val descriptor = Json.encodeToJsonElement(layout).jsonObject
        for (bad in listOf(JsonPrimitive("unknown"), JsonObject(descriptor - "layout"),
            JsonObject(descriptor + ("version" to JsonPrimitive(8))),
            JsonObject(descriptor + ("coordinateOrigin" to JsonPrimitive("BOTTOM_LEFT"))))) {
            assertTrue(RecordingRasterMetadata.read(sidecar(bad)) is RecordingRasterMetadata.Rejected)
        }
        assertTrue(RecordingRasterMetadata.read("{broken") is RecordingRasterMetadata.Rejected)
    }

    @Test fun sourceCoordinatesCannotBeReplacedWithEncodedDimensions() {
        assertEquals(RecordingRasterMetadata.Rejected("RASTER_SOURCE_SIZE_MISMATCH"),
            RecordingRasterMetadata.read(sidecar().replace("\"width\":1280", "\"width\":3840")))
    }

    @Test fun diagnosticMetadataIsNotProductionPlaybackPermission() {
        assertEquals(RecordingRasterMetadata.Rejected("DIAGNOSTIC_VIDEO_NOT_PRODUCT_RECORDING"),
            RecordingRasterMetadata.read("{\"kind\":\"OPENAVM_CAMERA_DIAGNOSTIC_LAYOUT\"}"))
    }

    @Test fun unknownExtensionsAreRetainedWithoutRejectingKnownLayout() {
        val text = sidecar().dropLast(1) + ",\"futureNote\":true}"
        assertEquals(RecordingRasterMetadata.Repacked(layout), RecordingRasterMetadata.read(text))
    }

    @Test fun receiversMustExplicitlyAdvertisePlaybackAndExportCompatibility() {
        val legacyHealth = "{\"deviceName\":\"phone\",\"phoneDeviceId\":\"id\"}"
        val oldPhone = Json.decodeFromString<HealthResponse>(legacyHealth)
        val raster = RecordingRasterMetadata.read(sidecar())
        assertEquals("RECEIVER_RASTER_UPGRADE_REQUIRED", raster.receiverError(oldPhone.recordingRasterLayouts))
        assertNull(raster.receiverError(listOf(StripRepackContract.TRANSFER_CAPABILITY)))
        assertNull(RecordingRasterMetadata.Original.receiverError(emptyList()))
        assertNotNull(RecordingRasterMetadata.Rejected("INVALID").receiverError(listOf(StripRepackContract.TRANSFER_CAPABILITY)))
    }

    @Test fun timelineUsesExclusiveSourceBoundaryRatherThanFinishWallTime() {
        val first = ContinuousSegmentTimeline(runId = "run", firstPtsUs = 0,
            lastPtsUs = 59_966_667, endExclusivePtsUs = 60_000_000, frames = 1800, startsWithKeyFrame = true)
        assertTrue(first.validate().isEmpty())
        assertTrue(first.copy(endExclusivePtsUs = first.lastPtsUs).validate().isNotEmpty())
        assertTrue(first.copy(startsWithKeyFrame = false).validate().isNotEmpty())
        assertTrue(first.copy(frames = 1).validate().isNotEmpty())
        assertTrue(first.copy(firstPtsUs = -1).validate().isNotEmpty())
        assertTrue(first.copy(version = 2).validate().isNotEmpty())
    }
}
