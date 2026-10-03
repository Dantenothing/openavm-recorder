package com.dante.zeekrcapabilitylab.enhancement.field

import android.content.Intent
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.mirror.MirrorSleepTestHostActivity
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Emulator-only synthetic observations and injected callbacks; no vehicle reads, GPS or camera starts. */
class FieldTestUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var originalLanguage = AppLanguageMode.SYSTEM
    private var languageChanged = false

    @Before fun before() {
        assumeTrue(Build.HARDWARE == "ranchu" && BuildConfig.VEHICLE_FIELD_TEST_ENABLED)
        originalLanguage = AppLanguage.mode.value
        AppLanguage.setMode(context, AppLanguageMode.ENGLISH)
        languageChanged = true
    }

    @After fun after() { if (languageChanged) AppLanguage.setMode(context, originalLanguage) }

    @Test fun startRequiresParkedCheckAndConsumesOneManualAcknowledgement() {
        val starts = mutableListOf<Triple<Int, Boolean, Boolean>>()
        show(onStart = { duration, coordinates, gps -> starts += Triple(duration, coordinates, gps) })
        compose.onNodeWithTag("field-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-gps").performScrollTo().assertIsOn()
        compose.onNodeWithTag("field-coordinates").performScrollTo().assertIsOff()
        compose.runOnIdle { assertTrue(starts.isEmpty()) }
        compose.onNodeWithTag("field-parked").performScrollTo().performClick()
        compose.onNodeWithTag("field-start").performScrollTo().assertIsEnabled().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("field-parked").performScrollTo().assertIsOff()
        compose.runOnIdle { assertEquals(listOf(Triple(120, false, true)), starts) }
    }

    @Test fun grantingLocationDoesNotAutomaticallyStart() {
        var granted by mutableStateOf(false)
        var permissionRequests = 0
        var starts = 0
        compose.setContent { ZeekrCapabilityLabTheme {
            Screen(locationGranted = granted, onRequestLocation = { permissionRequests++ }, onStart = { _, _, _ -> starts++ })
        } }
        compose.onNodeWithTag("field-parked").performScrollTo().performClick()
        compose.onNodeWithTag("field-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-location-permission").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests); assertEquals(0, starts); granted = true }
        compose.onNodeWithTag("field-location-permission").assertDoesNotExist()
        compose.onNodeWithTag("field-start").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("field-start").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun missingLocationPermissionAllowsExplicitVehicleOnlyCheck() {
        val starts = mutableListOf<Triple<Int, Boolean, Boolean>>()
        show(locationGranted = false, notice = "Location permission denied. Uncheck GPS to continue with vehicle signals only.",
            onStart = { duration, coordinates, gps -> starts += Triple(duration, coordinates, gps) })
        compose.onNodeWithTag("field-parked").performScrollTo().performClick()
        compose.onNodeWithTag("field-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-gps").performScrollTo().performClick()
        compose.onNodeWithTag("field-coordinates").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("field-start").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple(120, false, false)), starts) }
    }

    @Test fun roadChoiceNeedsFreshAcknowledgementAndCoordinatesNeedOptIn() {
        val starts = mutableListOf<Triple<Int, Boolean, Boolean>>()
        show(onStart = { duration, coordinates, gps -> starts += Triple(duration, coordinates, gps) })
        compose.onNodeWithTag("field-coordinates").performScrollTo().assertIsOff()
        compose.onNodeWithTag("field-parked").performScrollTo().performClick()
        compose.onNodeWithTag("field-duration-1800").performScrollTo().performClick()
        compose.onNodeWithTag("field-parked").assertIsOff()
        compose.onNodeWithTag("field-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-coordinates").performScrollTo().performClick()
        compose.onNodeWithTag("field-parked").performScrollTo().performClick()
        compose.onNodeWithTag("field-start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple(1800, true, true)), starts) }
    }

    @Test fun activeObservationKeepsStopAboveScrollAndLocksSetupAndCamera() {
        var stops = 0
        var backs = 0
        val markers = mutableListOf<String>()
        show(state = FieldTestUiState(active = true, phase = "RUNNING", elapsedSeconds = 73,
            durationSeconds = 1800, recorderRunning = true, reportAvailable = true),
            onStop = { stops++ }, onBack = { backs++ }, onMark = { markers += it })
        compose.onNodeWithTag("field-recorder").assertTextEquals("Recorder reports: recording")
        compose.onNodeWithTag("field-duration-1800").assertIsNotEnabled()
        compose.onNodeWithTag("field-gps").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-marker-parked").performScrollTo().performClick()
        compose.onNodeWithTag("field-marker-returned").performScrollTo().performClick()
        compose.onNodeWithTag("field-copy").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-camera").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-stop").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("field-back").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(1, backs); assertEquals(listOf("Parked check", "Returned"), markers) }
    }

    @Test fun sixtySignalsRetainMissingValuesAndKeepSensitiveValuesOffScreen() {
        val signals = listOf(
            FieldSignalUi("speed", "Vehicle speed signal", "车辆速度信号", "", "MISSING", null, 0),
            FieldSignalUi("vin", "Vehicle identity", "车辆身份", "TEST-VIN-SECRET", "VALID", 500, 0),
            FieldSignalUi("gps.latitude", "GPS latitude", "GPS 纬度", "-34.9285", "VALID", 500, 0),
        ) + (3 until 60).map { FieldSignalUi("signal-$it", "Signal $it", "信号 $it", "$it", "VALID", 1_000, it) }
        show(state = FieldTestUiState(phase = "FINISHED", signals = signals, sampledSignals = 60,
            validSignals = 58, changedSignals = 12, vehicleSpeedKmh = 45.6, gpsSpeedKmh = null))
        compose.onNodeWithTag("field-counts").assertTextEquals("Valid / changed / all: 58 / 12 / 60")
        compose.onNodeWithTag("field-vehicle-speed").assertTextEquals("Vehicle speed: 45.6 km/h")
        compose.onNodeWithTag("field-gps-speed").assertTextEquals("GPS speed: —")
        compose.onNodeWithTag("field-value-speed", useUnmergedTree = true).assertTextEquals("—")
        compose.onNodeWithTag("field-value-vin", useUnmergedTree = true).assertTextEquals("Hidden")
        compose.onNodeWithTag("field-signals").performScrollToNode(hasTestTag("field-signal-gps.latitude"))
        compose.onNodeWithTag("field-value-gps.latitude", useUnmergedTree = true).assertTextEquals("Hidden")
        compose.onAllNodesWithText("TEST-VIN-SECRET").assertCountEquals(0)
        compose.onAllNodesWithText("-34.9285").assertCountEquals(0)
        compose.onNodeWithTag("field-signals").performScrollToNode(hasTestTag("field-signal-signal-59"))
        compose.onNodeWithTag("field-signal-signal-59").assertIsDisplayed()
    }

    @Test fun savedCombinedReportCanBeExportedEvenWhilePreviousSdkReadIsBlocked() {
        var state by mutableStateOf(FieldTestUiState())
        var copied = 0
        var saved = 0
        compose.setContent { ZeekrCapabilityLabTheme { Screen(state = state, onCopy = { copied++ }, onSave = { saved++ }) } }
        compose.onNodeWithTag("field-copy").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(phase = "FINISHED", busy = true, savedReports = 2, reportAvailable = true) }
        compose.onNodeWithTag("field-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-copy").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("field-save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, copied); assertEquals(1, saved) }
    }

    @Test fun englishAndChineseExplainPrivacyAndDrivingWithIndependentSourceStatus() {
        var generation by mutableIntStateOf(0)
        compose.setContent { ZeekrCapabilityLabTheme { key(generation) {
            Screen(state = FieldTestUiState(gpsStatus = "NO_PERMISSION", sdkStatus = "UNAVAILABLE"))
        } } }
        listOf(AppLanguageMode.ENGLISH, AppLanguageMode.SIMPLIFIED_CHINESE).forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language); generation++ }
            val chinese = language == AppLanguageMode.SIMPLIFIED_CHINESE
            compose.onNodeWithText(if (chinese) "车辆实测检查" else "Vehicle field check").assertIsDisplayed()
            compose.onNodeWithTag("field-driving-guidance").assertTextEquals(if (chinese)
                "停车后开始；行驶时请勿操作此页面；再次停车后停止并导出。"
                else "Start while parked; drive without using this screen; stop/export after parking.")
            compose.onNodeWithTag("field-source-status").assertTextEquals(if (chinese)
                "车辆 SDK：不可用 · GPS：缺少权限" else "Vehicle SDK: Unavailable · GPS: Permission missing")
            compose.onNodeWithTag("field-privacy-note").performScrollTo().assertTextContains(
                if (chinese) "默认不导出坐标" else "Coordinates are excluded by default", substring = true)
            compose.onNodeWithTag("field-coordinates").performScrollTo().assertIsOff()
            capture(language.storedValue)
        }
    }

    @Test fun pendingSafExportSurvivesActivityRecreationWithoutStartingObservation() {
        val report = "{\"format\":\"OPENAVM_FIELD_REPORTS\",\"synthetic\":true}"
        ActivityScenario.launch<FieldTestActivity>(Intent(context, FieldTestActivity::class.java)).use { scenario ->
            scenario.onActivity { ViewModelProvider(it)[FieldTestExportModel::class.java].pendingReport = report }
            scenario.recreate()
            scenario.onActivity { assertEquals(report, ViewModelProvider(it)[FieldTestExportModel::class.java].pendingReport) }
        }
        assertFalse(FieldTestRuntime.state.value.active)
    }

    private fun show(
        state: FieldTestUiState = FieldTestUiState(), locationGranted: Boolean = true, notice: String? = null,
        onStart: (Int, Boolean, Boolean) -> Unit = { _, _, _ -> }, onStop: () -> Unit = {}, onMark: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) { compose.setContent { ZeekrCapabilityLabTheme { Screen(state, locationGranted, notice, onStart = onStart, onStop = onStop, onMark = onMark, onBack = onBack) } } }

    @Composable private fun Screen(
        state: FieldTestUiState = FieldTestUiState(), locationGranted: Boolean = true, notice: String? = null,
        onRequestLocation: () -> Unit = {}, onStart: (Int, Boolean, Boolean) -> Unit = { _, _, _ -> }, onStop: () -> Unit = {},
        onMark: (String) -> Unit = {}, onBack: () -> Unit = {}, onCopy: () -> Unit = {}, onSave: () -> Unit = {},
    ) { FieldTestScreen(state, locationGranted, notice, onRequestLocation = onRequestLocation, onStart = onStart,
        onStop = onStop, onMark = onMark, onBack = onBack, onCopy = onCopy, onSave = onSave, onOpenCamera = {}) }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val folder = File(context.getExternalFilesDir(null), "field-check-ui").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
