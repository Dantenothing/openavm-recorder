package com.dante.zeekrcapabilitylab.mirror

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.product.RecordingSetupOptions
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.ui.product.MirrorReturnSetupDialog
import com.dante.zeekrcapabilitylab.ui.product.MirrorReturnSettingsEntry
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class MirrorReturnUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var originalOptions: RecordingSetupOptions? = null
    @Before fun prepare() {
        assumeTrue(BuildConfig.MIRROR_RETURN_ENABLED && android.os.Build.HARDWARE == "ranchu")
        compose.runOnIdle {
            FloatingMirrorService.close(); AppLanguage.setMode(context, AppLanguageMode.ENGLISH)
            val settings = SettingsStore.get(context)
            originalOptions = settings.recordingSetupOptions
            settings.saveRecordingSetupOptions(RecordingSetupOptions(), false)
        }
    }
    @After fun clean() { compose.runOnIdle {
        FloatingMirrorService.close(); MirrorReturnSettings(context).save(MirrorReturnMode.LOGO)
        originalOptions?.let { SettingsStore.get(context).saveRecordingSetupOptions(it, false) }
    } }

    @Test fun installationNeverPreselectsOldAutomaticRecordingAndSaveStartsNothing() {
        val store = MirrorReturnSettings(context)
        store.save(MirrorReturnMode.RECORD)
        context.getSharedPreferences("mirror_return_v1", 0).edit().putString("accepted_installation", "older-install-same-version").commit()
        assertTrue(store.needsReview); assertEquals(MirrorReturnMode.LOGO, store.effective)
        var saved = false
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, true, {}, { saved = true }) } }
        compose.onNodeWithTag("return-auto-record").assertIsOff()
        compose.onNodeWithTag("return-auto-record").performClick().assertIsOn()
        compose.onNodeWithTag("return-next").performClick()
        listOf("setup-cabin", "setup-infrared", "setup-vehicle-hud", "setup-gps-route")
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsOff() }
        compose.onNodeWithTag("setup-playback-map").performScrollTo().assertIsOn()
        compose.onNodeWithTag("return-save").performClick()
        compose.runOnIdle {
            assertTrue(saved); assertFalse(store.needsReview)
            assertEquals(MirrorReturnMode.RECORD, MirrorReturnSettings(context).selected)
            assertFalse(CameraRecordingService.isRunning()); assertFalse(StandaloneMirrorService.isRunning())
        }
    }
    @Test fun logoChoiceAndNoOverlayChoiceAreBothReachable() {
        val store = MirrorReturnSettings(context); store.save(MirrorReturnMode.LOGO)
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, false, {}, {}) } }
        compose.onNodeWithTag("return-full").performClick()
        compose.onNodeWithTag("return-auto-record").assertIsOff()
        compose.onNodeWithTag("return-logo").performClick()
        compose.onNodeWithTag("return-auto-record").assertDoesNotExist()
        compose.onNodeWithTag("return-retain").performClick().assertIsOff()
        compose.onNodeWithTag("return-next").performClick()
        compose.onNodeWithTag("return-save").performClick()
        compose.runOnIdle { assertEquals(MirrorReturnMode.OFF, store.selected) }
    }
    @Test fun cancelInSettingsLeavesTheExistingChoiceUnchanged() {
        val store = MirrorReturnSettings(context); store.save(MirrorReturnMode.RECORD)
        var cancelled = false
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, false, { cancelled = true }, {}) } }
        compose.onNodeWithTag("return-auto-record").assertIsOn()
        compose.onNodeWithTag("return-retain").performClick()
        compose.onNodeWithTag("return-cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertEquals(MirrorReturnMode.RECORD, store.selected) }
    }
    @Test fun settingsEntryOpensTheSameEditor() {
        MirrorReturnSettings(context).save(MirrorReturnMode.LOGO)
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSettingsEntry() } }
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithTag("return-setup-dialog").assertIsDisplayed()
        compose.onNodeWithTag("return-full").performClick()
        compose.onNodeWithTag("return-next").performClick()
        compose.onNodeWithTag("return-save").performClick()
        compose.onNodeWithTag("return-setup-dialog").assertDoesNotExist()
        compose.onNodeWithText("Restore full window · preview only").assertIsDisplayed()
    }
    @Test fun guideShowsAllChoicesInEnglishAndChinese() {
        val store = MirrorReturnSettings(context); store.save(MirrorReturnMode.RECORD)
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, true, {}, {}) } }
        compose.onNodeWithTag("return-auto-record").assertIsOff()
        compose.onNodeWithTag("return-next").assertIsDisplayed()
        capture("return-setup-en.png")
        compose.runOnIdle { AppLanguage.setMode(context, AppLanguageMode.SIMPLIFIED_CHINESE) }
        compose.onNodeWithText("回车后，按你的习惯恢复").assertIsDisplayed()
        compose.onNodeWithTag("return-next").assertIsDisplayed()
        capture("return-setup-zh.png")
        compose.onNodeWithTag("return-next").performClick()
        compose.onNodeWithText("记录与回看行程").assertIsDisplayed()
        compose.onNodeWithTag("return-save").assertIsDisplayed()
        capture("recording-setup-zh.png")
        compose.runOnIdle { AppLanguage.setMode(context, AppLanguageMode.ENGLISH) }
        compose.onNodeWithText("Record and replay your drive").assertIsDisplayed()
        capture("recording-setup-en.png")
    }

    @Test fun recordingChoicesStayDraftUntilFinalSaveAndStartNothing() {
        val store = MirrorReturnSettings(context); store.save(MirrorReturnMode.LOGO)
        var saved = false
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, false, {}, { saved = true }) } }
        compose.onNodeWithTag("return-next").performClick()
        listOf("setup-cabin", "setup-infrared", "setup-vehicle-hud").forEach {
            compose.onNodeWithTag(it).performScrollTo().performClick().assertIsOn()
        }
        compose.onNodeWithTag("setup-playback-map").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(RecordingSetupOptions(), SettingsStore.get(context).recordingSetupOptions) }
        compose.onNodeWithTag("return-save").performClick()
        compose.runOnIdle {
            assertTrue(saved)
            assertEquals(RecordingSetupOptions(true, true, true, false, false), SettingsStore.get(context).recordingSetupOptions)
            assertFalse(CameraRecordingService.isRunning()); assertFalse(StandaloneMirrorService.isRunning())
        }
    }

    @Test fun secondPageCancelLeavesReturnAndRecordingChoicesUnchanged() {
        val store = MirrorReturnSettings(context); store.save(MirrorReturnMode.LOGO)
        var cancelled = false
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, false, { cancelled = true }, {}) } }
        compose.onNodeWithTag("return-full").performClick()
        compose.onNodeWithTag("return-next").performClick()
        compose.onNodeWithTag("setup-cabin").performScrollTo().performClick()
        compose.onNodeWithTag("setup-playback-map").performScrollTo().performClick()
        compose.onNodeWithTag("return-cancel").performClick()
        compose.runOnIdle {
            assertTrue(cancelled); assertEquals(MirrorReturnMode.LOGO, store.selected)
            assertEquals(RecordingSetupOptions(), SettingsStore.get(context).recordingSetupOptions)
        }
    }

    @Test fun useLogoStillAsksRecordingChoicesAndPreservesSavedOptIns() {
        val options = RecordingSetupOptions(frontCabin = true, vehicleHud = true, playbackMap = true)
        compose.runOnIdle { SettingsStore.get(context).saveRecordingSetupOptions(options, false) }
        val store = MirrorReturnSettings(context); store.save(MirrorReturnMode.RECORD)
        var saved = false
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, true, {}, { saved = true }) } }
        compose.onNodeWithTag("return-cancel").performClick()
        compose.onNodeWithTag("setup-cabin").performScrollTo().assertIsOn()
        compose.onNodeWithTag("setup-vehicle-hud").performScrollTo().assertIsOn()
        compose.runOnIdle { assertFalse(saved); assertEquals(MirrorReturnMode.RECORD, store.selected) }
        compose.onNodeWithTag("return-save").performClick()
        compose.runOnIdle {
            assertTrue(saved); assertEquals(MirrorReturnMode.LOGO, store.selected)
            assertEquals(options, SettingsStore.get(context).recordingSetupOptions)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
