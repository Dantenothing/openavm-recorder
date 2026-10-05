package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.CabinRecordingProfile
import org.junit.Assert.*
import org.junit.Test

class CabinRecordingProfileTest {
    @Test fun advertisedHdIsNotSilentlyReducedToVga() {
        val result = CabinRecordingProfile.choose(listOf(ProfileSize(640,480), ProfileSize(1280,720), ProfileSize(1920,1080)))!!
        assertEquals(ProfileSize(1280,720), result.size)
        assertEquals(4_000_000, result.bitrateBps)
    }
    @Test fun lowResolutionCameraKeepsItsRealSize() {
        val result = CabinRecordingProfile.choose(listOf(ProfileSize(640,480)))!!
        assertEquals(ProfileSize(640,480), result.size)
        assertEquals(2_000_000, result.bitrateBps)
    }
    @Test fun noFabricatedSizeAndNoUnboundedConcurrentInput() {
        assertNull(CabinRecordingProfile.choose(emptyList()))
        assertNull(CabinRecordingProfile.choose(listOf(ProfileSize(1920,1080), ProfileSize(1280,5140), ProfileSize(-1,720))))
        assertEquals(ProfileSize(960,720), CabinRecordingProfile.choose(listOf(ProfileSize(640,480), ProfileSize(960,720)))!!.size)
    }
}
