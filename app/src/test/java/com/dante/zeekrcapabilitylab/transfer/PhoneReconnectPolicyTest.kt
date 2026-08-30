package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.DiscoveryReply
import io.github.dantenothing.avmtransfer.protocol.HealthResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneReconnectPolicyTest {
    private val saved = PhoneEndpoint(
        host = "192.168.5.123",
        port = 8766,
        token = "long-term-secret",
        phoneName = "Old name",
        phoneId = "phone-1",
    )

    @Test
    fun samePhoneCanMoveAddressWithoutPairingAgain() {
        val migrated = PhoneReconnectPolicy.migrate(
            saved = saved,
            discovery = DiscoveryReply(deviceName = "My phone", ip = "192.0.0.2", port = 8766),
            health = HealthResponse(deviceName = "My phone", phoneDeviceId = "phone-1"),
        )

        assertEquals("192.0.0.2", migrated?.host)
        assertEquals(8766, migrated?.port)
        assertEquals("long-term-secret", migrated?.token)
        assertEquals("phone-1", migrated?.phoneId)
        assertEquals("My phone", migrated?.phoneName)
    }

    @Test
    fun differentOrUnexpectedReceiverCannotReplaceSavedPairing() {
        val discovery = DiscoveryReply(deviceName = "Other phone", ip = "192.0.0.2", port = 8766)

        assertNull(
            PhoneReconnectPolicy.migrate(
                saved,
                discovery,
                HealthResponse(deviceName = "Other phone", phoneDeviceId = "phone-2"),
            ),
        )
        assertNull(
            PhoneReconnectPolicy.migrate(
                saved,
                discovery,
                HealthResponse(service = "unexpected", deviceName = "My phone", phoneDeviceId = "phone-1"),
            ),
        )
    }
}
