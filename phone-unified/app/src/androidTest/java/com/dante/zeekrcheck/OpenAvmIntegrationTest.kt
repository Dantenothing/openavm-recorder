package com.dante.zeekrcheck

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.security.NetworkSecurityPolicy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.OpenAvmHost
import com.dante.zeekrbridge.core.*
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.service.BridgeService
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.github.dantenothing.avmtransfer.protocol.HealthResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Uses synthetic local media only. Never creates a cloud transport or sends a vehicle command. */
class OpenAvmIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var originalLanguage = PhoneLanguageMode.SYSTEM
    @Before fun useKnownFixtureLanguage() {
        check(context.packageName.endsWith(".freshqa"))
        OpenAvmIntegration.initialize(context)
        originalLanguage = PhoneLanguage.mode
        instrumentation.runOnMainSync { PhoneLanguage.selectMode(PhoneLanguageMode.SIMPLIFIED_CHINESE) }
    }
    @After fun restoreLanguage() { instrumentation.runOnMainSync { PhoneLanguage.selectMode(originalLanguage) } }

    @Test fun notificationsAndManifestUseOneHostAndKeepServicesPrivate() {
        val media = OpenAvmHost.openIntent(context, "media")
        val connection = OpenAvmHost.openIntent(context, "connection")
        val pm = context.packageManager
        val host = pm.getActivityInfo(requireNotNull(media.component), 0)
        assertEquals(MainActivity::class.java.name, host.targetActivity ?: host.name)
        assertEquals(media.component, connection.component)
        assertFalse("PendingIntent routes must stay distinct", media.filterEquals(connection))
        assertEquals("media", media.getStringExtra(OpenAvmHost.DESTINATION))
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName), 0)
        assertEquals(1, launchers.size)
        val launcher = launchers.single().activityInfo
        assertEquals(MainActivity::class.java.name, launcher.targetActivity ?: launcher.name)
        for (name in listOf("com.dante.zeekrbridge.service.BridgeService", "com.dante.zeekrbridge.service.MediaExportService")) {
            assertFalse(pm.getServiceInfo(ComponentName(context, name), 0).exported)
        }
        val provider = pm.resolveContentProvider("${context.packageName}.fileprovider", PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("gateway.zeekrlife.com"))
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
    }

    @Test fun mediaSharingCannotGrantAccessToAccountOrPairingFiles() {
        val authority = "${context.packageName}.fileprovider"
        for (directory in listOf("received", "trash", "exports", "usb-sentry-imports", "sounds", "openavm-share")) {
            assertEquals(authority, FileProvider.getUriForFile(context, authority, File(context.filesDir, "$directory/synthetic.txt")).authority)
        }
        assertEquals(authority, FileProvider.getUriForFile(context, authority, File(context.cacheDir, "sound-export/synthetic.wav")).authority)
        for (privateFile in listOf(File(context.filesDir,"pairing.json"), File(context.filesDir,"diagnostic-state.json"),
            File(context.noBackupFilesDir,"session.sealed"), File(context.noBackupFilesDir,"protocol.sealed"),
            File(context.noBackupFilesDir,"connection-profile.sealed"))) {
            try {
                FileProvider.getUriForFile(context, authority, privateFile)
                fail("Private records must not be shareable")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun embeddedLibraryConnectionAndToolsDoNotImplicitlyStartReceiver() {
        OpenAvmIntegration.initialize(context)
        assumeFalse(context.getSharedPreferences("phone_product_settings",0).getBoolean("auto_start_server",false))
        assumeFalse(BridgeService.running.value)
        var destination by mutableStateOf("media")
        compose.setContent { AssistantTheme { OpenAvmDestination(destination,false,{}, {destination="media"}, {destination=it}) } }
        compose.onNodeWithText("媒体库").assertIsDisplayed()
        compose.onNodeWithTag("media_tools").performClick()
        compose.onNodeWithTag("openavm_destination_media_tools").assertIsDisplayed()
        compose.onNodeWithTag("media_connection").performClick()
        compose.onNodeWithTag("openavm_destination_connection").assertIsDisplayed()
        compose.onNodeWithTag("openavm_back").performClick()
        compose.onNodeWithText("媒体库").assertIsDisplayed()
        compose.runOnIdle { destination="media_settings" }
        compose.onNodeWithTag("openavm_destination_media_settings").assertIsDisplayed()
        compose.onNodeWithText("已配对车辆").assertIsDisplayed()
        // Unified app uses the global language setting; the embedded media page has no second selector.
        compose.onNodeWithText("影像界面语言").assertDoesNotExist()
        compose.runOnIdle { assertFalse(BridgeService.running.value); assertFalse(BridgeServer.state.value.running) }
    }

    @Test fun authenticatedLoopbackTransferAppearsInLibraryAndPlaysOnPhone() {
        OpenAvmIntegration.initialize(context)
        assumeFalse(BridgeService.running.value || BridgeServer.state.value.running)
        val suffix = UUID.randomUUID().toString()
        val carId = "qa-local-$suffix"
        val fileName = "synthetic-unified-$suffix.mp4"
        val bytes = instrumentation.context.assets.open("synthetic-unified.mp4").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val received = File(ReceivedStore.receivedDir(), fileName)
        var uploadId: String? = null
        var mounted by mutableStateOf(false)
        var detail by mutableStateOf(false)
        try {
            BridgeServer.start(context)
            assertTrue(BridgeServer.state.value.running)
            // Default-valued service/version fields may be omitted by the shared serializer.
            val health=Json { ignoreUnknownKeys = true }.decodeFromString(HealthResponse.serializer(),request("GET","/health").toString())
            assertEquals("openavm-transfer",health.service)
            assertTrue(health.phoneDeviceId.isNotBlank())
            assertTrue(io.github.dantenothing.avmtransfer.protocol.StripRepackContract.TRANSFER_CAPABILITY in health.recordingRasterLayouts)
            request("POST","/api/uploads", "{}".toByteArray(), expected=401)
            PairingManager.newPairingCode()
            val pair = JSONObject().put("code",PairingManager.currentCode()).put("deviceName","Synthetic phone QA").put("carDeviceId",carId)
            val token = request("POST","/api/pair",pair.toString().toByteArray()).getString("token")
            val sidecar = JSONObject().put("recordingSessionId",suffix).put("recordingMode","NORMAL")
                .put("startedAtEpochMs",System.currentTimeMillis()).put("actualTrack",JSONObject().put("durationMs",12000))
            val create = JSONObject().put("clientTransferId",suffix).put("fileName",fileName).put("mimeType","video/mp4")
                .put("sizeBytes",bytes.size).put("sha256",sha).put("carId",carId).put("sidecarJson",sidecar.toString()).toString().toByteArray()
            val upload = request("POST","/api/uploads",create,token)
            uploadId = upload.getString("uploadId")
            assertEquals(uploadId,request("POST","/api/uploads",create,token).getString("uploadId"))
            assertEquals(1,upload.getInt("totalChunks"))
            request("PUT","/api/uploads/$uploadId/chunks/0",bytes,token)
            assertEquals(1,request("GET","/api/uploads/$uploadId",token=token).getJSONArray("receivedChunks").length())
            val complete = JSONObject().put("fileName",fileName).put("sha256",sha).toString().toByteArray()
            assertTrue(request("POST","/api/uploads/$uploadId/complete",complete,token).getBoolean("ok"))
            assertTrue(request("POST","/api/uploads/$uploadId/complete",complete,token).getBoolean("ok"))
            assertTrue(received.isFile)
            assertEquals(sha,ReceivedStore.sha256(received))
            val server = BridgeServer.state.value
            assertFalse(CarCatalogStore.online.value)
            assertEquals(VehicleConnectionStatus.RECENT, VehicleHomePolicy.resolve(
                PairingManager.devices.value.map { PairedVehicleSummary(it.carDeviceId, it.name, it.lastSeen) },
                false, server.running, emptyList(), server.startedAtEpochMs,
            ).status)
            runBlocking { MediaIndexStore.refresh(ReceivedStore.files.value,force=true) }
            assertTrue(MediaIndexStore.snapshot.value.sessions.any { session -> session.segments.any { it.fileName==fileName } })
            // Test the actual embedded library and player, not a separate test-only decoder.
            mounted=true
            compose.setContent { AssistantTheme {
                if(mounted) OpenAvmDestination("media",detail,{detail=it},{},{}) else Text("Local QA finished",Modifier.fillMaxSize())
            } }
            compose.onNode(hasText("普通视频") and hasText("1 个分段",substring=true) and hasText("0:12")).performClick()
            compose.waitUntil(15_000) { detail }
            compose.waitUntil(20_000) {
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes()
                    .any { it.config[SemanticsProperties.ProgressBarRangeInfo].let { progress -> progress.current > 500f && progress.range.endInclusive >= 12000f } }
            }
            compose.onNodeWithContentDescription("暂停").performClick()
            compose.onNodeWithContentDescription("播放").assertIsDisplayed()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                val output = File(context.getExternalFilesDir(null),"qa-native-ui/06-unified-synthetic-playback.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
        } finally {
            compose.runOnIdle { mounted=false }
            compose.waitForIdle()
            BridgeServer.stop(context)
            PairingManager.revoke(carId)
            received.delete()
            File(received.parentFile,received.nameWithoutExtension+".json").delete()
            uploadId?.let { id ->
                require(id.matches(Regex("[a-f0-9-]{36}")))
                val root=File(context.filesDir,"uploads").canonicalFile
                val fixture=File(root,id).canonicalFile
                check(fixture.parentFile==root)
                fixture.deleteRecursively()
            }
            ReceivedStore.refresh()
            runBlocking { MediaIndexStore.refresh(ReceivedStore.files.value,force=true) }
        }
    }

    @Test fun busyReceiverPortNeverReportsReadyAndCanRecover() {
        OpenAvmIntegration.initialize(context)
        assumeFalse(BridgeService.running.value || BridgeServer.state.value.running)
        try {
            java.net.ServerSocket(8766).use {
                BridgeServer.start(context)
                assertFalse(BridgeServer.state.value.running)
                assertEquals("TCP_BIND_FAILED", BridgeServer.state.value.startFailure)
            }
            BridgeServer.start(context)
            assertTrue(BridgeServer.state.value.running)
            assertNull(BridgeServer.state.value.startFailure)
        } finally { BridgeServer.stop(context) }
    }

    private fun request(method:String,path:String,body:ByteArray?=null,token:String?=null,expected:Int=200):JSONObject {
        val client = PhoneSecurityTestClient.trusted(context)
        try { return PhoneSecurityTestClient.request(client, method, path, body, token, expected) }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
