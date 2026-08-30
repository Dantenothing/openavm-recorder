package com.dante.zeekrbridge.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SurroundPlaybackDiagnosticTest {

    @Test
    fun rawOutputWithoutPlayerFirstFramePointsToDecoderBoundary() {
        assertEquals(
            SurroundPlaybackBoundary.DECODER_OR_OUTPUT,
            classifySurroundPlayback(
                outputPath = SurroundOutputPath.RAW_SURFACE,
                playerFirstFrames = 0,
                surfaceTextureFrames = 0,
                glFirstFrame = false,
            ),
        )
    }

    @Test
    fun rawOutputWithPlayerFirstFrameProvesDecoderCanRenderFile() {
        assertEquals(
            SurroundPlaybackBoundary.RENDERED,
            classifySurroundPlayback(
                outputPath = SurroundOutputPath.RAW_SURFACE,
                playerFirstFrames = 1,
                surfaceTextureFrames = 0,
                glFirstFrame = false,
            ),
        )
    }

    @Test
    fun glOutputNarrowsFailureAcrossSurfaceAndDrawBoundaries() {
        assertEquals(
            SurroundPlaybackBoundary.SURFACE_TEXTURE_DELIVERY,
            classifySurroundPlayback(SurroundOutputPath.GL_CROPPED, 1, 0, false),
        )
        assertEquals(
            SurroundPlaybackBoundary.GL_DRAW,
            classifySurroundPlayback(SurroundOutputPath.GL_CROPPED, 1, 4, false),
        )
        assertEquals(
            SurroundPlaybackBoundary.RENDERED,
            classifySurroundPlayback(SurroundOutputPath.GL_CROPPED, 1, 4, true),
        )
    }
}
