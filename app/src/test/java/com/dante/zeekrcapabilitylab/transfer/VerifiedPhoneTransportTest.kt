package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.*
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test

class VerifiedPhoneTransportTest {
    private val token = "1234567890abcdef".repeat(4) // Public, test-only credential.
    private val json = Json { ignoreUnknownKeys = true }
    private fun resource(name: String) = javaClass.getResourceAsStream("/phone-security/$name")!!.use { it.readBytes() }
    private fun certificate(name: String) = CertificateFactory.getInstance("X.509").generateCertificate(resource("$name.der").inputStream()) as X509Certificate
    private fun endpoint(port: Int, host: String = "127.0.0.1") = PhoneEndpoint(host, 8766, token, "Phone", "phone-test",
        2, port, PhoneCertificate.pin(certificate("phone")), 1)
    private val session = """{"service":"openavm-transfer","version":1,"securityVersion":2,"phoneDeviceId":"phone-test","carDeviceId":"car-test"}"""
    private val health = """{"service":"openavm-transfer","version":1,"deviceName":"Phone","phoneDeviceId":"phone-test"}"""

    @Test fun publicIdentifierAloneCannotCreateATransport() {
        val failure = runCatching { VerifiedPhoneTransport(PhoneEndpoint("127.0.0.1", 8766, token, "Phone", "phone-test")) { true } }.exceptionOrNull()
        assertEquals(PhoneSecurityError.SECURE_PAIRING_REQUIRED, (failure as PhoneSecurityException).error)
    }

