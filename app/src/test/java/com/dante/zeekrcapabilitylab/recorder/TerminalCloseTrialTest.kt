package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class TerminalCloseTrialTest {
    @Test fun privateDeviceFirstAppliesOnlyToTerminalReleaseAndNeverWeakensMandatoryFences() {
        for (order in TerminalCloseOrder.entries) {
            val trial = TerminalCloseTrial("trial", order)
            for (enabled in listOf(false, true)) for (terminal in listOf(false, true)) {
                assertTrue(trial.needsDeviceFence(enabled, terminal, "STOP", true))
                assertTrue(trial.needsDeviceFence(enabled, terminal, "CAMERA_LOSS", false))
                assertEquals(enabled && terminal && order == TerminalCloseOrder.DEVICE_FIRST,
                    trial.needsDeviceFence(enabled, terminal, "STOP", false))
            }
        }
    }
    @Test fun cancellationOrCleanupCannotEraseTheFirstFaultOrAffectAnotherRun() {
        val first = TerminalCloseTrial("one", TerminalCloseOrder.DEVICE_FIRST)
        val other = TerminalCloseTrial("two", TerminalCloseOrder.DRAIN_FIRST)
        first.fail("CAMERA_CLOSE_UNCONFIRMED"); first.fail("CANCELLED")
        assertEquals("CAMERA_CLOSE_UNCONFIRMED", first.failure); assertNull(other.failure)
    }
    @Test fun traceIncludesProducerAndInputBoundariesWithoutExportingExceptionMessages() {
        assertEquals("CAMERA_DEVICE_CLOSED", RecorderCloseTrace.facts("PRODUCER_CONFIRMED", "CAMERA_DEVICE_CLOSED")!!["producerEvidence"])
        assertEquals("RECORDER_RELEASE_NATIVE_RETURN", RecorderCloseTrace.facts("NATIVE_RETURN", "release")!!["closeStep"])
        assertNull(RecorderCloseTrace.facts("NATIVE_THROW", "release: /private/recording.mp4"))
        assertNull(RecorderCloseTrace.facts("STAGE", "address /private"))
    }
}
