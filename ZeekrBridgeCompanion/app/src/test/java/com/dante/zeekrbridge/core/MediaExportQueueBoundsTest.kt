package com.dante.zeekrbridge.core

import org.junit.Assert.*
import org.junit.Test

class MediaExportQueueBoundsTest {
    private fun job(id: String, state: MediaExportState) = MediaExportJob(id, "$id.mp4",
        MediaExportPlan(MediaExportTarget.ORIGINAL, IndexedSourceRole.SURROUND, IndexedRecordingMode.NORMAL,
            1, emptyList(), 0, 1_000, 0, 0), state)

    @Test fun fullLiveQueueRejectsNewWorkInsteadOfDroppingActiveJobs() {
        val current = listOf(job("running", MediaExportState.RUNNING), job("queued", MediaExportState.QUEUED))
        assertThrows(IllegalArgumentException::class.java) { boundedExportJobs(current, listOf(job("new", MediaExportState.QUEUED)), 2) }
    }

    @Test fun oldTerminalHistoryIsRemovedBeforeAnyLiveJob() {
        val current = listOf(job("running", MediaExportState.RUNNING), job("old", MediaExportState.COMPLETED),
            job("cancel", MediaExportState.CANCELLING))
        val result = boundedExportJobs(current, listOf(job("new", MediaExportState.QUEUED)), 3)
        assertEquals(listOf("running", "cancel", "new"), result.map { it.id })
    }

    @Test fun batchAdmissionIsAtomic() {
        val current = listOf(job("running", MediaExportState.RUNNING))
        val batch = listOf(job("one", MediaExportState.QUEUED), job("two", MediaExportState.QUEUED))
        assertThrows(IllegalArgumentException::class.java) { boundedExportJobs(current, batch, 2) }
        assertEquals(listOf("running"), current.map { it.id })
    }
}
