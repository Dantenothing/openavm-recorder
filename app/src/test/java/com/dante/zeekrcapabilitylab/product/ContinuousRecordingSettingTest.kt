package com.dante.zeekrcapabilitylab.product

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real settings getter/setter without Android resources or a device. */
class ContinuousRecordingSettingTest {
    private val key = "shared_input_recording_trial" // Persisted by Beta26–29.
    private fun store(values: MutableMap<String, Boolean>): SettingsStore {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { values[args!![0] as String] = args[1] as Boolean; proxy }
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
        return SettingsStore::class.java.getDeclaredConstructor(SharedPreferences::class.java)
            .apply { isAccessible = true }.newInstance(prefs)
    }

    @Test fun freshInstallUsesContinuousRecordingWithoutDeveloperMode() {
        val settings = store(mutableMapOf())
        assertFalse(settings.developerModeEnabled)
        assertTrue(settings.sharedInputRecordingEnabled)
    }
    @Test fun existingOptInSurvivesClosingDeveloperTools() {
        val values = mutableMapOf(key to true, SettingsStore.KEY_DEVELOPER_MODE to true)
        val settings = store(values)
        assertTrue(settings.sharedInputRecordingEnabled)
        values[SettingsStore.KEY_DEVELOPER_MODE] = false
        assertTrue(settings.sharedInputRecordingEnabled)
        assertTrue(store(values).sharedInputRecordingEnabled)
    }
    @Test fun explicitCompatibilityChoiceSurvivesUpgrade() {
        assertFalse(store(mutableMapOf(key to false)).sharedInputRecordingEnabled)
        assertFalse(store(mutableMapOf(key to false, SettingsStore.KEY_DEVELOPER_MODE to true))
            .sharedInputRecordingEnabled)
    }
    @Test fun compatibilitySwitchPersistsWithoutChangingOtherPreferences() {
        val values = mutableMapOf(SettingsStore.KEY_AUTO_START_RECORDING to false)
        val settings = store(values)
        settings.setSharedInputRecordingEnabled(false)
        assertFalse(store(values).sharedInputRecordingEnabled)
        settings.setSharedInputRecordingEnabled(true)
        assertTrue(store(values).sharedInputRecordingEnabled)
        assertEquals(mapOf(SettingsStore.KEY_AUTO_START_RECORDING to false, key to true), values)
    }
}
