package com.dante.zeekrcapabilitylab.usbexport

import com.dante.zeekrcapabilitylab.enhancement.CameraWorkState
import org.junit.Assert.*
import org.junit.Test

class UsbDeleteAdmissionTest {
    @Test fun residentPreviewDoesNotBlockCompletedUsbVideos() {
        val preview = CameraWorkState(owner = "mirror-preview", kind = "PREVIEW")
        assertTrue(preview.active)
        assertNull(UsbDeleteAdmission.blockingReason(false, preview))
    }
    @Test fun recordingOrFinalizingServiceStillBlocksEvenWhenAuxiliarySaysPreview() {
        for (aux in listOf(CameraWorkState(), CameraWorkState("mirror", "PREVIEW"))) {
            assertEquals("RECORDING_ACTIVE", UsbDeleteAdmission.blockingReason(true, aux))
        }
    }
    @Test fun twoCameraRecorderStillBlocksWithoutTheMainRecorderService() {
        assertEquals("RECORDING_ACTIVE", UsbDeleteAdmission.blockingReason(false, CameraWorkState("two", "MULTI")))
    }
    @Test fun idleIsAllowed() {
        assertNull(UsbDeleteAdmission.blockingReason(false, CameraWorkState()))
    }
    @Test fun unknownCameraWorkIsNotSilentlyTreatedAsPreview() {
        assertEquals("CAMERA_WORK_BUSY", UsbDeleteAdmission.blockingReason(false, CameraWorkState("unknown", "OTHER")))
    }
    @Test fun unconfirmedPreviewCleanupHasAnAccurateBusyReason() {
        assertEquals("CAMERA_WORK_BUSY", UsbDeleteAdmission.blockingReason(false,
            CameraWorkState("mirror", "PREVIEW", error = "CLEANUP_UNCONFIRMED")))
    }
}
