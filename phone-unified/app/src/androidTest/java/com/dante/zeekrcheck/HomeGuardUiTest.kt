package com.dante.zeekrcheck

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only: these callbacks never invoke a live car client or alter saved user settings. */
class HomeGuardUiTest {
    @get:Rule val compose = createComposeRule()
    private val home = CarLocation(-34.9, 138.6, null, verified = true)
    @Test fun independentSwitchDoesNotChangeAwayGuard() {
        val state = mutableStateOf(AssistantState(home = home, guardEnabled = true))
        compose.setContent { AssistantTheme { HomeGuardSettingsCard(state.value,
            { state.value = state.value.copy(homeGuardEnabled = it) }, {}) } }
        compose.onNodeWithTag("home_guard_toggle").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(state.value.guardEnabled); assertTrue(state.value.homeGuardEnabled) }
    }
    @Test fun homeMustBeVerifiedAndUnknownStateCannotPretendClosed() {
        compose.setContent { AssistantTheme { HomeGuardSettingsCard(AssistantState(), {}, {}) } }
        compose.onNodeWithTag("home_guard_toggle").assertIsNotEnabled()
        compose.onNodeWithTag("home_guard_status").assertTextEquals("到家自动关闭未启用")
    }
    @Test fun manualHoldHasExplicitResumeAction() {
        var resumed = false
        compose.setContent { AssistantTheme { HomeGuardSettingsCard(AssistantState(home = home, homeGuardEnabled = true,
            homeGuard = HomeSentryGuard("test").hold(1), guardMessage = "本次手动保持开启"), {}, { resumed = true }) } }
        compose.onNodeWithTag("home_guard_status").assertTextEquals("本次手动保持开启")
        compose.onNodeWithTag("home_guard_resume").performClick()
        compose.runOnIdle { assertTrue(resumed) }
    }
}
