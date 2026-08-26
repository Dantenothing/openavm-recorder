package com.dante.zeekrbridge.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsIdentityTest {

    @Test
    fun exactCarIdMatches() {
        assertTrue(WsIdentity.matches("car-1", "car-1"))
    }

    @Test
    fun mismatchedOrMissingIdentityRejected() {
        assertFalse(WsIdentity.matches("car-1", "car-2"))
        assertFalse(WsIdentity.matches(null, "car-1"))
        assertFalse(WsIdentity.matches("car-1", null))
        assertFalse(WsIdentity.matches("car-1", ""))
    }
}
