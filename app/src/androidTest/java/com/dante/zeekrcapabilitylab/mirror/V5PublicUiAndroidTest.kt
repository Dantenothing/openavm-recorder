package com.dante.zeekrcapabilitylab.mirror

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.ui.product.ProductMainScreen
import com.dante.zeekrcapabilitylab.ui.product.QuickStartGuide
import com.dante.zeekrcapabilitylab.ui.product.MirrorReturnSetupDialog
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Emulator-only visual audit of real product composables, with the public feature flags. */
class V5PublicUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val languages = AppLanguageMode.entries.filter { it != AppLanguageMode.SYSTEM }
    @Before fun prepare() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        assertTrue("V5 return feature must ship in a default build", BuildConfig.MIRROR_RETURN_ENABLED)
        assertFalse("Run with -PpublicUiCheck=true", BuildConfig.EXPERIMENTAL_TOOLS_ENABLED)
        compose.runOnIdle {
            FloatingMirrorService.close()
            SettingsStore.get(context).setDeveloperModeEnabled(false)
            MirrorReturnSettings(context).save(MirrorReturnMode.LOGO)
        }
    }
    @After fun clean() {
        compose.runOnIdle { FloatingMirrorService.close(); AppLanguage.setMode(context, AppLanguageMode.ENGLISH) }
        assertFalse("Browsing never records", CameraRecordingService.isRunning())
    }
    @Test fun productTabsAndNormalSettingsInEveryLanguage() {
        compose.setContent { ZeekrCapabilityLabTheme { ProductMainScreen() } }
        languages.forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language) }
            listOf("Record" to "录像", "Library" to "记录", "Phone / Tools" to "手机 / 工具", "Settings" to "设置").forEach { (en, zh) ->
                val label = AppLanguage.text(en, zh)
                compose.onNodeWithContentDescription(label, useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription(label, useUnmergedTree = true).assertIsDisplayed()
                compose.onAllNodesWithText("Two-camera recording · experimental").assertCountEquals(0)
                compose.onAllNodesWithText("Copy sleep check report").assertCountEquals(0)
                capture("${language.storedValue}-${en.substringBefore(' ').lowercase()}")
            }
            compose.onNodeWithText(AppLanguage.text("When you return", "回车后的行为")).assertIsDisplayed()
            compose.onNodeWithText(AppLanguage.text("About & privacy", "关于与隐私")).performScrollTo()
            compose.onNodeWithText(AppLanguage.text("Check for updates", "检查更新")).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(AppLanguage.text("Include Beta releases", "包含 Beta 测试版")).assertDoesNotExist()
            compose.onNodeWithText(AppLanguage.text("Developer tools", "开发者工具")).assertDoesNotExist()
            capture("${language.storedValue}-about")
        }
    }
    @Test fun sevenGuidePagesRemainNavigableInEveryLanguage() {
        var session by mutableIntStateOf(0)
        var closed = false
        compose.setContent { ZeekrCapabilityLabTheme { key(session) { QuickStartGuide { closed = true } } } }
        languages.forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language); session++; closed = false }
            repeat(7) { page ->
                compose.onNodeWithText("${page + 1} / 7").assertIsDisplayed()
                if (page == 3) {
                    compose.onNodeWithText(AppLanguage.text("4 · When you return", "4 · 回车后自动恢复")).assertIsDisplayed()
                    capture("${language.storedValue}-guide-return")
                }
                compose.onNodeWithText(AppLanguage.text(if (page == 6) "Done" else "Next", if (page == 6) "完成" else "下一步")).performClick()
            }
            compose.runOnIdle { assertTrue(closed) }
        }
    }
    @Test fun installationChoicesAndFooterRemainReachableInEveryLanguage() {
        var session by mutableIntStateOf(0)
        val store = MirrorReturnSettings(context)
        store.save(MirrorReturnMode.RECORD)
        compose.setContent { ZeekrCapabilityLabTheme { key(session) { MirrorReturnSetupDialog(store, true, {}, {}) } } }
        languages.forEach { language ->
            compose.runOnIdle { AppLanguage.setMode(context, language); session++ }
            compose.onNodeWithTag("return-save").assertIsDisplayed()
            compose.onNodeWithTag("return-cancel").assertIsDisplayed()
            compose.onNodeWithTag("return-auto-record").performScrollTo().assertIsOff()
            capture("${language.storedValue}-return-full")
            compose.onNodeWithTag("return-retain").performScrollTo().performClick()
            compose.onNodeWithTag("return-retain").assertIsOff()
            compose.onNodeWithTag("return-save").assertIsDisplayed()
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix") ?: "wide"
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            val directory = File(context.getExternalFilesDir(null), "v5-ui").apply { mkdirs() }
            File(directory, "$prefix-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
