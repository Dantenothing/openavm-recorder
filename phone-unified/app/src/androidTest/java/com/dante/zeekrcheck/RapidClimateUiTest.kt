package com.dante.zeekrcheck

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import com.dante.zeekrcheck.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Synthetic callbacks only. These tests never instantiate a controller or send vehicle requests. */
class RapidClimateUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var language: PhoneLanguageMode
    @Before fun saveLanguage() { language = PhoneLanguage.mode; instrumentation.runOnMainSync { PhoneLanguage.selectMode(PhoneLanguageMode.SIMPLIFIED_CHINESE) } }
    @After fun restoreLanguage() { instrumentation.runOnMainSync { PhoneLanguage.selectMode(language); VehicleWidgetProvider.updateAll(instrumentation.targetContext) } }

    @Test fun pairedModesPreserveTargetAndCanReturnToComfortOrStop() {
        val chosen = mutableListOf<ClimateTarget>()
        compose.setContent { MaterialTheme { AcTemperatureActions(22, 30, true) { chosen += it } } }
        for (tag in listOf("ac_lo", "ac_hi", "ac_comfort", "ac_off")) compose.onNodeWithTag(tag).performClick()
        compose.runOnIdle {
            assertEquals(listOf(AcMode.LO, AcMode.HI, AcMode.TARGET, AcMode.TARGET), chosen.map { it.acMode })
            assertEquals(listOf(22, 22, 22, 0), chosen.map { it.value })
            assertEquals(listOf(5, 5, 30, 15), chosen.map { it.minutes })
        }
    }
    @Test fun disabledActionsCannotIssueAnyIntent() {
        compose.setContent { MaterialTheme { Column {
            AcTemperatureActions(22, 30, false) { fail("Disabled climate control") }
            SteeringHeatControl(null, ClimateQueueState(), false) { fail("Disabled wheel control") }
        } } }
        for (tag in listOf("ac_lo", "ac_hi", "ac_comfort", "ac_off", "wheel_on", "wheel_off")) compose.onNodeWithTag(tag).assertIsNotEnabled()
    }
    @Test fun queuedWheelOnDoesNotFalsifyReportedOffAndUsesOneBoundedOnOffControl() {
        val chosen = mutableListOf<ClimateTarget>()
        compose.setContent { MaterialTheme { SteeringHeatControl(ClimateSnapshot(steeringHeat = false, fetchedAt = Instant.now()),
            ClimateQueueState(pending = listOf(ClimateTarget(ClimateChannel.STEERING, 1))), true) { chosen += it } } }
        compose.onNodeWithTag("wheel_on").performClick()
        compose.onNodeWithTag("wheel_reported").assertTextEquals("回传：关闭")
        compose.onNodeWithTag("wheel_off").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ClimateTarget(ClimateChannel.STEERING, 1, 8), ClimateTarget(ClimateChannel.STEERING, 0)), chosen)
        }
    }
    @Test fun chineseActionsRenderWithUnknownWheelState() { render(PhoneLanguageMode.SIMPLIFIED_CHINESE, "zh") }
    @Test fun englishActionsRenderWithUnknownWheelState() { render(PhoneLanguageMode.ENGLISH, "en") }

    private fun render(mode: PhoneLanguageMode, name: String) {
        instrumentation.runOnMainSync { PhoneLanguage.selectMode(mode) }
        compose.setContent { AssistantTheme { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            UiText("空调与座椅")
            AcTemperatureActions(22, 30, true) { fail("Rendering must not send") }
            UiText("LO / HI 请求 5 分钟，不改变舒适目标。需要恢复目标时，点上方的温度按钮。")
            SteeringHeatControl(null, ClimateQueueState(), true) { fail("Rendering must not send") }
        } } }
        compose.onNodeWithTag("ac_hi").assertIsDisplayed()
        compose.onNodeWithTag("wheel_off").assertIsDisplayed()
        compose.onNodeWithTag("wheel_reported").assertTextEquals(if (name == "zh") "回传：未知" else "Reported: Unknown")
        compose.waitForIdle()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "qa-rapid-climate").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "climate-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
