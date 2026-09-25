package com.dante.zeekrcapabilitylab.mirror

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
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
    @Before fun prepare() {
        assumeTrue(BuildConfig.MIRROR_RETURN_ENABLED && android.os.Build.HARDWARE == "ranchu")
        compose.runOnIdle { FloatingMirrorService.close(); AppLanguage.setMode(context, AppLanguageMode.ENGLISH) }
    }
    @After fun clean() { compose.runOnIdle { FloatingMirrorService.close(); MirrorReturnSettings(context).save(MirrorReturnMode.LOGO) } }

    @Test fun installationNeverPreselectsOldAutomaticRecordingAndSaveStartsNothing() {
        val store = MirrorReturnSettings(context)
        store.save(MirrorReturnMode.RECORD)
        context.getSharedPreferences("mirror_return_v1", 0).edit().putString("accepted_installation", "older-install-same-version").commit()
        assertTrue(store.needsReview); assertEquals(MirrorReturnMode.LOGO, store.effective)
        var saved = false
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, true, {}, { saved = true }) } }
        compose.onNodeWithTag("return-auto-record").assertIsOff()
        compose.onNodeWithTag("return-auto-record").performClick().assertIsOn()
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
        compose.onNodeWithTag("return-save").performClick()
        compose.onNodeWithTag("return-setup-dialog").assertDoesNotExist()
        compose.onNodeWithText("Restore full window · preview only").assertIsDisplayed()
    }
    @Test fun guideShowsAllChoicesInEnglishAndChinese() {
        val store = MirrorReturnSettings(context); store.save(MirrorReturnMode.RECORD)
        compose.setContent { ZeekrCapabilityLabTheme { MirrorReturnSetupDialog(store, true, {}, {}) } }
        compose.onNodeWithTag("return-auto-record").assertIsOff()
        compose.onNodeWithTag("return-save").assertIsDisplayed()
        capture("return-setup-en.png")
        compose.runOnIdle { AppLanguage.setMode(context, AppLanguageMode.SIMPLIFIED_CHINESE) }
        compose.onNodeWithText("回车后，按你的习惯恢复").assertIsDisplayed()
        compose.onNodeWithTag("return-save").assertIsDisplayed()
        capture("return-setup-zh.png")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
