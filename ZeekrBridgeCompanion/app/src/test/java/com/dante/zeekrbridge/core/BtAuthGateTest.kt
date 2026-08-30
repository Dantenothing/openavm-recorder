package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtAuthGateTest {
    private val device = PairedDevice(
        carDeviceId = "car-1",
        name = "Zeekr Car",
        token = "abc",
        pairedAt = 1L,
    )

    @Test
    fun startsUnauthenticated() {
        val gate = BtAuthGate()
        assertFalse(gate.authenticated)
        assertNull(gate.device())
    }

    @Test
    fun helloAuthenticatesAndRemembersDevice() {
        val gate = BtAuthGate()
        gate.authenticate(device)
        assertTrue(gate.authenticated)
        assertEquals(device, gate.device())
    }

    @Test
    fun onlyPairAndHelloAreUnauthenticatedEntryPoints() {
        assertFalse(BtAuthGate.requiresAuth("PAIR"))
        assertFalse(BtAuthGate.requiresAuth("HELLO"))
        assertTrue(BtAuthGate.requiresAuth("HEARTBEAT"))
        assertTrue(BtAuthGate.requiresAuth("UPLOAD_CREATE"))
        assertTrue(BtAuthGate.requiresAuth("UPLOAD_STATUS"))
        assertTrue(BtAuthGate.requiresAuth("CHUNK"))
        assertTrue(BtAuthGate.requiresAuth("UPLOAD_COMPLETE"))
    }

    @Test
    fun clearResetsAuthentication() {
        val gate = BtAuthGate()
        gate.authenticate(device)
        gate.clear()
        assertFalse(gate.authenticated)
    }
}
