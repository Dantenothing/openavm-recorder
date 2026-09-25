package com.dante.zeekrbridge.core

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import io.github.dantenothing.avmtransfer.protocol.PhoneSecurityProtocol
import io.github.dantenothing.avmtransfer.protocol.SecurePhonePairResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.io.File

@Serializable
data class PairedDevice(
    val carDeviceId: String,
    val name: String,
    val token: String,
    val pairedAt: Long,
    var lastSeen: Long = 0L,
    val securityVersion: Int = 0,
    val phoneIdentityPin: String = "",
) {
    override fun toString() = "PairedDevice(securityVersion=$securityVersion, token=<redacted>)"
}

data class SecurePairResult(val status: Int, val response: SecurePhonePairResponse? = null)

object PairingManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private lateinit var appContext: Context
    private val lock = Any()
    private val random = SecureRandom()
    private val window = PairingWindow({ SystemClock.elapsedRealtime() })
    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()
    private val _codeExpiresAt = MutableStateFlow(0L)
    val codeExpiresAt: StateFlow<Long> = _codeExpiresAt.asStateFlow()
    private val _lockedOut = MutableStateFlow(false)
    val lockedOut: StateFlow<Boolean> = _lockedOut.asStateFlow()
    private val _devices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()
    private val _phoneDeviceId = MutableStateFlow("")
    val phoneDeviceId: StateFlow<String> = _phoneDeviceId.asStateFlow()
    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName.asStateFlow()
    @Volatile private var identityPin = ""
    var onSessionInvalidated: (String) -> Unit = {}

    fun init(context: Context) = synchronized(lock) {
        appContext = context.applicationContext
        closePairingWindow()
        val file = AtomicFile(File(appContext.filesDir, "pairing.json"))
        val saved = runCatching { file.openRead().bufferedReader().use { json.decodeFromString(PairingData.serializer(), it.readText()) } }.getOrNull()
        _phoneDeviceId.value = saved?.phoneDeviceId?.takeIf { it.isNotEmpty() } ?: newToken(16)
        _phoneName.value = saved?.phoneName?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        // Legacy records remain visible for migration but cannot authorize TLS traffic.
        _devices.value = saved?.devices.orEmpty()
        saveLocked(_devices.value)
    }

    fun bindServerIdentity(pin: String) = synchronized(lock) {
        require(pin.matches(Regex("[0-9a-f]{64}")))
        identityPin = pin
    }

    /** Called only by explicit local UI. Service start/restart never calls this. */
    fun newPairingCode() = synchronized(lock) {
        check(identityPin.isNotEmpty()) { "Secure receiver is not ready" }
        _code.value = PairingCodePolicy.newSixDigitCode(random)
        window.open(_code.value)
        _codeExpiresAt.value = System.currentTimeMillis() + Protocol.PAIR_CODE_TTL_MS
        _lockedOut.value = false
        ServerLog.log("PAIRING_WINDOW_OPENED")
    }

    fun closePairingWindow() = synchronized(lock) {
        window.close()
        _code.value = ""
        _codeExpiresAt.value = 0
        _lockedOut.value = false
    }

    fun currentCode(): String = _code.value
    fun codeValid(code: String): Boolean = synchronized(lock) { window.valid(code) }

    /** TLS listener only; source comes from the socket, never a proxy header. */
    fun pairSecure(request: PairRequest, source: String): SecurePairResult = synchronized(lock) {
        val carId = PairingRules.saneCarDeviceId(request.carDeviceId)
        val name = PairingRules.saneDeviceName(request.deviceName)
        val attempt = window.attempt(request.code, source, carId != null && name != null && identityPin.isNotEmpty())
        _lockedOut.value = window.lockedOut
        if (window.lockedOut) { _code.value = ""; _codeExpiresAt.value = 0 }
        when (attempt) {
            PairingWindow.Result.LIMITED -> return SecurePairResult(429)
            PairingWindow.Result.REJECTED -> return SecurePairResult(401)
            PairingWindow.Result.ACCEPTED -> Unit
        }
        val token = newToken(32)
        val device = PairedDevice(carId!!, name!!, PhoneTokenCipher.encrypt(appContext, token),
            System.currentTimeMillis(), securityVersion = PhoneSecurityProtocol.VERSION, phoneIdentityPin = identityPin)
        val updated = _devices.value.filterNot { it.carDeviceId == carId } + device
        // Commit before returning a token. A disk failure cannot report a successful pair.
        saveLocked(updated)
        _devices.value = updated
        closePairingWindow()
        onSessionInvalidated(carId)
        ServerLog.log("PAIRING_COMPLETED securityVersion=2")
        SecurePairResult(200, SecurePhonePairResponse(token, _phoneDeviceId.value, _phoneName.value, PhoneSecurityProtocol.VERSION))
    }

    /** Retired transports cannot mint TLS credentials. */
    @Suppress("UNUSED_PARAMETER") fun pair(request: PairRequest): PairResponse? = null
    @Suppress("UNUSED_PARAMETER") fun registerCar(carDeviceId: String, name: String): String? = null

    fun findCar(carDeviceId: String): PairedDevice? = _devices.value.firstOrNull { it.carDeviceId == carDeviceId }

    fun authenticate(bearer: String?): PairedDevice? = synchronized(lock) {
        if (bearer == null || !bearer.startsWith("Bearer ")) return null
        val token = bearer.removePrefix("Bearer ")
        if (!token.matches(Regex("[0-9a-f]{64}"))) return null
        val device = _devices.value.firstOrNull { candidate ->
            candidate.securityVersion == PhoneSecurityProtocol.VERSION && identityPin.isNotEmpty() &&
                SecureCompare.equals(candidate.phoneIdentityPin, identityPin) &&
                // Never accept the encrypted blob or failed decrypt as a plaintext credential.
                SecureCompare.equals(PhoneTokenCipher.decrypt(appContext, candidate.token), token)
        } ?: return null
        touch(device.carDeviceId)
        device
    }

    fun isCurrent(device: PairedDevice): Boolean = synchronized(lock) {
        _devices.value.any { it.carDeviceId == device.carDeviceId &&
            it.securityVersion == PhoneSecurityProtocol.VERSION &&
            SecureCompare.equals(it.token, device.token) &&
            SecureCompare.equals(it.phoneIdentityPin, identityPin) }
    }

    /** Linearizes a short mutation with revoke/re-pair; never use while reading sockets. */
    fun withCurrent(device: PairedDevice, action: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrent(device)) return false
        action()
        true
    }

    fun revoke(carDeviceId: String) = synchronized(lock) {
        val updated = _devices.value.filterNot { it.carDeviceId == carDeviceId }
        saveLocked(updated)
        _devices.value = updated
        onSessionInvalidated(carDeviceId)
        ServerLog.log("PAIRING_REVOKED")
    }

    fun touch(carDeviceId: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        _devices.value = _devices.value.map { if (it.carDeviceId == carDeviceId) it.copy(lastSeen = now) else it }
    }

    private fun newToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun saveLocked(devices: List<PairedDevice>) {
        val data = PairingData(devices = devices, phoneDeviceId = _phoneDeviceId.value, phoneName = _phoneName.value)
        val file = AtomicFile(File(appContext.filesDir, "pairing.json"))
        val output = file.startWrite()
        try { output.write(json.encodeToString(PairingData.serializer(), data).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    object PairingCodePolicy {
        fun newSixDigitCode(random: SecureRandom): String = (random.nextInt(900000) + 100000).toString()
    }

    @Serializable private data class PairingData(
        val code: String = "", val codeExpiresAt: Long = 0,
        val devices: List<PairedDevice>, val phoneDeviceId: String, val phoneName: String = "",
    )
}
