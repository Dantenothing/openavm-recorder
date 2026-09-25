package com.dante.zeekrcapabilitylab.transfer

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import com.dante.zeekrcheck.OpenAvmIntegration
import com.dante.zeekrcheck.CloudAccess
import com.dante.zeekrcheck.core.NetworkTrace
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.MediaIndexStore
import com.dante.zeekrbridge.server.BridgeServer
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Test
import org.junit.Assert.*
import java.net.URL
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Snapshot is byte-identical to V5 Recorder's production transport. No cloud configuration/account. */
class CarPhoneSecurityInteropTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun v5TransportPairsTransfersAndReconnectsToTheActualAndroidReceiverWithoutCloudSetup() {
        val releaseEmulator = context.packageName == "com.dante.zeekrbridge" && Build.HARDWARE in setOf("ranchu", "goldfish") &&
            InstrumentationRegistry.getArguments().getString("allowReleaseEmulator") == "true"
        check(context.packageName.endsWith(".freshqa") || releaseEmulator)
        runBlocking { CloudAccess.loaded(context) }
        assertFalse(CloudAccess.ready || CloudAccess.authorized)
        OpenAvmIntegration.initialize(context)
        val car = "interop-${UUID.randomUUID()}"
        var uploadId: String? = null
        val name = "interop-${UUID.randomUUID()}.mp4"
        try {
            BridgeServer.start(context)
            assertTrue(BridgeServer.state.value.running)
            assertEquals("", PairingManager.currentCode())
            val identity = PhoneIdentityJson.decode(URL("http://127.0.0.1:8766/api/security/identity").readText())
            val pin = PhoneCertificate.pin(PhoneCertificate.decode(identity))
            // Independent phone-side display is the trust source, not the network payload alone.
            assertEquals(BridgeServer.state.value.identityFingerprint, pin)
            var endpoint = PhoneEndpoint("127.0.0.1", 8766, "", "Phone fixture", identity.phoneDeviceId, 2, identity.tlsPort, pin, 1)
            PairingManager.newPairingCode()
            VerifiedPhoneTransport(endpoint) { true }.use { pairing ->
                val body = json.encodeToString(PairRequest.serializer(), PairRequest(PairingManager.currentCode(), "V5 fixture", car))
                val response = pairing.newCall("/api/pair", "POST", body.toRequestBody("application/json".toMediaType()), authenticated = false)
                    .execute().use { assertEquals(200, it.code); json.decodeFromString(SecurePhonePairResponse.serializer(), it.body!!.string()) }
                assertEquals(2, response.securityVersion)
                endpoint = endpoint.copy(token = response.token)
            }
            VerifiedPhoneTransport(endpoint) { true }.use { transport ->
                assertEquals(car, transport.verifySession(car).response.carDeviceId)
                assertEquals(listOf(2), transport.health().securityVersions)
                assertTrue(StripRepackContract.TRANSFER_CAPABILITY in transport.health().recordingRasterLayouts)
                val opened = CountDownLatch(1)
                val message = CountDownLatch(1)
                val ws = transport.newWebSocket(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
                    override fun onMessage(webSocket: WebSocket, text: String) { message.countDown() }
                })
                try {
                    assertTrue(opened.await(5, TimeUnit.SECONDS))
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (BridgeServer.connectedCars() == 0 && System.nanoTime() < until) Thread.sleep(10)
                    assertTrue(BridgeServer.connectedCars() > 0)
                    BridgeServer.sendToCars("HEARTBEAT", emptyMap())
                    assertTrue(message.await(5, TimeUnit.SECONDS))
                } finally { ws.cancel() }
                val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-unified.mp4").use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val create = UploadCreateRequest(UUID.randomUUID().toString(), name, "video/mp4", bytes.size.toLong(), sha, car)
                uploadId = transport.newCall("/api/uploads", "POST", json.encodeToString(UploadCreateRequest.serializer(), create)
                    .toRequestBody("application/json".toMediaType())).execute().use {
                    assertEquals(200, it.code); json.decodeFromString(UploadCreateResponse.serializer(), it.body!!.string()).uploadId
                }
                transport.newCall("/api/uploads/$uploadId/chunks/0", "PUT", bytes.toRequestBody()).execute().use { assertEquals(200, it.code) }
                // Receiver restart emulates transport loss; completed chunks must survive.
                BridgeServer.stop(context); BridgeServer.start(context)
                transport.newCall("/api/uploads/$uploadId").execute().use {
                    assertEquals(listOf(0), json.decodeFromString(UploadStatusResponse.serializer(), it.body!!.string()).receivedChunks)
                }
                val complete = json.encodeToString(UploadCompleteRequest.serializer(), UploadCompleteRequest(sha, name))
                transport.newCall("/api/uploads/$uploadId/complete", "POST", complete.toRequestBody("application/json".toMediaType())).execute().use {
                    assertEquals(200, it.code); assertTrue(json.decodeFromString(UploadCompleteResponse.serializer(), it.body!!.string()).ok)
                }
                val received = File(ReceivedStore.receivedDir(), name)
                assertTrue(received.isFile)
                assertEquals(sha, ReceivedStore.sha256(received))
                runBlocking { MediaIndexStore.refresh(ReceivedStore.files.value, force = true) }
                assertTrue(MediaIndexStore.snapshot.value.sessions.any { session -> session.segments.any { it.fileName == name } })
                PairingManager.revoke(car)
                try { transport.verifySession(car); fail("Revoked authorization must fail in the real V5 client") }
                catch (e: PhoneSecurityException) { assertEquals(PhoneSecurityError.AUTH_REVOKED, e.error) }
            }
            assertFalse(CloudAccess.ready || CloudAccess.authorized)
            assertTrue(Json.parseToJsonElement(NetworkTrace.export()).jsonObject.getValue("events").jsonArray.isEmpty())
        } finally {
            BridgeServer.stop(context); PairingManager.revoke(car)
            File(context.filesDir, "received/$name").delete()
            ReceivedStore.refresh()
            uploadId?.let { id ->
                require(id.matches(Regex("[a-f0-9-]{36}")))
                val root = File(context.filesDir, "uploads").canonicalFile
                val dir = File(root, id).canonicalFile
                require(dir.parentFile == root); dir.deleteRecursively()
            }
        }
    }
}
