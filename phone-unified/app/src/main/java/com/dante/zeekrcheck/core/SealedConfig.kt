package com.dante.zeekrcheck.core

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Version, IV and ciphertext are authenticated. No plaintext fallback. */
object SealedConfig {
    enum class Purpose(val context: String) { PROTOCOL("zeekr-check/protocol/1"), IMPORTED_PROTOCOL("openavm/imported-protocol/1"), SESSION("zeekr-check/session/1"), ASSISTANT("zeekr-assistant/settings/1"), APPEARANCE("zeekr-assistant/appearance/1") }
    const val MAX_BYTES = 65_536
    fun seal(plain: ByteArray, key: SecretKey, purpose: Purpose = Purpose.PROTOCOL): ByteArray {
        require(plain.size in 1..MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(purpose.context.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }
    fun open(sealed: ByteArray, key: SecretKey, purpose: Purpose = Purpose.PROTOCOL): ByteArray {
        require(sealed.size in 30..(MAX_BYTES + 29) && sealed[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        cipher.updateAAD(purpose.context.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, 13, sealed.size - 13)
    }
}
