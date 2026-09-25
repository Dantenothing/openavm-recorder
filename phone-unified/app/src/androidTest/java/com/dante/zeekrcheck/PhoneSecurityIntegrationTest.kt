package com.dante.zeekrcheck

import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.core.*
import com.dante.zeekrbridge.server.*
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import okhttp3.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

class PhoneSecurityIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cars = mutableListOf<String>()
    private lateinit var client: OkHttpClient
    private val files = mutableListOf<File>()
    private val uploads = mutableListOf<String>()

    @Before fun prepare() {
        check(context.packageName.endsWith(".freshqa")) { "Security fixtures require an isolated QA app" }
        OpenAvmIntegration.initialize(context)
        BridgeServer.stop(context)
        BridgeServer.start(context)
        assertTrue("Real Android TLS receiver starts", BridgeServer.state.value.running)
        client = PhoneSecurityTestClient.trusted(context)
    }
    @After fun cleanup() {
        if (!context.packageName.endsWith(".freshqa")) return
        BridgeServer.stop(context)
        cars.forEach(PairingManager::revoke)
        files.forEach { it.delete() }
        uploads.forEach { id ->
            require(id.matches(Regex("[a-f0-9-]{36}")))
            val root = File(context.filesDir, "uploads").canonicalFile
            val dir = File(root, id).canonicalFile
            require(dir.parentFile == root); dir.deleteRecursively()
        }
        ReceivedStore.refresh()
        if (::client.isInitialized) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private fun newCar() = "security-qa-${UUID.randomUUID()}".also(cars::add)
    private fun pair(id: String = newCar()): String {
        PairingManager.newPairingCode()
        val body = JSONObject().put("code", PairingManager.currentCode()).put("carDeviceId", id).put("deviceName", "Security fixture").toString().toByteArray()
        val result = request("POST", "/api/pair", body)
        assertEquals(2, result.getInt("securityVersion"))
        assertEquals("", PairingManager.currentCode())
        return result.getString("token")
    }
    private fun request(method: String, path: String, body: ByteArray? = null, token: String? = null, expected: Int = 200) =
        PhoneSecurityTestClient.request(client, method, path, body, token, expected)

    @Test fun plaintextPrivilegesBlockedBeforeBodiesAndPublicDiscoveryContainsNoCode() {
        PairingManager.newPairingCode()
        val code = PairingManager.currentCode()
        for (discovery in listOf(com.dante.zeekrbridge.core.Protocol.DISCOVERY_REQUEST, TransferProtocol.DISCOVERY_REQUEST)) {
            DatagramSocket().use { socket ->
                socket.soTimeout = 3000
                val data = discovery.toByteArray()
                socket.send(DatagramPacket(data, data.size, InetAddress.getLoopbackAddress(), 8766))
                val reply = DatagramPacket(ByteArray(4096), 4096); socket.receive(reply)
                val text = String(reply.data, 0, reply.length)
                assertFalse(text.contains(code))
                val value = JSONObject(text)
                assertEquals(8767, value.getInt("tlsPort"))
                assertEquals(2, value.getJSONArray("securityVersions").getInt(0))
                assertEquals("", value.optString("pairingId"))
            }
        }
        for (path in listOf("/api/pair", "/api/pair/begin", "/api/pair/finalize", "/api/session", "/api/uploads", "/api/outbound", "/control")) {
            // Deliberately don't send the declared body. Rejection must precede reading it.
            Socket("127.0.0.1", 8766).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write(("POST $path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nContent-Length: 4096\r\n\r\n").toByteArray())
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 403"))
                assertTrue(response.contains("SECURE_TRANSPORT_REQUIRED"))
            }
        }
        for (path in listOf("/health", "/api/security/identity")) {
            val text = URL("http://127.0.0.1:8766$path").readText()
            assertFalse(text.contains(code)); assertFalse(text.contains("token", true))
        }
        assertFalse(ServerLog.lines.value.any { it.contains(code) })
    }

    @Test fun identityAndTokenPersistButActiveWindowDoesNotSurviveRestart() {
        val pin = BridgeServer.state.value.identityFingerprint
        val phoneId = PairingManager.phoneDeviceId.value
        val token = pair()
        PairingManager.newPairingCode()
        val oldCode = PairingManager.currentCode()
        assertFalse(File(context.filesDir, "pairing.json").readText().contains(oldCode))
        assertFalse(File(context.filesDir, "pairing.json").readText().contains(token))
        BridgeServer.stop(context)
        PairingManager.init(context)
        BridgeServer.start(context)
        assertEquals(pin, BridgeServer.state.value.identityFingerprint)
        assertEquals(phoneId, PairingManager.phoneDeviceId.value)
        assertFalse(PairingManager.codeValid(oldCode))
        assertEquals("", PairingManager.currentCode())
        assertEquals(2, request("GET", "/api/session", token = token).getInt("securityVersion"))
        assertEquals(64, pin.length)
    }

    @Test fun guessesCloseWindowAndLegacyRecordsNeverAuthenticate() {
        val id = newCar()
        val legacyToken = "ab".repeat(32)
        val file = File(context.filesDir, "pairing.json")
        val saved = JSONObject(file.readText())
        saved.getJSONArray("devices").put(JSONObject().put("carDeviceId", id).put("name", "Legacy fixture")
            .put("token", PhoneTokenCipher.encrypt(context, legacyToken)).put("pairedAt", 1))
        file.writeText(saved.toString())
        PairingManager.init(context)
        request("GET", "/api/session", token = legacyToken, expected = 401)
        PairingManager.newPairingCode()
        val wrong = "000000"
        val body = JSONObject().put("code", wrong).put("deviceName", "Fixture").put("carDeviceId", id).toString().toByteArray()
        repeat(3) { request("POST", "/api/pair", body, expected = 401) }
        repeat(2) { request("POST", "/api/pair", body, expected = 429) }
        assertTrue(PairingManager.lockedOut.value)
        assertEquals("", PairingManager.currentCode())
        val token = pair(id)
        assertEquals(id, request("GET", "/api/session", token = token).getString("carDeviceId"))
        request("GET", "/api/session", token = legacyToken, expected = 401)
    }

    @Test fun strictClientRejectsChangedPinAndWrongHostname() {
        val badClient = PhoneSecurityTestClient.trusted(context, badPin = true)
        try {
            PhoneSecurityTestClient.request(badClient, "GET", "/health")
            fail("Wrong SPKI must reject TLS")
        } catch (_: SSLPeerUnverifiedException) { }
        finally { badClient.connectionPool.evictAll(); badClient.dispatcher.executorService.shutdown() }
        try {
            PhoneSecurityTestClient.request(client, "GET", "/health", host = "wrong.invalid")
            fail("Normal hostname verification must remain enabled")
        } catch (_: SSLPeerUnverifiedException) { }
    }

    @Test fun sameIdentityAtAnotherLoopbackAddressKeepsTheSession() {
        val token = pair()
        val moved = PhoneSecurityTestClient.trusted(context, address = "127.0.0.2")
        try {
            assertEquals(2, PhoneSecurityTestClient.request(moved, "GET", "/api/session", token = token).getInt("securityVersion"))
        } finally { moved.connectionPool.evictAll(); moved.dispatcher.executorService.shutdown() }
    }

    @Test fun secureMusicListRangeDownloadAndReceiptAreOwnedByTheAuthenticatedCar() {
        val car = newCar(); val token = pair(car); val other = pair()
        val wav = java.nio.ByteBuffer.allocate(52).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(44).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(8).put(ByteArray(8)).array()
        val offer = OutboundOfferStore.importStream("security-fixture.wav", targetCarDeviceId = car) { wav.inputStream() }
        try {
            assertEquals(1, request("GET", "/api/outbound", token = token).getJSONArray("offers").length())
            assertEquals(0, request("GET", "/api/outbound", token = other).getJSONArray("offers").length())
            request("GET", "/api/outbound/${offer.offerId}", token = other, expected = 403)
            val download = Request.Builder().url("https://${PhoneSecurityTestClient.NAME}:8767/api/outbound/${offer.offerId}")
                .header("Authorization", "Bearer $token").header("Range", "bytes=8-19").build()
            client.newCall(download).execute().use {
                assertEquals(206, it.code); assertArrayEquals(wav.copyOfRange(8, 20), it.body!!.bytes())
            }
            val update = """{"state":"COMPLETED","operationId":"synthetic-install"}""".toByteArray()
            request("POST", "/api/outbound/${offer.offerId}/status", update, other, expected = 404)
            request("POST", "/api/outbound/${offer.offerId}/status", update, token)
            assertEquals("COMPLETED", OutboundOfferStore.get(offer.offerId)!!.state)
            PairingManager.revoke(car)
            request("POST", "/api/outbound/${offer.offerId}/status", update, token, expected = 401)
        } finally { OutboundOfferStore.delete(offer.offerId) }
    }

    @Test fun wssRequiresBearerBeforeUpgradeAndRejectsSpoofedCarIdentity() {
        val rejected = CountDownLatch(1)
        var httpCode = 0
        val unauthenticated = client.newWebSocket(Request.Builder().url("wss://${PhoneSecurityTestClient.NAME}:8767/control").build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { httpCode = response?.code ?: 0; rejected.countDown() }
        })
        try { assertTrue(rejected.await(5, TimeUnit.SECONDS)); assertEquals(401, httpCode) } finally { unauthenticated.cancel() }
        val token = pair()
        val closed = CountDownLatch(1)
        val ws = client.newWebSocket(Request.Builder().url("wss://${PhoneSecurityTestClient.NAME}:8767/control")
            .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"HELLO","sequence":1,"payload":{"carDeviceId":"someone-else"}}""")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
            })
        try { assertTrue(closed.await(5, TimeUnit.SECONDS)) } finally { ws.cancel() }
    }

    @Test fun tlsPortConflictFailsClosedAndReleasesPublicPort() {
        BridgeServer.stop(context)
        java.net.ServerSocket(8767).use {
            BridgeServer.start(context)
            assertFalse(BridgeServer.state.value.running)
            assertEquals("TLS_START_FAILED", BridgeServer.state.value.startFailure)
            java.net.ServerSocket(8766).close()
        }
        BridgeServer.start(context)
        assertTrue(BridgeServer.state.value.running)
    }

    @Test fun rapidReceiverRestartAfterHttpProbeDoesNotStickInTimeWait() {
        repeat(5) {
            assertTrue(URL("http://127.0.0.1:8766/health").readText().contains("OK"))
            BridgeServer.stop(context)
            BridgeServer.start(context)
            assertTrue("Public/TLS listener rebinds after recent traffic", BridgeServer.state.value.running)
            request("GET", "/health")
        }
    }

    @Test fun rePairAndRevokeCloseWssAndRejectOldCredentials() {
        val id = newCar()
        val old = pair(id)
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val ws = client.newWebSocket(Request.Builder().url("wss://${PhoneSecurityTestClient.NAME}:8767/control")
            .header("Authorization", "Bearer $old").build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
            })
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val fresh = pair(id)
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            request("GET", "/api/session", token = old, expected = 401)
            assertEquals(id, request("GET", "/api/session", token = fresh).getString("carDeviceId"))
            PairingManager.revoke(id)
            request("GET", "/api/session", token = fresh, expected = 401)
            assertFalse(ServerLog.lines.value.any { it.contains(old) || it.contains(fresh) })
        } finally { ws.cancel() }
    }

    @Test fun encryptedChunkResumeOwnershipHashAndIdempotentCommit() {
        val car = newCar(); val token = pair(car); val other = pair()
        val bytes = ByteArray(TransferProtocol.CHUNK_SIZE + 17) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val name = "security-${UUID.randomUUID()}.mp4"
        val body = JSONObject().put("clientTransferId", UUID.randomUUID().toString()).put("fileName", name)
            .put("mimeType", "video/mp4").put("sizeBytes", bytes.size).put("sha256", sha).put("carId", car).toString().toByteArray()
        val id = request("POST", "/api/uploads", body, token).getString("uploadId").also(uploads::add)
        assertEquals(id, request("POST", "/api/uploads", body, token).getString("uploadId"))
        request("GET", "/api/uploads/$id", token = other, expected = 404)
        request("PUT", "/api/uploads/$id/chunks/0", bytes.copyOfRange(0, TransferProtocol.CHUNK_SIZE), token)
        val pin = BridgeServer.state.value.identityFingerprint
        BridgeServer.stop(context); BridgeServer.start(context)
        assertEquals(pin, BridgeServer.state.value.identityFingerprint)
        assertEquals(1, request("GET", "/api/uploads/$id", token = token).getJSONArray("receivedChunks").length())
        request("PUT", "/api/uploads/$id/chunks/1", bytes.copyOfRange(TransferProtocol.CHUNK_SIZE, bytes.size), token)
        val complete = JSONObject().put("fileName", name).put("sha256", sha).toString().toByteArray()
        val received = File(ReceivedStore.receivedDir(), name).also(files::add)
        repeat(2) { assertTrue(request("POST", "/api/uploads/$id/complete", complete, token).getBoolean("ok")) }
        assertEquals(sha, ReceivedStore.sha256(received))
        PairingManager.revoke(car)
        request("GET", "/api/uploads/$id", token = token, expected = 401)
        assertTrue(received.isFile)
    }
}
