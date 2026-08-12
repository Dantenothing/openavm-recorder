package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.ui.product.FourLaneDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class FourLaneDisplayModeTest {
    @Test
    fun tappingLaneEnlargesItAndTappingAgainReturnsToGrid() {
        val enlarged = FourLaneDisplayMode.FOUR_GRID.toggleLane(3)

        assertEquals(FourLaneDisplayMode.LANE_3, enlarged)
        assertEquals(FourLaneDisplayMode.FOUR_GRID, enlarged.toggleLane(3))
    }
}
