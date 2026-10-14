package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import org.junit.Assert.*
import org.junit.Test

class MirrorHudSourcePolicyTest {
    @Test fun returningPreviewCanReadWithoutRecording() {
        assertTrue(MirrorHudSourcePolicy.previewReads(true, true, true, RecorderStatus.IDLE))
        assertTrue(MirrorHudSourcePolicy.previewReads(true, true, true, RecorderStatus.PREVIEWING))
    }
    @Test fun hiddenSleepingOrOptedOutHudDoesNotRead() {
        assertFalse(MirrorHudSourcePolicy.previewReads(false, true, true, RecorderStatus.IDLE))
        assertFalse(MirrorHudSourcePolicy.previewReads(true, false, true, RecorderStatus.IDLE))
        assertFalse(MirrorHudSourcePolicy.previewReads(true, true, false, RecorderStatus.IDLE))
    }
    @Test fun recordingAndHandoffsKeepTheirExistingSingleProducer() {
        listOf(RecorderStatus.RECORDING, RecorderStatus.STARTING, RecorderStatus.FINALIZING,
            RecorderStatus.WAITING_CAMERA, RecorderStatus.RESUMING).forEach {
            assertFalse(it, MirrorHudSourcePolicy.previewReads(true, true, true, it))
        }
    }
}
