package com.dante.zeekrcapabilitylab.mirror

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.ui.product.HomePreviewRetryControl
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** Synthetic control tests: the callback is a counter, never a camera command. */
class HomePreviewRetryUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    @Before fun english() { compose.runOnIdle { AppLanguage.setMode(compose.activity, AppLanguageMode.ENGLISH) } }
    @After fun restoreLanguage() { compose.runOnIdle { AppLanguage.setMode(compose.activity, AppLanguageMode.ENGLISH) } }

    @Test fun enabledRetryRequiresAnExplicitClick() {
        var requests = 0
        compose.setContent { ZeekrCapabilityLabTheme { HomePreviewRetryControl(true, { requests++ }) } }
        compose.onNodeWithTag("home-preview-retry").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("home-preview-retry").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun lateResourceReleaseEnablesTheControlWithoutDispatchingRetry() {
        val enabled = mutableStateOf(false)
        var requests = 0
        compose.setContent { ZeekrCapabilityLabTheme { HomePreviewRetryControl(enabled.value, { requests++ }) } }
        compose.onNodeWithTag("home-preview-retry").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, requests); enabled.value = true }
        compose.onNodeWithTag("home-preview-retry").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun retryLabelSupportsBothLanguages() {
        compose.setContent { ZeekrCapabilityLabTheme { HomePreviewRetryControl(true, {}) } }
        compose.onNodeWithText("Retry preview").assertIsDisplayed()
        compose.runOnIdle { AppLanguage.setMode(compose.activity, AppLanguageMode.SIMPLIFIED_CHINESE) }
        compose.onNodeWithText("重试预览").assertIsDisplayed()
    }
}
