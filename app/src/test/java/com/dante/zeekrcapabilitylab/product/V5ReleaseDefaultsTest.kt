package com.dante.zeekrcapabilitylab.product

import android.content.SharedPreferences
import com.dante.zeekrcapabilitylab.mirror.MirrorPresentation
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStoragePreference
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class V5ReleaseDefaultsTest {
    private class Preferences(val values: MutableMap<String, Any> = mutableMapOf()) {
        val prefs: SharedPreferences
        init {
            val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putBoolean", "putInt", "putString", "putFloat", "putLong" -> {
                        values[args!![0] as String] = args[1]; proxy
                    }
                    "remove" -> { values.remove(args!![0] as String); proxy }
                    "apply" -> null
                    else -> error("Unexpected edit: ${method.name}")
                }
            } as SharedPreferences.Editor
            prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getBoolean", "getInt", "getString", "getFloat", "getLong" -> values[args!![0]] ?: args[1]
                    "contains" -> values.containsKey(args!![0])
                    "edit" -> editor
                    else -> error("Unexpected read: ${method.name}")
                }
            } as SharedPreferences
        }
    }
    private fun settings(p: Preferences): SettingsStore {
        V5ReleaseDefaults.applyRecorder(p.prefs)
        return SettingsStore::class.java.getDeclaredConstructor(SharedPreferences::class.java)
            .apply { isAccessible = true }.newInstance(p.prefs)
    }
    private fun mirror(p: Preferences, settings: SettingsStore): MirrorPresentation =
        MirrorPresentation::class.java.getDeclaredConstructor(SharedPreferences::class.java, SettingsStore::class.java)
            .apply { isAccessible = true }.newInstance(p.prefs, settings)

    @Test fun newInstallHasUsableRearAndDirectionControlsWithoutCalibration() {
        val settings = settings(Preferences())
        val mirror = mirror(Preferences(), settings)
        assertTrue(settings.mirrorPreviewEnabled)
        assertTrue(mirror.rightHandDrive)
        assertEquals(2, settings.mirrorRearLane)
        assertEquals(2, mirror.selectedLane)
        assertEquals(listOf(1, 2, 3, 4), mirror.directionLanes())
        assertEquals(listOf(3, 2, 4), mirror.lanes())
        assertEquals("auto", settings.cameraMapping(RecordingSourceRole.SURROUND))
        assertEquals("1", settings.cameraMapping(RecordingSourceRole.CABIN))
        assertEquals("0", settings.cameraMapping(RecordingSourceRole.IR))
        assertFalse(settings.developerModeEnabled)
    }
    @Test fun firstUpgradeResetsOnlyApprovedMappingAndClosesDeveloperTools() {
        val recorder = Preferences(mutableMapOf(
            SettingsStore.KEY_CABIN_CAMERA_MAPPING to "8", SettingsStore.KEY_IR_CAMERA_MAPPING to "9",
            SettingsStore.KEY_CAMERA_MAPPING_REVISION to 7, "mirror_rear_lane" to 4,
            SettingsStore.KEY_DEVELOPER_MODE to true, "mirror_view_zoom" to 2f,
            SettingsStore.KEY_USB_QUOTA_BYTES to 45L * 1024 * 1024 * 1024,
            SettingsStore.KEY_LANE_ORDER to "4,3,2,1", "shared_input_recording_trial" to false,
            "experimental_mirror_preview" to false))
        val presentation = Preferences(mutableMapOf("front_lane" to 3, "left_lane" to 1,
            "right_lane" to 2, "right_hand_drive" to false, "width" to 850, "rx" to 0.6f))
        val settings = settings(recorder)
        val mirror = mirror(presentation, settings)
        assertEquals(listOf(1, 2, 3, 4), mirror.directionLanes())
        assertTrue(mirror.rightHandDrive)
        assertEquals("1", settings.cameraMapping(RecordingSourceRole.CABIN))
        assertEquals("0", settings.cameraMapping(RecordingSourceRole.IR))
        assertEquals(8, settings.cameraMappingRevision)
        assertFalse(settings.developerModeEnabled)
        assertFalse(recorder.values.containsKey("mirror_view_zoom"))
        assertEquals(850, mirror.widthDp)
        assertEquals(0.6f, mirror.xFraction)
        assertEquals(listOf(4, 3, 2, 1), settings.laneOrder) // Historical playback calibration is separate.
        assertEquals(45L * 1024 * 1024 * 1024, settings.usbQuotaBytes)
        assertFalse(settings.sharedInputRecordingEnabled)
        assertFalse(settings.mirrorPreviewEnabled) // Existing explicit mirror opt-out is retained.
    }
    @Test fun laterUserChangesSurviveAnotherInitialization() {
        val recorder = Preferences()
        val presentation = Preferences()
        val first = settings(recorder)
        val mirror = mirror(presentation, first)
        first.setCameraMapping(RecordingSourceRole.CABIN, "7")
        first.setMirrorRearLane(4)
        first.setDeveloperModeEnabled(true)
        mirror.setDirection(0, 3); mirror.leftLane = 2; mirror.rightLane = 1; mirror.rightHandDrive = false
        val beforeRecorder = recorder.values.toMap()
        val beforeMirror = presentation.values.toMap()
        val reopened = settings(recorder)
        val restored = mirror(presentation, reopened)
        assertEquals("7", reopened.cameraMapping(RecordingSourceRole.CABIN))
        assertEquals(listOf(3, 4, 2, 1), restored.directionLanes())
        assertFalse(restored.rightHandDrive)
        assertEquals(beforeRecorder, recorder.values)
        assertEquals(beforeMirror, presentation.values)
    }
    @Test fun recorderAndMirrorMigrationsFinishIndependentlyAfterPartialInitialization() {
        val recorder = Preferences()
        val settings = settings(recorder)
        settings.setCameraMapping(RecordingSourceRole.IR, "6")
        val presentation = Preferences(mutableMapOf("left_lane" to 1))
        val restored = mirror(presentation, settings(recorder))
        assertEquals("6", settings.cameraMapping(RecordingSourceRole.IR))
        assertEquals(listOf(1, 2, 3, 4), restored.directionLanes())
    }
    @Test fun defaultsDoNotEnableAutomaticRecordingOrChangeStoragePolicy() {
        val settings = settings(Preferences())
        assertFalse(settings.autoStartRecordingEnabled)
        assertFalse(settings.recordingOverlayEnabled)
        assertEquals(RecordingStoragePreference.USB_PREFERRED, settings.recordingStoragePreference)
        assertTrue(settings.autoCleanupEnabled)
        assertTrue(settings.sharedInputRecordingEnabled)
        assertEquals(60, settings.segmentSeconds)
    }
    @Test fun explicitStorageAndCleanupChoicesAreUntouched() {
        val prefs = Preferences(mutableMapOf(SettingsStore.KEY_RECORDING_STORAGE_PREFERENCE to "INTERNAL_ONLY",
            SettingsStore.KEY_AUTO_CLEANUP to false, SettingsStore.KEY_SEGMENT_SECONDS to 180))
        val settings = settings(prefs)
        assertEquals(RecordingStoragePreference.INTERNAL_ONLY, settings.recordingStoragePreference)
        assertFalse(settings.autoCleanupEnabled)
        assertEquals(180, settings.segmentSeconds)
    }
    @Test fun sourceRevisionDoesNotOverflowDuringUpgrade() {
        val settings = settings(Preferences(mutableMapOf(SettingsStore.KEY_CAMERA_MAPPING_REVISION to Int.MAX_VALUE)))
        assertEquals(Int.MAX_VALUE, settings.cameraMappingRevision)
    }
}
