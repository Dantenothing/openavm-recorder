package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun validPayloadParses() {
        val now = System.currentTimeMillis()
        val raw = "zeekr-sidecar://pair?v=1&car=car-abc&host=192.168.43.5&port=8877&token=tok123&exp=${now + 60_000}"
        val parsed = PairingPayload.parse(raw)
        assertNotNull(parsed)
        assertEquals(1, parsed?.version)
        assertEquals("car-abc", parsed?.carId)
        assertEquals("192.168.43.5", parsed?.host)
        assertEquals(8877, parsed?.port)
        assertEquals("tok123", parsed?.token)
        assertTrue(PairingPayload.isValid(parsed!!, now))
    }

    @Test
    fun expiredPayloadRejected() {
        val now = System.currentTimeMillis()
        val raw = "zeekr-sidecar://pair?v=1&car=car-abc&host=192.168.43.5&port=8877&token=tok&exp=${now - 1}"
        assertFalse(PairingPayload.isValid(PairingPayload.parse(raw)!!, now))
    }

    @Test
    fun wrongVersionOrHostRejected() {
        val wrongVersion = PairingPayload.parse("zeekr-sidecar://pair?v=2&car=a&host=1.2.3.4&port=1&token=t&exp=1")
        assertFalse(PairingPayload.isValid(wrongVersion!!, System.currentTimeMillis()))
        assertNull(PairingPayload.parse("zeekr-sidecar://other?v=1&car=a&host=1.2.3.4&port=1&token=t&exp=1"))
    }

    @Test
    fun urlEncodedValuesRoundTrip() {
        val raw = "zeekr-sidecar://pair?v=1&car=car%20x&host=10.0.0.2&port=8877&token=t&exp=${System.currentTimeMillis() + 60_000}"
        assertEquals("car x", PairingPayload.parse(raw)?.carId)
    }
}
