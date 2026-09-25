package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test

class ProbeFramePatternTest {
    private fun luma(frame: Int, region: Int, nonce: Int = 0x38a2) =
        ProbeFramePattern.marker(nonce, frame, region).let { bytes ->
            IntArray(56) { if (ProbeFramePattern.bit(bytes, it) == 1) 220 else 28 }
        }
    @Test fun eachColumnCarriesTheSameFrameButItsOwnRegionAndChecksum() {
        assertEquals(519, ProbeFramePattern.readFrame(0x38a2, List(3) { luma(519, it) }))
        assertNull(ProbeFramePattern.readFrame(0x38a2, listOf(luma(519,0), luma(518,1), luma(519,2))))
        assertNull(ProbeFramePattern.readFrame(0x38a2, listOf(luma(519,1), luma(519,0), luma(519,2))))
    }
    @Test fun badCrcUncertainLumaAndForeignRunCannotGuessAFrameId() {
        val corrupt = luma(5,0).also { it[25] = 248 - it[25] }
        assertNull(ProbeFramePattern.parse(corrupt))
        assertNull(ProbeFramePattern.parse(luma(5,0).also { it[4] = 128 }))
        assertNull(ProbeFramePattern.readFrame(0x1234, List(3) { luma(5,it) }))
    }
    @Test fun hardCodedTailAndPaddingOracleRetainsAllTwentyRows() {
        val layout = ContinuousProbeSpec.surroundRepack().layout
        assertEquals(0x10dcdc, ProbeFramePattern.rgbAtEncoded(2700,1664,layout,1,0))
        assertEquals(0x10dcdc, ProbeFramePattern.rgbAtEncoded(2700,1683,layout,1,0))
        assertEquals(0, ProbeFramePattern.rgbAtEncoded(2700,1684,layout,1,0))
        assertNotEquals(0x10dcdc, ProbeFramePattern.rgbAtEncoded(2700,1663,layout,1,0))
    }
    @Test fun smallAndFullMarkersHaveRoomForChromaTolerantBitCells() {
        assertTrue(ProbeFramePattern.cellWidth(ContinuousProbeSpec.healthCheck().layout.input.width) >= 4)
        assertTrue(ProbeFramePattern.cellWidth(1280) >= 4)
        assertEquals(ProbeFramePattern.Identity(0xffff,0xffff,2), ProbeFramePattern.parse(luma(0xffff,2,0xffff)))
    }
}
