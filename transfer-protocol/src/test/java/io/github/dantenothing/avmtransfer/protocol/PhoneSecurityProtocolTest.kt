package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PhoneSecurityProtocolTest {
    private val identity = """{"securityVersion":2,"phoneDeviceId":"phone-test","tlsPort":8767,"certificateDerBase64":"YQ=="}"""

    @Test fun identityMatchesTheSharedWireContract() {
        val decoded = PhoneIdentityJson.decode(identity)
        assertEquals(2, decoded.securityVersion)
        assertEquals("phone-test", decoded.phoneDeviceId)
        assertEquals(8767, decoded.tlsPort)
    }

    @Test fun duplicateOrEscapedDuplicateKeysCannotOverrideTheIdentity() {
        for (key in listOf("tlsPort", "tls\\u0050ort")) {
            assertTrue(runCatching { PhoneIdentityJson.decode(identity.dropLast(1) + ",\"$key\":9000}") }.isFailure)
        }
    }

    @Test fun missingSecurityVersionMalformedOrOversizedIdentityIsRejected() {
        for (value in listOf(identity.replace("\"securityVersion\":2,", ""), "[]", "not-json", " ".repeat(PhoneSecurityProtocol.MAX_IDENTITY_BYTES) + identity)) {
            assertTrue(runCatching { PhoneIdentityJson.decode(value) }.isFailure)
        }
    }

    @Test fun unrelatedNestedFieldsDoNotConfuseDuplicateDetection() {
        val value = identity.dropLast(1) + """, "extra":{"tlsPort":90,"label":"a \\\"quoted\\\" value"}}"""
        assertEquals(8767, PhoneIdentityJson.decode(value).tlsPort)
    }

    @Test fun oldDiscoveryAndHealthStillDecodeWithoutGrantingSecurity() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(DiscoveryReply.serializer(), """{"deviceName":"Phone","ip":"192.0.0.2"}""").securityVersions.isEmpty())
        assertNull(json.decodeFromString(HealthResponse.serializer(), """{"deviceName":"Phone","phoneDeviceId":"phone-test"}""").tlsPort)
        assertTrue(runCatching { json.decodeFromString(SecurePhonePairResponse.serializer(), """{"token":"test","phoneDeviceId":"phone-test","deviceName":"Phone"}""") }.isFailure)
    }
}
