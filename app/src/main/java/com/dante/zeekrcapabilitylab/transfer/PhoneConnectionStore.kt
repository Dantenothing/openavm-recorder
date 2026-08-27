package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
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
import okhttp3.HttpUrl
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
    private val discoveryClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(1_500, TimeUnit.MILLISECONDS)
        .build()
    private lateinit var preferences: SharedPreferences
    private lateinit var applicationContext: Context

    val carId: String get() = prefs().getString("carId", null) ?: error("not initialized")

    fun init(appContext: Context) {
        applicationContext = appContext.applicationContext
        preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            val request = Request.Builder().url(endpointUrl(host, port, "api/pair"))
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
            val request = Request.Builder().url(endpointUrl(endpoint.host, endpoint.port, "health"))
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
            val network = discoveryNetwork()
            discoverUdp(network.targets)
                ?: discoverHealth(network.gateways)
                ?: error(
                    "未收到手机回应（已尝试 ${network.targets.take(6).joinToString()}）。" +
                        "请确认 OpenAVM Companion 显示“运行中”，或输入手机页显示的 IP / IP:8766",
                )
        }
    }

    private data class DiscoveryNetwork(val targets: List<String>, val gateways: List<String>)

    private fun discoveryNetwork(): DiscoveryNetwork {
        val gateways = linkedSetOf<String>()
        val broadcasts = linkedSetOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .forEach { address ->
                    (address.broadcast as? Inet4Address)?.hostAddress?.let(broadcasts::add)
                    (address.address as? Inet4Address)?.hostAddress
                        ?.let { DiscoveryTargetPolicy.directedBroadcast(it, address.networkPrefixLength.toInt()) }
                        ?.let(broadcasts::add)
                }
        }
        runCatching {
            val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.allNetworks.sortedByDescending { network ->
                when {
                    connectivity.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 2
                    network == connectivity.activeNetwork -> 1
                    else -> 0
                }
            }.forEach { network ->
                connectivity.getLinkProperties(network)?.let { properties ->
                    properties.routes.mapNotNull { it.gateway as? Inet4Address }
                        .mapNotNull { it.hostAddress }
                        .forEach(gateways::add)
                    properties.linkAddresses.forEach { link ->
                        (link.address as? Inet4Address)?.hostAddress
                            ?.let { DiscoveryTargetPolicy.directedBroadcast(it, link.prefixLength) }
                            ?.let(broadcasts::add)
                    }
                }
            }
        }
        return DiscoveryNetwork(
            targets = DiscoveryTargetPolicy.targets(gateways, broadcasts),
            gateways = DiscoveryTargetPolicy.targets(gateways, emptyList()).filterNot { it == "255.255.255.255" },
        )
    }

    private fun discoverUdp(targets: List<String>): DiscoveryReply? = DatagramSocket().use { socket ->
        socket.broadcast = true
        val request = TransferProtocol.DISCOVERY_REQUEST.toByteArray()
        repeat(2) {
            targets.forEach { target ->
                runCatching {
                    socket.send(
                        DatagramPacket(request, request.size, InetAddress.getByName(target), TransferProtocol.UDP_PORT),
                    )
                }
            }
            val roundDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
            while (true) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(roundDeadline - System.nanoTime()).toInt()
                if (remainingMs <= 0) break
                socket.soTimeout = remainingMs.coerceAtLeast(1)
                val buffer = ByteArray(2_048)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val reply = runCatching {
                    json.decodeFromString(
                        DiscoveryReply.serializer(),
                        String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
                    )
                }.getOrNull() ?: continue
                if (reply.service != TransferProtocol.SERVICE || reply.port !in 1..65_535) continue
                val sourceIp = (packet.address as? Inet4Address)?.hostAddress.orEmpty()
                return@use reply.copy(ip = sourceIp.ifBlank { reply.ip })
            }
        }
        null
    }

    private fun discoverHealth(gateways: List<String>): DiscoveryReply? {
        gateways.take(4).forEach { host ->
            val reply = runCatching {
                val request = Request.Builder()
                    .url(endpointUrl(host, TransferProtocol.PORT, "health"))
                    .header(TransferProtocol.HTTP_HEADER, TransferProtocol.HTTP_HEADER_VALUE)
                    .get()
                    .build()
                discoveryClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val health = json.decodeFromString(HealthResponse.serializer(), response.body?.string().orEmpty())
                    if (health.service != TransferProtocol.SERVICE) return@use null
                    DiscoveryReply(deviceName = health.deviceName, ip = host, port = TransferProtocol.PORT)
                }
            }.getOrNull()
            if (reply != null) return reply
        }
        return null
    }

    private fun endpointUrl(host: String, port: Int, path: String): HttpUrl = HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addPathSegments(path.trim('/'))
        .build()

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
