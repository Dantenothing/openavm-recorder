package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.HealthResponse
import io.github.dantenothing.avmtransfer.protocol.PhoneSecurityProtocol
import io.github.dantenothing.avmtransfer.protocol.PhoneSecuritySession
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import java.io.Closeable
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** This receipt can only be obtained after the pinned TLS peer validates the car's token. */
class VerifiedPhoneSession internal constructor(val endpoint: PhoneEndpoint, val response: PhoneSecuritySession)

class VerifiedPhoneTransport(
    val endpoint: PhoneEndpoint,
    private val stillCurrent: () -> Boolean,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<WebSocket>()
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient

    init {
        if (!endpoint.securelyPaired) throw PhoneSecurityException(PhoneSecurityError.SECURE_PAIRING_REQUIRED)
        val trust = PhoneCertificate.trustManager(endpoint.publicKeySha256)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        client = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust)
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build()))
            // Keep OkHttp's standard hostname verifier. SAN is independent of hotspot IP.
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (hostname != PhoneSecurityProtocol.TLS_NAME) throw UnknownHostException("Unexpected phone hostname")
                    ensureCurrent()
                    return listOf(InetAddress.getByName(endpoint.host))
                }
            })
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                ensureCurrent()
                val request = chain.request()
                if (!request.url.isHttps || request.url.host != PhoneSecurityProtocol.TLS_NAME || request.url.port != endpoint.tlsPort) {
                    throw PhoneSecurityException(PhoneSecurityError.PHONE_IDENTITY_CHANGED)
                }
                chain.proceed(request).also { response ->
                    if (response.code == 401 && request.header("Authorization") != null) {
                        response.close()
                        throw PhoneSecurityException(PhoneSecurityError.AUTH_REVOKED)
                    }
                }
            }.build()
    }

    fun newCall(path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true): Call {
        ensureCurrent()
        return client.newCall(request(path, method, body, authenticated))
    }

    fun newWebSocket(listener: WebSocketListener): WebSocket {
        ensureCurrent()
        val socket = client.newWebSocket(request("/control", "GET", null, true), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get() || !stillCurrent()) { webSocket.cancel(); return }
                listener.onOpen(webSocket, response)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!closed.get() && stillCurrent()) listener.onMessage(webSocket, text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(webSocket)
                listener.onFailure(webSocket, if (response?.code == 401) PhoneSecurityException(PhoneSecurityError.AUTH_REVOKED) else phoneFailure(t), response)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                listener.onClosing(webSocket, code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(webSocket)
                listener.onClosed(webSocket, code, reason)
            }
        })
        sockets.add(socket)
        if (closed.get() || !stillCurrent()) { sockets.remove(socket); socket.cancel() }
        return socket
    }

    fun verifySession(carId: String): VerifiedPhoneSession {
        val call = newCall(PhoneSecurityProtocol.SESSION_PATH)
        call.timeout().timeout(6, TimeUnit.SECONDS)
        return try {
            call.execute().use { response ->
                if (response.code !in 200..299) throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
                val session = json.decodeFromString(PhoneSecuritySession.serializer(), response.limitedText())
                if (session.service != TransferProtocol.SERVICE || session.version != TransferProtocol.VERSION ||
                    session.securityVersion != PhoneSecurityProtocol.VERSION || session.phoneDeviceId != endpoint.phoneId || session.carDeviceId != carId) {
                    throw PhoneSecurityException(PhoneSecurityError.PHONE_IDENTITY_CHANGED)
                }
                ensureCurrent()
                VerifiedPhoneSession(endpoint, session)
            }
        } catch (t: Exception) { throw phoneFailure(t) }
    }

    fun health(): HealthResponse = try {
        val call = newCall("/health", authenticated = false)
        call.timeout().timeout(6, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (response.code !in 200..299) throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
            json.decodeFromString(HealthResponse.serializer(), response.limitedText()).also {
                if (it.phoneDeviceId != endpoint.phoneId || it.service != TransferProtocol.SERVICE) {
                    throw PhoneSecurityException(PhoneSecurityError.PHONE_IDENTITY_CHANGED)
                }
            }
        }
    } catch (t: Exception) { throw phoneFailure(t) }

    private fun request(path: String, method: String, body: RequestBody?, authenticated: Boolean): Request {
        require(path.startsWith('/') && !path.startsWith("//") && '?' !in path && '#' !in path && '\\' !in path)
        val url = HttpUrl.Builder().scheme("https").host(PhoneSecurityProtocol.TLS_NAME)
            .port(endpoint.tlsPort).encodedPath(path).build()
        return Request.Builder().url(url).header(TransferProtocol.HTTP_HEADER, TransferProtocol.HTTP_HEADER_VALUE)
            .apply {
                if (authenticated) {
                    if (endpoint.token.isBlank()) throw PhoneSecurityException(PhoneSecurityError.SECURE_PAIRING_REQUIRED)
                    header("Authorization", "Bearer ${endpoint.token}")
                }
            }.method(method, body).build()
    }

    private fun ensureCurrent() {
        if (closed.get() || !stillCurrent()) throw PhoneSecurityException(PhoneSecurityError.CONNECTION_REPLACED)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sockets.forEach { it.cancel() }; sockets.clear()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal fun Response.limitedText(limit: Int = 1024 * 1024): String {
    val source = body?.source() ?: throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
    source.request(limit.toLong() + 1)
    if (source.buffer.size > limit) throw PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
    return source.readUtf8()
}

/** Never surface provider exceptions, server bodies, or request headers as user diagnostics. */
fun phoneFailure(t: Throwable): PhoneSecurityException = when {
    t is PhoneSecurityException -> t
    generateSequence(t) { it.cause }.take(8).any { it is SSLException || it is java.security.cert.CertificateException } ->
        PhoneSecurityException(PhoneSecurityError.PHONE_IDENTITY_CHANGED)
    t is kotlinx.serialization.SerializationException || t is IllegalArgumentException -> PhoneSecurityException(PhoneSecurityError.INVALID_PHONE_RESPONSE)
    else -> PhoneSecurityException(PhoneSecurityError.PHONE_UNAVAILABLE)
}
