package com.dante.zeekrbridge.server

import com.dante.zeekrbridge.core.ConnectionLogRedactor
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest

class PhoneSecurityPolicyTest {
    @Test fun publicSurfaceIsAnExactGetAllowlist() {
        for (path in listOf("/api/pair", "/api/pair/begin", "/api/pair/finalize", "/api/session", "/api/uploads", "/api/uploads/id", "/api/outbound", "/control", "/health/../api/pair")) {
            for (method in listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")) assertFalse(PhoneTransportPolicy.publicRequest(method, path))
        }
        assertTrue(PhoneTransportPolicy.publicRequest("GET", "/health"))
        assertTrue(PhoneTransportPolicy.publicRequest("GET", "/api/security/identity"))
        assertFalse(PhoneTransportPolicy.publicRequest("POST", "/health"))
    }
    @Test fun oldAndNewLogsCannotCarryPairingSecrets() {
        for (value in listOf("SERVER_STARTED code=123456", "pairingId=123456", "{\"token\":\"fake-token-value\"}", "Authorization: Bearer fake-token-value", "exchangeToken=fake-token-value")) {
            val safe = ConnectionLogRedactor.redact(value)
            assertFalse(safe.contains("123456")); assertFalse(safe.contains("fake-token-value"))
        }
    }
    @Test fun certificateContainsRequiredSanAndFullIndependentSpkiFingerprint() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val certificate = PhoneIdentityCertificate.issue(pair)
        certificate.checkValidity(); certificate.verify(pair.public)
        assertEquals("SHA256withECDSA", certificate.sigAlgName)
        assertTrue(certificate.subjectAlternativeNames.any { it[0] == 2 && it[1] == "openavm-phone.invalid" })
        val independent = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pair.public.encoded))
        assertEquals(independent, PhoneIdentityCertificate.fingerprint(certificate))
        assertEquals(64, PhoneIdentityCertificate.displayFingerprint(independent).filter { it.isLetterOrDigit() }.length)
        val renewed = PhoneIdentityCertificate.issue(pair, System.currentTimeMillis() + 5000)
        assertEquals(independent, PhoneIdentityCertificate.fingerprint(renewed))
        assertFalse(certificate.encoded.contentEquals(renewed.encoded))
    }
}
