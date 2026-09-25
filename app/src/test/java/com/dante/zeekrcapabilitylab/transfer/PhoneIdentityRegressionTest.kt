package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.DiscoveryReply
import io.github.dantenothing.avmtransfer.protocol.HealthResponse
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneIdentityRegressionTest {
    @Test fun publicPhoneIdDoesNotAuthorizeSendingSavedTokenToAnotherAddress() {
        val saved = PhoneEndpoint("192.168.1.2", 8766, "test-only-token", "Phone", "public-id")
        val impostor = DiscoveryReply(deviceName = "Phone", ip = "192.168.1.3")
        val publicHealth = HealthResponse(deviceName = "Phone", phoneDeviceId = "public-id")
        assertNull(PhoneReconnectPolicy.migrate(saved, impostor, publicHealth))
    }
}
