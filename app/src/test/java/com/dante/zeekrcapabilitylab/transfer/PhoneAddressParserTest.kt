package com.dante.zeekrcapabilitylab.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAddressParserTest {
    @Test
    fun acceptsPlainIpv4WithDefaultPort() {
        val address = PhoneAddressParser.parse("192.0.0.2").getOrThrow()

        assertEquals("192.0.0.2", address.host)
        assertEquals(8766, address.port)
        assertEquals("192.0.0.2:8766", address.displayValue)
    }

    @Test
    fun acceptsAddressCopiedFromCompanionIncludingPort() {
        val address = PhoneAddressParser.parse(" 192.168.5.123:8766 ").getOrThrow()

        assertEquals("192.168.5.123", address.host)
        assertEquals(8766, address.port)
    }

    @Test
    fun acceptsHttpUrlAndExplicitCustomPort() {
        val address = PhoneAddressParser.parse("http://192.168.5.123:9876/").getOrThrow()

        assertEquals("192.168.5.123", address.host)
        assertEquals(9876, address.port)
    }

    @Test
    fun rejectsDoublePortAndUnexpectedUrlParts() {
        assertTrue(PhoneAddressParser.parse("192.0.0.2:8766:8766").isFailure)
        assertTrue(PhoneAddressParser.parse("192.0.0.2:8766/api/pair").isFailure)
        assertTrue(PhoneAddressParser.parse("https://192.0.0.2:8766").isFailure)
    }
}
