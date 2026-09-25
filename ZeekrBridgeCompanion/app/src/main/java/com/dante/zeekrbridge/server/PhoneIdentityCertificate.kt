package com.dante.zeekrbridge.server

import io.github.dantenothing.avmtransfer.protocol.PhoneSecurityProtocol
import okhttp3.tls.HeldCertificate
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Standard X.509/TLS only; no application-specific encryption protocol. */
object PhoneIdentityCertificate {
    const val DAY_MS = 86_400_000L
    fun issue(pair: KeyPair, now: Long = System.currentTimeMillis()): X509Certificate =
        HeldCertificate.Builder().keyPair(pair).commonName(PhoneSecurityProtocol.TLS_NAME)
            .addSubjectAlternativeName(PhoneSecurityProtocol.TLS_NAME)
            .serialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
            .validityInterval(now - DAY_MS, now + 365 * DAY_MS).build().certificate

    fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }

    fun displayFingerprint(pin: String): String {
        require(pin.matches(Regex("[0-9a-f]{64}")))
        return pin.uppercase().chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }
    }

    fun context(privateKey: PrivateKey, certificate: X509Certificate): SSLContext {
        val manager = object : X509ExtendedKeyManager() {
            override fun getCertificateChain(alias: String?) = if (alias == "phone") arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?) = if (alias == "phone") privateKey else null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == privateKey.algorithm) arrayOf("phone") else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                getServerAliases(keyType, issuers)?.firstOrNull()
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
                getServerAliases(keyType, issuers)?.firstOrNull()
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, SecureRandom()) }
    }
}
