package com.dante.zeekrbridge.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class PairedDevice(
    val carDeviceId: String,
    val name: String,
    val token: String,
    val pairedAt: Long,
    var lastSeen: Long = 0L,
)

object PairingManager {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private lateinit var appContext: Context

    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()

    private val _codeExpiresAt = MutableStateFlow(0L)
    val codeExpiresAt: StateFlow<Long> = _codeExpiresAt.asStateFlow()

    private val _devices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    private val _phoneDeviceId = MutableStateFlow("")
    val phoneDeviceId: StateFlow<String> = _phoneDeviceId.asStateFlow()

    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName.asStateFlow()

    private val random = SecureRandom()
    /** Serializes pair/auth/revoke/touch and every metadata save. */
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
        load()
        if (_phoneDeviceId.value.isEmpty()) {
            val id = newToken(16)
            _phoneDeviceId.value = id
            save()
        }
        if (_phoneName.value.isBlank()) {
            _phoneName.value = android.os.Build.MODEL
            save()
        }
    }

    fun newPairingCode() {
        synchronized(lock) {
            _code.value = PairingCodePolicy.newSixDigitCode(random)
            _codeExpiresAt.value = System.currentTimeMillis() + Protocol.PAIR_CODE_TTL_MS
            saveLocked()
            ServerLog.log("PAIRING_CODE_GENERATED expires=${_codeExpiresAt.value}")
        }
    }

    fun currentCode(): String = _code.value

    fun codeValid(code: String): Boolean =
        code.isNotBlank() &&
            code == _code.value &&
            System.currentTimeMillis() < _codeExpiresAt.value

    fun pair(request: PairRequest): PairResponse? {
        synchronized(lock) {
            if (!codeValidLocked(request.code)) return null
            val carDeviceId = PairingRules.saneCarDeviceId(request.carDeviceId) ?: return null
            val name = PairingRules.saneDeviceName(request.deviceName) ?: return null
            val token = newToken(32)
            val device = PairedDevice(
                carDeviceId = carDeviceId,
                name = name,
                token = PhoneTokenCipher.encrypt(appContext, token),
                pairedAt = System.currentTimeMillis(),
            )
            _devices.value = _devices.value.filterNot { it.carDeviceId == device.carDeviceId } + device
            // The code is single-use: a successful pair invalidates it
            // immediately and persists that invalidation.
            _code.value = ""
            _codeExpiresAt.value = 0L
            saveLocked()
            ServerLog.log("PAIRING_COMPLETED car=${device.carDeviceId} name=${device.name}")
            return PairResponse(
                token = token,
                phoneDeviceId = _phoneDeviceId.value,
                deviceName = _phoneName.value,
            )
        }
    }

    /**
     * V2 QR flow: the phone registers the car with a fresh long-term token and
     * returns the plaintext token so the car can persist it (Keystore on the
     * car side). Stored token is Keystore-encrypted on this side.
     */
    fun registerCar(carDeviceId: String, name: String): String? {
        synchronized(lock) {
            val cleanId = PairingRules.saneCarDeviceId(carDeviceId) ?: return null
            val cleanName = PairingRules.saneDeviceName(name) ?: return null
            val token = newToken(32)
            val device = PairedDevice(
                carDeviceId = cleanId,
                name = cleanName,
                token = PhoneTokenCipher.encrypt(appContext, token),
                pairedAt = System.currentTimeMillis(),
            )
            _devices.value = _devices.value.filterNot { it.carDeviceId == cleanId } + device
            saveLocked()
            ServerLog.log("CAR_REGISTERED car=${device.carDeviceId} name=${device.name}")
            return token
        }
    }

    fun findCar(carDeviceId: String): PairedDevice? =
        _devices.value.firstOrNull { it.carDeviceId == carDeviceId }

    fun authenticate(bearer: String?): PairedDevice? {
        synchronized(lock) {
            if (bearer == null || !bearer.startsWith("Bearer ")) return null
            val token = bearer.removePrefix("Bearer ").trim()
            val device = _devices.value.firstOrNull { candidate ->
                val stored = PhoneTokenCipher.decrypt(appContext, candidate.token) ?: candidate.token
                SecureCompare.equals(stored, token)
            } ?: return null
            _devices.value = _devices.value.map {
                if (it.carDeviceId == device.carDeviceId) it.copy(lastSeen = System.currentTimeMillis()) else it
            }
            saveLocked()
            return device
        }
    }

    fun revoke(carDeviceId: String) {
        synchronized(lock) {
            _devices.value = _devices.value.filterNot { it.carDeviceId == carDeviceId }
            saveLocked()
            ServerLog.log("PAIRING_REVOKED car=$carDeviceId")
        }
    }

    fun touch(carDeviceId: String) {
        synchronized(lock) {
            _devices.value = _devices.value.map {
                if (it.carDeviceId == carDeviceId) it.copy(lastSeen = System.currentTimeMillis()) else it
            }
        }
    }

    private fun codeValidLocked(code: String): Boolean =
        code.isNotBlank() &&
            code == _code.value &&
            System.currentTimeMillis() < _codeExpiresAt.value

    private fun newToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private fun load() {
        try {
            val file = File(appContext.filesDir, "pairing.json")
            if (file.exists()) {
                val data = json.decodeFromString(PairingData.serializer(), file.readText())
                _code.value = data.code
                _codeExpiresAt.value = data.codeExpiresAt
                _devices.value = data.devices
                _phoneDeviceId.value = data.phoneDeviceId
                _phoneName.value = data.phoneName.ifBlank { android.os.Build.MODEL }
                // Migrate any legacy plaintext tokens to Keystore-encrypted.
                var migrated = false
                _devices.value = _devices.value.map { device ->
                    val plain = PhoneTokenCipher.decrypt(appContext, device.token)
                    if (plain == null && device.token.isNotBlank()) {
                        migrated = true
                        device.copy(token = PhoneTokenCipher.encrypt(appContext, device.token))
                    } else {
                        device
                    }
                }
                if (migrated) saveLocked()
            }
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    private fun save() {
        synchronized(lock) {
            saveLocked()
        }
    }

    private fun saveLocked() {
        try {
            val data = PairingData(
                _code.value,
                _codeExpiresAt.value,
                _devices.value,
                _phoneDeviceId.value,
                _phoneName.value,
            )
            val file = File(appContext.filesDir, "pairing.json")
            val tmp = File(file.parentFile, "pairing.json.tmp")
            tmp.writeText(json.encodeToString(PairingData.serializer(), data))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (t: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    /** Pure 6-digit code generator backed by SecureRandom. */
    object PairingCodePolicy {
        fun newSixDigitCode(random: SecureRandom): String =
            (random.nextInt(900000) + 100000).toString()
    }

    @Serializable
    private data class PairingData(
        val code: String,
        val codeExpiresAt: Long,
        val devices: List<PairedDevice>,
        val phoneDeviceId: String,
        val phoneName: String = "",
    )
}
