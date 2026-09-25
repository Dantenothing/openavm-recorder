package com.dante.zeekrcheck

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.ProtocolConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Background-only first-install verification. Never run on a configured application UID. */
class BundledProtocolIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun assetInitializesAnEncryptedProfileWithoutAnExternalFile() {
        check(context.packageName.endsWith(".freshqa"))
        val store = SecureConfigStore(context, "bundled-profile-test")
        try {
            store.clear()
            assertNull(store.load())
            val text = store.loadOrDefault()
            ProtocolConfig.parse(text)
            assertTrue("Profile must persist without exposing its values", store.load() == text)
            assertTrue("Repeated initialization must reuse the saved profile", store.loadOrDefault() == text)
            val sealed = File(context.noBackupFilesDir, "bundled-profile-test.sealed").readBytes().toString(Charsets.UTF_8)
            assertFalse(sealed.contains("hmac_access_key"))
        } finally { store.clear() }
    }

    @Test fun freshAppControllerIsReadyForLoginWithoutImportingOrContactingTheCar() {
        check(context.packageName.endsWith(".freshqa"))
        check(SecureSessionStore(context).load() == null) { "Fresh-install test requires no saved account" }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var model: CheckViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(
                context.applicationContext as VehicleApplication))[CheckViewModel::class.java]
        }
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (model.state.value.busy && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            assertTrue("Bundled connection profile must be ready", model.state.value.configReady)
            assertTrue("Default must be encrypted in local storage", model.state.value.configSaved)
            assertFalse(model.state.value.busy)
            assertFalse(model.state.value.connected)
            assertFalse(model.state.value.sessionSaved)
            assertTrue(model.state.value.vehicles.isEmpty())
        } finally { instrumentation.runOnMainSync { owner.viewModelStore.clear() } }
    }
}
