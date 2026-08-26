package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.dantenothing.avmtransfer.protocol.DiscoveryReply
import io.github.dantenothing.avmtransfer.protocol.HealthResponse
import io.github.dantenothing.avmtransfer.protocol.PairRequest
import io.github.dantenothing.avmtransfer.protocol.PairResponse
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class PhoneEndpoint(val host: String, val port: Int, val token: String, val phoneName: String, val phoneId: String)
data class PhoneConnectionState(val endpoint: PhoneEndpoint? = null, val connected: Boolean = false, val message: String = "Not connected")

object PhoneConnectionStore {
    private const val PREFS = "phone_connection"
    private const val KEY_ALIAS = "openavm_car_phone_token"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    private lateinit var preferences: SharedPreferences

    val carId: String get() = prefs().getString("carId", null) ?: error("not initialized")

    fun init(appContext: Context) {
        preferences = appContext.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs().getString("carId", null) == null) prefs().edit().putString("carId", UUID.randomUUID().toString()).apply()
    }

    fun saved(): PhoneEndpoint? {
        val host = prefs().getString("host", null) ?: return null
        val port = prefs().getInt("port", TransferProtocol.PORT)
        val token = decrypt(prefs().getString("token", null)) ?: return null
        val phoneId = prefs().getString("phoneId", null) ?: return null
        return PhoneEndpoint(host, port, token, prefs().getString("name", "Android phone").orEmpty(), phoneId)
    }

    suspend fun pair(host: String, code: String, port: Int = TransferProtocol.PORT): Result<PhoneEndpoint> = withContext(Dispatchers.IO) {
        runCatching {
            require(host.isNotBlank() && port in 1..65535) { "Invalid phone address" }
            require(Regex("^[0-9]{6}$").matches(code)) { "Enter the six-digit code" }
            val payload = json.encodeToString(PairRequest.serializer(), PairRequest(code, Build.MODEL.ifBlank { "Zeekr" }, carId))
            val request = Request.Builder().url("http://$host:$port/api/pair")
                .header(TransferProtocol.HTTP_HEADER, TransferProtocol.HTTP_HEADER_VALUE)
                .post(payload.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Pairing rejected (${response.code})")
                val paired = json.decodeFromString(PairResponse.serializer(), response.body?.string().orEmpty())
                val endpoint = PhoneEndpoint(host, port, paired.token, paired.deviceName, paired.phoneDeviceId)
                prefs().edit().putString("host", host).putInt("port", port).putString("name", paired.deviceName)
                    .putString("phoneId", paired.phoneDeviceId).putString("token", encrypt(paired.token)).apply()
                endpoint
            }
        }
    }

    suspend fun health(): Result<HealthResponse> {
        val endpoint = saved() ?: return Result.failure(IllegalStateException("Not paired"))
        return health(endpoint)
    }

    suspend fun health(endpoint: PhoneEndpoint): Result<HealthResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("http://${endpoint.host}:${endpoint.port}/health")
                .header(TransferProtocol.HTTP_HEADER, TransferProtocol.HTTP_HEADER_VALUE)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Phone unavailable (${response.code})")
                json.decodeFromString(HealthResponse.serializer(), response.body?.string().orEmpty()).also {
                    require(it.phoneDeviceId == endpoint.phoneId && it.service == TransferProtocol.SERVICE) { "Unexpected receiver identity" }
                }
            }
        }
    }

    suspend fun discover(): Result<DiscoveryReply> = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 3_000
                val bytes = TransferProtocol.DISCOVERY_REQUEST.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), TransferProtocol.UDP_PORT))
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                json.decodeFromString(DiscoveryReply.serializer(), String(packet.data, packet.offset, packet.length))
            }
        }
    }

    fun forget() { prefs().edit().remove("host").remove("port").remove("name").remove("phoneId").remove("token").apply() }
    private fun prefs() = preferences

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
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
