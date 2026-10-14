package com.dante.zeekrcapabilitylab.preflight.remote

import org.junit.Assert.*
import org.junit.Test

class ProductSupportAdmissionTest {
    @Test fun ordinaryPreviewMayBeReleasedButAnotherJobOrUnconfirmedOwnerBlocks() {
        assertTrue(LabPolicy.productStartReady(false,0,"",true,false))
        assertTrue(LabPolicy.productStartReady(false,0,"PREVIEW",false,false))
        assertFalse(LabPolicy.productStartReady(true,0,"PREVIEW",false,false))
        assertFalse(LabPolicy.productStartReady(false,1,"PREVIEW",false,false))
        assertFalse(LabPolicy.productStartReady(false,0,"PROBE",true,false))
        assertFalse(LabPolicy.productStartReady(false,0,"",false,false))
        assertFalse(LabPolicy.productStartReady(false,0,"PREVIEW",false,true))
    }
    @Test fun productProfilesCannotOverrideCamerasOrRunTime() {
        for(profile in LabExperiment.productProfiles) {
            assertTrue(LabExperiment.parse(com.dante.zeekrcapabilitylab.preflight.obj("profile" to profile)).product)
            assertThrows(IllegalArgumentException::class.java) {
                LabExperiment.parse(com.dante.zeekrcapabilitylab.preflight.obj("profile" to profile,"bitrateBps" to 8000000))
            }
        }
    }
}
