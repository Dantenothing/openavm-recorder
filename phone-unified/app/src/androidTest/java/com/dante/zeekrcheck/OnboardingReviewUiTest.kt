package com.dante.zeekrcheck

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Preview-only UI. No live model, vehicle transport, widget pinning or receiver start. */
class OnboardingReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var language: PhoneLanguageMode
    @Before fun save() { language = PhoneLanguage.mode }
    @After fun restore() { instrumentation.runOnMainSync { PhoneLanguage.selectMode(language); VehicleWidgetProvider.updateAll(context) } }

    @Test fun longGuideAtLargeFontKeepsNavigationVisibleAndDirectoryJumpsWithoutActions() {
        var savedStep = 6
        var generation by mutableIntStateOf(0)
        var resumed = 6
        var exited = false
        val routes = mutableListOf<String>()
        compose.setContent { AssistantTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { key(generation) {
                    BeginnerGuideScreen(false, false, { routes += it }, { exited = true }, {},
                        initialStep = resumed, onStepChanged = { savedStep = it })
                } }
            }
        } }
        for (mode in listOf(PhoneLanguageMode.ENGLISH, PhoneLanguageMode.SIMPLIFIED_CHINESE)) {
            compose.runOnIdle { PhoneLanguage.selectMode(mode); resumed = 6; generation++ }
            compose.onNodeWithTag("guide_next").assertIsDisplayed()
            compose.onNodeWithTag("guide_recorder").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("guide_next").assertIsDisplayed()
            capture("guide-large-${mode.storedValue}")
            compose.onNodeWithTag("guide_directory").performClick()
            compose.onNodeWithTag("guide_step_2").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(2, savedStep); resumed = savedStep; generation++ }
            compose.onNodeWithTag("guide_title").assertTextEquals(if (mode == PhoneLanguageMode.ENGLISH) "Set your home location" else "告诉助手哪里是家")
            compose.onNodeWithTag("guide_next").assertIsDisplayed()
            compose.onNodeWithTag("guide_later").performClick()
            compose.runOnIdle { assertTrue(exited); assertTrue(routes.isEmpty()) }
        }
    }

    @Test fun allWidgetChoicesPreviewWithoutPinningOrSendingControls() {
        compose.setContent { AssistantTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp)) { WidgetChoices() } } }
        for (mode in listOf(PhoneLanguageMode.ENGLISH, PhoneLanguageMode.SIMPLIFIED_CHINESE)) {
            compose.runOnIdle { PhoneLanguage.selectMode(mode) }
            for ((index,size) in listOf("2×2", "4×1", "4×2", "4×3").withIndex()) {
                compose.onNodeWithTag("widget_size_$index").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("widget_preview").assertIsDisplayed()
                compose.onNodeWithTag("widget_add").assertTextContains(size, substring = true)
                capture("widget-choice-$index-${mode.storedValue}")
            }
        }
    }

    private fun capture(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.getExternalFilesDir(null), "qa-local16/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
}
