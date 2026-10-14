package com.dante.zeekrcapabilitylab.diagnostic

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android SDK only: the same signed test APK exercises the old and new minified release. */
class StabilityUpgradeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence get() = File(context.filesDir, "stability-upgrade-evidence.json")
    private val sampleToken = "a1".repeat(32) // Emulator fixture, never a real pairing credential.
    private val alias = "openavm_car_phone_token"

    @Before fun dedicatedEmulatorOnly() {
        assumeTrue(Build.HARDWARE == "ranchu" &&
            InstrumentationRegistry.getArguments().getString("stabilityUpgrade") == "true")
    }

    @Test fun seedDrive11ForUpgrade() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("5.1.0-drive11", info.versionName)
        val installation = "${info.firstInstallTime}:${info.lastUpdateTime}:116:${info.versionName}"
        val settings = prefs("zeekr_product_settings_v2")
        assertTrue(settings.edit().putBoolean("v5_release_defaults_applied", true)
            .putBoolean("record_front_cabin_v1", true).putBoolean("record_rear_cabin_v1", false)
            .putBoolean("recording_vehicle_signals_v1", true).putBoolean("recording_location_speed_v1", true)
            .putBoolean("playback_map_default_open_v1", true).putBoolean("auto_start_recording", false)
            .putBoolean("shared_input_recording_trial", false).putBoolean("experimental_mirror_preview", false)
            .putBoolean("developer_mode", false).putBoolean("lane_calibrated", true)
            .putString("lane_order", "2,1,4,3").putString("lane_rotations", "0,90,180,270")
            .putString("source_mapping_cabin", "1").putString("source_mapping_ir", "0")
            .putInt("mirror_rotation", 90).putInt("segment_seconds", 120)
            .putString("recording_storage_preference", "INTERNAL")
            .putLong("usb_quota_bytes", 15L * 1024 * 1024 * 1024).commit())
        assertTrue(prefs("mirror_return_v1").edit().putString("mode", "RECORD")
            .putLong("revision", 4).putString("accepted_installation", installation).commit())
        assertTrue(prefs("zeekr_product_language").edit().putString("mode", "en").commit())
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(iv + doFinal(sampleToken.toByteArray()), Base64.NO_WRAP)
        }
        assertTrue(prefs("phone_connection").edit().putString("carId", "stability-emulator-car")
            .putString("phoneId", "stability-emulator-phone").putString("name", "Upgrade fixture")
            .putString("host", "127.0.0.1").putInt("port", 1).putInt("tlsPort", 1)
            .putInt("securityVersion", 1).putString("publicKeySha256", "b2".repeat(32))
            .putLong("pairingGeneration", 42).putString("token", encrypted).commit())
        val video = File(context.filesDir, "recordings/segments/stability-upgrade-fixture.mp4")
        video.parentFile!!.mkdirs()
        instrumentation.context.assets.open("playback/stability-upgrade.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val sidecar = File(video.path + ".json")
        val template = instrumentation.context.assets.open("playback/stability-upgrade.mp4.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        template.put("file", video.absolutePath)
        sidecar.writeText(template.toString())
        val expected = JSONObject().put("oldVersion", info.versionName).put("oldInstallation", installation)
        val expectedPrefs = JSONObject()
        listOf("zeekr_product_settings_v2", "mirror_return_v1", "zeekr_product_language", "phone_connection")
            .forEach { name -> expectedPrefs.put(name, JSONObject(prefs(name).all)) }
        expected.put("preferences", expectedPrefs)
        expected.put("files", JSONObject().put(video.name, sha(video)).put(sidecar.name, sha(sidecar)))
        evidence.writeText(expected.toString())
        noCameraServices()
        status("SEEDED_OLD_RELEASE", 2)
    }

    @Test fun upgradePreservesFilesCalibrationPairingAndRequiresReturnReview() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("5.1.0-stability1", info.versionName)
        assertEquals(117L, info.longVersionCode)
        val expected = JSONObject(evidence.readText())
        val expectedPrefs = expected.getJSONObject("preferences")
        expectedPrefs.keys().forEach { name ->
            val values = expectedPrefs.getJSONObject(name)
            values.keys().forEach { key ->
                val actual = prefs(name).all[key]
                val value = values.get(key)
                if (value is Number && actual is Number) assertEquals("$name/$key", value.toDouble(), actual.toDouble(), 0.000001)
                else assertEquals("$name/$key", value, actual)
            }
        }
        val files = expected.getJSONObject("files")
        files.keys().forEach { name -> assertEquals(files.getString(name), sha(File(context.filesDir, "recordings/segments/$name"))) }
        val encrypted = Base64.decode(prefs("phone_connection").getString("token", null), Base64.NO_WRAP)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            doFinal(encrypted.copyOfRange(12, encrypted.size)).toString(Charsets.UTF_8)
        }
        assertEquals("Existing Android Keystore key still decrypts the synthetic pairing", sampleToken, plain)
        val current = "${info.firstInstallTime}:${info.lastUpdateTime}:117:${info.versionName}"
        assertNotEquals("New installation must need fresh return confirmation",
            current, prefs("mirror_return_v1").getString("accepted_installation", null))
        noCameraServices()
        status("UPGRADE_PRESERVED_AND_KEYSTORE_DECRYPTED", 2)
    }

    @Test fun cleanInstallKeepsOptionalRecordingAndReturnUnconfirmed() {
        assertEquals("5.1.0-stability1", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        val settings = prefs("zeekr_product_settings_v2")
        listOf("record_front_cabin_v1", "record_rear_cabin_v1", "recording_vehicle_signals_v1",
            "recording_location_speed_v1", "playback_map_default_open_v1", "auto_start_recording", "developer_mode")
            .forEach { assertFalse("Fresh optional setting: $it", settings.getBoolean(it, false)) }
        assertNull(prefs("mirror_return_v1").getString("accepted_installation", null))
        assertNull(prefs("phone_connection").getString("token", null))
        assertFalse(evidence.exists())
        noCameraServices()
        status("CLEAN_INSTALL_OPTIONAL_FEATURES_OFF", 0)
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    @Suppress("DEPRECATION")
    private fun noCameraServices() {
        val active = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        assertFalse(active.any { it.service.packageName == context.packageName &&
            (it.service.className.endsWith("CameraRecordingService") || it.service.className.endsWith("StandaloneMirrorService")) })
    }
    private fun status(result: String, preservedFiles: Int) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stabilityUpgradeEvidence",
            JSONObject().put("result", result).put("preservedFiles", preservedFiles)
                .put("syntheticPairingOnly", true).put("cameraServicesRunning", false).toString()) })
    }
}
