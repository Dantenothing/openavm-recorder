package com.dante.zeekrcheck

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import com.dante.zeekrbridge.core.PhoneTokenCipher
import com.dante.zeekrbridge.server.PhoneTlsIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

/** Run against the retained, signed Phone 5.0.0 on an OFFLINE emulator only, before installing 5.0.1. */
class LegacyCloudUpgradeSeedTest {
    @Suppress("DEPRECATION")
    @Test fun seedTheActualLegacyStorageFormatWithoutOpeningTheCloudController() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName == "com.dante.zeekrbridge")
        check(context.packageManager.getPackageInfo(context.packageName, 0).versionCode == 51)
        val now = System.currentTimeMillis()
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val text = buildJsonObject {
            put("hmac_access_key", "SYNTHETIC-ACCESS"); put("hmac_secret_key", "SYNTHETIC-HMAC")
            put("password_public_key", Base64.getEncoder().encodeToString(rsa.encoded)); put("prod_secret", "SYNTHETIC-PROD")
            put("vin_key", "0123456789abcdef"); put("vin_iv", "fedcba9876543210")
        }.toString()
        SecureRecordStore(context, "protocol", SealedConfig.Purpose.PROTOCOL) { }.save(text)
        SecureSessionStore(context).save(SavedSession("SYNTHETIC-USER", "SYNTHETIC-ACCESS",
            "d294932f-f97e-4b6d-a63c-41bfa80d83ca", ProtocolConfig.parse(text).fingerprint()))
        val plan = DeparturePlan(id = "33e1f930-bae3-4c5c-a23b-7e843a6e38c0", vehicleKey = "b".repeat(64),
            departure = now + 86_400_000, preferences = ComfortPreferences(target = 24))
        val assistant = AssistantState(preferences = plan.preferences, plans = listOf(plan),
            home = CarLocation(-35.0, 138.0, now, "Synthetic QA home", true), guardEnabled = true, homeGuardEnabled = true,
            operationPending = true, pendingBodyAction = BodyAction.HORN)
        SecureRecordStore(context, "assistant", SealedConfig.Purpose.ASSISTANT) { }.save(assistant.encode())
        File(context.noBackupFilesDir, "vehicle-overview.json").writeText(VehicleOverview(vehicleKey = "b".repeat(64), nickname = "QA retained vehicle").encode())
        val identity = PhoneTlsIdentity.load(context).fingerprint
        val token = PhoneTokenCipher.encrypt(context, "e".repeat(64))
        val pairing = buildJsonObject {
            put("code", ""); put("codeExpiresAt", 0); put("phoneDeviceId", "synthetic-qa-phone"); put("phoneName", "QA phone")
            putJsonArray("devices") { add(buildJsonObject {
                put("carDeviceId", "synthetic-qa-recorder"); put("name", "QA Recorder"); put("token", token)
                put("pairedAt", now); put("lastSeen", now); put("securityVersion", 2); put("phoneIdentityPin", identity)
            }) }
        }.toString()
        File(context.filesDir, "pairing.json").writeText(pairing)
        File(context.filesDir, "upgrade-qa-media.marker").writeText("synthetic-media-kept")
        File(context.filesDir, "upgrade-qa-evidence.json").writeText(buildJsonObject {
            put("uid", android.os.Process.myUid()); put("identity", identity); put("pairing", pairing)
        }.toString())
        check(context.getSharedPreferences("cloud_access_v1", 0).edit().clear().commit())
        val scheduler = context.getSystemService(JobScheduler::class.java)
        listOf(3101, 3104, 4102, 4103, 4104, 4105).forEach { id ->
            val service = if (id < 4000) WidgetRefreshService::class.java else AwayGuardService::class.java
            assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(JobInfo.Builder(id, ComponentName(context, service))
                .setMinimumLatency(86_400_000).setPersisted(true).build()))
        }
        assertTrue(Json.parseToJsonElement(NetworkTrace.export()).jsonObject.getValue("events").jsonArray.isEmpty())
    }
}
