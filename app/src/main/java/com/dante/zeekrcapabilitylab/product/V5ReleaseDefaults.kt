package com.dante.zeekrcapabilitylab.product

import android.content.SharedPreferences

/** Owner-approved V5 preset: apply once, then preserve any later user calibration. */
object V5ReleaseDefaults {
    const val FRONT_LANE = 1
    const val REAR_LANE = 2
    const val LEFT_LANE = 3
    const val RIGHT_LANE = 4
    const val MARKER = "v5_release_defaults_applied"

    fun applyRecorder(prefs: SharedPreferences) {
        if (prefs.getBoolean(MARKER, false)) return
        prefs.edit()
            .putString(SettingsStore.KEY_CABIN_CAMERA_MAPPING, RecordingSourcePolicy.DEFAULT_CABIN_CAMERA_ID)
            .putString(SettingsStore.KEY_IR_CAMERA_MAPPING, RecordingSourcePolicy.DEFAULT_IR_CAMERA_ID)
            .putInt(SettingsStore.KEY_CAMERA_MAPPING_REVISION,
                (prefs.getInt(SettingsStore.KEY_CAMERA_MAPPING_REVISION, 0).coerceAtLeast(0).toLong() + 1)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .putInt("mirror_rear_lane", REAR_LANE)
            .remove("mirror_view_zoom").remove("mirror_view_center_x").remove("mirror_view_center_y")
            .putBoolean(SettingsStore.KEY_DEVELOPER_MODE, false)
            .putBoolean(MARKER, true)
            .apply()
    }

    fun applyMirror(prefs: SharedPreferences) {
        if (prefs.getBoolean(MARKER, false)) return
        prefs.edit()
            .putBoolean("right_hand_drive", true)
            .putInt("front_lane", FRONT_LANE)
            .putInt("left_lane", LEFT_LANE)
            .putInt("right_lane", RIGHT_LANE)
            .putInt("lane", REAR_LANE)
            .putBoolean(MARKER, true)
            .apply()
    }
}
