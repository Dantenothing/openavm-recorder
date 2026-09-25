package com.dante.zeekrcheck

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** UI only, on the separate QA UID; never enters credentials or sends a vehicle command. */
class CloudSetupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun optionalCloudSetupAndRecordingHomeAreReadableInBothLanguages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".freshqa"))
        withContext(Dispatchers.Main) { CloudAccess.loaded(context); CloudAccess.remove(context, true) }
        val original = PhoneLanguage.mode
        compose.runOnUiThread {
            BeginnerGuide.preferences(context).edit().putBoolean("introduced", true).commit()
            OpenAvmIntegration.initialize(context)
        }
        compose.setContent { AssistantApp() }
        try {
            for (language in listOf(PhoneLanguageMode.ENGLISH, PhoneLanguageMode.SIMPLIFIED_CHINESE)) {
                compose.runOnIdle { PhoneLanguage.selectMode(language) }
                compose.waitUntil(10_000) { !CloudAccess.state.value.checking }
                compose.onNodeWithTag("openavm_library").performScrollTo().assertIsDisplayed()
                capture("home-${language.storedValue}")
                compose.onNodeWithTag("setup_cloud").performScrollTo().performClick()
                compose.onNodeWithTag("import_config").performScrollTo().assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithTag("email").assertDoesNotExist()
                capture("setup-${language.storedValue}")
                compose.onNodeWithTag("config_help").performScrollTo().performClick()
                compose.onNodeWithTag("forget_config").performScrollTo().assertIsDisplayed()
                capture("configuration-help-${language.storedValue}")
                compose.onNodeWithText(if (language == PhoneLanguageMode.ENGLISH) "Set up later · Back to OpenAVM" else "稍后设置，返回 OpenAVM").performClick()
            }
        } finally { compose.runOnIdle { PhoneLanguage.selectMode(original) } }
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "config-qa/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
