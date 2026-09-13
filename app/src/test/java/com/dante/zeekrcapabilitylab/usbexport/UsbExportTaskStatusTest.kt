package com.dante.zeekrcapabilitylab.usbexport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExportTaskStatusTest {
    @Test
    fun completedTaskCanHideItsCardWithoutLosingDurableTask() {
        val completed = task(UsbExportState.COMPLETED)

        assertTrue(completed.statusCardVisible())
        assertTrue(completed.statusCardDismissible())

        val dismissed = completed.copy(statusCardDismissed = true)
        assertFalse(dismissed.statusCardVisible())
        assertTrue(dismissed.statusCardDismissible())
    }

    @Test
    fun activeAndRecoverableTasksCannotBeDismissed() {
        assertFalse(task(UsbExportState.COPYING).statusCardDismissible())
        assertFalse(task(UsbExportState.FAILED_RECOVERABLE).statusCardDismissible())
        assertFalse(task(UsbExportState.WAITING_FOR_TARGET).statusCardDismissible())
    }

    private fun task(state: UsbExportState) = UsbExportTask(
        id = "task-id",
        logicalId = "session-id",
        recordingMode = "NORMAL",
        startedAtEpochMs = 1L,
        stoppedAtEpochMs = 2L,
        target = UsbExportTarget(
            volumeName = "test-volume",
            storageUuid = "test-uuid",
            description = "Test USB",
            collectionUri = "content://test",
        ),
        sources = emptyList(),
        state = state,
    )
}
