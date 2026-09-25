package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the production orchestrator with controlled callback, native and IO queues. */
class CaptureCloseTransactionTest {
    @Test fun oemLossDuringDrainUpgradesTheFenceWithoutTurningIntoManualStop() {
        val h = Harness(setOf(7))
        h.start(); h.tx.merge(requireDeviceClose = true); h.pump()
        assertTrue("deviceClose" in h.calls)
        h.tx.sessionReady(); h.tx.sequenceEnded(7); h.pump()
        assertFalse("release" in h.calls)
        h.closed()
        assertTrue(h.result!!.safeToContinue)
        assertTrue(h.result!!.deviceClosed)
        assertFalse(h.result!!.terminal)
    }
    @Test fun stoppingDuringAnOemInterruptionStillCancelsRecovery() {
        val h = Harness(setOf(7), deviceFence = true)
        h.start(); h.tx.merge(terminal = true); h.pump(); h.closed()
        assertTrue(h.result!!.safeToContinue)
        assertTrue(h.result!!.terminal)
    }
    @Test fun interruptedOpenWithNoEncoderSegmentStillWaitsForActualDeviceClose() {
        val h = Harness(session = false, recording = false,
            deviceFence = RecorderClosePolicy.needsDeviceFence("RECOVERY_ATTEMPT_CONTENTION", false))
        h.start(); assertNull(h.result); assertEquals(listOf("deviceClose"), h.calls)
        h.closed(); assertTrue(h.result!!.safeToContinue); assertFalse(h.result!!.terminal)
        assertFalse("stop" in h.calls)
    }
    @Test fun interruptionDuringSegmentRotationMustNotStartAnotherSegmentOnTheLostDevice() {
        assertEquals("CAMERA_LOSS", RecorderClosePolicy.mergeReason("TIMEOUT", "CAMERA_LOSS", false))
        assertEquals("STOP", RecorderClosePolicy.mergeReason("STOP", "CAMERA_LOSS", true))
    }
    @Test fun oemInterruptionClosesDeviceBeforeRecorderAndDoesNotCancelTheManualSession() {
        val h = Harness(setOf(9), deviceFence = RecorderClosePolicy.needsDeviceFence("CAMERA_LOSS", false))
        h.start()
        assertEquals(listOf("deviceClose"), h.calls)
        h.tx.sessionReady(); h.tx.sessionClosed(); h.tx.sequenceEnded(9); h.pump()
        assertFalse("No release before the device acknowledgement", "release" in h.calls)
        h.closed()
        assertTrue(h.result!!.safeToContinue)
        assertTrue(h.result!!.deviceClosed)
        assertFalse("A device close is not a manual Stop", h.result!!.terminal)
        val recovery = CameraRecoveryStateMachine()
        recovery.beginManualSession(1, "2"); recovery.markRecordingStarted(1, 100)
        recovery.beginRecoverableLoss(1, "CAMERA_DISCONNECTED", 200)
        recovery.onAvailability(1, "2", true, 300)
        recovery.finalizeCompleted(1, 400)
        assertEquals(CameraRecoveryAction.Attempt(1, 1), recovery.onTimer(1, 1900))
    }
    private class Queue : CloseDispatcher {
        val tasks = ArrayDeque<() -> Unit>()
        data class Timer(val due: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val timers = mutableListOf<Timer>()
        var time = 0L
        var accept = true
        var blocked = false
        override fun execute(action: () -> Unit): Boolean {
            if (accept) tasks.add(action)
            return accept
        }
        override fun after(delayMs: Long, action: () -> Unit): () -> Unit {
            val timer = Timer(time + delayMs, action)
            timers.add(timer)
            return { timer.cancelled = true }
        }
        fun one(): Boolean {
            if (blocked || tasks.isEmpty()) return false
            tasks.removeFirst().invoke(); return true
        }
        fun advance(ms: Long) {
            time += ms
            timers.filter { !it.cancelled && it.due <= time }.forEach { it.cancelled = true; tasks.add(it.task) }
        }
    }
    private class Harness(sequences: Set<Int> = emptySet(), terminal: Boolean = false,
                          lost: Boolean = false, session: Boolean = true, device: Boolean = true,
                          recording: Boolean = true, deviceFence: Boolean = false,
                          onFault: (String) -> Unit = {}, onComplete: (CaptureCloseResult) -> Unit = {}) {
        val control = Queue(); val native = Queue(); val io = Queue()
        val calls = mutableListOf<String>(); val faults = mutableListOf<String>()
        val throws = mutableSetOf<String>(); val traces = mutableListOf<String>()
        var result: CaptureCloseResult? = null
        var syncFailure = false
        private fun call(name: String) { calls.add(name); if (name in throws) error(name) }
        val tx = CaptureCloseTransaction(control, native, io, object : CaptureCloseResources {
            override fun stopRepeating() = call("stopRepeating")
            override fun abortCaptures() = call("abort")
            override fun closeSession() = call("sessionClose")
            override fun closeDevice() = call("deviceClose")
            override fun stopRecorder() = call("stop")
            override fun resetRecorder() = call("reset")
            override fun releaseRecorder() = call("release")
            override fun closeOutput(lost: Boolean) {
                call(if (lost) "abandonOutput" else "closeOutput")
                if (syncFailure) throw OutputFlushException(IllegalStateException("sync"))
            }
        }, session, device, recording, sequences, terminal, lost,
            { name, detail -> traces.add(name + ":" + detail) }, { faults.add(it); onFault(it) }, { result = it; onComplete(it) },
            preferDeviceClose = deviceFence)
        fun pump() {
            repeat(200) { if (!(control.one() or native.one() or io.one())) return }
            error("unbounded work")
        }
        fun start() { tx.begin(); pump() }
        fun advance(ms: Long) { control.advance(ms); pump() }
        fun ready() { tx.sessionReady(); pump() }
        fun closed() { tx.deviceClosed(); pump() }
    }
    @Test fun ordinaryRotationDrainsBeforeStoppingAndRetainsHealthyDevice() {
        val h = Harness(setOf(7)); h.start(); h.ready()
        assertFalse("stop" in h.calls)
        h.tx.sequenceEnded(7); h.pump()
        assertTrue(h.result!!.safeToContinue)
        assertEquals(listOf("stopRepeating", "abort", "stop", "reset", "release", "sessionClose", "closeOutput"), h.calls)
        assertFalse(h.result!!.terminal)
    }
    @Test fun oldProducerDeviceFenceCannotBeBypassedByCurrentSessionCallbacks() {
        val h = Harness(setOf(7), terminal = true, deviceFence = true)
        h.start()
        assertEquals(listOf("deviceClose"), h.calls)
        h.tx.sessionReady(); h.tx.sessionClosed(); h.tx.sequenceEnded(7); h.pump()
        assertFalse("The older producer still needs its device acknowledgement", "release" in h.calls)
        assertNull(h.result)
        h.closed()
        assertTrue("release" in h.calls)
        assertTrue(h.result!!.safeToContinue)
        assertTrue(h.result!!.terminal)
    }
    @Test fun terminalRequiresActualDeviceAckNotCloseReturn() {
        val h = Harness(terminal = true); h.start(); h.ready()
        assertTrue("deviceClose" in h.calls); assertNull(h.result)
        assertFalse("closeOutput" in h.calls)
        h.closed(); assertTrue(h.result!!.safeToContinue)
    }
    @Test fun noCallbacksNeverReleaseRecorderOrDescriptor() {
        val h = Harness(); h.start(); h.advance(2_000); h.advance(2_000); h.advance(3_000)
        assertNull(h.result); assertFalse("release" in h.calls); assertFalse("closeOutput" in h.calls)
        assertTrue(h.faults.contains("CAMERA_CLOSE_UNCONFIRMED"))
    }
    @Test fun lateDeviceAckSettlesAfterTimeoutButCannotResume() {
        val h = Harness(); h.start(); h.advance(2_000); h.advance(2_000); h.advance(3_000); h.closed()
        assertTrue(h.result!!.safeToContinue); assertTrue(h.result!!.terminal)
    }
    @Test fun usbLossMergedDuringDrainNeverUsesStopOrReset() {
        val h = Harness(setOf(8)); h.start()
        h.tx.merge(outputLost = true); h.pump(); h.ready()
        h.tx.sequenceEnded(8); h.pump()
        assertFalse("stop" in h.calls); assertFalse("reset" in h.calls)
        assertTrue("release" in h.calls); assertNull(h.result)
        h.closed()
        assertTrue(h.result!!.outputLost); assertTrue("abandonOutput" in h.calls)
    }
    @Test fun stopMergedIntoUsbLossRemovesContinuation() {
        val h = Harness(lost = true); h.start(); h.tx.merge(terminal = true); h.pump()
        h.ready(); h.closed(); assertTrue(h.result!!.terminal)
    }
    @Test fun backgroundUsbLossClosesSafelyWithoutGrantingInternalRecording() {
        val away = VehicleAwayStateMachine().also { it.beginManualSession(1L) }
        away.onPowerSnapshot(1L, false, false, false, 1_000L)
        val h = Harness(lost = true)
        h.start()
        if (away.onUsbFallback(1L) is VehicleAwayAction.Confirm) {
            h.tx.merge(terminal = true)
        }
        h.ready(); h.closed()
        assertTrue(h.result!!.safeToContinue)
        assertTrue("Safe cleanup is not permission to record a new internal segment", h.result!!.terminal)
        assertFalse("stop" in h.calls)
        assertFalse("reset" in h.calls)
        assertTrue("abandonOutput" in h.calls)
    }

