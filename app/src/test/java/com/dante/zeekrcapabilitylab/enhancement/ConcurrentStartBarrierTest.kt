package com.dante.zeekrcapabilitylab.enhancement

import com.dante.zeekrcapabilitylab.enhancement.ConcurrentStartBarrier.*
import org.junit.Assert.*
import org.junit.Test

class ConcurrentStartBarrierTest {
    private fun gate() = ConcurrentStartBarrier("run-1", setOf("2", "1"), "usb-A:mount-7")
    private fun opened(g: ConcurrentStartBarrier) {
        g.opened("run-1", "2", "surround-device"); g.opened("run-1", "1", "cabin-device")
    }
    private fun ready(g: ConcurrentStartBarrier) {
        opened(g)
        g.consumerReady("run-1", g.configurationEpoch, "2", "surround-device")
        g.consumerReady("run-1", g.configurationEpoch, "1", "cabin-device")
    }
    @Test fun allDevicesMustOpenBeforeAnySessionMayConfigure() {
        val g = gate()
        assertEquals(setOf("1", "2"), g.pendingOpenIds)
        assertEquals(OpenAction.WAIT, g.opened("run-1", "2", "surround-device"))
        assertFalse(g.mayConfigure)
        assertEquals(OpenAction.CONFIGURE_ALL, g.opened("run-1", "1", "cabin-device"))
        assertTrue(g.mayConfigure); assertFalse(g.maySubmitCapture)
        assertEquals(OpenAction.DUPLICATE, g.opened("run-1", "1", "cabin-device"))
    }
    @Test fun consumerAndWriterProgressAreSeparateFromConfiguredState() {
        val g = gate(); opened(g)
        g.consumerReady("run-1", 1, "2", "surround-device"); assertFalse(g.maySubmitCapture)
        g.consumerReady("run-1", 1, "1", "cabin-device"); assertTrue(g.maySubmitCapture)
        assertEquals(Phase.AWAITING_PROGRESS, g.phase)
        g.writtenSample("run-1", 1, "2", "surround-device"); assertEquals(Phase.AWAITING_PROGRESS, g.phase)
        g.writtenSample("run-1", 1, "1", "cabin-device"); assertEquals(Phase.RECORDING, g.phase)
    }
    @Test fun stopBetweenOpensMakesLateDeviceCloseOnly() {
        val g = gate(); g.opened("run-1", "2", "surround-device"); g.stop(StopReason.USER)
        assertEquals(OpenAction.CLOSE_DEVICE, g.opened("run-1", "1", "cabin-device"))
        assertFalse(g.mayConfigure); assertFalse(g.mayCreateUsbOutput)
        g.deviceClosed("run-1", "surround-device"); assertEquals(Phase.STOPPING, g.phase)
        g.deviceClosed("run-1", "cabin-device"); assertEquals(Phase.DEVICES_CLOSED, g.phase)
    }
    @Test fun failureOfOnePendingOpenKeepsTheOtherOwnedUntilActualClose() {
        val g = gate(); g.opened("run-1", "2", "surround-device")
        g.failedWithoutDevice("run-1", "1")
        assertEquals(setOf("surround-device"), g.ownedDevices)
        g.cleanupDeadline(); assertEquals(Phase.CLEANUP_UNKNOWN, g.phase)
        g.deviceClosed("run-1", "surround-device"); assertEquals(Phase.DEVICES_CLOSED, g.phase)
    }
    @Test fun missingOpenCallbackCannotBeReleasedByDeadlineOrClosingOtherDevice() {
        val g = gate(); g.opened("run-1", "2", "surround-device"); g.cleanupDeadline()
        g.deviceClosed("run-1", "surround-device")
        assertEquals(Phase.CLEANUP_UNKNOWN, g.phase); assertEquals(setOf("1"), g.pendingOpenIds)
        assertEquals(OpenAction.CLOSE_DEVICE, g.opened("run-1", "1", "late-cabin"))
        g.deviceClosed("run-1", "late-cabin"); assertEquals(Phase.DEVICES_CLOSED, g.phase)
    }
    @Test fun usbRemountOrReturnCannotRestorePermission() {
        val g = gate(); ready(g)
        g.observeUsb("usb-A:mount-8"); g.observeUsb("usb-A:mount-7")
        g.consumerReady("run-1", 1, "1", "cabin-device")
        g.writtenSample("run-1", 1, "2", "surround-device")
        assertFalse(g.maySubmitCapture); assertFalse(g.mayCreateUsbOutput)
        assertEquals(setOf(StopReason.USB_LOST), g.stopReasons)
    }
    @Test fun foreignRunAndOldEpochCannotReleaseOrStartThisGroup() {
        val g = gate(); opened(g)
        g.deviceClosed("old-run", "surround-device")
        g.failedWithoutDevice("old-run", "1")
        g.consumerReady("run-1", 0, "2", "surround-device")
        g.consumerReady("old-run", 1, "1", "cabin-device")
        assertEquals(2, g.ownedDevices.size); assertFalse(g.maySubmitCapture)
        assertEquals(OpenAction.CLOSE_DEVICE, g.opened("old-run", "1", "old-device"))
        assertEquals(2, g.ownedDevices.size)
    }
    @Test fun allStopReasonsSurviveLaterCallbacks() {
        val g = gate(); ready(g)
        g.stop(StopReason.USER); g.observeUsb(null); g.cleanupDeadline()
        assertEquals(setOf(StopReason.USER, StopReason.USB_LOST, StopReason.DEADLINE), g.stopReasons)
        g.writtenSample("run-1", 1, "1", "cabin-device"); assertFalse(g.maySubmitCapture)
    }
    @Test fun unexpectedSecondDeviceMustAlsoCloseBeforeGroupSettles() {
        val g = gate(); opened(g)
        assertEquals(OpenAction.CLOSE_DEVICE, g.opened("run-1", "1", "duplicate-device"))
        g.deviceClosed("run-1", "cabin-device"); g.deviceClosed("run-1", "surround-device")
        assertEquals(Phase.STOPPING, g.phase)
        g.deviceClosed("run-1", "duplicate-device"); assertEquals(Phase.DEVICES_CLOSED, g.phase)
    }
    @Test fun unexpectedDeviceAfterReleaseReentersCleanupWithoutGrantingPermission() {
        val g = gate(); g.failedWithoutDevice("run-1", "1"); g.failedWithoutDevice("run-1", "2")
        assertEquals(Phase.DEVICES_CLOSED, g.phase)
        assertEquals(OpenAction.CLOSE_DEVICE, g.opened("run-1", "2", "unexpected-late-device"))
        assertEquals(Phase.CLEANUP_UNKNOWN, g.phase)
        assertFalse(g.mayConfigure); assertFalse(g.maySubmitCapture); assertFalse(g.mayCreateUsbOutput)
        g.deviceClosed("run-1", "unexpected-late-device"); assertEquals(Phase.DEVICES_CLOSED, g.phase)
    }
    @Test fun threeCamerasStillRequireEveryOpenAndEveryConsumer() {
        val g = ConcurrentStartBarrier("three", setOf("2", "1", "0"), "usb")
        for (id in listOf("2", "1")) assertEquals(OpenAction.WAIT, g.opened("three", id, "device$id"))
        assertEquals(OpenAction.CONFIGURE_ALL, g.opened("three", "0", "device0"))
        for (id in listOf("2", "1")) g.consumerReady("three", 1, id, "device$id")
        assertFalse(g.maySubmitCapture)
        g.consumerReady("three", 1, "0", "device0"); assertTrue(g.maySubmitCapture)
    }
}
