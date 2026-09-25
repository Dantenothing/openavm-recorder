package com.dante.zeekrcheck

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

/** Isolated first-install QA only. All configuration values are generated synthetic fixtures. */
class ImportedConfigIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun isolated() = check(context.packageName.endsWith(".freshqa"))
    private fun synthetic(): String = buildJsonObject {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        put("hmac_access_key", "SYNTHETIC-ACCESS"); put("hmac_secret_key", "SYNTHETIC-HMAC")
        put("password_public_key", Base64.getEncoder().encodeToString(key.encoded)); put("prod_secret", "SYNTHETIC-PROD")
        put("vin_key", "0123456789abcdef"); put("vin_iv", "fedcba9876543210")
    }.toString()

    @Test fun noConfigurationStartsNormallyWithoutABundledAssetOrSavedAccount() = runBlocking {
        isolated()
        withContext(Dispatchers.Main) { CloudAccess.loaded(context); CloudAccess.remove(context, true) }
        assertFalse(context.assets.list("connection").orEmpty().contains("zeekr-au-166.json"))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: CheckViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as VehicleApplication))[CheckViewModel::class.java]
        }
        try {
            val state = withTimeout(10_000) { model.state.first { !it.busy } }
            assertFalse(state.configReady || state.connected || state.sessionSaved)
            assertFalse(CloudAccess.ready)
            assertNull(SecureConfigStore(context).load())
            assertNull(SecureSessionStore(context).load())
            // The real local-media initialization is available without a cloud profile.
            instrumentation.runOnMainSync { OpenAvmIntegration.initialize(context) }
            assertFalse(com.dante.zeekrbridge.server.BridgeServer.state.value.running)
        } finally { instrumentation.runOnMainSync { owner.viewModelStore.clear() } }
    }

    @Test fun importAndRemoveNeverLoginAndKeepLocalMediaWhileInvalidImportKeepsProfile() = runBlocking {
        isolated()
        val retained = File(context.filesDir, "import-qa-preserved-media.marker").apply { writeText("synthetic-media-marker") }
        try {
            val imported = withContext(Dispatchers.Main) { CloudAccess.import(context, synthetic()) }
            assertTrue(CloudAccess.ready)
            assertFalse(CloudAccess.authorized)
            assertNull(SecureSessionStore(context).load())
            assertEquals(imported.id, SecureConfigStore(context).profile()!!.id)
            val ciphertext = File(context.noBackupFilesDir, "connection-profile.sealed").readBytes().toString(Charsets.UTF_8)
            assertFalse(ciphertext.contains("SYNTHETIC") || ciphertext.contains("USER_IMPORTED"))
            try { withContext(Dispatchers.Main) { CloudAccess.import(context, "{\"token\":\"SYNTHETIC\"}") }; fail("Account exports must be rejected") }
            catch (_: IllegalArgumentException) { assertEquals(imported.id, SecureConfigStore(context).profile()!!.id) }
            withContext(Dispatchers.Main) { CloudAccess.remove(context, true) }
            assertNull(SecureConfigStore(context).load())
            assertFalse(CloudAccess.authorized || CloudAccess.ready)
            assertEquals("synthetic-media-marker", retained.readText())
        } finally { retained.delete() }
    }
}
