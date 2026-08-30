package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingRulesTest {

    @Test
    fun carDeviceIdMustBeFlatAndBounded() {
        assertEquals("abc-123_XYZ", PairingRules.saneCarDeviceId(" abc-123_XYZ "))
        assertNull(PairingRules.saneCarDeviceId(""))
        assertNull(PairingRules.saneCarDeviceId("a/b"))
        assertNull(PairingRules.saneCarDeviceId(".."))
        assertNull(PairingRules.saneCarDeviceId("a.b"))
        assertNull(PairingRules.saneCarDeviceId("a".repeat(129)))
    }

    @Test
    fun deviceNameMustBeBoundedAndClean() {
        assertEquals("Zeekr Car", PairingRules.saneDeviceName(" Zeekr Car "))
        assertNull(PairingRules.saneDeviceName(""))
        assertNull(PairingRules.saneDeviceName("a".repeat(65)))
        assertNull(PairingRules.saneDeviceName("bad\u0000name"))
    }
}
