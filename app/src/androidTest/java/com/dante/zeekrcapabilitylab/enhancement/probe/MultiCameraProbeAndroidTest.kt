package com.dante.zeekrcapabilitylab.enhancement.probe

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.mirror.MirrorSleepTestHostActivity
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Synthetic UI and read-only runtime checks; never opens the emulator or vehicle cameras. */
class MultiCameraProbeAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val sources = listOf(ProbeSource(0, "2", ProfileSize(1280, 5140)),
        ProbeSource(1, "1", ProfileSize(640, 480)), ProbeSource(2, "0", ProfileSize(640, 480)))
    private fun catalog(sets: Set<Set<String>> = emptySet()) = ProbeCatalog(sources, sets, ProbeDeclaration.DECLARED, emptyList())
    @Before fun before() {
        assumeTrue(Build.HARDWARE == "ranchu" && BuildConfig.CAMERA_CONCURRENCY_PROBE_ENABLED)
        AppLanguage.setMode(context, AppLanguageMode.ENGLISH)
    }

    @Test fun unknownCombinationNeedsBothChecksAndOneTapOnlyArmsOneRun() {
        var requests = 0
        var selection = emptyList<Int>()
        compose.setContent { ZeekrCapabilityLabTheme {
            MultiCameraProbeScreen(ProbeUiState(catalog = catalog()), onRead = {},
                onStart = { slots, parked, unlisted -> assertTrue(parked && unlisted); requests++; selection = slots },
                onStop = {}, onBack = {}, onCopy = {}, onSave = {}, preview = { Synthetic(it) })
        } }
        compose.onNodeWithTag("probe-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("probe-parked").performScrollTo().performClick()
        compose.onNodeWithTag("probe-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("probe-unlisted").performScrollTo().performClick()
        capture("en-pair-ready")
        compose.onNodeWithTag("probe-start").performScrollTo().performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(listOf(0, 1), selection) }
        compose.onNodeWithTag("probe-parked").assertIsOff()
        compose.onNodeWithTag("probe-unlisted").assertIsOff()
    }

    @Test fun selectionChangesResetConsentAndExposeThreePanesInBothLanguages() {
        var generation by mutableIntStateOf(0)
        compose.setContent { ZeekrCapabilityLabTheme { key(generation) {
            MultiCameraProbeScreen(ProbeUiState(catalog = catalog()), onRead = {}, onStart = { _, _, _ -> fail("No click expected") },
                onStop = {}, onBack = {}, onCopy = {}, onSave = {}, preview = { Synthetic(it) })
        } } }
        listOf(AppLanguageMode.ENGLISH, AppLanguageMode.SIMPLIFIED_CHINESE).forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language); generation++ }
            compose.onNodeWithTag("probe-parked").performScrollTo().performClick()
            compose.onNodeWithTag("probe-unlisted").performScrollTo().performClick()
            compose.onNodeWithTag("probe-choice-2").performScrollTo().performClick()
            compose.onNodeWithTag("probe-parked").assertIsOff()
            compose.onNodeWithTag("probe-unlisted").assertIsOff()
            compose.onNodeWithTag("probe-start").performScrollTo().assertIsNotEnabled()
            compose.onAllNodesWithText("Synthetic camera", substring = true).assertCountEquals(3)
            capture(language.storedValue + "-triple")
        }
    }

    @Test fun declaredPairDoesNotEnableTripleWithoutNewConsent() {
        compose.setContent { ZeekrCapabilityLabTheme {
            MultiCameraProbeScreen(ProbeUiState(catalog = catalog(setOf(setOf("2", "1")))), onRead = {},
                onStart = { _, _, _ -> }, onStop = {}, onBack = {}, onCopy = {}, onSave = {}, preview = { Synthetic(it) })
        } }
        compose.onNodeWithTag("probe-unlisted").assertDoesNotExist()
        compose.onNodeWithTag("probe-parked").performScrollTo().performClick()
        compose.onNodeWithTag("probe-start").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("probe-choice-2").performScrollTo().performClick()
        compose.onNodeWithTag("probe-unlisted").performScrollTo().assertIsOff()
        compose.onNodeWithTag("probe-start").performScrollTo().assertIsNotEnabled()
    }

    @Test fun missingSourceNeverOffersStartEvenWithOptIn() {
        compose.setContent { ZeekrCapabilityLabTheme {
            MultiCameraProbeScreen(ProbeUiState(catalog = catalog().copy(sources = sources.take(1))), onRead = {},
                onStart = { _, _, _ -> fail("Missing source must block") }, onStop = {}, onBack = {},
                onCopy = {}, onSave = {}, preview = { Synthetic(it) })
        } }
        compose.onNodeWithTag("probe-parked").performScrollTo().performClick()
        compose.onNodeWithTag("probe-unlisted").performScrollTo().performClick()
        compose.onNodeWithTag("probe-start").performScrollTo().assertIsNotEnabled()
    }

    @Test fun releaseUnconfirmedKeepsStartBlockedAndReportAccessible() {
        var stopped = 0; var copied = 0
        compose.setContent { ZeekrCapabilityLabTheme {
            MultiCameraProbeScreen(ProbeUiState(catalog = catalog(), active = true, phase = "RELEASE_UNCONFIRMED",
                reason = "STREAM_STALLED", report = "{}", reportSaved = true), onRead = {}, onStart = { _, _, _ -> },
                onStop = { stopped++ }, onBack = {}, onCopy = { copied++ }, onSave = {}, preview = { Synthetic(it) })
        } }
        compose.onNodeWithTag("probe-choice-2").assertIsNotEnabled()
        compose.onNodeWithTag("probe-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("probe-stop").performScrollTo().performClick()
        compose.onNodeWithTag("probe-copy").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(1, copied) }
        capture("en-release-unconfirmed")
    }

    @Test fun cameraInfoReadDoesNotOpenOrReserveDevices() {
        assertTrue(CanaryCameraInterlock.normalIdle())
        val oldOwner = CameraWorkCoordinator.state.value.owner
        MultiCameraProbeRuntime.inspect(context)
        compose.waitUntil(20_000) {
            val value = MultiCameraProbeRuntime.state.value
            !value.reading && (value.catalog != null || value.reason != null)
        }
        assertFalse(MultiCameraProbeRuntime.state.value.active)
        assertTrue(CanaryCameraInterlock.normalIdle())
        assertEquals(oldOwner, CameraWorkCoordinator.state.value.owner)
    }

    @Test fun pendingExportSurvivesActivityRecreationWithoutOpeningCameras() {
        val pending = "{\"format\":\"OPENAVM_MULTI_PREVIEW_PROBE\",\"recordingTested\":false}"
        val field = MultiCameraProbeActivity::class.java.getDeclaredField("exportReport").apply { isAccessible = true }
        androidx.test.core.app.ActivityScenario.launch<MultiCameraProbeActivity>(
            android.content.Intent(context, MultiCameraProbeActivity::class.java)).use { scenario ->
            scenario.onActivity { field.set(it, pending) }
            scenario.recreate()
            scenario.onActivity { assertEquals(pending, field.get(it)) }
        }
        assertTrue(CanaryCameraInterlock.normalIdle())
        assertFalse(MultiCameraProbeRuntime.state.value.active)
    }

    @Composable private fun Synthetic(source: ProbeSource) {
        Box(Modifier.fillMaxSize().background(if (source.slot == 0) Color(0xff233946) else Color(0xff334740)), Alignment.Center) {
            Text("Synthetic camera ${source.id}", color = Color.White)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val folder = File(context.getExternalFilesDir(null), "multi-preview-ui").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
