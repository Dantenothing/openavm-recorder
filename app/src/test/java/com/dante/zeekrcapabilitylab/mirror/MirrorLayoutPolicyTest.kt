package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorLayoutPolicyTest {
    @Test fun unconfirmedOrDuplicateDirectionsCannotBecomeTriple() {
        assertNull(MirrorLayoutPolicy.triple(0, 2, 3))
        assertNull(MirrorLayoutPolicy.triple(1, 1, 3))
        assertEquals(listOf(3, 1, 4), MirrorLayoutPolicy.triple(3, 1, 4))
    }
    @Test fun touchSelectionMatchesQuarterHalfQuarterLayout() {
        assertEquals(listOf(0, 1, 1, 2), listOf(0f, 0.25f, 0.74f, 1f).map(MirrorLayoutPolicy::panelAt))
    }
    @Test fun positionsSurviveInvalidStoredValuesAndDockOnBothEdges() {
        assertEquals(0f, MirrorLayoutPolicy.clamp(Float.NaN))
        assertEquals(1f, MirrorLayoutPolicy.clamp(2f))
        assertEquals(-1, MirrorLayoutPolicy.dockSide(-20, 460, 1920, 24))
        assertEquals(1, MirrorLayoutPolicy.dockSide(1500, 460, 1920, 24))
        assertEquals(0, MirrorLayoutPolicy.dockSide(100, 460, 1920, 24))
    }
}
