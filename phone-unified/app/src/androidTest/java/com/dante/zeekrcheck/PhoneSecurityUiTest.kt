package com.dante.zeekrcheck

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.server.PhoneIdentityCertificate
import com.dante.zeekrbridge.service.BridgeService
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhoneSecurityUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun completeFingerprintAndExplicitPairingWindowInBothLanguages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".freshqa"))
        OpenAvmIntegration.initialize(context)
        val savedLanguage = PhoneLanguage.mode
        var generation by mutableIntStateOf(0)
        BridgeServer.start(context)
        assertTrue(BridgeServer.state.value.running)
        val display = PhoneIdentityCertificate.displayFingerprint(BridgeServer.state.value.identityFingerprint)
        assertEquals("", PairingManager.currentCode())
        try {
            compose.setContent { AssistantTheme { key(generation) { OpenAvmDestination("connection", false, {}, {}, {}) } } }
            for (language in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.ENGLISH)) {
                compose.runOnIdle { PhoneLanguage.selectMode(language); generation++ }
                compose.onNodeWithTag("recorder_pair_details").performScrollTo().performClick()
                compose.onNodeWithTag("recorder_security_fingerprint").performScrollTo().assertTextEquals(display).assertIsDisplayed()
                compose.onNodeWithTag("recorder_pair_code").performScrollTo().performClick()
                compose.waitUntil(5000) { PairingManager.currentCode().isNotBlank() }
                compose.onNodeWithTag("recorder_pair_close").performScrollTo().performClick()
                compose.waitUntil(5000) { PairingManager.currentCode().isBlank() }
                compose.onNodeWithTag("recorder_pair_close").assertDoesNotExist()
                compose.onNodeWithTag("recorder_security_fingerprint").performScrollTo().assertIsDisplayed()
                val shot = instrumentation.uiAutomation.takeScreenshot()!!
                val path = File(context.getExternalFilesDir(null), "security-qa/pairing-${language.storedValue}.png")
                path.parentFile!!.mkdirs(); path.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { PhoneLanguage.selectMode(savedLanguage) }
            BridgeService.stop(context); BridgeServer.stop(context)
        }
    }
}