    @Test fun powerOffDuringLostUsbDrainMakesLateCompletionTerminal() {
        val away = VehicleAwayStateMachine().also { it.beginManualSession(1L) }
        away.onPowerSnapshot(1L, false, true, true, 1_000L)
        assertEquals(VehicleAwayAction.None, away.onUsbFallback(1L))
        val h = Harness(lost = true)
        h.start(); h.ready()
        assertNull(h.result)
        away.onPowerSnapshot(1L, false, false, true, 2_000L)
        if (away.onUsbFallback(1L) is VehicleAwayAction.Confirm) {
            h.tx.merge(terminal = true)
        }
        h.closed()
        assertTrue(h.result!!.terminal)
        assertTrue(h.result!!.outputLost)
        assertFalse("stop" in h.calls)
    }

    @Test fun resetThrowDoesNotSuppressReleaseOrDeviceClose() {
        val h = Harness(terminal = true); h.throws.add("reset"); h.start(); h.ready(); h.closed()
        assertTrue("release" in h.calls); assertTrue("deviceClose" in h.calls)
        assertTrue("reset" in h.result!!.errors)
    }
    @Test fun stopThrowDoesNotSuppressResetAndRelease() {
        val h = Harness(); h.throws.add("stop"); h.start(); h.ready()
        assertTrue(h.calls.containsAll(listOf("reset", "release", "closeOutput")))
        assertTrue("stop" in h.result!!.errors)
    }
    @Test fun sessionCloseThrowDoesNotSuppressDeviceClose() {
        val h = Harness(); h.throws.add("sessionClose"); h.start(); h.advance(2_000)
        assertTrue("deviceClose" in h.calls); assertFalse("release" in h.calls)
        h.closed(); assertTrue(h.result!!.safeToContinue)
    }
    @Test fun releaseThrowRetainsQuarantineEvenAfterDeviceAck() {
        val h = Harness(); h.throws.add("release"); h.start(); h.ready(); h.closed()
        assertFalse(h.result!!.safeToContinue); assertTrue(h.result!!.terminal)
        assertTrue(h.faults.contains("RECORDER_RELEASE_UNCONFIRMED"))
    }
    @Test fun failedDescriptorCloseCannotGrantContinuation() {
        val h = Harness(terminal = true); h.throws.add("closeOutput")
        h.start(); h.ready(); h.closed(); assertFalse(h.result!!.safeToContinue)
    }
    @Test fun blockedNativeQueueStillHasIndependentDeadlineAndNoFakeSuccess() {
        val h = Harness(); h.native.blocked = true
        h.start(); h.advance(2_000); h.advance(2_000); h.advance(3_000)
        assertTrue(h.calls.isEmpty()); assertNull(h.result); assertEquals(1, h.faults.size)
        h.native.blocked = false; h.pump(); h.closed()
        assertTrue(h.result!!.terminal)
    }
    @Test fun blockedOutputCannotBlockDeviceCloseOrDeadline() {
        val h = Harness(); h.io.blocked = true; h.start(); h.ready(); h.advance(8_000)
        assertTrue("deviceClose" in h.calls); assertNull(h.result)
        h.closed(); h.io.blocked = false; h.pump()
        assertTrue(h.result!!.terminal)
    }
    @Test fun rejectedNativeDispatchNeverGrantsAccess() {
        val h = Harness(); h.native.accept = false; h.start()
        assertTrue(h.faults.isNotEmpty()); assertNull(h.result); assertTrue(h.calls.isEmpty())
    }
    @Test fun rejectedOutputDispatchCannotComplete() {
        val h = Harness(); h.io.accept = false; h.start(); h.ready(); h.closed()
        assertNull(h.result); assertTrue(h.faults.isNotEmpty())
    }
    @Test fun terminalIntentDuringSlowIoMustStillWaitForDeviceAck() {
        val h = Harness(); h.io.blocked = true; h.start(); h.ready()
        h.tx.merge(terminal = true); h.pump(); h.io.blocked = false; h.pump()
        assertNull(h.result); assertTrue("deviceClose" in h.calls)
        h.closed(); assertTrue(h.result!!.terminal)
    }
    @Test fun captureSequenceAloneIsNotSessionIdleEvidence() {
        val h = Harness(setOf(1)); h.start(); h.tx.sequenceEnded(1); h.pump()
        assertFalse("stop" in h.calls); h.ready(); assertNotNull(h.result)
    }
    @Test fun sessionClosedDoesNotDrainOutstandingCaptures() {
        val h = Harness(setOf(1, 2)); h.start(); h.tx.sessionClosed(); h.pump()
        assertNull(h.result)
        assertFalse("release" in h.calls)
        assertFalse("stop" in h.calls)
        h.tx.sequenceEnded(1); h.pump()
        assertFalse("release" in h.calls)
        h.tx.sequenceEnded(2); h.pump()
        assertTrue(h.result!!.safeToContinue)
        assertEquals("CLOSED_AND_SEQUENCES_ENDED", h.result!!.producerEvidence)
        assertFalse("deviceClose" in h.calls)
    }
    @Test fun closedSessionMissingCaptureAckMustWaitForDeviceAndCannotResume() {
        val h = Harness(setOf(1)); h.start(); h.tx.sessionClosed(); h.pump()
        h.advance(2_000)
        assertTrue("deviceClose" in h.calls)
        assertFalse("release" in h.calls)
        assertNull(h.result)
        h.closed()
        assertTrue(h.result!!.safeToContinue)
        assertTrue(h.result!!.terminal)
        assertEquals("CAMERA_DEVICE_CLOSED", h.result!!.producerEvidence)
    }
    @Test fun closedSessionWithoutDeviceCannotInventMissingCaptureAck() {
        val h = Harness(setOf(1), device = false)
        h.start(); h.tx.sessionClosed(); h.pump(); h.advance(2_000)
        assertNull(h.result)
        assertFalse("release" in h.calls)
        assertTrue(h.faults.isNotEmpty())
    }
    @Test fun closedSessionCanFinishAfterAllKnownCapturesEnded() {
        val h = Harness(setOf(1)); h.start(); h.tx.sequenceEnded(1); h.pump()
        assertFalse("release" in h.calls)
        h.tx.sessionClosed(); h.pump()
        assertTrue(h.result!!.safeToContinue)
        assertEquals("CLOSED_AND_SEQUENCES_ENDED", h.result!!.producerEvidence)
    }
    @Test fun stopWhileClosedSessionIsDrainingRemainsTerminal() {
        val h = Harness(setOf(1)); h.start(); h.tx.sessionClosed(); h.pump()
        h.tx.merge(terminal = true); h.pump()
        assertFalse("release" in h.calls)
        h.tx.sequenceEnded(1); h.pump()
        assertNull(h.result)
        h.closed()
        assertTrue(h.result!!.terminal)
    }
    @Test fun duplicateClosedCallbackCannotExtendMissingCaptureDeadline() {
        val h = Harness(setOf(1)); h.start(); h.tx.sessionClosed(); h.pump()
        h.advance(1_500); h.tx.sessionClosed(); h.pump(); h.advance(500)
        assertTrue(h.faults.isNotEmpty())
        assertFalse("release" in h.calls)
    }
    @Test fun noDeviceReferenceDoesNotManufactureProofForExistingSession() {
        val h = Harness(device = false); h.start(); h.advance(2_000); h.advance(2_000)
        assertNull(h.result); assertFalse("release" in h.calls); assertTrue(h.faults.isNotEmpty())
    }
    @Test fun unstartedRecorderStillReleasesWithoutStop() {
        val h = Harness(session = false, device = false, recording = false); h.start()
        assertNotNull(h.result); assertFalse("stop" in h.calls); assertTrue("release" in h.calls)
    }
    @Test fun flushFailureWithConfirmedDescriptorCloseEndsSessionButDoesNotInventCameraLockup() {
        val h = Harness(); h.syncFailure = true; h.start(); h.ready(); h.closed()
        assertTrue(h.result!!.safeToContinue); assertTrue(h.result!!.terminal)
        assertTrue("outputSync" in h.result!!.errors)
    }

