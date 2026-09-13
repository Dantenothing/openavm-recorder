package com.dante.zeekrbridge.player

import org.junit.Assert.*
import org.junit.Test

class VideoDisplayGeometryTest {
    @Test fun exportedSingleViewStaysSquare() {
        assertEquals(1f, VideoDisplayGeometry(1280, 1280).aspectRatio()!!, 0.0001f)
    }
    @Test fun landscapeAndPortraitKeepTheirDisplayShape() {
        assertEquals(16f / 9f, VideoDisplayGeometry(1920, 1080).aspectRatio()!!, 0.0001f)
        assertEquals(9f / 16f, VideoDisplayGeometry(1080, 1920).aspectRatio()!!, 0.0001f)
    }
    @Test fun metadataRotationIsAppliedOnce() {
        for (rotation in listOf(90, 270, -90, 450)) {
            assertEquals(9f / 16f, VideoDisplayGeometry(1920, 1080, metadataRotationDegrees = rotation).aspectRatio()!!, 0.0001f)
        }
        assertEquals(16f / 9f, VideoDisplayGeometry(1920, 1080, metadataRotationDegrees = 180).aspectRatio()!!, 0.0001f)
        // Media3 has already rotated the decoded dimensions; do not apply metadata to them again.
        assertEquals(9f / 16f, VideoDisplayGeometry(1080, 1920).aspectRatio()!!, 0.0001f)
    }
    @Test fun anamorphicPixelsAndInvalidSizes() {
        assertEquals(4f / 3f, VideoDisplayGeometry(720, 576, 16f / 15f).aspectRatio()!!, 0.0001f)
        assertNull(VideoDisplayGeometry(0, 1280).aspectRatio())
        assertNull(VideoDisplayGeometry(1280, -1).aspectRatio())
        for (ratio in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertEquals(1f, VideoDisplayGeometry(1280, 1280, ratio).aspectRatio()!!, 0.0001f)
        }
    }
    @Test fun playlistTransitionCannotReusePreviousGeometry() {
        val square = PlaybackDisplaySize("square", VideoDisplayGeometry(1280, 1280))
        assertEquals(1f, square.aspectRatioFor("square")!!, 0.0001f)
        assertNull(square.aspectRatioFor("portrait"))
        val portrait = PlaybackDisplaySize("portrait", VideoDisplayGeometry(1080, 1920))
        assertEquals(9f / 16f, portrait.aspectRatioFor("portrait")!!, 0.0001f)
    }
}
