package com.dante.zeekrcapabilitylab.product

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** The real preference API, including transaction boundaries, without a device or cameras. */
class RecordingSetupSettingsTest {
    private class Preferences(initial: Map<String, Boolean> = emptyMap()) {
        val values = initial.toMutableMap()
        val snapshots = mutableListOf<Map<String, Boolean>>()
        var edits = 0

        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> {
                    edits++
                    val staged = mutableMapOf<String, Boolean>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { proxy, call, parameters ->
                        when (call.name) {
                            "putBoolean" -> {
                                staged[parameters!![0] as String] = parameters[1] as Boolean
                                proxy
                            }
                            "apply", "commit" -> {
                                values.putAll(staged)
                                snapshots += values.toMap()
                                if (call.name == "commit") true else null
                            }
                            else -> error("Unexpected editor call: ${call.name}")
                        }
                    }
                }
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        fun store(): SettingsStore = SettingsStore::class.java
            .getDeclaredConstructor(SharedPreferences::class.java)
            .apply { isAccessible = true }.newInstance(prefs)
    }

    private val allEnabled = RecordingSetupOptions(frontCabin = true, rearCabin = true,
        vehicleHud = true, gpsRoute = true, playbackMap = true)

    @Test fun freshInstallOpensPlaybackMapWithoutOptingIntoCapture() {
        val prefs = Preferences()
        val settings = prefs.store()
        assertEquals(RecordingSetupOptions(), settings.recordingSetupOptions)
        assertFalse(settings.recordFrontCabin)
        assertFalse(settings.recordRearCabin)
        assertFalse(settings.recordingVehicleTelemetryEnabled)
        assertFalse(settings.recordingTelemetryEnabled)
        assertTrue(settings.playbackMapDefaultOpen)
        assertEquals(0, prefs.edits)
        assertTrue(prefs.values.isEmpty())
    }

    @Test fun upgradeReadsExistingCaptureChoicesWithoutRewritingThem() {
        val previous = mapOf(SettingsStore.KEY_RECORD_FRONT_CABIN to true,
            SettingsStore.KEY_RECORD_REAR_CABIN to false,
            SettingsStore.KEY_RECORDING_VEHICLE_TELEMETRY to true,
            SettingsStore.KEY_RECORDING_TELEMETRY to true,
            SettingsStore.KEY_PLAYBACK_MAP_DEFAULT_OPEN to false)
        val prefs = Preferences(previous)
        assertEquals(RecordingSetupOptions(frontCabin = true, vehicleHud = true, gpsRoute = true, playbackMap = false),
            prefs.store().recordingSetupOptions)
        assertEquals(previous, prefs.values)
        assertEquals(0, prefs.edits)
    }

    @Test fun oneSaveUpdatesAllRealRecorderConsumersInOneTransaction() {
        val prefs = Preferences()
        assertTrue(prefs.store().saveRecordingSetupOptions(allEnabled, recordingBusy = false))
        val reopened = prefs.store()
        assertEquals(allEnabled, reopened.recordingSetupOptions)
        assertTrue(reopened.recordFrontCabin)
        assertTrue(reopened.recordRearCabin)
        assertTrue(reopened.recordingVehicleTelemetryEnabled)
        assertTrue(reopened.recordingTelemetryEnabled)
        assertTrue(reopened.playbackMapDefaultOpen)
        assertEquals(1, prefs.edits)
        assertEquals(listOf(prefs.values.toMap()), prefs.snapshots)
    }

    @Test fun busyRejectsEveryCaptureChangeWithoutPartiallySavingMapChoice() {
        val captureChanges = listOf(
            RecordingSetupOptions(frontCabin = true, playbackMap = true),
            RecordingSetupOptions(rearCabin = true, playbackMap = true),
            RecordingSetupOptions(vehicleHud = true, playbackMap = true),
            RecordingSetupOptions(gpsRoute = true, playbackMap = true),
            allEnabled,
        )
        for (change in captureChanges) {
            val prefs = Preferences(mapOf("unrelated" to true))
            assertFalse(change.toString(), prefs.store().saveRecordingSetupOptions(change, recordingBusy = true))
            assertEquals(mapOf("unrelated" to true), prefs.values)
            assertEquals(RecordingSetupOptions(), prefs.store().recordingSetupOptions)
            assertEquals(0, prefs.edits)
            assertTrue(prefs.snapshots.isEmpty())
        }
    }

    @Test fun busyAlsoRejectsDisablingAnyCaptureChoice() {
        val changes = listOf(allEnabled.copy(frontCabin = false), allEnabled.copy(rearCabin = false),
            allEnabled.copy(vehicleHud = false), allEnabled.copy(gpsRoute = false))
        for (change in changes) {
            val prefs = Preferences()
            val settings = prefs.store()
            assertTrue(settings.saveRecordingSetupOptions(allEnabled, recordingBusy = false))
            val before = prefs.values.toMap()
            val edits = prefs.edits
            assertFalse(change.toString(), settings.saveRecordingSetupOptions(change, recordingBusy = true))
            assertEquals(before, prefs.values)
            assertEquals(edits, prefs.edits)
            assertEquals(allEnabled, prefs.store().recordingSetupOptions)
        }
    }

    @Test fun busyAllowsMapOnlyChangeAndKeepsActiveCaptureChoices() {
        val prefs = Preferences()
        val settings = prefs.store()
        assertTrue(settings.saveRecordingSetupOptions(allEnabled, recordingBusy = false))
        val mapClosed = allEnabled.copy(playbackMap = false)
        assertTrue(settings.saveRecordingSetupOptions(mapClosed, recordingBusy = true))
        assertEquals(mapClosed, prefs.store().recordingSetupOptions)
        assertTrue(settings.saveRecordingSetupOptions(allEnabled, recordingBusy = true))
        assertEquals(allEnabled, prefs.store().recordingSetupOptions)
        assertEquals(3, prefs.snapshots.size)
    }

    @Test fun settingsSavePreservesOtherPreferencesIncludingLegacyAutoStart() {
        val previous = mapOf(SettingsStore.KEY_AUTO_START_RECORDING to false,
            SettingsStore.KEY_DEVELOPER_MODE to true,
            "shared_input_recording_trial" to false,
            "unrelated" to true)
        val prefs = Preferences(previous)
        val settings = prefs.store()
        assertTrue(settings.saveRecordingSetupOptions(allEnabled, recordingBusy = false))
        previous.forEach { (key, value) -> assertEquals(key, value, prefs.values[key]) }
        assertFalse(settings.autoStartRecordingEnabled)
        assertTrue(settings.developerModeEnabled)
        assertFalse(settings.sharedInputRecordingEnabled)
    }

    @Test fun playbackPreferenceCanChangeWithoutEnablingAnyCapture() {
        val prefs = Preferences()
        val settings = prefs.store()
        settings.setPlaybackMapDefaultOpen(true)
        assertEquals(RecordingSetupOptions(playbackMap = true), prefs.store().recordingSetupOptions)
        settings.setPlaybackMapDefaultOpen(false)
        assertEquals(RecordingSetupOptions(playbackMap = false), prefs.store().recordingSetupOptions)
        assertTrue(prefs.values.keys.none { it in setOf(SettingsStore.KEY_RECORD_FRONT_CABIN,
            SettingsStore.KEY_RECORD_REAR_CABIN, SettingsStore.KEY_RECORDING_VEHICLE_TELEMETRY,
            SettingsStore.KEY_RECORDING_TELEMETRY) })
    }
}
