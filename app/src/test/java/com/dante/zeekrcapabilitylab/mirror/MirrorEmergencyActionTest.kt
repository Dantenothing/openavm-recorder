package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import org.junit.Assert.*
import org.junit.Test

class MirrorEmergencyActionTest {
    @Test fun onlyNormalRecordingAdmitsEmergencyBookmark() {
        assertTrue(MirrorEmergencyAction.allowed(false, RecorderStatus.RECORDING, RecordingMode.NORMAL, false))
        assertFalse(MirrorEmergencyAction.allowed(true, RecorderStatus.RECORDING, RecordingMode.NORMAL, false))
        assertFalse(MirrorEmergencyAction.allowed(false, RecorderStatus.RECORDING, RecordingMode.TIME_LAPSE, false))
        assertFalse(MirrorEmergencyAction.allowed(false, RecorderStatus.RECORDING, RecordingMode.NORMAL, true))
    }
    @Test fun stoppingWaitingAndIdleNeverCreateAnEmergencyRecordingCommand() {
        listOf(RecorderStatus.IDLE, RecorderStatus.STOPPED, RecorderStatus.FINALIZING,
            RecorderStatus.WAITING_CAMERA, RecorderStatus.RESUMING).forEach {
            assertFalse(MirrorEmergencyAction.allowed(false, it, RecordingMode.NORMAL, false))
        }
    }
}
