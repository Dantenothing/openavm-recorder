package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.FrameHealthAnalyzer
import com.dante.zeekrcapabilitylab.service.recorder.FrameHealthReport
import com.dante.zeekrcapabilitylab.service.recorder.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameHealthAnalyzerTest {

    private fun frame(argb: IntArray, width: Int = 8, height: Int = 8) =
        PixelFrame(width = width, height = height, argb = argb)

    private fun solid(color: Int, size: Int = 64) = IntArray(size) { color }

    /** Deterministic 8x8 pattern: left half white / right half black, or vice versa. */
    private fun halfFrame(leftWhite: Boolean, size: Int = 64): IntArray {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        return IntArray(size) { index ->
            val column = index % 8
            if (if (leftWhite) column < 4 else column >= 4) white else black
        }
    }

    @Test
    fun blackFrameIsSuspectedBlack() {
        val report = FrameHealthAnalyzer.analyze(
            listOf(
                frame(solid(0xFF000000.toInt())),
                frame(solid(0xFF000000.toInt())),
                frame(solid(0xFF000000.toInt())),
            ),
        )

        assertTrue(report.blackSuspected)
        assertEquals(FrameHealthReport.STATUS_OK, report.status)
        assertEquals(0.0, report.averageBrightness ?: -1.0, 0.001)
    }

    @Test
    fun identicalFramesAreSuspectedFrozen() {
        val pattern = halfFrame(leftWhite = true)
        val report = FrameHealthAnalyzer.analyze(
            listOf(frame(pattern), frame(pattern), frame(pattern)),
        )

        assertTrue(report.frozenSuspected)
        assertEquals(0, report.maxHammingDistance ?: -1)
    }

    @Test
    fun differentFramesAreNotSuspectedFrozen() {
        val report = FrameHealthAnalyzer.analyze(
            listOf(
                frame(halfFrame(leftWhite = true)),
                frame(halfFrame(leftWhite = false)),
                frame(halfFrame(leftWhite = true)),
            ),
        )

        assertFalse(report.frozenSuspected)
        assertTrue((report.maxHammingDistance ?: 0) > FrameHealthAnalyzer.FROZEN_HAMMING_THRESHOLD)
    }

    @Test
    fun hammingDistanceIsSymmetricAndZeroForSameHash() {
        val hash = FrameHealthAnalyzer.ahash64(halfFrame(leftWhite = true), 8, 8)

        assertEquals(0, FrameHealthAnalyzer.hammingDistance(hash, hash))
        val hashB = FrameHealthAnalyzer.ahash64(halfFrame(leftWhite = false), 8, 8)
        assertEquals(
            FrameHealthAnalyzer.hammingDistance(hash, hashB),
            FrameHealthAnalyzer.hammingDistance(hashB, hash),
        )
    }

    @Test
    fun emptyFramesReportUnavailable() {
        val report = FrameHealthAnalyzer.analyze(emptyList())

        assertEquals(FrameHealthReport.STATUS_UNAVAILABLE, report.status)
        assertEquals(0, report.sampledFrames)
    }

    @Test
    fun brightnessAveragesLumaAcrossPixels() {
        val argb = IntArray(4) { 0xFFFFFFFF.toInt() }
        assertEquals(255.0, FrameHealthAnalyzer.brightness(argb), 0.001)

        val dark = IntArray(4) { 0xFF101010.toInt() }
        assertEquals(16.0, FrameHealthAnalyzer.brightness(dark), 0.001)
    }
}
