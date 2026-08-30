package io.github.dantenothing.openavmreceiver

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.dantenothing.avmtransfer.protocol.PairRequest
import io.github.dantenothing.avmtransfer.protocol.PairResponse
import io.github.dantenothing.avmtransfer.protocol.ProtocolValidation
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PairingStore {
    private const val PREFS = "pairing"
    private const val KEY_ALIAS = "openavm_phone_pairing"
    private lateinit var preferences: SharedPreferences
    private val random = SecureRandom()
    private val lock = Any()
    private val _code = MutableStateFlow("")
    val code = _code.asStateFlow()
    private val _expiresAt = MutableStateFlow(0L)
    val expiresAt = _expiresAt.asStateFlow()
    val phoneId: String get() = preferences.getString("phoneId", null) ?: error("not initialized")
    val phoneName: String get() = Build.MODEL.ifBlank { "Android phone" }

    fun init(appContext: Context) {
        preferences = appContext.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val p = preferences
        if (p.getString("phoneId", null) == null) p.edit().putString("phoneId", UUID.randomUUID().toString()).apply()
        _code.value = p.getString("code", "").orEmpty()
        _expiresAt.value = p.getLong("expiresAt", 0L)
    }

    fun newCode(): String = synchronized(lock) {
        val value = (random.nextInt(900_000) + 100_000).toString()
        _code.value = value
        _expiresAt.value = System.currentTimeMillis() + TransferProtocol.PAIR_CODE_TTL_MS
        preferences.edit().putString("code", value).putLong("expiresAt", _expiresAt.value).apply()
        value
    }

    fun pair(request: PairRequest): PairResponse? = synchronized(lock) {
        if (request.code != _code.value || System.currentTimeMillis() >= _expiresAt.value) return null
        if (!ProtocolValidation.validIdentity(request.carDeviceId)) return null
        if (request.deviceName.isBlank() || request.deviceName.length > 64) return null
        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        preferences.edit()
            .putString("car.${request.carDeviceId}.name", request.deviceName)
            .putString("car.${request.carDeviceId}.token", encrypt(token))
            .putString("code", "").putLong("expiresAt", 0L).apply()
        _code.value = ""
        _expiresAt.value = 0L
        PairResponse(token, phoneId, phoneName)
    }

    fun authenticate(header: String?): String? {
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim() ?: return null
        return preferences.all.entries.asSequence()
            .filter { it.key.startsWith("car.") && it.key.endsWith(".token") }
            .firstOrNull { (_, value) -> secureEquals(decrypt(value as? String), token) }
            ?.key?.removePrefix("car.")?.removeSuffix(".token")
    }

    private fun secureEquals(a: String?, b: String): Boolean = a != null && java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? = try {
        val all = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(cipher.doFinal(all.copyOfRange(12, all.size)))
    } catch (_: Throwable) { null }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
