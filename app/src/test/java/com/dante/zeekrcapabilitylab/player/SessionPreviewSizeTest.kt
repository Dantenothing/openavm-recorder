package com.dante.zeekrcapabilitylab.player

import org.junit.Assert.*
import org.junit.Test

class SessionPreviewSizeTest {
    @Test fun surroundStaysSquareInWideAndTallPanes() {
        assertEquals(SessionPreviewSize(360f, 360f), fitSessionPreview(900f, 360f, 1f))
        assertEquals(SessionPreviewSize(280f, 280f), fitSessionPreview(280f, 700f, 1f))
    }

    @Test fun cabinFitsItsRecordedAspectWithoutStretchOrCropping() {
        val wide = fitSessionPreview(900f, 360f, sessionVideoAspect(1920, 1080, 0))
        assertEquals(640f, wide.width, .01f)
        assertEquals(360f, wide.height, .01f)
        val tall = fitSessionPreview(320f, 700f, sessionVideoAspect(640, 480, 0))
        assertEquals(320f, tall.width, .01f)
        assertEquals(240f, tall.height, .01f)
    }

    @Test fun rotatedPortraitAndSmallOverviewRespectTheirBounds() {
        val portrait = fitSessionPreview(320f, 240f, sessionVideoAspect(1920, 1080, 90))
        assertEquals(135f, portrait.width, .01f)
        assertEquals(240f, portrait.height, .01f)
        assertEquals(SessionPreviewSize(90f, 90f), fitSessionPreview(500f, 90f, 1f))
    }

    @Test fun unavailableMetadataAndZeroConstraintsHaveFiniteFallbacks() {
        assertEquals(16f / 9f, sessionVideoAspect(0, 0, 0), .001f)
        assertEquals(SessionPreviewSize(0f, 0f), fitSessionPreview(0f, 100f, 1f))
        assertEquals(SessionPreviewSize(100f, 100f), fitSessionPreview(100f, 100f, Float.NaN))
    }
}
