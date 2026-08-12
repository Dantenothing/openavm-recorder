package com.dante.zeekrcapabilitylab.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FourLaneCanvasLayoutTest {

    @Test
    fun tallCompositeIsRearrangedIntoFourSquareDestinations() {
        val contentTop = 375f
        val contentHeight = 250f
        val plan = FourLaneCanvasLayout.plan(
            videoWidth = 1280,
            videoHeight = 5140,
            contentLeft = 0f,
            contentTop = contentTop,
            contentWidth = 1000f,
            contentHeight = contentHeight,
            destinationWidth = 1000f,
            destinationHeight = 1000f,
        )

        assertEquals(listOf(1, 2, 3, 4), plan.map { it.lane })
        assertEquals(
            listOf(
                FloatBounds(0f, 0f, 500f, 500f),
                FloatBounds(500f, 0f, 1000f, 500f),
                FloatBounds(0f, 500f, 500f, 1000f),
                FloatBounds(500f, 500f, 1000f, 1000f),
            ),
            plan.map { it.destination },
        )

        val expectedStarts = listOf(4f, 1288f, 2572f, 3856f)
        plan.forEachIndexed { index, draw ->
            assertEquals(0f, draw.source.left, 0.001f)
            assertEquals(1000f, draw.source.right, 0.001f)
            assertEquals(
                contentTop + contentHeight * expectedStarts[index] / 5140f,
                draw.source.top,
                0.001f,
            )
            assertEquals(
                contentHeight * 1280f / 5140f,
                draw.source.height,
                0.001f,
            )
        }
    }

    @Test
    fun selectedLaneKeepsItsCropAndFillsTheWholeDestination() {
        val grid = FourLaneCanvasLayout.plan(
            videoWidth = 1280,
            videoHeight = 5140,
            contentLeft = 120f,
            contentTop = 300f,
            contentWidth = 800f,
            contentHeight = 400f,
            destinationWidth = 1200f,
            destinationHeight = 1200f,
        )
        val enlarged = FourLaneCanvasLayout.planSingle(
            videoWidth = 1280,
            videoHeight = 5140,
            contentLeft = 120f,
            contentTop = 300f,
            contentWidth = 800f,
            contentHeight = 400f,
            destinationWidth = 1200f,
            destinationHeight = 1200f,
            lane = 3,
        )

        assertEquals(grid[2].source, enlarged.source)
        assertEquals(FloatBounds(0f, 0f, 1200f, 1200f), enlarged.destination)
    }
}