    @Test fun rc5OrderTimeoutThenFileCallbackThenLateCloseThenScreenOffRemainsTerminal() {
        var held = false
        var visible = RecorderState()
        val p = RecorderStatePublication({ RecorderWakeLockPolicy.shouldHold(it).also { held = it } },
            { held = false }, { visible = it })
        p.begin("run-a")
        p.update(p.state.copy(status = RecorderStatus.RECORDING))
        p.update(p.state.copy(status = RecorderStatus.FINALIZING, cleanupPending = true))
        val queued = p.state
        val h = Harness(deviceFence = true,
            onFault = { p.unconfirmed(it, "timeout", "run-a") },
            onComplete = { result ->
                if (result.safeToContinue) {
                    p.cleanupSettled("run-a")
                    p.update(p.state.copy(status = RecorderStatus.STOPPED))
                }
            })
        h.start(); h.advance(3_000)
        assertEquals(listOf("CAMERA_CLOSE_UNCONFIRMED"), h.faults)
        assertNull(h.result); assertFalse("release" in h.calls)
        assertTrue(visible.cleanupUnconfirmed); assertFalse(held)
        p.update(queued.copy(libraryRevision = 1))
        assertEquals(RecorderStatus.ERROR, visible.status)
        assertTrue(visible.cleanupPending); assertFalse(held)
        h.closed()
        assertTrue(h.result!!.safeToContinue); assertTrue(h.result!!.terminal)
        assertTrue("closeOutput" in h.calls)
        assertFalse(visible.cleanupPending); assertFalse(held)
        assertEquals(RecorderStatus.STOPPED, visible.status)
        val away = VehicleAwayStateMachine().apply { beginManualSession(1) }
        away.onPowerSnapshot(1, false, false, false, 55_438)
        assertFalse(p.allowsOpen("run-a"))
    }

    @Test fun normalAwayConfirmationEndsAuthorityAndWaitsForRealNativeRelease() {
        val away = VehicleAwayStateMachine().apply {
            beginManualSession(1); onPowerSnapshot(1, false, false, false, 0)
        }
        assertTrue(away.onTimer(1, away.snapshot.pendingToken, 30_000) is VehicleAwayAction.Confirm)
        val h = Harness(terminal = true)
        h.start(); h.ready()
        assertNull(h.result)
        h.closed()
        assertTrue(h.result!!.safeToContinue); assertTrue(h.result!!.terminal)
    }
    @Test fun stopBeforeAnEncoderExistsStillWaitsForTheReturnedDeviceToClose() {
        val h = Harness(terminal = true, session = false, recording = false)
        h.start()
        assertEquals(listOf("deviceClose"), h.calls)
        assertNull(h.result)
        h.closed()
        assertTrue(h.result!!.safeToContinue); assertTrue(h.result!!.terminal)
        assertFalse("stop" in h.calls)
    }
}
