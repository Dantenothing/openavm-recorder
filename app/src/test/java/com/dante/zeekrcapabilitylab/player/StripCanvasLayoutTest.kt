package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.StripRepackContract
import org.junit.Assert.*
import org.junit.Test

class StripCanvasLayoutTest {
    private val layout = StripRepackContract(inputWidth = 1280, inputHeight = 5140,
        stripHeight = 1728, encodedWidth = 3840, encodedHeight = 1728)

    @Test fun restoresEveryPixelCenterIncludingBothSeamsAndLastRow() {
        for (child in listOf(FloatBounds(0f, 0f, 1280f, 5140f), FloatBounds(17f, 31f, 812f, 429f))) {
            val pieces = StripCanvasLayout.plan(layout, child)
            assertEquals(3, pieces.size)
            for (row in 0 until 5140) {
                for (column in listOf(0, 1, 639, 1278, 1279)) {
                    val x = child.left + (column + 0.5f) * child.width / 1280
                    val y = child.top + (row + 0.5f) * child.height / 5140
                    val piece = pieces.single { y >= it.logical.top && y < it.logical.bottom }
                    val encodedX = piece.encoded.left + (x - piece.logical.left) / piece.logical.width * piece.encoded.width
                    val encodedY = piece.encoded.top + (y - piece.logical.top) / piece.logical.height * piece.encoded.height
                    val pixel = layout.toEncoded(column, row)
                    assertEquals(pixel.x + 0.5f, (encodedX - child.left) / child.width * 3840, 0.003f)
                    assertEquals(pixel.y + 0.5f, (encodedY - child.top) / child.height * 1728, 0.003f)
                }
            }
        }
    }

    @Test fun excludesPaddingAndHasNoLogicalGaps() {
        val pieces = StripCanvasLayout.plan(layout, FloatBounds(0f, 0f, 1280f, 5140f))
        assertEquals(0f, pieces.first().logical.top, 0f)
        assertEquals(5140f, pieces.last().logical.bottom, 0f)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.logical.bottom, b.logical.top, 0f) }
        assertEquals(1684f / 1728 * 5140, pieces.last().encoded.bottom, 0.001f)
    }

    @Test fun fourViewsKeepTheirSourceGeometryAcrossReconstruction() {
        assertEquals(listOf(4f, 1288f, 2572f, 3856f), (1..4).map {
            FourLaneTextureLayout.windowForLane(1280, 5140, it).sourceTopPx
        })
        val draws = FourLaneCanvasLayout.plan(1280, 5140, 0f, 0f, 1280f, 5140f, 800f, 400f)
        assertTrue(draws.all { it.source.width == 1280f && kotlin.math.abs(it.source.height - 1280f) < 0.001f })
    }
}
