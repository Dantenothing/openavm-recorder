package com.dante.zeekrcheck

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class CheckUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun freshInstallBlocksLoginUntilConfigAndOffersLocalPreparation() {
        compose.onNodeWithTag("login").assertIsNotEnabled()
        compose.onNodeWithTag("config_help").performClick()
        compose.onNodeWithText("需要从你使用的原厂 App", substring = true).assertExists()
        screenshot("01-preparation.png")
    }

    @Test fun demoShowsStaleAndUnknownTimeAndClearsCleanly() {
        compose.onNodeWithTag("check_list").performScrollToNode(hasTestTag("demo"))
        compose.onNodeWithTag("demo").performClick()
        compose.onNodeWithTag("check_list").performScrollToNode(hasTestTag("demo_banner"))
        compose.onNodeWithTag("demo_banner").assertIsDisplayed()
        screenshot("02-demo-banner.png")
        compose.onNodeWithTag("check_list").performScrollToNode(hasTestTag("cap_cabin_temperature"))
        compose.onNode(hasText("读到字段 · 旧数据") and hasAnyAncestor(hasTestTag("cap_cabin_temperature"))).assertIsDisplayed()
        screenshot("03-capability-report.png")
        compose.onNodeWithTag("check_list").performScrollToNode(hasTestTag("cap_sentry"))
        compose.onNode(hasText("读到字段 · 来源时间未确认") and hasAnyAncestor(hasTestTag("cap_sentry"))).assertIsDisplayed()
        compose.onNodeWithTag("check_list").performScrollToNode(hasTestTag("export_report"))
        compose.onNodeWithTag("export_report").assertIsEnabled()
        compose.onNodeWithTag("persistent_demo_label").assertIsDisplayed()
        screenshot("04-diagnostics.png")
        compose.onNodeWithTag("check_list").performScrollToNode(hasTestTag("clear_session"))
        compose.onNodeWithTag("clear_session").performClick()
        compose.onNodeWithTag("demo_banner").assertDoesNotExist()
        compose.onNodeWithTag("persistent_demo_label").assertDoesNotExist()
        compose.onNodeWithTag("check_list").performScrollToNode(hasTestTag("login"))
        compose.onNodeWithTag("login").assertIsNotEnabled()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
