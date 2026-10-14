package com.dante.zeekrcapabilitylab.diagnostic

import org.junit.Assert.*
import org.junit.Test

class ProductDiagnosticTrialTest {
    private class Host : ProductTrialEngine.Host {
        val calls = mutableListOf<String>()
        var snapshot = ProductTrialEngine.Sample(idle = true)
        override fun sample() = snapshot
        override fun preview() { calls += "preview" }
        override fun record() { calls += "record" }
        override fun stop() { calls += "stop" }
        override fun close() { calls += "close" }
    }
    @Test fun twoRoundsUseActualPreviewAndWritingBeforeAdvancing() {
        val h = Host(); val e = ProductTrialEngine(0, h, recordingMs = 2_000)
        e.tick(0); assertEquals(listOf("close", "preview"), h.calls)
        h.snapshot = ProductTrialEngine.Sample(previewFresh = true, previewFrames = 1)
        e.tick(10); e.tick(2_020)
        assertFalse("A frozen image cannot start recording", "record" in h.calls)
        h.snapshot = h.snapshot.copy(previewFrames = 2); e.tick(2_030)
        assertEquals("record", h.calls.last())
        h.snapshot = ProductTrialEngine.Sample(recording = true, allChannelsWriting = true, writtenFrames = 5)
        e.tick(2_040); h.snapshot = h.snapshot.copy(writtenFrames = 50); e.tick(4_041)
        assertEquals("stop", h.calls.last())
        h.snapshot = ProductTrialEngine.Sample(previewFresh = true, previewFrames = 1)
        e.tick(4_050); h.snapshot = h.snapshot.copy(previewFrames = 2); e.tick(6_060)
        assertEquals(2, h.calls.count { it == "record" })
        h.snapshot = ProductTrialEngine.Sample(recording = true, allChannelsWriting = true, writtenFrames = 6)
        e.tick(6_070); h.snapshot = h.snapshot.copy(writtenFrames = 51); e.tick(8_080)
        h.snapshot = ProductTrialEngine.Sample(previewFresh = true, previewFrames = 1)
        e.tick(8_090); h.snapshot = h.snapshot.copy(previewFrames = 2); e.tick(10_100)
        assertEquals("close", h.calls.last()); assertFalse(e.terminal)
        h.snapshot = ProductTrialEngine.Sample(idle = true); e.tick(10_110)
        assertTrue(e.terminal); assertEquals("PASS", e.outcome); assertEquals(2, e.completedRecordings)
    }
    @Test fun channelFaultStopsOnceAndDoesNotRetryRecording() {
        val h = Host(); val e = ProductTrialEngine(0, h)
        e.tick(0)
        h.snapshot = ProductTrialEngine.Sample(previewFresh = true, previewFrames = 1); e.tick(1)
        h.snapshot = h.snapshot.copy(previewFrames = 2); e.tick(2_001)
        h.snapshot = ProductTrialEngine.Sample(recording = true, error = "AUXILIARY_NO_WRITTEN_FRAMES")
        e.tick(22_001); e.tick(30_000)
        assertEquals(1, h.calls.count { it == "record" })
        assertEquals("AUXILIARY_NO_WRITTEN_FRAMES", e.reason)
        h.snapshot = ProductTrialEngine.Sample(idle = true); e.tick(30_001)
        assertTrue(e.terminal); assertEquals("FAIL", e.outcome)
    }
    @Test fun cleanupTimeoutNeverPermitsANewOwner() {
        val h = Host(); h.snapshot = ProductTrialEngine.Sample(idle = false)
        val e = ProductTrialEngine(0, h)
        e.tick(65_001); e.tick(130_002)
        assertTrue(e.terminal); assertEquals("UNCONFIRMED", e.cleanup)
        assertFalse("preview" in h.calls); assertFalse("record" in h.calls)
    }
    @Test fun firstReturnFaultFreezesVerdictAndCannotStartTheSecondRecording() {
        val h = Host(); val e = ProductTrialEngine(0, h, recordingMs = 2_000)
        e.tick(0)
        h.snapshot = ProductTrialEngine.Sample(previewFresh = true, previewFrames = 1); e.tick(1)
        h.snapshot = h.snapshot.copy(previewFrames = 2); e.tick(2_001)
        h.snapshot = ProductTrialEngine.Sample(recording = true, allChannelsWriting = true, writtenFrames = 20); e.tick(2_010)
        h.snapshot = h.snapshot.copy(writtenFrames = 40); e.tick(4_010)
        assertEquals("RETURN_PREVIEW", e.phase)
        h.snapshot = ProductTrialEngine.Sample(error = "CAMERA_CLOSE_UNCONFIRMED"); e.tick(4_200)
        h.snapshot = ProductTrialEngine.Sample(previewFresh = true, previewFrames = 100); e.tick(10_000)
        h.snapshot = ProductTrialEngine.Sample(idle = true); e.tick(10_100)
        assertEquals("FAIL", e.outcome); assertEquals("CONFIRMED", e.cleanup)
        assertEquals("CAMERA_CLOSE_UNCONFIRMED", e.reason)
        assertEquals(1, h.calls.count { it == "record" })
    }
    @Test fun cancellationIsIdempotentAndFirstFailureSurvivesCleanupTimeout() {
        val h = Host(); val e = ProductTrialEngine(0, h)
        e.tick(0); e.cancel(5, "PAGE_HIDDEN"); e.cancel(10, "SECOND_CANCEL")
        h.snapshot = ProductTrialEngine.Sample(); e.tick(65_006)
        assertTrue(e.terminal); assertEquals("PAGE_HIDDEN", e.reason)
        assertEquals("UNCONFIRMED", e.cleanup); assertEquals(2, h.calls.count { it == "close" })
    }
}
