package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

/** Runs the production orchestrator with controlled callback, native and IO queues. */
class CaptureCloseTransactionTest {
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
                          recording: Boolean = true) {
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
            { name, detail -> traces.add(name + ":" + detail) }, { faults.add(it) }, { result = it })
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
    @Test fun sessionClosedIsValidEvenIfVendorDropsSequenceCallbacks() {
        val h = Harness(setOf(1, 2)); h.start(); h.tx.sessionClosed(); h.pump()
        assertNotNull(h.result); assertEquals("SESSION_CLOSED", h.result!!.producerEvidence)
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
}
