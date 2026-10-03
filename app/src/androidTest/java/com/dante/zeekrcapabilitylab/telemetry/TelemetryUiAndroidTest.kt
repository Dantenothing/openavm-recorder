package com.dante.zeekrcapabilitylab.telemetry

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.mirror.MirrorSleepTestHostActivity
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.ui.product.TelemetryRouteDialog
import com.dante.zeekrcapabilitylab.ui.product.TelemetrySettingsCard
import com.dante.zeekrcapabilitylab.ui.product.VehicleTelemetrySettingsCard
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class TelemetryUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val languages = listOf(AppLanguageMode.ENGLISH, AppLanguageMode.SIMPLIFIED_CHINESE)
    @Before fun prepare() {
        assumeTrue(Build.HARDWARE == "ranchu")
        // V5 has no such key; prior camera settings and granted permissions must not opt it in.
        context.getSharedPreferences("zeekr_product_settings_v2", android.content.Context.MODE_PRIVATE)
            .edit().remove(SettingsStore.KEY_RECORDING_TELEMETRY).remove(SettingsStore.KEY_RECORDING_VEHICLE_TELEMETRY).commit()
        assertFalse(SettingsStore.get(context).recordingTelemetryEnabled)
        assertFalse(SettingsStore.get(context).recordingVehicleTelemetryEnabled)
    }
    @After fun cleanup() {
        SettingsStore.get(context).setRecordingTelemetryEnabled(false)
        SettingsStore.get(context).setRecordingVehicleTelemetryEnabled(false)
    }

    @Test fun vehicleDataOptInIsIndependentFromLocationAndCanBeDisabled() {
        var generation by mutableIntStateOf(0)
        compose.setContent { ZeekrCapabilityLabTheme { key(generation) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { VehicleTelemetrySettingsCard() }
        } } }
        languages.forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language); generation++ }
            compose.onNodeWithTag("vehicle-telemetry-switch").assertIsOff().performClick().assertIsOn()
            assertTrue(SettingsStore.get(context).recordingVehicleTelemetryEnabled)
            assertFalse(SettingsStore.get(context).recordingTelemetryEnabled)
            compose.onNodeWithTag("vehicle-telemetry-switch").performClick().assertIsOff()
            compose.onNodeWithTag("vehicle-telemetry-clear").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText(AppLanguage.text("Cancel", "取消")).performClick()
        }
    }

    @Test fun playbackKeepsVehicleSpeedSeparateFromGpsAndUnknownDoesNotBecomeZero() {
        val fix = TelemetryFix(1_000, 1_790_000_000_000, 0.0, 0.0, 5f, "gps", 10f)
        val track = TelemetryTrack(TelemetryHeader(sessionId = "00000000-0000-4000-8000-000000000003", startedElapsedMs = 1_000), listOf(fix))
        val vehicle = VehicleTelemetrySample(1_000, fix.epochMs, mapOf(
            "speed" to VehicleTelemetryValue(1_000, number = 0.0),
            "gear" to VehicleTelemetryValue(1_000, text = "P")))
        var sample by mutableStateOf<VehicleTelemetrySample?>(vehicle)
        compose.runOnIdle { AppLanguage.setMode(context, AppLanguageMode.ENGLISH) }
        compose.setContent { ZeekrCapabilityLabTheme {
            TelemetryRouteDialog(track, fix, false, true, 0, 1_000, false, {}, {}, {}, {},
                vehicleSample = sample, vehicleAvailable = true)
        } }
        compose.onNodeWithTag("telemetry-speed").assertTextEquals("36 km/h")
        compose.onNodeWithTag("vehicle-playback-data").performScrollTo().assertTextEquals("Vehicle · 0 km/h · P")
        compose.runOnIdle { sample = null }
        compose.onNodeWithTag("vehicle-playback-data").assertTextEquals("Vehicle · Speed unavailable · —")
        compose.onNodeWithTag("telemetry-export-gpx").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("telemetry-google-point").performScrollTo().assertIsEnabled()
    }

    @Test fun settingsDefaultsOffAndHasReachableEnglishAndChineseControls() {
        var generation by mutableIntStateOf(0)
        compose.setContent { ZeekrCapabilityLabTheme { key(generation) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { TelemetrySettingsCard() }
        } } }
        languages.forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language); generation++ }
            compose.onNodeWithTag("telemetry-switch").assertIsOff().assertIsDisplayed()
            compose.onNodeWithTag("telemetry-clear").performScrollTo().assertIsDisplayed()
            capture(language.storedValue + "-settings")
            compose.onNodeWithTag("telemetry-clear").performClick()
            compose.onNodeWithText(AppLanguage.text("Cancel", "取消")).assertIsDisplayed().performClick()
            assertFalse(SettingsStore.get(context).recordingTelemetryEnabled)
        }
    }
    @Test fun grantedPermissionStillNeedsExplicitOptInAndOptOutPersists() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        compose.setContent { ZeekrCapabilityLabTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { TelemetrySettingsCard() }
        } }
        compose.onNodeWithTag("telemetry-switch").assertIsOff().performClick().assertIsOn()
        assertTrue(SettingsStore.get(context).recordingTelemetryEnabled)
        compose.onNodeWithTag("telemetry-switch").performClick().assertIsOff()
        assertFalse(SettingsStore.get(context).recordingTelemetryEnabled)
    }
    @Test fun routeShowsMissingAndZeroSpeedDifferentlyAndCanBeScrubbed() {
        val points = (0..60).map { index -> TelemetryFix(1_000 + index * 1_000L, 1_790_000_000_000 + index * 1_000L,
            -34.9 + index * 0.00001, 138.6 + kotlin.math.sin(index * 0.1) * 0.0004,
            5f, "gps", if (index < 20) null else 0f, breakBefore = index == 0 || index == 35) }
        val track = TelemetryTrack(TelemetryHeader(sessionId = "00000000-0000-4000-8000-000000000003", startedElapsedMs = 1_000), points)
        var position by mutableLongStateOf(0)
        var session by mutableIntStateOf(0)
        var closed = false
        var seeks = 0
        compose.setContent { ZeekrCapabilityLabTheme { key(session) {
            TelemetryRouteDialog(track, TelemetryPlayback.sampleAt(points, 1_000 + position), false, true,
                position, 60_000, false, { position = it }, { seeks++ }, {}, { closed = true })
        } } }
        languages.forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language); session++; position = 0; seeks = 0 }
            compose.onNodeWithTag("telemetry-speed").assertTextEquals(AppLanguage.text("Speed unavailable", "暂无速度"))
            compose.onNodeWithTag("telemetry-route").performScrollTo().assertIsDisplayed()
            capture(language.storedValue + "-route-missing")
            compose.onNodeWithTag("telemetry-seek").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.7f, height / 2f)) }
            compose.waitUntil { position > 20_000 }
            compose.runOnIdle { assertEquals(1, seeks) }
            compose.onNodeWithTag("telemetry-speed").performScrollTo().assertTextEquals("0 km/h")
            capture(language.storedValue + "-route-zero")
            compose.onNodeWithText(AppLanguage.text("Close", "关闭")).performClick()
            compose.runOnIdle { assertTrue(closed); closed = false }
        }
    }
    @Test fun legacyVideoHasAnHonestEmptyState() {
        compose.runOnIdle { AppLanguage.setMode(context, AppLanguageMode.ENGLISH) }
        compose.setContent { ZeekrCapabilityLabTheme {
            TelemetryRouteDialog(null, null, false, false, 0, 0, false, {}, {}, {}, {})
        } }
        compose.onNodeWithText("No location was recorded for this video, or its local history has been cleared.").assertIsDisplayed()
        compose.onNodeWithTag("telemetry-seek").assertIsNotEnabled()
        capture("legacy-no-location")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val directory = File(context.getExternalFilesDir(null), "telemetry-ui").apply { mkdirs() }
            File(directory, name + ".png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
