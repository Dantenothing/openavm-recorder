package com.dante.zeekrbridge.core

import java.io.File
import java.nio.ByteBuffer
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContinuousRasterCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun usbStemSidecarIsRecognizedWithoutRenamingTheVideo() {
        val video = temp.newFile("usb.mp4")
        File(temp.root, "usb.sidecar.json").writeText("""{
            "sourceRole":"SURROUND", "layoutKind":"FOUR_LANE_V1",
            "profile":{"size":{"width":1280,"height":5140}},
            "actualTrack":{"durationMs":60000}
        }""")
        val segment = MediaIndexScanner.readSegment(video)
        assertEquals(IndexedSourceRole.SURROUND, segment.sourceRole)
        assertEquals(60000L, segment.durationMs)
    }

    private val raster = StripRepackContract(inputWidth = 1280, inputHeight = 5140,
        stripHeight = 1728, encodedWidth = 3840, encodedHeight = 1728)

    private fun recording(name: String = "camera"): IndexedMediaSegment {
        val file = temp.newFile("$name.mp4")
        // Minimal box structure; these tests validate metadata, not video decoding.
        file.writeBytes(ByteBuffer.allocate(24).putInt(16).put("ftyp".toByteArray()).put("isom0000".toByteArray())
            .putInt(8).put("moov".toByteArray()).array())
        val sidecar = buildJsonObject {
            put("sourceRole", "SURROUND"); put("layoutKind", "FOUR_LANE_V1"); put("startedAtEpochMs", 1000L)
            put("rasterLayout", Json.encodeToJsonElement(raster))
            putJsonObject("actualTrack") { put("durationMs", 60000L) }
            putJsonObject("laneLayout") {
                put("originalWidth", 1280); put("originalHeight", 5140)
                putJsonArray("lanes") {
                    listOf(4, 1288, 2572, 3856).forEachIndexed { index, top ->
                        addJsonObject {
                            put("lane", index + 1); put("displayOrder", index + 1); put("label", "View ${index + 1}")
                            put("x0", 0); put("x1", 1280); put("y0", top); put("y1", top + 1280)
                        }
                    }
                }
            }
        }.toString()
        File(file.absolutePath + ".sidecar.json").writeText(sidecar)
        return MediaIndexScanner.readSegment(file)
    }

    @Test fun storedGeometryDoesNotReplaceLogicalSourceDimensions() {
        val segment = recording()
        assertEquals(RecordingRasterMetadata.Repacked(raster), segment.raster)
        assertNull(ContinuousRasterSupport.error(segment))
        assertEquals(1280, segment.originalWidth)
        assertEquals(5140, segment.originalHeight)
        assertNull(segment.raster.trackError(3840, 1728))
        assertNotNull(segment.raster.trackError(1280, 5140))
    }

    @Test fun everyDirectionPreservesRowsAcrossBothStorageSeams() {
        val segment = recording()
        val targets = listOf(MediaExportTarget.FRONT, MediaExportTarget.REAR, MediaExportTarget.LEFT, MediaExportTarget.RIGHT)
        val heights = listOf(listOf(1280), listOf(440, 840), listOf(884, 396), listOf(1280))
        targets.forEachIndexed { index, target ->
            val plan = MediaExportPlanner.build(listOf(segment), target, 1000, 2000)
            assertNull(plan.embeddedMetadata)
            val clip = plan.clips.single()
            assertEquals(raster, clip.raster)
            val crop = clip.crop!!
            val pieces = raster.cropPieces(PixelRectangle(crop.x0, crop.y0, crop.x1 - crop.x0, crop.y1 - crop.y0))
            assertEquals(heights[index], pieces.map { it.destination.height })
            assertEquals(1280, pieces.sumOf { it.destination.height })
            for (row in 0 until 1280) {
                val piece = pieces.single { row in it.destination.top until it.destination.top + it.destination.height }
                val encodedRow = piece.encoded.top + row - piece.destination.top
                for (x in listOf(0, 1, 639, 1278, 1279)) {
                    assertEquals(RasterPixel(x, crop.y0 + row), raster.toSource(piece.encoded.left + x, encodedRow))
                }
            }
        }
    }

    @Test fun originalExportCarriesMetadataWhenTheSidecarIsNotTransferred() {
        val segment = recording()
        val plan = MediaExportPlanner.build(listOf(segment), MediaExportTarget.ORIGINAL, 1000, 2000)
        assertNull(plan.clips.single().crop)
        assertNotNull(plan.embeddedMetadata)
        val copied = temp.newFile("exported.mp4")
        copied.writeBytes(segment.file.readBytes() + EmbeddedRecordingMetadata.box(plan.embeddedMetadata!!))
        val imported = MediaIndexScanner.readSegment(copied)
        assertEquals(segment.raster, imported.raster)
        assertEquals(segment.lanes, imported.lanes)
        assertEquals(1000L, imported.durationMs)
        assertNull(ContinuousRasterSupport.error(imported))
    }

    @Test fun missingOrConflictingGeometryCannotFallBackToLegacyCropping() {
        val segment = recording()
        listOf(segment.copy(lanes = emptyList()), segment.copy(originalHeight = 1728),
            segment.copy(sourceRole = IndexedSourceRole.CABIN),
            segment.copy(raster = RecordingRasterMetadata.Rejected("UNSUPPORTED_STRIP_LAYOUT")),
            segment.copy(lanes = segment.lanes.map { it.copy(y1 = 99999) })).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaExportPlanner.build(listOf(invalid), MediaExportTarget.REAR)
            }
        }
    }

    @Test fun mixedRasterAndChangedCalibrationsAreRejectedForAnOriginalPlaylistExport() {
        val segment = recording()
        val second = recording("second")
        listOf(second.copy(raster = RecordingRasterMetadata.Original),
            second.copy(lanes = second.lanes.map { it.copy(x0 = 1) })).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) {
                MediaExportPlanner.build(listOf(segment, changed), MediaExportTarget.ORIGINAL)
            }
        }
    }

    @Test fun malformedSidecarIsNotAnOrdinaryRecordingAndDimensionsAloneDoNotSelectRepacking() {
        val segment = recording()
        File(segment.sidecarPath!!).writeText("{broken")
        assertTrue(MediaIndexScanner.readSegment(segment.file).raster is RecordingRasterMetadata.Rejected)
        assertNull(FourLaneLayoutClassifier.classify(3840, 1728))
    }

    @Test fun queuedExportRejectsAChangedSourceLayoutBeforeStartingTheDecoder() {
        val segment = recording()
        val clip = MediaExportPlanner.build(listOf(segment), MediaExportTarget.REAR).clips.single()
        MediaExportPlanner.validateFrozenInput(clip, segment)
        assertThrows(IllegalArgumentException::class.java) {
            MediaExportPlanner.validateFrozenInput(clip, segment.copy(lanes = segment.lanes.map { it.copy(x0 = 1) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaExportPlanner.validateFrozenInput(clip, segment.copy(sourceRole = IndexedSourceRole.CABIN))
        }
    }

    @Test fun missingDescriptorCannotRenderARepackedTrackThroughTheLegacyFourLanePath() {
        assertNotNull(ContinuousRasterSupport.trackError(RecordingRasterMetadata.Original,
            IndexedLayoutKind.FOUR_LANE_V1, 3840, 1728))
        assertNull(ContinuousRasterSupport.trackError(RecordingRasterMetadata.Original,
            IndexedLayoutKind.FOUR_LANE_V1, 1280, 5140))
        assertNull(ContinuousRasterSupport.trackError(RecordingRasterMetadata.Original,
            IndexedLayoutKind.FOUR_LANE_GRID_2X2, 2560, 2560))
        assertNotNull(ContinuousRasterSupport.trackError(RecordingRasterMetadata.Repacked(raster),
            IndexedLayoutKind.FOUR_LANE_V1, 3840, 1728, rotation = 180))
    }
}
