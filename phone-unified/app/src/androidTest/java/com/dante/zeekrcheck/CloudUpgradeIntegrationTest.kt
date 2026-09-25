package com.dante.zeekrcheck

import android.app.job.JobScheduler
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.PhoneTokenCipher
import com.dante.zeekrbridge.server.PhoneTlsIdentity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Requires the explicit legacy seed test followed by an in-place APK update. No real account. */
class CloudUpgradeIntegrationTest {
    @Suppress("DEPRECATION")
    @Test fun actualReleaseUpgradeBlocksOldCloudBeforeAnyActivityAndKeepsRecorderIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName == "com.dante.zeekrbridge")
        check(context.packageManager.getPackageInfo(context.packageName, 0).versionCode == 52)
        val evidence = Json.parseToJsonElement(File(context.filesDir, "upgrade-qa-evidence.json").readText()).jsonObject
        withTimeout(15_000) { CloudAccess.loaded(context) }
        assertEquals(evidence.getValue("uid").jsonPrimitive.int, android.os.Process.myUid())
        assertTrue(CloudAccess.state.value.needsImport)
        assertFalse(CloudAccess.ready || CloudAccess.authorized)
        assertNull(SecureConfigStore(context).load())
        assertNull(SecureSessionStore(context).load())
        assertFalse(SecureConfigStore.legacyExists(context))
        val state = AssistantStore.get(context).state.value
        assertFalse(state.guardEnabled || state.homeGuardEnabled || state.widgetSyncEnabled || state.operationPending)
        assertEquals(24, state.preferences.target)
        assertEquals("Synthetic QA home", state.home!!.address)
        assertEquals(1, state.plans.size)
        assertFalse(state.plans.single().enabled)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assertTrue(scheduler.allPendingJobs.none { it.id in setOf(3101, 3104, 4102, 4103, 4104, 4105) })
        assertEquals("synthetic-media-kept", File(context.filesDir, "upgrade-qa-media.marker").readText())
        // PairingManager normalizes whitespace/order when local media initializes. Compare all
        // persisted fields, not formatting, so a subsequent process start is tested as well.
        assertEquals(Json.parseToJsonElement(evidence.getValue("pairing").jsonPrimitive.content),
            Json.parseToJsonElement(File(context.filesDir, "pairing.json").readText()))
        assertEquals(evidence.getValue("identity").jsonPrimitive.content, PhoneTlsIdentity.load(context).fingerprint)
        instrumentation.runOnMainSync { OpenAvmIntegration.initialize(context) }
        val recorder = PairingManager.devices.value.single()
        assertEquals("synthetic-qa-recorder", recorder.carDeviceId)
        assertEquals(2, recorder.securityVersion)
        assertEquals("e".repeat(64), PhoneTokenCipher.decrypt(context, recorder.token))
        val stale = Intent(context, CardActionService::class.java).putExtra("cardAction", "find").putExtra("vehicleKey", "b".repeat(64))
        instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, stale) }
        withTimeout(5_000) {
            while (OverviewStore.get(context).state.value.message != "请打开 App 设置云端连接；未发送操作") delay(30)
        }
        assertNull(OverviewStore.get(context).state.value.actionInFlight)
        assertTrue(Json.parseToJsonElement(NetworkTrace.export()).jsonObject.getValue("events").jsonArray.isEmpty())
    }
}