    @Test fun trustedTlsSessionAndHealthHaveDifferentCredentialRules() {
        PhoneServer("phone") { request -> Reply(body = if (request.path == "/health") health else session) }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                assertEquals("car-test", transport.verifySession("car-test").response.carDeviceId)
                assertEquals("phone-test", transport.health().phoneDeviceId)
                assertEquals("Bearer $token", server.requests[0].headers["authorization"])
                assertNull(server.requests[1].headers["authorization"])
                assertTrue(server.requests.all { it.headers["host"] == "openavm-phone.invalid:${server.port}" })
            }
        }
    }

    @Test fun wrongKeyWrongHostnameAndExpiredCertificateReceiveNoHttpCredentials() {
        for (name in listOf("impostor", "wrong-san", "expired")) {
            PhoneServer(name) { Reply(body = session) }.use { server ->
                VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                    val failure = runCatching { transport.verifySession("car-test") }.exceptionOrNull()
                    assertEquals(name, PhoneSecurityError.PHONE_IDENTITY_CHANGED, (failure as PhoneSecurityException).error)
                    assertEquals(name, 0, server.requests.size)
                }
            }
        }
    }

    @Test fun certificateRenewalKeepingTheConfirmedKeyWorks() {
        assertEquals(PhoneCertificate.pin(certificate("phone")), PhoneCertificate.pin(certificate("renewed")))
        PhoneServer("renewed") { Reply(body = session) }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port)) { true }.use { assertEquals("phone-test", it.verifySession("car-test").response.phoneDeviceId) }
        }
    }

    @Test fun samePhoneAtAnotherAddressStillUsesPinnedIdentity() {
        PhoneServer("phone") { Reply(body = session) }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port, "localhost")) { true }.use { assertEquals("phone-test", it.verifySession("car-test").response.phoneDeviceId) }
        }
    }

    @Test fun missingOrRevokedAuthenticationStopsWithStableError() {
        PhoneServer("phone") { Reply(401, "Do not expose this server diagnostic") }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                val failure = runCatching { transport.verifySession("car-test") }.exceptionOrNull() as PhoneSecurityException
                assertEquals(PhoneSecurityError.AUTH_REVOKED, failure.error)
                assertFalse(failure.message!!.contains("diagnostic"))
            }
        }
    }

    @Test fun sessionMustMatchServiceVersionsPhoneAndCar() {
        for ((from, to) in listOf("car-test" to "other-car", "phone-test" to "other-phone", "openavm-transfer" to "other-service", "\"securityVersion\":2" to "\"securityVersion\":1", "\"version\":1" to "\"version\":7")) {
            PhoneServer("phone") { Reply(body = session.replace(from, to)) }.use { server ->
                VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                    val failure = runCatching { transport.verifySession("car-test") }.exceptionOrNull() as PhoneSecurityException
                    assertEquals(PhoneSecurityError.PHONE_IDENTITY_CHANGED, failure.error)
                }
            }
        }
    }

    @Test fun httpRedirectIsNotFollowedAndDoesNotLeakCredentials() {
        PlainTrap().use { trap ->
            PhoneServer("phone") { Reply(302, "", mapOf("Location" to "http://127.0.0.1:${trap.port}/steal")) }.use { server ->
                VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                    transport.newCall("/api/outbound").execute().use { assertEquals(302, it.code) }
                    assertEquals(1, server.requests.size)
                    assertFalse(trap.connected.get())
                }
            }
        }
    }

    @Test fun plaintextServiceNeverReceivesBearerOrPairingCode() {
        PlainTrap().use { trap ->
            VerifiedPhoneTransport(endpoint(trap.port)) { true }.use { transport ->
                assertTrue(runCatching { transport.verifySession("car-test") }.isFailure)
                assertTrue(trap.received.await(2, TimeUnit.SECONDS))
                assertFalse(trap.bytes.toString(Charsets.ISO_8859_1).contains(token))
                assertFalse(trap.bytes.toString(Charsets.ISO_8859_1).contains("Authorization"))
            }
        }
    }

    @Test fun queuedCallCannotOutliveItsPairingGeneration() {
        val current = AtomicBoolean(true)
        PhoneServer("phone") { Reply(body = session) }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port)) { current.get() }.use { transport ->
                val call = transport.newCall("/api/outbound")
                current.set(false)
                val failure = runCatching { call.execute().close() }.exceptionOrNull()
                assertEquals(PhoneSecurityError.CONNECTION_REPLACED, phoneFailure(failure!!).error)
                assertEquals(0, server.requests.size)
            }
        }
    }

    @Test fun closedTransportCannotCreateMoreCalls() {
        val transport = VerifiedPhoneTransport(endpoint(8767)) { true }
        transport.close()
        val failure = runCatching { transport.newCall("/api/outbound") }.exceptionOrNull() as PhoneSecurityException
        assertEquals(PhoneSecurityError.CONNECTION_REPLACED, failure.error)
    }

    @Test fun pairingUsesTlsWithoutOldBearerAndWrongKeyCannotReadCode() {
        val body = """{"code":"012345","deviceName":"Test vehicle","carDeviceId":"car-test"}"""
        for (name in listOf("phone", "impostor")) {
            PhoneServer(name) { Reply(body = "{}") }.use { server ->
                VerifiedPhoneTransport(endpoint(server.port).copy(token = "")) { true }.use { transport ->
                    val result = runCatching { transport.newCall("/api/pair", "POST", body.toRequestBody("application/json".toMediaType()), false).execute().close() }
                    assertEquals(name == "phone", result.isSuccess)
                    if (name == "phone") {
                        assertEquals(body, server.requests.single().body)
                        assertNull(server.requests.single().headers["authorization"])
                    } else assertEquals(0, server.requests.size)
                }
            }
        }
    }

    @Test fun webSocketUsesTheSamePinnedTlsIdentity() {
        PhoneServer("phone") { Reply(webSocket = true) }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                val opened = CountDownLatch(1)
                transport.newWebSocket(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
                })
                assertTrue(opened.await(5, TimeUnit.SECONDS))
                assertEquals("/control", server.requests.single().path)
                assertEquals("Bearer $token", server.requests.single().headers["authorization"])
            }
        }
    }

    @Test fun impostorWebSocketCannotReceiveHandshakeAuthorization() {
        PhoneServer("impostor") { Reply(webSocket = true) }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                val failed = CountDownLatch(1)
                transport.newWebSocket(object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failed.countDown() }
                    override fun onOpen(webSocket: WebSocket, response: Response) { fail("Impostor accepted") }
                })
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                assertEquals(0, server.requests.size)
            }
        }
    }

    @Test fun responsesAreBoundedAndSecretsAreNotPrinted() {
        PhoneServer("phone") { Reply(body = "x".repeat(1024)) }.use { server ->
            VerifiedPhoneTransport(endpoint(server.port)) { true }.use { transport ->
                transport.newCall("/health", authenticated = false).execute().use { response ->
                    assertTrue(runCatching { response.limitedText(32) }.isFailure)
                }
            }
        }
        assertFalse(endpoint(8767).toString().contains(token))
        assertFalse(SecurePhonePairResponse(token, "phone-test", "Phone", 2).toString().contains(token))
    }

    @Test fun candidateCertificateMustHaveUsableIdentityAndAlgorithms() {
        for (name in listOf("wrong-san", "expired", "weak")) {
            val identity = PhoneSecurityIdentity(2, "phone-test", 8767, Base64.getEncoder().encodeToString(resource("$name.der")))
            assertTrue(name, runCatching { PhoneCertificate.decode(identity) }.isFailure)
        }
        val identity = PhoneSecurityIdentity(2, "phone-test", 8767, Base64.getEncoder().encodeToString(resource("phone.der")))
        assertEquals(PhoneCertificate.pin(certificate("phone")), PhoneCertificate.pin(PhoneCertificate.decode(identity)))
        assertTrue(runCatching { PhoneCertificate.decode(identity.copy(certificateDerBase64 = "A".repeat(40_000))) }.isFailure)
        assertTrue(runCatching { PhoneCertificate.decode(identity.copy(securityVersion = 1)) }.isFailure)
        assertTrue(runCatching { PhoneCertificate.decode(identity.copy(phoneDeviceId = "bad\nidentity")) }.isFailure)
    }

    data class Recorded(val path: String, val headers: Map<String, String>, val body: String)
    data class Reply(val code: Int = 200, val body: String = "{}", val headers: Map<String, String> = emptyMap(), val webSocket: Boolean = false)

    private inner class PhoneServer(name: String, val handler: (Recorded) -> Reply) : Closeable {
        val requests = CopyOnWriteArrayList<Recorded>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val alive = AtomicBoolean(true)
        private val server: SSLServerSocket
        val port: Int get() = server.localPort
        init {
            val cert = certificate(name)
            val key = KeyFactory.getInstance(cert.publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(resource("$name-key.pk8")))
            val store = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("test", key, "test".toCharArray(), arrayOf(cert)) }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            server = context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            server.soTimeout = 200
            thread(isDaemon = true, name = "test-phone-tls") {
                while (alive.get()) {
                    val socket = try { server.accept() } catch (_: Exception) { continue }
                    sockets.add(socket)
                    thread(isDaemon = true) {
                        socket.use {
                            runCatching {
                                socket.soTimeout = 5000
                                val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
                                val first = reader.readLine() ?: return@runCatching
                                val headers = linkedMapOf<String, String>()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                                }
                                val length = headers["content-length"]?.toIntOrNull() ?: 0
                                val chars = CharArray(length)
                                var offset = 0
                                while (offset < length) { val count = reader.read(chars, offset, length - offset); if (count < 0) break; offset += count }
                                val request = Recorded(first.split(' ')[1], headers, String(chars))
                                requests.add(request)
                                val reply = handler(request)
                                if (reply.webSocket) {
                                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                                    socket.outputStream.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                                    socket.outputStream.flush()
                                    socket.inputStream.read()
                                } else {
                                    val payload = reply.body.toByteArray()
                                    val custom = reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                                    socket.outputStream.write("HTTP/1.1 ${reply.code} Test\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n${custom}Connection: close\r\n\r\n".toByteArray())
                                    socket.outputStream.write(payload); socket.outputStream.flush()
                                }
                            }
                        }
                        sockets.remove(socket)
                    }
                }
            }
        }
        override fun close() { alive.set(false); server.close(); sockets.forEach { runCatching { it.close() } } }
    }

    private class PlainTrap : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val connected = AtomicBoolean(false)
        val received = CountDownLatch(1)
        @Volatile var bytes = ByteArray(0)
        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        connected.set(true); socket.soTimeout = 3000
                        val buffer = ByteArray(8192)
                        val n = socket.inputStream.read(buffer)
                        if (n > 0) bytes = buffer.copyOf(n)
                        received.countDown()
                        socket.outputStream.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    }
                }
            }
        }
        override fun close() { server.close() }
    }
}
