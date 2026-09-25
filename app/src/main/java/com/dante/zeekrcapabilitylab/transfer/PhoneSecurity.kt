package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.PhoneSecurityIdentity
import io.github.dantenothing.avmtransfer.protocol.PhoneSecurityProtocol
import io.github.dantenothing.avmtransfer.protocol.ProtocolValidation
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.net.ssl.X509TrustManager

data class PhoneEndpoint(
    val host: String,
    val port: Int,
    internal val token: String,
    val phoneName: String,
    val phoneId: String,
    val securityVersion: Int = 0,
    val tlsPort: Int = 0,
    val publicKeySha256: String = "",
    val pairingGeneration: Long = 0,
) {
    val securelyPaired: Boolean get() = securityVersion == PhoneSecurityProtocol.VERSION &&
        tlsPort in 1..65535 && PhoneCertificate.validPin(publicKeySha256)

    fun samePairing(other: PhoneEndpoint): Boolean = pairingGeneration == other.pairingGeneration &&
        phoneId == other.phoneId && token == other.token && securityVersion == other.securityVersion &&
        publicKeySha256 == other.publicKeySha256

    override fun toString() = "PhoneEndpoint(securityVersion=$securityVersion, generation=$pairingGeneration, token=<redacted>)"
}

data class PhoneConnectionState(
    val endpoint: PhoneEndpoint? = null,
    val connected: Boolean = false,
    val message: String = "Not connected",
    val securityError: PhoneSecurityError? = null,
)

enum class PhoneSecurityError(val retryable: Boolean = false) {
    PHONE_UPGRADE_REQUIRED, SECURE_PAIRING_REQUIRED, PHONE_IDENTITY_CHANGED, AUTH_REVOKED,
    PAIR_CODE_REJECTED, PAIR_RATE_LIMITED, PAIRING_EXPIRED, INVALID_PHONE_RESPONSE,
    CONNECTION_REPLACED, PHONE_UNAVAILABLE(true),
}

class PhoneSecurityException(val error: PhoneSecurityError) : IOException(error.name)

/** Immutable candidate; obtaining this is not permission to send a pairing code. */
class PhonePairingCandidate internal constructor(
    val host: String,
    val discoveryPort: Int,
    val phoneId: String,
    val tlsPort: Int,
    val publicKeySha256: String,
    internal val startedAtNanos: Long,
    internal val pairingGeneration: Long,
) {
    val displayFingerprint: String get() = publicKeySha256.uppercase().chunked(4).chunked(4)
        .joinToString("\n") { it.joinToString(" ") }
}

object PhoneCertificate {
    private val pinPattern = Regex("^[0-9a-f]{64}$")
    fun validPin(value: String) = pinPattern.matches(value)
    fun pin(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }

    fun decode(identity: PhoneSecurityIdentity): X509Certificate {
        if (identity.securityVersion != PhoneSecurityProtocol.VERSION) throw PhoneSecurityException(PhoneSecurityError.PHONE_UPGRADE_REQUIRED)
        if (!ProtocolValidation.validIdentity(identity.phoneDeviceId) || identity.tlsPort !in 1..65535 ||
            identity.certificateDerBase64.length > 4 * ((PhoneSecurityProtocol.MAX_CERTIFICATE_BYTES + 2) / 3)) {
            throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
        }
        return try {
            val bytes = Base64.getDecoder().decode(identity.certificateDerBase64)
            require(bytes.size in 1..PhoneSecurityProtocol.MAX_CERTIFICATE_BYTES)
            val input = ByteArrayInputStream(bytes)
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            require(input.available() == 0 && bytes.contentEquals(certificate.encoded))
            validate(certificate)
            val names = certificate.subjectAlternativeNames.orEmpty()
            require(names.any { it.size >= 2 && it[0] == 2 && it[1] == PhoneSecurityProtocol.TLS_NAME })
            certificate
        } catch (_: Exception) {
            throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
        }
    }

    private fun validate(certificate: X509Certificate) {
        certificate.checkValidity()
        require(certificate.subjectX500Principal == certificate.issuerX500Principal)
        require(certificate.sigAlgName.uppercase().replace("-", "") in setOf("SHA256WITHECDSA", "SHA384WITHECDSA", "SHA512WITHECDSA", "SHA256WITHRSA", "SHA384WITHRSA", "SHA512WITHRSA"))
        when (val key = certificate.publicKey) {
            is ECPublicKey -> require(key.params.curve.field.fieldSize >= 256)
            is RSAPublicKey -> require(key.modulus.bitLength() >= 2048)
            else -> error("Unsupported phone identity key")
        }
        certificate.verify(certificate.publicKey)
        certificate.keyUsage?.let { require(it.isNotEmpty() && it[0]) }
        certificate.extendedKeyUsage?.let { require("1.3.6.1.5.5.7.3.1" in it) }
        require(!certificate.hasUnsupportedCriticalExtension())
    }

    /** Trust exactly the user-confirmed leaf public key; never consult arbitrary LAN roots. */
    fun trustManager(expectedPin: String): X509TrustManager {
        require(validPin(expectedPin))
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
                throw CertificateException("Client certificates are not supported")
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                try {
                    require(chain?.size == 1)
                    val certificate = chain!![0]
                    require(MessageDigest.isEqual(expectedPin.toByteArray(Charsets.US_ASCII), pin(certificate).toByteArray(Charsets.US_ASCII)))
                    validate(certificate)
                } catch (_: Exception) {
                    throw CertificateException("Phone identity validation failed")
                }
            }
        }
    }
}
