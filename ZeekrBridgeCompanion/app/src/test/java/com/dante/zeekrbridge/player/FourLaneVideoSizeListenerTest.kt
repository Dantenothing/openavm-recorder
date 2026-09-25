package com.dante.zeekrbridge.player

import androidx.media3.common.VideoSize
import com.dante.zeekrbridge.core.ContinuousRasterSupport
import com.dante.zeekrbridge.core.IndexedLayoutKind
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import io.github.dantenothing.avmtransfer.protocol.StripRepackContract
import org.junit.Assert.*
import org.junit.Test

class FourLaneVideoSizeListenerTest {
    private val repacked = RecordingRasterMetadata.Repacked(StripRepackContract(
        inputWidth = 1280, inputHeight = 5140, stripHeight = 1728,
        encodedWidth = 3840, encodedHeight = 1728,
    ))

    @Test fun legacyStripSeekKeepsTheVerifiedSizeUntilTheNextSegmentIsReady() {
        val player = Playback()
        player.size(1280, 5140)
        player.size(0, 0)
        assertFalse("A seek must not pause playback or reject its raster", player.paused)
        assertEquals(listOf(1280 to 5140), player.rendererSizes)
        player.size(1280, 5140)
        assertEquals(1280 to 5140, player.rendererSizes.last())
        assertTrue(player.errors.isEmpty())
    }

    @Test fun legacyGridAlsoSurvivesRepeatedForwardAndBackwardSeekResets() {
        val player = Playback(layout = IndexedLayoutKind.FOUR_LANE_GRID_2X2)
        player.size(2560, 2560)
        repeat(4) {
            player.size(0, 0)
            player.listener.onRenderedFirstFrame()
            player.size(2560, 2560)
        }
        assertFalse(player.paused)
        assertTrue(player.errors.isEmpty())
        assertEquals(List(5) { 2560 to 2560 }, player.rendererSizes)
    }

    @Test fun repackedSegmentsUseTheSameSeekHandlingWithoutLegacyFallback() {
        val player = Playback(raster = repacked)
        player.size(3840, 1728)
        player.size(0, 0)
        player.size(3840, 1728)
        assertFalse(player.paused)
        assertEquals(listOf(3840 to 1728, 3840 to 1728), player.rendererSizes)
        assertTrue(player.errors.isEmpty())
    }

    @Test fun firstFrameCallbackWithAnUnknownGetterIsNotATrackFailure() {
        val player = Playback()
        player.listener.onRenderedFirstFrame()
        assertFalse(player.paused)
        assertTrue(player.rendererSizes.isEmpty())
        player.currentSize = VideoSize(1280, 5140)
        player.listener.onRenderedFirstFrame()
        assertEquals(listOf(1280 to 5140), player.rendererSizes)
        assertTrue(player.errors.isEmpty())
    }

    @Test fun incompleteSizeEventsCannotEraseKnownRendererDimensions() {
        val player = Playback()
        player.size(1280, 5140)
        player.size(0, 5140)
        player.size(1280, 0)
        assertEquals(listOf(1280 to 5140), player.rendererSizes)
        assertFalse(player.paused)
        assertTrue(player.errors.isEmpty())
    }

    @Test fun aRealLegacyLayoutMismatchAfterSeekingStillStopsPlayback() {
        val player = Playback()
        player.size(1280, 5140)
        player.size(0, 0)
        player.size(3840, 1728)
        assertTrue(player.paused)
        assertEquals(listOf("FOUR_LANE_TRACK_LAYOUT_UNSUPPORTED"), player.errors)
        assertEquals(listOf(1280 to 5140), player.rendererSizes)
    }

    @Test fun aRealRepackedSizeMismatchAfterSeekingStillStopsPlayback() {
        val player = Playback(raster = repacked)
        player.size(3840, 1728)
        player.size(0, 0)
        player.currentSize = VideoSize(1280, 5140)
        player.listener.onRenderedFirstFrame()
        assertTrue(player.paused)
        assertEquals(listOf("RASTER_TRACK_SIZE_MISMATCH"), player.errors)
        assertEquals(listOf(3840 to 1728), player.rendererSizes)
    }

    @Test fun missingFileGeometryStillFailsPreflightValidation() {
        for (raster in listOf(RecordingRasterMetadata.Original, repacked)) {
            assertEquals("VIDEO_TRACK_SIZE_UNAVAILABLE", ContinuousRasterSupport.trackError(
                raster, IndexedLayoutKind.FOUR_LANE_V1, 0, 0,
            ))
        }
    }

    @Test fun invalidRasterMetadataIsStillRejected() {
        val player = Playback(raster = RecordingRasterMetadata.Rejected("INVALID_RASTER_METADATA"))
        player.size(1280, 5140)
        assertTrue(player.paused)
        assertEquals(listOf("INVALID_RASTER_METADATA"), player.errors)
        assertTrue(player.rendererSizes.isEmpty())
    }

    private class Playback(
        raster: RecordingRasterMetadata = RecordingRasterMetadata.Original,
        layout: IndexedLayoutKind = IndexedLayoutKind.FOUR_LANE_V1,
    ) {
        var currentSize: VideoSize = VideoSize.UNKNOWN
        val rendererSizes = mutableListOf<Pair<Int, Int>>()
        val errors = mutableListOf<String>()
        var paused = false
        val listener = FourLaneVideoSizeListener(
            raster, layout, { currentSize },
            onValidSize = { width, height -> rendererSizes += width to height },
            onInvalidSize = { issue -> paused = true; errors += issue },
        )

        fun size(width: Int, height: Int) {
            currentSize = VideoSize(width, height)
            listener.onVideoSizeChanged(currentSize)
        }
    }
}
