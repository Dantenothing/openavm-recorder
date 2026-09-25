package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.dantenothing.avmtransfer.protocol.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object PhoneConnectionStore {
    private const val PREFS = "phone_connection"
    private const val KEY_ALIAS = "openavm_car_phone_token"
    private val json = Json { ignoreUnknownKeys = true }
    private val discoveryClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(1_500, TimeUnit.MILLISECONDS).build()
    private lateinit var preferences: SharedPreferences
    private lateinit var applicationContext: Context
    private val stateLock = Any()
    private val connectLock = Mutex()
    @Volatile private var current: PhoneEndpoint? = null
    @Volatile private var generation = 0L
    @Volatile private var networkGeneration = 0L
    @Volatile private var blocked: PhoneSecurityError? = null
    private var transport: VerifiedPhoneTransport? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val carId: String get() = prefs().getString("carId", null) ?: error("not initialized")

    fun init(appContext: Context) {
        applicationContext = appContext.applicationContext
        preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs().getString("carId", null) == null) prefs().edit().putString("carId", UUID.randomUUID().toString()).commit()
        synchronized(stateLock) {
            generation = prefs().getLong("pairingGeneration", 0)
            val host = prefs().getString("host", null)
            val token = decrypt(prefs().getString("token", null))
            val id = prefs().getString("phoneId", null)
            current = if (host != null && token != null && id != null) PhoneEndpoint(
                host, prefs().getInt("port", TransferProtocol.PORT), token,
                prefs().getString("name", "Android phone").orEmpty(), id,
                prefs().getInt("securityVersion", 0), prefs().getInt("tlsPort", 0),
                prefs().getString("publicKeySha256", "").orEmpty(), generation,
            ) else null
            blocked = current?.takeUnless { it.securelyPaired }?.let { PhoneSecurityError.SECURE_PAIRING_REQUIRED }
        }
        if (networkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { invalidateNetwork() }
                override fun onLost(network: Network) { invalidateNetwork() }
            }
            runCatching {
                applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
                networkCallback = callback
            }
        }
    }

    private fun invalidateNetwork() {
        val endpoint = synchronized(stateLock) {
            networkGeneration++
            val previous = transport
            transport = null
            previous?.close()
            current
        }
        endpoint?.let {
            TransferRepository.markConnected(it, false, phoneSecurityMessage(blocked ?: PhoneSecurityError.PHONE_UNAVAILABLE))
            TransferRepository.reconnectInBackground()
        }
    }

    fun saved(): PhoneEndpoint? = current
    fun securityError(): PhoneSecurityError? = blocked
    fun isCurrentPairing(endpoint: PhoneEndpoint): Boolean = current?.samePairing(endpoint) == true

    suspend fun inspectPairing(host: String, port: Int): Result<PhonePairingCandidate> = withContext(Dispatchers.IO) {
        secureResult {
            require(host.isNotBlank() && port in 1..65535)
            val expectedGeneration = generation
            val request = Request.Builder().url(endpointUrl(host, port, PhoneSecurityProtocol.IDENTITY_PATH))
                .header(TransferProtocol.HTTP_HEADER, TransferProtocol.HTTP_HEADER_VALUE).get().build()
            discoveryClient.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 426) throw PhoneSecurityException(PhoneSecurityError.PHONE_UPGRADE_REQUIRED)
                if (!response.isSuccessful) throw PhoneSecurityException(PhoneSecurityError.PHONE_UNAVAILABLE)
                val identity = PhoneIdentityJson.decode(response.limitedText(PhoneSecurityProtocol.MAX_IDENTITY_BYTES))
                val certificate = PhoneCertificate.decode(identity)
                if (generation != expectedGeneration) throw PhoneSecurityException(PhoneSecurityError.CONNECTION_REPLACED)
                PhonePairingCandidate(host, port, identity.phoneDeviceId, identity.tlsPort, PhoneCertificate.pin(certificate), System.nanoTime(), expectedGeneration)
            }
        }
    }

    /** Only called by the explicit fingerprint-confirmation action. No trust-on-first-use. */
    suspend fun pair(candidate: PhonePairingCandidate, code: String): Result<PhoneEndpoint> = withContext(Dispatchers.IO) {
        connectLock.withLock {
            secureResult {
                val network = networkGeneration
                if (candidate.pairingGeneration != generation) throw PhoneSecurityException(PhoneSecurityError.CONNECTION_REPLACED)
                if (System.nanoTime() - candidate.startedAtNanos !in 0..TimeUnit.MINUTES.toNanos(5)) throw PhoneSecurityException(PhoneSecurityError.PAIRING_EXPIRED)
                if (!Regex("^[0-9]{6}$").matches(code)) throw PhoneSecurityException(PhoneSecurityError.PAIR_CODE_REJECTED)
                val pending = PhoneEndpoint(candidate.host, candidate.discoveryPort, "", "", candidate.phoneId,
                    PhoneSecurityProtocol.VERSION, candidate.tlsPort, candidate.publicKeySha256, candidate.pairingGeneration)
                val payload = json.encodeToString(PairRequest.serializer(), PairRequest(code, CarPairingIdentity.DISPLAY_NAME, carId))
                val paired = VerifiedPhoneTransport(pending) { generation == candidate.pairingGeneration && networkGeneration == network }.use { client ->
                    val call = client.newCall("/api/pair", "POST", payload.toRequestBody("application/json".toMediaType()), authenticated = false)
                    call.timeout().timeout(8, TimeUnit.SECONDS)
                    call.execute().use { response ->
                        when (response.code) {
                            401 -> throw PhoneSecurityException(PhoneSecurityError.PAIR_CODE_REJECTED)
                            429 -> throw PhoneSecurityException(PhoneSecurityError.PAIR_RATE_LIMITED)
                        }
                        if (!response.isSuccessful) throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
                        json.decodeFromString(SecurePhonePairResponse.serializer(), response.limitedText(32 * 1024))
                    }
                }
                if (paired.securityVersion != PhoneSecurityProtocol.VERSION || paired.phoneDeviceId != candidate.phoneId ||
                    !Regex("^[0-9a-f]{64}$").matches(paired.token) || paired.deviceName.length !in 1..200 || paired.deviceName.any { it.code < 0x20 }) {
                    throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
                }
                val endpoint = pending.copy(token = paired.token, phoneName = paired.deviceName, pairingGeneration = candidate.pairingGeneration + 1)
                // This temporary client has no access to any old credential or address.
                VerifiedPhoneTransport(endpoint) { generation == candidate.pairingGeneration && networkGeneration == network }.use { client -> client.verifySession(carId) }
                synchronized(stateLock) {
                    if (generation != candidate.pairingGeneration || networkGeneration != network) throw PhoneSecurityException(PhoneSecurityError.CONNECTION_REPLACED)
                    persist(endpoint)
                    generation = endpoint.pairingGeneration
                    current = endpoint
                    blocked = null
                    transport?.close()
                    transport = VerifiedPhoneTransport(endpoint) { isCurrentPairing(endpoint) && networkGeneration == network }
                }
                endpoint
            }
        }
    }

    /** Called on IO workers; concurrent callers share one bounded discovery/verification. */
    fun requireConnected(): PhoneEndpoint {
        blocked?.let { throw PhoneSecurityException(it) }
        synchronized(stateLock) { transport?.let { if (isCurrentPairing(it.endpoint)) return it.endpoint } }
        return runBlocking { reconnectSaved().getOrThrow() }
    }

    fun transportFor(endpoint: PhoneEndpoint): VerifiedPhoneTransport {
        if (!isCurrentPairing(endpoint)) throw PhoneSecurityException(PhoneSecurityError.CONNECTION_REPLACED)
        requireConnected()
        return synchronized(stateLock) {
            if (!isCurrentPairing(endpoint)) throw PhoneSecurityException(PhoneSecurityError.CONNECTION_REPLACED)
            blocked?.let { throw PhoneSecurityException(it) }
            transport ?: throw PhoneSecurityException(PhoneSecurityError.PHONE_UNAVAILABLE)
        }
    }

    fun reportFailure(endpoint: PhoneEndpoint, failure: Throwable, source: VerifiedPhoneTransport? = null): PhoneSecurityException {
        val error = phoneFailure(failure)
        synchronized(stateLock) {
            if (source != null && transport !== source) return error
            if (isCurrentPairing(endpoint) && (transport == null || transport?.endpoint?.host == endpoint.host)) {
                transport?.close(); transport = null
                if (!error.error.retryable && error.error != PhoneSecurityError.CONNECTION_REPLACED) blocked = error.error
            }
        }
        TransferRepository.markConnected(endpoint, false, phoneSecurityMessage(error.error))
        return error
    }

    suspend fun reconnectSaved(force: Boolean = false): Result<PhoneEndpoint> = withContext(Dispatchers.IO) {
        connectLock.withLock {
            if (!force) synchronized(stateLock) {
                transport?.let { if (blocked == null && isCurrentPairing(it.endpoint)) return@withLock Result.success(it.endpoint) }
            }
            val original = saved() ?: return@withLock Result.failure(PhoneSecurityException(PhoneSecurityError.SECURE_PAIRING_REQUIRED))
            if (!original.securelyPaired) return@withLock Result.failure(PhoneSecurityException(PhoneSecurityError.SECURE_PAIRING_REQUIRED))
            val result = secureResult {
                val connectionNetwork = networkGeneration
                // First verify the remembered address without consulting unauthenticated metadata.
                var last: PhoneSecurityException? = null
                fun tryCandidate(candidate: PhoneEndpoint): PhoneEndpoint? {
                    val client = VerifiedPhoneTransport(candidate) { isCurrentPairing(original) && networkGeneration == connectionNetwork }
                    try {
                        val verified = client.verifySession(carId)
                        val health = client.health()
                        val refreshed = PhoneReconnectPolicy.migrate(original,
                            DiscoveryReply(deviceName = health.deviceName, ip = candidate.host, port = candidate.port), health, verified)
                            ?.copy(phoneName = health.deviceName.take(200))
                            ?: throw PhoneSecurityException(PhoneSecurityError.PHONE_IDENTITY_CHANGED)
                        synchronized(stateLock) {
                            if (!isCurrentPairing(original) || networkGeneration != connectionNetwork) throw PhoneSecurityException(PhoneSecurityError.CONNECTION_REPLACED)
                            persist(refreshed)
                            current = refreshed
                            blocked = null
                            transport?.close(); transport = client
                        }
                        return refreshed
                    } catch (t: Exception) {
                        client.close()
                        val failure = phoneFailure(t)
                        if (failure.error in setOf(PhoneSecurityError.AUTH_REVOKED, PhoneSecurityError.CONNECTION_REPLACED)) throw failure
                        if (last == null || failure.error == PhoneSecurityError.PHONE_IDENTITY_CHANGED) last = failure
                        return null
                    }
                }
                tryCandidate(original)?.let { return@secureResult it }
                val network = discoveryNetwork()
                val replies = discoverUdp(network.targets).toMutableList()
                if (replies.size < 6) discoverHealth(network.healthCandidates)?.let(replies::add)
                replies.distinctBy { it.ip to it.port }.take(6).forEach { reply ->
                    // Even spoofed capability hints cannot replace the saved key.
                    val candidate = original.copy(host = reply.ip, port = reply.port,
                        tlsPort = reply.tlsPort?.takeIf { it in 1..65535 } ?: original.tlsPort)
                    if (candidate.host != original.host || candidate.tlsPort != original.tlsPort) {
                        tryCandidate(candidate)?.let { return@secureResult it }
                    }
                }
                throw (last ?: PhoneSecurityException(PhoneSecurityError.PHONE_UNAVAILABLE))
            }
            result.onFailure { reportFailure(original, it) }
            result
        }
    }

    suspend fun discover(): Result<DiscoveryReply> = withContext(Dispatchers.IO) {
        secureResult {
            val network = discoveryNetwork()
            discoverUdp(network.targets).firstOrNull() ?: discoverHealth(network.healthCandidates)
                ?: throw PhoneSecurityException(PhoneSecurityError.PHONE_UNAVAILABLE)
        }
    }

    private inline fun <T> secureResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (t: CancellationException) { throw t
    } catch (t: Exception) { Result.failure(phoneFailure(t)) }

    private data class DiscoveryNetwork(val targets: List<String>, val healthCandidates: List<String>)

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
        val savedHost = prefs().getString("host", null)
        return DiscoveryNetwork(
            targets = DiscoveryTargetPolicy.targets(savedHost, gateways, broadcasts),
            healthCandidates = DiscoveryTargetPolicy.healthCandidates(savedHost, gateways),
        )
    }

    private fun discoverUdp(targets: List<String>): List<DiscoveryReply> = DatagramSocket().use { socket ->
        val replies = linkedMapOf<String, DiscoveryReply>()
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
                if (sourceIp.isNotBlank()) replies.putIfAbsent("$sourceIp:${reply.port}", reply.copy(ip = sourceIp))
                if (replies.size >= 6) return@use replies.values.toList()
            }
        }
        replies.values.toList()
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
                    val health = json.decodeFromString(HealthResponse.serializer(), response.limitedText(32 * 1024))
                    if (health.service != TransferProtocol.SERVICE) return@use null
                    DiscoveryReply(deviceName = health.deviceName, ip = host, port = TransferProtocol.PORT,
                        securityVersions = health.securityVersions, tlsPort = health.tlsPort)
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

    private fun persist(endpoint: PhoneEndpoint) {
        val ok = prefs().edit()
            .putString("host", endpoint.host).putInt("port", endpoint.port)
            .putString("name", endpoint.phoneName).putString("phoneId", endpoint.phoneId)
            .putString("token", encrypt(endpoint.token))
            .putInt("securityVersion", endpoint.securityVersion).putInt("tlsPort", endpoint.tlsPort)
            .putString("publicKeySha256", endpoint.publicKeySha256).putLong("pairingGeneration", endpoint.pairingGeneration)
            .commit()
        if (!ok) throw PhoneSecurityException(PhoneSecurityError.PHONE_UNAVAILABLE)
    }

    fun forget() = synchronized(stateLock) {
        generation++
        transport?.close(); transport = null
        current = null; blocked = null
        prefs().edit().remove("host").remove("port").remove("name").remove("phoneId").remove("token")
            .remove("securityVersion").remove("tlsPort").remove("publicKeySha256")
            .putLong("pairingGeneration", generation).commit()
        Unit
    }
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
