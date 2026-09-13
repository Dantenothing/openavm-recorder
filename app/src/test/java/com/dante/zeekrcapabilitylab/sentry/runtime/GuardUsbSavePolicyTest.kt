package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.probe.camera.*
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*
import org.junit.Assert.*
import org.junit.Test

class GuardUsbSavePolicyTest {
    private val event = GuardEvent(id = "event", runId = "run",
        source = SessionSourceSnapshot(RecordingSourceRole.SURROUND, "2",
            CameraFormatProfile(ProfileSize(1280, 5140), 4_000_000), RecordingLayoutKind.FOUR_LANE_V1),
        state = "COMPLETE", createdAtEpochMs = 1, triggerPtsUs = 1, targetEndPtsUs = 2,
        triggers = emptyList(), ai = GuardAiState(),
        assets = listOf(GuardAsset("1.mp4", 1, 0, 1, 1), GuardAsset("2.mp4", 2, 1, 2, 1)))
    private fun task(name: String, state: UsbExportState) = UsbExportTask(
        id = name, logicalId = event.id, recordingMode = "SENTRY", startedAtEpochMs = 1, stoppedAtEpochMs = 2,
        target = UsbExportTarget("volume", "UUID", "USB", collectionUri = "content://test"),
        sources = listOf(UsbExportSourceSnapshot("/events/" + name, name, 100, 1, 1, name + ".json", "{}")),
        state = state)
    @Test fun enqueueAndVerifyAreNotUsbSuccess() {
        for (state in UsbExportState.entries.filter { it !in setOf(UsbExportState.COMPLETED, UsbExportState.ALREADY_EXPORTED) }) {
            assertFalse(GuardUsbSavePolicy.label(event, listOf(task("1.mp4", state), task("2.mp4", state)), null)
                .startsWith("USB 已校验保存"))
        }
    }
    @Test fun everyAssetMustHaveVerifiedCopy() {
        assertFalse(GuardUsbSavePolicy.label(event, listOf(task("1.mp4", UsbExportState.COMPLETED)), null)
            .startsWith("USB 已校验保存"))
        assertTrue(GuardUsbSavePolicy.label(event, listOf(task("1.mp4", UsbExportState.COMPLETED),
            task("2.mp4", UsbExportState.ALREADY_EXPORTED)), null).startsWith("USB 已校验保存"))
    }
    @Test fun anotherEventsMatchingFileNameDoesNotCount() {
        val tasks = event.assets.map { task(it.name, UsbExportState.COMPLETED).copy(logicalId = "other") }
        assertFalse(GuardUsbSavePolicy.label(event, tasks, null).startsWith("USB 已校验保存"))
    }
    @Test fun failedAndRunningTasksAreRetriedInsteadOfDuplicated() {
        for (state in UsbExportState.entries.filter { it != UsbExportState.CANCELLED })
            assertFalse(GuardUsbSavePolicy.needsEnqueue("/events/1.mp4", listOf(task("1.mp4", state))))
    }
    @Test fun cancellationIsRespectedWhileMissingAssetsMayBeEnqueued() {
        assertFalse(GuardUsbSavePolicy.needsEnqueue("/events/1.mp4", listOf(task("1.mp4", UsbExportState.CANCELLED))))
        assertTrue(GuardUsbSavePolicy.label(event, listOf(task("1.mp4", UsbExportState.CANCELLED)), null).contains("已取消"))
        assertTrue(GuardUsbSavePolicy.needsEnqueue("/events/2.mp4", listOf(task("1.mp4", UsbExportState.COMPLETED))))
    }
    @Test fun noUsbShowsRetainedLocalEvent() {
        assertEquals("本机已保存；等待 USB", GuardUsbSavePolicy.label(event, emptyList(), "本机已保存；等待 USB"))
        assertEquals("暂无已完成分段可保存到 USB",
            GuardUsbSavePolicy.label(event.copy(state = "FAILED", assets = emptyList()), emptyList(), null))
    }
}
