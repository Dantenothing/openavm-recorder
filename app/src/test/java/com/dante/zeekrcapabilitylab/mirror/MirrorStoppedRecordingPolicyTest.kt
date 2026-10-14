package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class MirrorStoppedRecordingPolicyTest {
    @Test fun unexpectedRecorderEndPreservesAnExistingPreviewIntent() {
        for (status in listOf(RecorderStatus.STOPPED, RecorderStatus.ERROR, RecorderStatus.CAMERA_UNAVAILABLE)) {
            assertTrue(MirrorStoppedRecordingPolicy.restoreRequestedPreview(
                RecorderState(status = status, lastError = "PRODUCT_SOURCE_WAIT_TIMEOUT"), true, true, true))
        }
    }

    @Test fun hiddenPreviewSleepAndClosedControlsNeverAuthorizePreviewReturn() {
        val stopped = RecorderState(status = RecorderStatus.ERROR)
        assertFalse(MirrorStoppedRecordingPolicy.restoreRequestedPreview(stopped, false, true, true))
        assertFalse(MirrorStoppedRecordingPolicy.restoreRequestedPreview(stopped, true, false, true))
        assertFalse(MirrorStoppedRecordingPolicy.restoreRequestedPreview(stopped, true, true, false))
        for (status in listOf(RecorderStatus.STARTING, RecorderStatus.RECORDING, RecorderStatus.FINALIZING, RecorderStatus.IDLE))
            assertFalse(MirrorStoppedRecordingPolicy.restoreRequestedPreview(RecorderState(status = status), true, true, true))
    }

    private fun stoppedAfterLateAcknowledgement(): RecorderState {
        val publication = RecorderStatePublication({ false }, {}, {})
        publication.begin("manual-stop")
        publication.update(publication.state.copy(status = RecorderStatus.RECORDING))
        publication.terminate("MANUAL_STOP", "STOP_REQUEST")
        publication.unconfirmed("CAMERA_CLOSE_UNCONFIRMED", "waiting for real close")
        publication.cleanupSettled("manual-stop")
        publication.update(publication.state.copy(status = RecorderStatus.STOPPED))
        return publication.state
    }

    @Test fun confirmedLateCloseRestoresPreviewWithoutErasingRecordingError() {
        val stopped = stoppedAfterLateAcknowledgement()
        assertEquals("CAMERA_CLOSE_UNCONFIRMED", stopped.lastError)
        for (target in listOf(MirrorHandoffGate.Target.PREVIEW, MirrorHandoffGate.Target.CABIN)) {
            val gate = MirrorHandoffGate()
            val request = gate.begin(target, 0, "manual-stop")!!
            assertTrue(MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, stopped))
        }
    }

    @Test fun historicalErrorDoesNotBypassAnyRemainingResourceOwner() {
        val stopped = stoppedAfterLateAcknowledgement()
        for (held in 0..4) {
            val gate = MirrorHandoffGate()
            val request = gate.begin(MirrorHandoffGate.Target.PREVIEW, 0, "manual-stop")!!
            val confirmed = MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, stopped)
            assertEquals(MirrorHandoffGate.Decision.WAIT, gate.poll(request.token, 100, true,
                held != 0, held != 1, held != 2, held != 3, held != 4, confirmed))
            assertEquals(MirrorHandoffGate.Decision.START,
                gate.poll(request.token, 101, true, true, true, true, true, true, confirmed))
        }
    }

    @Test fun activeOrDifferentSessionCannotAcknowledgeStop() {
        val request = MirrorHandoffGate().begin(MirrorHandoffGate.Target.PREVIEW, 0, "manual-stop")!!
        val stopped = stoppedAfterLateAcknowledgement()
        for (invalid in listOf(stopped.copy(status = RecorderStatus.RECORDING),
            stopped.copy(status = RecorderStatus.FINALIZING), stopped.copy(recordingSessionId = "old-session"))) {
            assertFalse(MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, invalid))
        }
    }

    @Test fun stopBeforeFirstFileWaitsForActualCloseEvenAfterServiceDiscardsItsState() {
        val gate = MirrorHandoffGate()
        val request = gate.begin(MirrorHandoffGate.Target.PREVIEW, 0, "manual-stop")!!
        val earlyCallback = RecorderState(status = RecorderStatus.STOPPED, recordingSessionId = "manual-stop", cleanupPending = true)
        val acknowledged = MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, earlyCallback)
        assertTrue(acknowledged)
        // Service destruction does not manufacture resource proof or require a new callback.
        assertEquals(MirrorHandoffGate.Decision.WAIT,
            gate.poll(request.token, 100, true, true, true, true, false, true, acknowledged))
        assertEquals(MirrorHandoffGate.Decision.START,
            gate.poll(request.token, 101, true, true, true, true, true, true, acknowledged))
    }

    @Test fun terminalFailureAcknowledgementStillCannotOpenAnUnreleasedCamera() {
        val gate = MirrorHandoffGate()
        val request = gate.begin(MirrorHandoffGate.Target.CABIN, 0, "manual-stop")!!
        val failed = RecorderState(status = RecorderStatus.ERROR, recordingSessionId = "manual-stop",
            cleanupPending = true, cleanupUnconfirmed = true, lastError = "CAMERA_CLOSE_UNCONFIRMED")
        val acknowledged = MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, failed)
        assertTrue(acknowledged)
        assertEquals(MirrorHandoffGate.Decision.WAIT,
            gate.poll(request.token, 100, true, true, true, true, false, true, acknowledged))
        assertEquals(MirrorHandoffGate.Decision.CANCEL,
            gate.poll(request.token, 20_000, true, true, true, true, false, true, acknowledged))
    }

    @Test fun aRecordingSourceSwitchStillRejectsAFailedRecording() {
        val request = MirrorHandoffGate().begin(MirrorHandoffGate.Target.RECORD, 0, "manual-stop")!!
        val stopped = stoppedAfterLateAcknowledgement()
        assertFalse(MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, stopped))
        assertTrue(MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, stopped.copy(lastError = null)))
    }

    @Test fun cancelledPreviewRequestCannotBeRevivedByLateClose() {
        val gate = MirrorHandoffGate()
        val request = gate.begin(MirrorHandoffGate.Target.PREVIEW, 0, "manual-stop")!!
        gate.cancel()
        assertEquals(MirrorHandoffGate.Decision.CANCEL, gate.poll(request.token, 100, true,
            true, true, true, true, true, MirrorStoppedRecordingPolicy.stopCallbackAcknowledged(request, stoppedAfterLateAcknowledgement())))
    }
}
