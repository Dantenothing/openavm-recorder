package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** A format transition is permitted only at the next already-requested independent MP4. */
class ContinuousTrackRolloverTest {
    private fun spec(revision: Int = 1, extra: Map<String, Any?> = emptyMap()): ContinuousTrackFormatSpec {
        val values = mapOf<String, Any?>("mime" to "video/avc", "width" to 640, "height" to 480,
            "csd-0" to ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67, revision.toByte())),
            "csd-1" to ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x68, 1))) + extra
        return ContinuousTrackFormatSpec.capture(object : ContinuousTrackFormatFields {
            override val keys = values.keys
            override fun string(key: String) = values[key] as String?
            override fun integer(key: String) = values[key] as Int
            override fun buffer(key: String) = values[key] as ByteBuffer?
        })
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected an explicit format failure") } catch (_: IllegalStateException) { }
    }
    @Test fun changedCsdAtRequestedCutIsStagedThenCommittedOnTheNewIdr() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        assertEquals(ContinuousFormatDecision.STAGE_ROLLOVER, state.accept(spec(2), cutPending = true))
        assertEquals("ROLLOVER_PENDING", state.snapshot().decision)
        state.beforeMediaSample(keyframe = true)
        assertTrue(state.commitBoundary(cutBefore = true))
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(2)))
        assertFalse(state.commitBoundary(cutBefore = false))
    }
    @Test fun repeatedPendingCandidateDoesNotDoubleStageButDifferentCandidateFails() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        state.accept(spec(2), cutPending = true)
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(2), cutPending = true))
        reject { state.accept(spec(3), cutPending = true) }
        reject { state.accept(spec(), cutPending = true) }
    }
    @Test fun changedFormatWithoutRequestedCutStillFails() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        reject { state.accept(spec(2)) }
    }
    @Test fun dependentFramesWaitButFirstWrittenFrameStillRequiresTheActualPlannerBoundary() {
        for (keyframe in listOf(false, true)) {
            val state = ContinuousTrackFormatState(); state.accept(spec()); state.accept(spec(2), true)
            if (!keyframe) assertFalse(state.beforeMediaSample(false))
            else { state.beforeMediaSample(true); reject { state.commitBoundary(false) } }
        }
    }
    @Test fun layoutChangesAreRejectedEvenDuringCut() {
        for (changed in listOf(mapOf("width" to 1280), mapOf("height" to 720), mapOf("crop-left" to 1),
            mapOf("rotation-degrees" to 90), mapOf("sar-width" to 2, "sar-height" to 1))) {
            val state = ContinuousTrackFormatState(); state.accept(spec())
            reject { state.accept(spec(2, changed), true) }
        }
    }
    @Test fun compatibleNotificationNeverCreatesFormatBoundary() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(), true))
        state.beforeMediaSample(false)
        assertFalse(state.commitBoundary(false))
    }
    @Test fun cancellationDoesNotInstallThePendingTrackOrInventANewFile() {
        val state = ContinuousTrackFormatState(); state.accept(spec()); state.accept(spec(2), true)
        state.cancelPending()
        assertFalse(state.commitBoundary(true))
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec()))
    }
    @Test fun writerBoundarySeparatesOldSamplesFromNewTrackAndKeepsFileLocalPts() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        val planner = KeyframeSegmentPlanner()
        val trace = mutableListOf("configure:A")
        val initial = requireNotNull(planner.accept(1_000_000, true)); trace += "write:A:${initial.ptsUs}"
        planner.requestCut(); state.accept(spec(2), true)
        state.beforeMediaSample(true)
        val next = requireNotNull(planner.accept(61_000_000, true))
        val boundaryFormat = if (state.commitBoundary(next.cutBefore)) "B" else null
        finishContinuousTrackBoundary(finish = { trace += "finish:A"; "old-summary" },
            newFormat = boundaryFormat, applyFormat = { trace += "configure:$it" },
            done = { trace += "write:B:${next.ptsUs}" })
        assertEquals(listOf("configure:A", "write:A:0", "finish:A", "configure:B", "write:B:0"), trace)
    }

    @Test fun productionBoundaryDoesNotApplyOrOpenAFileWhenFinalizeFails() {
        val trace = mutableListOf<String>()
        reject {
            finishContinuousTrackBoundary<String, String>(
                finish = { trace += "finish"; error("finalize-failed") },
                newFormat = "B", applyFormat = { trace += "apply" }, done = { trace += "open" })
        }
        assertEquals(listOf("finish"), trace)
    }

    @Test fun committedBoundaryAppliesItsTrackAndKeyframeEvenIfStopArrivesDuringFinalization() {
        val trace = mutableListOf<String>()
        assertTrue(finishContinuousTrackBoundary(finish = {
            trace += "finish"; trace += "stop-requested"; "summary"
        }, newFormat = "B", applyFormat = { trace += "apply:$it" }, done = { trace += "write-keyframe" }))
        assertEquals(listOf("finish", "stop-requested", "apply:B", "write-keyframe"), trace)
    }

    @Test fun productionBoundaryKeepsExistingRotationWhenThereIsNoNewFormat() {
        val trace = mutableListOf<String>()
        assertTrue(finishContinuousTrackBoundary<String, String>(
            finish = { trace += "finish"; "summary" }, newFormat = null,
            applyFormat = { trace += "apply" }, done = { trace += "open:$it" }))
        assertEquals(listOf("finish", "open:summary"), trace)
    }

    @Test fun changedDecoderHintsAndHdrCanUseNewTrackButStrictDefaultStillRejects() {
        val first = spec(extra = mapOf("profile" to 1, "hdr-static-info" to ByteBuffer.wrap(byteArrayOf(1))))
        val second = spec(extra = mapOf("profile" to 2, "hdr-static-info" to ByteBuffer.wrap(byteArrayOf(2))))
        val state = ContinuousTrackFormatState(); state.accept(first)
        assertEquals(ContinuousFormatDecision.STAGE_ROLLOVER, state.accept(second, true))
        state.beforeMediaSample(true); assertTrue(state.commitBoundary(true))
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(second))
        reject { state.accept(first) }
    }

    @Test fun cancelBeforeAnyNewMediaFrameDoesNotCreateABoundary() {
        val state = ContinuousTrackFormatState(); state.accept(spec()); state.accept(spec(2), true)
        state.cancelPending() // EOS / manual stop before the next media frame.
        assertEquals("ROLLOVER_CANCELLED", state.snapshot().decision)
        state.beforeMediaSample(false)
        assertFalse(state.commitBoundary(false))
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec()))
    }

    @Test fun stopFlagBeforeCodecCancelMessageDoesNotMakeRepeatedPendingFormatFatal() {
        val state = ContinuousTrackFormatState(); state.accept(spec()); state.accept(spec(2), true)
        // stop() has published its flag, but its queued cancellation has not reached the codec.
        assertEquals(ContinuousFormatDecision.DISCARD_TERMINAL,
            state.accept(spec(2), cutPending = false, terminal = true))
        assertTrue(state.discardTerminalMedia)
        assertFalse(state.commitBoundary(true))
        assertEquals("INCOMPATIBLE", state.snapshot().decision)
        assertEquals("CSD_0", state.snapshot().fields.single().field)
        assertEquals(ContinuousFormatDecision.DISCARD_TERMINAL, state.accept(spec(2), terminal = true))
    }

    @Test fun terminalChangedOrUnreadableFormatDiscardsNewMediaButKeepsItsDiagnostic() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        assertEquals(ContinuousFormatDecision.DISCARD_TERMINAL, state.accept(spec(2), terminal = true))
        assertTrue(state.discardTerminalMedia)
        val evidence = listOf(ContinuousTrackFieldEvidence("CSD_1", "MISSING", null, false))
        state.incomplete(ContinuousTrackFormatException(ContinuousTrackFormatSpec.INCOMPLETE, evidence), terminal = true)
        assertEquals("INCOMPLETE", state.snapshot().decision)
        assertEquals(evidence, state.snapshot().fields)
        assertFalse(state.commitBoundary(true))
    }

    @Test fun terminalCompatibleFormatAndFirstCompleteFormatCanDrainTheirExistingData() {
        val state = ContinuousTrackFormatState()
        assertEquals(ContinuousFormatDecision.INSTALL, state.accept(spec(), terminal = true))
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(), terminal = true))
        assertFalse(state.discardTerminalMedia)
    }

    @Test fun stopRacingAfterStrictRejectionCanRevokeMediaWithoutErasingItsEvidence() {
        for (formatNotification in listOf(true, false)) {
            val state = ContinuousTrackFormatState(); state.accept(spec()); state.accept(spec(2), true)
            reject {
                if (formatNotification) state.accept(spec(3), cutPending = false)
                else state.commitBoundary(false)
            } // terminal flag was not visible yet.
            val rejected = state.snapshot()
            state.discardAtEnd() // It becomes visible at the catch site, before any failure publication.
            assertEquals(rejected, state.snapshot())
            assertTrue(state.discardTerminalMedia)
            assertFalse(state.commitBoundary(true))
        }
    }
}
