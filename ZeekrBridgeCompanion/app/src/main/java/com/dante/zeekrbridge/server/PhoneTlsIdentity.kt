package com.dante.zeekrbridge.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.dantenothing.avmtransfer.protocol.PhoneSecurityProtocol
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext

data class PhoneTlsMaterial(val certificate: X509Certificate, val context: SSLContext) {
    val fingerprint: String get() = PhoneIdentityCertificate.fingerprint(certificate)
}

/** The private identity key never leaves Android Keystore, including during renewal. */
object PhoneTlsIdentity {
    private const val ALIAS = "openavm_phone_tls_identity_v2"
    @Synchronized fun load(context: Context): PhoneTlsMaterial {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt hashes the TLS transcript itself, then asks Keystore
                    // to sign that digest. NONE authorizes this raw digest operation;
                    // certificate issuance still uses SHA256withECDSA.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        // Existing but unreadable keys fail closed. Never silently overwrite an identity.
        val key = store.getKey(ALIAS, null) as PrivateKey
        val pair = KeyPair(store.getCertificate(ALIAS).publicKey, key)
        val file = AtomicFile(File(context.noBackupFilesDir, "openavm-phone-tls-certificate.der"))
        val old = runCatching {
            val bytes = file.openRead().use { it.readBytes() }
            require(bytes.size <= PhoneSecurityProtocol.MAX_CERTIFICATE_BYTES)
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        }.getOrNull()
        val now = System.currentTimeMillis()
        val reusable = old != null && runCatching {
            require(old.publicKey.encoded.contentEquals(pair.public.encoded))
            old.checkValidity()
            old.verify(pair.public)
            require(old.subjectAlternativeNames.orEmpty().any { it[0] == 2 && it[1] == PhoneSecurityProtocol.TLS_NAME })
            require(old.notAfter.time > now + 30 * PhoneIdentityCertificate.DAY_MS)
        }.isSuccess
        val certificate = if (reusable) old!! else PhoneIdentityCertificate.issue(pair, now).also { cert ->
            val output = file.startWrite()
            try { output.write(cert.encoded); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        }
        return PhoneTlsMaterial(certificate, PhoneIdentityCertificate.context(key, certificate))
    }
}
