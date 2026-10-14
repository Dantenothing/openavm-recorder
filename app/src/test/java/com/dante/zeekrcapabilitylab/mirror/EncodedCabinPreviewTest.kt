package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class EncodedCabinPreviewTest {
    private val format = CabinPreviewFormat("video/avc", 640, 480, listOf(byteArrayOf(1, 2)))

    @Test fun lateViewerStartsAtLatestKeyframeAndFollowsWithoutARecorderCommand() {
        val stream = CabinPreviewBuffer("run", maxBytes = 64, maxFrames = 8)
        stream.format(format)
        stream.frame(byteArrayOf(0), 0, false, 1)
        assertTrue(stream.read(0).frames.isEmpty())
        stream.frame(byteArrayOf(1), 10, true, 2)
        stream.frame(byteArrayOf(2), 20, false, 3)
        val first = stream.read(0)
        assertTrue(first.reset)
        assertEquals(listOf(10L, 20L), first.frames.map { it.ptsUs })
        stream.frame(byteArrayOf(3), 30, false, 4)
        val next = stream.read(first.frames.last().sequence)
        assertFalse(next.reset)
        assertEquals(listOf(30L), next.frames.map { it.ptsUs })
        stream.frame(byteArrayOf(4), 40, true, 5)
        assertEquals(listOf(40L), stream.read(0).frames.map { it.ptsUs })
        assertFalse(stream.read(next.frames.last().sequence).reset)
    }

    @Test fun overflowDropsOnlyTheDisplayCacheAndWaitsForAnotherKeyframe() {
        val stream = CabinPreviewBuffer("run", maxBytes = 4, maxFrames = 3)
        stream.format(format)
        stream.frame(byteArrayOf(1, 2, 3), 0, true, 1)
        stream.frame(byteArrayOf(4, 5), 10, false, 2)
        assertTrue(stream.read(0).frames.isEmpty())
        stream.frame(byteArrayOf(6), 20, false, 3)
        assertTrue(stream.read(0).frames.isEmpty())
        stream.frame(byteArrayOf(7), 30, true, 4)
        assertEquals(30L, stream.read(0).frames.single().ptsUs)
    }

    @Test fun laggingViewerResetsAtAKeyframeRatherThanDecodingMissingFrames() {
        val stream = CabinPreviewBuffer("run")
        stream.format(format)
        stream.frame(byteArrayOf(1), 0, true, 1)
        val previous = stream.read(0).frames.last().sequence
        stream.frame(byteArrayOf(2), 10, false, 2)
        stream.frame(byteArrayOf(3), 20, true, 3)
        assertTrue(stream.read(previous).reset)
        assertTrue(stream.read(previous).frames.first().keyframe)
    }

    @Test fun repeatFormatKeepsFramesButChangedFormatRequiresNewKeyframe() {
        val stream = CabinPreviewBuffer("run")
        stream.format(format)
        stream.frame(byteArrayOf(1), 0, true, 1)
        stream.format(format.copy(csd = listOf(byteArrayOf(1, 2))))
        assertEquals(1, stream.read(0).frames.size)
        stream.format(format.copy(width = 1280))
        assertTrue(stream.read(0).frames.isEmpty())
        stream.frame(byteArrayOf(2), 10, false, 2)
        assertTrue(stream.read(0).frames.isEmpty())
        stream.frame(byteArrayOf(3), 20, true, 3)
        assertEquals(1280, stream.read(0).format!!.width)
    }

    @Test fun closedRunCannotLeakStaleFramesOrBeRevived() {
        val stream = CabinPreviewBuffer("old")
        stream.format(format)
        stream.frame(byteArrayOf(1), 0, true, 1)
        stream.close()
        stream.format(format)
        stream.frame(byteArrayOf(2), 10, true, 2)
        assertTrue(stream.read(0).closed)
        assertNull(stream.read(0).format)
        assertTrue(stream.read(0).frames.isEmpty())
    }

    @Test fun missingKeyframesCannotGrowHistoryForever() {
        val stream = CabinPreviewBuffer("run", maxFrames = 3)
        stream.format(format)
        repeat(200) { stream.frame(byteArrayOf(1), it * 1_000L, it == 0, it.toLong()) }
        assertTrue(stream.read(0).frames.isEmpty())
    }
}
