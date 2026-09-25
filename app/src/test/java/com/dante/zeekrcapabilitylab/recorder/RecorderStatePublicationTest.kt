package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class RecorderStatePublicationTest {
    private class Harness {
        var held = false
        var acquires = 0
        var visible = RecorderState()
        val terminalReasons = mutableListOf<String>()
        val publisher = RecorderStatePublication(
            syncWakeLock = { status ->
                val needed = RecorderWakeLockPolicy.shouldHold(status)
                if (needed && !held) acquires++
                held = needed
                held
            },
            releaseWakeLock = { held = false },
            publish = { visible = it },
            onTerminated = { _, reason, _ -> terminalReasons += reason },
        )
        fun loss() {
            publisher.begin("run-a")
            publisher.update(RecorderState(status = RecorderStatus.RECORDING, recordingSessionId = "run-a"))
            publisher.update(publisher.state.copy(status = RecorderStatus.FINALIZING))
        }
    }

    @Test fun closeTimeoutUpdatesInternalAndVisibleStateTogether() {
        val h = Harness(); h.loss()
        h.publisher.unconfirmed("CAMERA_CLOSE_UNCONFIRMED", "close timed out")
        assertEquals(RecorderStatus.ERROR, h.visible.status)
        assertEquals("The next file callback reads the internal state", h.visible, h.publisher.state)
    }

    @Test fun lateFilePublicationAfterCloseTimeoutCannotReacquireRecordingLock() {
        val h = Harness(); h.loss()
        h.publisher.unconfirmed("CAMERA_CLOSE_UNCONFIRMED", "close timed out")
        assertFalse(h.held)
        // The same operation performed by onProductFileClosed's successful IO callback.
        h.publisher.update(h.publisher.state.copy(libraryRevision = 1))
        assertEquals("Late metadata may still finish", 1L, h.publisher.state.libraryRevision)
        assertFalse("A file publication cannot renew terminated recording authority", h.held)
        assertEquals(1, h.acquires)
        assertEquals(RecorderStatus.ERROR, h.visible.status)
    }

    @Test fun aSnapshotQueuedBeforeTimeoutCannotUndoTheError() {
        val h = Harness(); h.loss()
        val before = h.publisher.state.copy(status = RecorderStatus.RECORDING, libraryRevision = 2)
        h.publisher.unconfirmed("CAMERA_CLOSE_UNCONFIRMED", "timeout")
        h.publisher.update(before)
        assertEquals(RecorderStatus.ERROR, h.visible.status)
        assertFalse(h.held); assertFalse(h.visible.recovery.resumeAllowed)
        assertTrue(h.visible.cleanupPending); assertTrue(h.visible.cleanupUnconfirmed)
        assertFalse(h.publisher.allowsOpen("run-a"))
    }

    @Test fun normalStopRevokesRecoveryButKeepsBoundedFinalizationPossible() {
        val h = Harness(); h.loss()
        h.publisher.terminate("MANUAL_STOP", "STOP_REQUEST")
        assertEquals(RecorderStatus.FINALIZING, h.visible.status)
        assertFalse(h.publisher.allowsOpen("run-a"))
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.RESUMING))
        assertEquals(RecorderStatus.FINALIZING, h.visible.status)
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.STOPPED, cleanupPending = false))
        assertFalse(h.held)
        assertEquals(listOf("MANUAL_STOP"), h.terminalReasons)
    }

    @Test fun lateCloseAndFileCompletionStillWorkAfterTimeout() {
        val h = Harness(); h.loss()
        h.publisher.unconfirmed("CAMERA_CLOSE_UNCONFIRMED", "timeout")
        // Only the resource transaction may supply these confirmed cleanup values.
        h.publisher.cleanupSettled("run-a")
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.STOPPED,
            cleanupPending = false, cleanupUnconfirmed = false))
        h.publisher.update(h.publisher.state.copy(libraryRevision = 3))
        assertEquals(3L, h.visible.libraryRevision)
        assertEquals(RecorderStatus.STOPPED, h.visible.status)
        assertFalse(h.visible.cleanupPending); assertFalse(h.held)
        assertEquals(listOf("CAMERA_CLOSE_UNCONFIRMED"), h.terminalReasons)
    }

    @Test fun anOldRunCannotStopOrPublishIntoANewManualRun() {
        val h = Harness(); h.loss()
        h.publisher.terminate("MANUAL_STOP", "STOP_REQUEST")
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.STOPPED))
        val old = h.publisher.state
        h.publisher.begin("run-b")
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.RECORDING))
        h.publisher.update(old)
        h.publisher.update(h.publisher.state.copy(libraryRevision = 90), "run-a")
        h.publisher.unconfirmed("CAMERA_CLOSE_UNCONFIRMED", "late old timeout", "run-a")
        assertEquals("run-b", h.visible.recordingSessionId)
        assertEquals(RecorderStatus.RECORDING, h.visible.status)
        assertEquals(0L, h.visible.libraryRevision)
        assertTrue(h.held); assertTrue(h.publisher.allowsOpen("run-b"))
    }

    @Test fun cleanInterruptionIsNotTerminalAndCanResume() {
        val h = Harness(); h.loss()
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.WAITING_CAMERA))
        assertFalse(h.held)
        assertTrue(h.publisher.allowsOpen("run-a"))
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.RESUMING))
        h.publisher.update(h.publisher.state.copy(status = RecorderStatus.RECORDING))
        assertTrue(h.held); assertTrue(h.terminalReasons.isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun unknownCleanupCannotBeClearedByANewStart() {
        val h = Harness(); h.loss()
        h.publisher.unconfirmed("CAMERA_CLOSE_UNCONFIRMED", "timeout")
        h.publisher.begin("run-b")
    }
}
