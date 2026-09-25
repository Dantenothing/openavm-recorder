package com.dante.zeekrcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic callbacks only; no services or real vehicle commands. Requires foreground screen access. */
class SingleRefreshUiTest {
    @get:Rule val compose = createComposeRule()
    private val at = 1_800_000_000_000L

    @Test fun statusRefreshAndTemperatureStartAndStopHaveIndependentCallbacks() {
        val task = mutableStateOf<TemperatureUpdate?>(null)
        var statusTaps = 0
        var temperatureTaps = 0
        compose.setContent { AssistantTheme { Column {
            ManualRefreshButton(false, true, { statusTaps++ })
            CabinTemperatureReading("24.5°C", task.value, true, {
                temperatureTaps++
                task.value = task.value?.copy(cancelRequested = true) ?: TemperatureUpdate("demo", at, 22)
            }, at)
            TemperatureUpdatePanel(task.value, true, {}, at, showAction = false)
        } } }
        compose.onNodeWithTag("home_refresh").performClick()
        compose.runOnIdle { assertEquals(1, statusTaps); assertEquals(0, temperatureTaps); assertNull(task.value) }
        compose.onNodeWithTag("home_temperature_refresh").performClick()
        compose.onNodeWithTag("temperature_task_progress").assertIsDisplayed()
        compose.onNodeWithTag("home_refresh").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, statusTaps); assertEquals(1, temperatureTaps); assertFalse(task.value!!.cancelRequested) }
        compose.onNodeWithTag("home_temperature_refresh").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, temperatureTaps); assertTrue(task.value!!.cancelRequested) }
    }
    @Test fun statusLoadingDoesNotDisableTemperatureButton() {
        compose.setContent { AssistantTheme { Column {
            ManualRefreshButton(true, true, {})
            CabinTemperatureReading("24.5°C", null, true, {}, at)
        } } }
        compose.onNodeWithTag("home_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("home_temperature_refresh").assertIsEnabled()
    }
    @Test fun refreshExplanationReplacesCombinedToggleInBothLanguages() {
        val original = PhoneLanguage.mode
        try {
            compose.runOnUiThread { PhoneLanguage.selectMode(PhoneLanguageMode.SIMPLIFIED_CHINESE) }
            compose.setContent { AssistantTheme { RefreshExplanation() } }
            for (language in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.ENGLISH)) {
                compose.runOnUiThread { PhoneLanguage.selectMode(language) }
                compose.onNodeWithText(ui("车况与车温分开刷新")).assertIsDisplayed()
                compose.onNodeWithTag("refresh_temperature_toggle").assertDoesNotExist()
            }
        } finally { compose.runOnUiThread { PhoneLanguage.selectMode(original) } }
    }
}
