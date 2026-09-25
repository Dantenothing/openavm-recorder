package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorAppEntryGateTest {
    private fun request(gate: MirrorAppEntryGate, foreground: Boolean = true,
                        enabled: Boolean = true, resident: Boolean = true,
                        camera: Boolean = true, overlay: Boolean = true, rear: Boolean = true,
                        surround: Boolean = true,
                        recording: Boolean = false, auxiliary: Boolean = false, controls: Boolean = false) =
        gate.request(foreground, enabled, resident, camera, overlay, rear, surround,
            recording, auxiliary, controls)

    @Test fun configuredHomeEntryRequestsPurePreviewExactlyOnce() {
        val gate = MirrorAppEntryGate()
        assertTrue(request(gate))
        assertFalse(request(gate)) // Recomposition or closing the controls is not another request.
        assertFalse(request(gate))
    }
    @Test fun aNewForegroundVisitCanRestorePreviewWithoutRestartingTheProcess() {
        val gate = MirrorAppEntryGate()
        assertTrue(request(gate))
        assertFalse(request(gate))
        assertFalse(request(gate, foreground = false))
        assertTrue(request(gate))
        assertFalse(request(gate))
    }
    @Test fun returningToAnActiveRecordingDoesNotOpenAnotherProducer() {
        val gate = MirrorAppEntryGate()
        assertTrue(request(gate))
        assertFalse(request(gate, foreground = false))
        assertFalse(request(gate, recording = true))
        assertFalse(request(gate)) // A subsequent Stop during this visit is not an entry event.
    }
    @Test fun backgroundPageCannotStartCameraButAnExplicitForegroundVisitCan() {
        val gate = MirrorAppEntryGate()
        assertFalse(request(gate, foreground = false))
        assertTrue(request(gate))
    }
    @Test fun permissionResultCanCompleteTheFirstForegroundRequest() {
        val gate = MirrorAppEntryGate()
        assertFalse(request(gate, camera = false))
        assertTrue(request(gate))
    }
    @Test fun disabledUncalibratedOrNonresidentMirrorDoesNotAutoOpen() {
        for (missing in 0..3) {
            val gate = MirrorAppEntryGate()
            assertFalse(request(gate, enabled = missing != 0, resident = missing != 1,
                overlay = missing != 2, rear = missing != 3))
            assertFalse(request(gate)) // Requires a new page visit or explicit Open, no late retry.
        }
    }
    @Test fun anExistingOwnerConsumesTheEntrySoStoppingItCannotTriggerAnotherCameraStart() {
        for (owner in 0..2) {
            val gate = MirrorAppEntryGate()
            assertFalse(request(gate, recording = owner == 0, auxiliary = owner == 1, controls = owner == 2))
            assertFalse(request(gate))
        }
    }
    @Test fun selectingCabinMustNotStartASurroundPreviewLater() {
        val gate = MirrorAppEntryGate()
        assertFalse(request(gate, surround = false))
        assertFalse(request(gate))
    }
}
