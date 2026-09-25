package com.dante.zeekrcheck

import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

/** Run the named phases in separate instrumentation processes, in order, on an offline QA emulator. */
class CloudLifecycleIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun isolated() = check(context.packageName.endsWith(".freshqa") && Build.HARDWARE in setOf("ranchu", "goldfish"))
    private fun synthetic(): String = buildJsonObject {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        put("hmac_access_key", "SYNTHETIC-ACCESS"); put("hmac_secret_key", "SYNTHETIC-HMAC")
        put("password_public_key", Base64.getEncoder().encodeToString(rsa.encoded)); put("prod_secret", "SYNTHETIC-PROD")
        put("vin_key", "0123456789abcdef"); put("vin_iv", "fedcba9876543210")
    }.toString()
    private suspend fun saveSyntheticSession(profile: ImportedProtocol) = withContext(Dispatchers.Main) {
        AppSessions.get(context).write(AppSessions.get(context).current(), SavedSession("SYNTHETIC-USER", "SYNTHETIC-TOKEN",
            "d294932f-f97e-4b6d-a63c-41bfa80d83ca", ProtocolConfig.parse(profile.text).fingerprint()))
        CloudAccess.sessionChanged(context, true)
    }

    @Test fun phase1ImportThenPersistSyntheticAccount() = runBlocking {
        isolated()
        val profile = withContext(Dispatchers.Main) { CloudAccess.import(context, synthetic()) }
        saveSyntheticSession(profile)
        File(context.filesDir, "config-restart-qa.json").writeText(buildJsonObject {
            put("profile", profile.id); put("action", CloudAccess.actionId())
        }.toString())
        assertTrue(CloudAccess.authorized)
        assertTrue(Json.parseToJsonElement(NetworkTrace.export()).jsonObject.getValue("events").jsonArray.isEmpty())
    }

    @Test fun phase2RestartKeepsImportedProfileAndRejectsStaleActionAfterReplacement() = runBlocking {
        isolated()
        val expected = Json.parseToJsonElement(File(context.filesDir, "config-restart-qa.json").readText()).jsonObject
        val profile = withTimeout(10_000) { CloudAccess.loaded(context) }!!
        assertEquals(expected.getValue("profile").jsonPrimitive.content, profile.id)
        assertEquals(expected.getValue("action").jsonPrimitive.content, CloudAccess.actionId())
        assertTrue(CloudAccess.authorized)
        assertNotNull(SecureSessionStore(context).load())
        val key = "b".repeat(64)
        val stale = CardActionService.intent(context, "find", key, null)
        val replacement = withContext(Dispatchers.Main) { CloudAccess.import(context, synthetic()) }
        assertFalse(CloudAccess.authorized)
        assertNull(SecureSessionStore(context).load())
        saveSyntheticSession(replacement)
        assertFalse(CloudAccess.accepts(stale))
        assertTrue(CloudAccess.accepts(CardActionService.intent(context, "find", key, null)))
        instrumentation.runOnMainSync {
            OverviewStore.get(context).edit { it.copy(vehicleKey = key, message = null) }
            ContextCompat.startForegroundService(context, stale)
        }
        withTimeout(5_000) {
            while (OverviewStore.get(context).state.value.message != "请打开 App 设置云端连接；未发送操作") delay(30)
        }
        assertNull(OverviewStore.get(context).state.value.actionInFlight)
        assertTrue(Json.parseToJsonElement(NetworkTrace.export()).jsonObject.getValue("events").jsonArray.isEmpty())
        // Simulate process death after the durable switch marker, before transaction completion.
        check(context.getSharedPreferences("cloud_access_v1", 0).edit().putBoolean("switchPending", true).commit())
    }

    @Test fun phase3InterruptedSwitchFailsClosedAfterRestart() = runBlocking {
        isolated()
        withTimeout(10_000) { CloudAccess.loaded(context) }
        assertFalse(CloudAccess.ready || CloudAccess.authorized)
        assertTrue(CloudAccess.state.value.needsImport)
        assertNull(SecureConfigStore(context).load())
        assertNull(SecureSessionStore(context).load())
        assertFalse(AssistantStore.get(context).state.value.widgetSyncEnabled)
        assertTrue(Json.parseToJsonElement(NetworkTrace.export()).jsonObject.getValue("events").jsonArray.isEmpty())
    }
}
