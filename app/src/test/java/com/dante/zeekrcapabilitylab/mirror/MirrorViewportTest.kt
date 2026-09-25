package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorViewportTest {
    @Test fun standardViewCanPanAtDefaultZoomBecauseItsProjectionAlreadyCrops() {
        val moved = MirrorViewport().gesture(1f, .2f, -.1f, 1.25f)
        assertEquals(-.16f, moved.centerX, .0001f)
        assertEquals(.08f, moved.centerY, .0001f)
        assertEquals(1f, moved.zoom, .0001f)
    }
    @Test fun extremeGesturesStayInsideTheSourceAndZoomingOutClampsTheCenter() {
        val magnified = MirrorViewport().gesture(100f, -100f, 100f, 1.25f)
        assertEquals(3f, magnified.zoom, .0001f)
        val wide = magnified.gesture(.01f, 0f, 0f, 1.25f)
        assertEquals(1f, wide.zoom, .0001f)
        assertEquals(.2f, wide.centerX, .0001f)
        assertEquals(-.2f, wide.centerY, .0001f)
        assertEquals(MirrorViewport(), wide.sanitized(1f))
    }
    @Test fun storedNonFiniteValuesAndInvalidGestureCannotPoisonTheRenderer() {
        assertEquals(MirrorViewport(), MirrorViewport(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN).sanitized())
        val value = MirrorViewport(2f, .1f, -.1f)
        assertEquals(value, value.gesture(Float.NaN, 0f, 0f, 1.25f))
        assertEquals(value, value.gesture(0f, 0f, 0f, 1.25f))
    }
    @Test fun panningUndoesLetterboxRotationAndMirroringForEveryOrientation() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirrored in listOf(false, true)) {
            val p = MirrorGeometry.corners(400f, 230f, 1.2f, rotation, mirrored)
            // A known source displacement is projected to the screen, then recovered.
            val dx = (p[2] - p[0]) * .10f + (p[6] - p[0]) * -.15f
            val dy = (p[3] - p[1]) * .10f + (p[7] - p[1]) * -.15f
            val delta = MirrorPanTransform.sourceDelta(400f, 230f, 1.2f, rotation, mirrored, dx, dy)
            assertEquals(.2f, delta.first, .0001f)
            assertEquals(-.3f, delta.second, .0001f)
        }
    }
    @Test fun screenRightHasOppositeSourceDirectionWhenHorizontallyMirrored() {
        assertEquals(.2f, MirrorPanTransform.sourceDelta(200f, 100f, 1f, 0, false, 10f, 0f).first, .0001f)
        assertEquals(-.2f, MirrorPanTransform.sourceDelta(200f, 100f, 1f, 0, true, 10f, 0f).first, .0001f)
    }
}
