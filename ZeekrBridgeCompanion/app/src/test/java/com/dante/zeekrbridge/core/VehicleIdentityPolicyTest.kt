package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleIdentityPolicyTest {
    @Test
    fun appLabPixelModelIsPresentedAsZeekr7x() {
        assertEquals("ZEEKR 7X", VehicleIdentityPolicy.displayName("Pixel 3a"))
    }

    @Test
    fun blankNameUsesProductDefault() {
        assertEquals("ZEEKR 7X", VehicleIdentityPolicy.displayName("  "))
    }

    @Test
    fun explicitVehicleNicknameIsPreserved() {
        assertEquals("Dante's 7X", VehicleIdentityPolicy.displayName("Dante's 7X"))
    }
}
