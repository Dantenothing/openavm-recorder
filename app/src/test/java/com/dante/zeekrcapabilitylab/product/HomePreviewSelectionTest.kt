package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.enhancement.CameraWorkState
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole.*
import org.junit.Assert.*
import org.junit.Test

class HomePreviewSelectionTest {
    private fun allowed(work: CameraWorkState = CameraWorkState(), recording: Boolean = false,
                        service: Boolean = false, mirror: Boolean = true, busy: Boolean = false,
                        loading: Boolean = false) = HomePreviewSelection.canSelect(recording, service, work, mirror, busy, loading)

    @Test fun previewOnlyAllowsSelectingCabinOrInfraredWithoutManualStop() {
        assertTrue(allowed(CameraWorkState(owner = "preview", kind = "PREVIEW")))
        assertTrue(allowed()) // resident controls can remain while the video is hidden
    }
    @Test fun recordingSavingAndCameraTransitionsStillBlockSourceChanges() {
        assertFalse(allowed(recording = true))
        assertFalse(allowed(service = true))
        assertFalse(allowed(busy = true))
        assertFalse(allowed(loading = true))
        assertFalse(allowed(CameraWorkState(owner = "multi", kind = "MULTI")))
        assertFalse(allowed(CameraWorkState(owner = "unknown", kind = "")))
        assertFalse(allowed(CameraWorkState(owner = "preview", kind = "PREVIEW", error = "CLEANUP_UNCONFIRMED")))
    }
    @Test fun enteringHomeKeepsTheActualCabinSourceInsteadOfSurroundGrid() {
        assertEquals(CABIN, HomePreviewSelection.displayedSource(SURROUND, false, null, true, CABIN))
        assertEquals(SURROUND, HomePreviewSelection.displayedSource(CABIN, false, null, true, SURROUND))
    }
    @Test fun actualRecordingAndLocalInfraredRemainAuthoritative() {
        assertEquals(IR, HomePreviewSelection.displayedSource(SURROUND, true, IR, true, CABIN))
        assertEquals(IR, HomePreviewSelection.displayedSource(IR, false, null, false, CABIN))
        assertEquals(CABIN, HomePreviewSelection.displayedSource(CABIN, false, null, true, null))
    }
}
