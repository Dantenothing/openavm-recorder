package com.dante.zeekrbridge.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SharedPhoneCertificateTest {
    @Test fun rc2WireSamplesDecodeWithTheSameRequiredSecurityFields() {
        val loader = javaClass.classLoader!!
        fun sample(name: String) = loader.getResourceAsStream("phone-security-wire/$name.json")!!.bufferedReader().use { it.readText() }
        val identity = io.github.dantenothing.avmtransfer.protocol.PhoneIdentityJson.decode(sample("identity-response"))
        assertEquals(2, identity.securityVersion)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(java.util.Base64.getDecoder().decode(identity.certificateDerBase64).inputStream()) as X509Certificate
        val expected = Json.parseToJsonElement(sample("expected-fingerprint")).jsonObject
        assertEquals(expected["hex"]!!.jsonPrimitive.content, PhoneIdentityCertificate.fingerprint(cert))
        assertEquals(expected["display"]!!.jsonPrimitive.content, PhoneIdentityCertificate.displayFingerprint(PhoneIdentityCertificate.fingerprint(cert)))
        val pair = Json.decodeFromString(io.github.dantenothing.avmtransfer.protocol.SecurePhonePairResponse.serializer(), sample("pair-response"))
        assertEquals(2, pair.securityVersion)
        val session = Json.decodeFromString(io.github.dantenothing.avmtransfer.protocol.PhoneSecuritySession.serializer(), sample("session-response"))
        assertEquals(1, session.version); assertEquals(2, session.securityVersion)
        assertEquals("openavm-transfer", session.service)
    }

    @Test fun independentlyComputedFingerprintsMatchCarSidePublicFixtures() {
        val loader = javaClass.classLoader!!
        val pins = loader.getResourceAsStream("phone-security/pins.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        for ((name, pin) in pins) {
            val cert = loader.getResourceAsStream("phone-security/$name.der")!!.use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            assertEquals(name, pin.jsonPrimitive.content, PhoneIdentityCertificate.fingerprint(cert))
        }
        assertEquals(pins["phone"], pins["renewed"])
        assertNotEquals(pins["phone"], pins["impostor"])
    }
}
