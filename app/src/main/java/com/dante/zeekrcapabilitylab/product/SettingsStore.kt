package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.content.SharedPreferences
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapsePolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStoragePreference
import com.dante.zeekrcapabilitylab.service.recorder.UsbRecordingQuotaPolicy
import com.dante.zeekrcapabilitylab.sentry.GuardPolicy

/**
 * Product-level recorder and four-lane calibration settings for the V2 UI.
 * Persisted in SharedPreferences; every getter falls back to safe defaults so a
 * corrupted/partial prefs file can never produce an invalid recorder config.
 */
class SettingsStore private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS = "zeekr_product_settings_v2"

        const val SEGMENT_1_MIN = 60
        const val SEGMENT_2_MIN = 120
        const val SEGMENT_3_MIN = 180

        const val STORAGE_5_GB = 5L * 1024L * 1024L * 1024L
        const val STORAGE_10_GB = 10L * 1024L * 1024L * 1024L
        const val STORAGE_15_GB = 15L * 1024L * 1024L * 1024L
        const val STORAGE_30_GB = 30L * 1024L * 1024L * 1024L
        const val STORAGE_45_GB = 45L * 1024L * 1024L * 1024L

        const val RESERVE_10_GB = 10L * 1024L * 1024L * 1024L
        const val RESERVE_20_GB = 20L * 1024L * 1024L * 1024L
        const val RESERVE_30_GB = 30L * 1024L * 1024L * 1024L

        const val KEY_SEGMENT_SECONDS = "segment_seconds"
        const val KEY_STORAGE_LIMIT_BYTES = "storage_limit_bytes"
        const val KEY_INTERNAL_STORAGE_LIMIT_BYTES = "internal_storage_limit_bytes"
        const val KEY_RECORDING_STORAGE_PREFERENCE = "recording_storage_preference"
        const val KEY_USB_QUOTA_BYTES = "usb_quota_bytes"
        const val KEY_MIN_FREE_BYTES = "min_free_bytes"
        const val KEY_AUTO_CLEANUP = "auto_cleanup"
        const val KEY_PREVIEW_WHILE_RECORDING = "preview_while_recording_beta2"
        const val KEY_AUTO_START_RECORDING = "auto_start_recording"
        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_SENTRY_GUARD_POLICY = "sentry_guard_policy_v1"
        const val KEY_SURROUND_CAMERA_MAPPING = "source_mapping_surround"
        const val KEY_CABIN_CAMERA_MAPPING = "source_mapping_cabin"
        const val KEY_IR_CAMERA_MAPPING = "source_mapping_ir"
        const val KEY_CAMERA_MAPPING_REVISION = "source_mapping_revision"
        const val KEY_SOURCE_CONFLICT_WARNING_ACK = "source_conflict_warning_ack"
        const val KEY_TIME_LAPSE_MULTIPLIER = "time_lapse_multiplier"
        const val KEY_LANE_ORDER = "lane_order"
        const val KEY_LANE_ROTATIONS = "lane_rotations"
        const val KEY_LANE_LABELS = "lane_labels"
        const val KEY_CALIBRATED = "lane_calibrated"
        const val KEY_LENS_MODE = "four_lane_lens_mode"
        const val KEY_CORRECTION_FOV = "fisheye_correction_fov"
        const val KEY_CORRECTION_ZOOM = "fisheye_correction_zoom"
        const val KEY_CORRECTION_CENTER_X = "fisheye_correction_center_x"
        const val KEY_CORRECTION_CENTER_Y = "fisheye_correction_center_y"

        val SEGMENT_OPTIONS = listOf(SEGMENT_1_MIN, SEGMENT_2_MIN, SEGMENT_3_MIN)
        val STORAGE_OPTIONS = listOf(STORAGE_5_GB, STORAGE_10_GB, STORAGE_15_GB, STORAGE_30_GB)
        val USB_QUOTA_PRESETS = listOf(STORAGE_15_GB, STORAGE_30_GB, STORAGE_45_GB)
        val RESERVE_OPTIONS = listOf(RESERVE_10_GB, RESERVE_20_GB, RESERVE_30_GB)

        private val DEFAULT_LABELS_ZH = listOf("视角1", "视角2", "视角3", "视角4")
        private val DEFAULT_LABELS_EN = listOf("View 1", "View 2", "View 3", "View 4")
        private val CALIBRATED_LABELS_ZH = listOf("前", "后", "左", "右")
        private val CALIBRATED_LABELS_EN = listOf("Front", "Rear", "Left", "Right")

        @Volatile
        private var instance: SettingsStore? = null

        fun init(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
        }

        fun get(context: Context): SettingsStore = init(context)
    }

    val segmentSeconds: Int
        get() = prefs.getInt(KEY_SEGMENT_SECONDS, SEGMENT_1_MIN)
            .let { if (it in SEGMENT_OPTIONS) it else SEGMENT_1_MIN }

    val internalStorageLimitBytes: Long
        get() = prefs.getLong(
            KEY_INTERNAL_STORAGE_LIMIT_BYTES,
            prefs.getLong(KEY_STORAGE_LIMIT_BYTES, STORAGE_15_GB),
        )
            .let { if (it in STORAGE_OPTIONS) it else STORAGE_15_GB }

    /** Compatibility alias for existing recorder and lab call sites. */
    val storageLimitBytes: Long get() = internalStorageLimitBytes

    val recordingStoragePreference: RecordingStoragePreference
        get() = prefs.getString(KEY_RECORDING_STORAGE_PREFERENCE, null)
            ?.let { runCatching { RecordingStoragePreference.valueOf(it) }.getOrNull() }
            ?: RecordingStoragePreference.USB_PREFERRED

    val usbQuotaBytes: Long
        get() = prefs.getLong(KEY_USB_QUOTA_BYTES, UsbRecordingQuotaPolicy.DEFAULT_QUOTA_BYTES)
            .let {
                if (UsbRecordingQuotaPolicy.isValidQuota(it)) {
                    it
                } else {
                    UsbRecordingQuotaPolicy.DEFAULT_QUOTA_BYTES
                }
            }

    val minFreeBytes: Long
        get() = prefs.getLong(KEY_MIN_FREE_BYTES, RESERVE_20_GB)
            .let { if (it in RESERVE_OPTIONS) it else RESERVE_20_GB }

    val autoCleanupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEANUP, true)

    val previewWhileRecordingEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_WHILE_RECORDING, true)

    val autoStartRecordingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_RECORDING, false)

    val developerModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER_MODE, false)

    /** Policy only. A recording permit is deliberately never persisted. */
    val sentryGuardPolicy: GuardPolicy
        get() = GuardPolicy.fromStoredValue(runCatching {
            prefs.getString(KEY_SENTRY_GUARD_POLICY, null)
        }.getOrNull())

    fun setSentryGuardPolicy(value: GuardPolicy) {
        prefs.edit().putString(KEY_SENTRY_GUARD_POLICY, value.name).apply()
    }

    val cameraMappingRevision: Int
        get() = prefs.getInt(KEY_CAMERA_MAPPING_REVISION, 0).coerceAtLeast(0)

    val sourceConflictWarningAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_SOURCE_CONFLICT_WARNING_ACK, false)

    /** The speed is remembered, but every fresh app process still starts in NORMAL mode. */
    val timeLapseMultiplier: Int
        get() = prefs.getInt(KEY_TIME_LAPSE_MULTIPLIER, TimeLapsePolicy.DEFAULT_MULTIPLIER)
            .let { if (it in TimeLapsePolicy.MULTIPLIERS) it else TimeLapsePolicy.DEFAULT_MULTIPLIER }

    fun cameraMapping(role: RecordingSourceRole): String {
        val key = when (role) {
            RecordingSourceRole.SURROUND -> KEY_SURROUND_CAMERA_MAPPING
            RecordingSourceRole.CABIN -> KEY_CABIN_CAMERA_MAPPING
            RecordingSourceRole.IR -> KEY_IR_CAMERA_MAPPING
        }
        return prefs.getString(key, null)
            ?.takeIf { it.isNotBlank() }
            ?: RecordingSourcePolicy.defaultMapping(role)
    }

    /** Display order: slot i shows source lane [laneOrder[i]] (1-based). */
    val laneOrder: List<Int>
        get() {
            val raw = prefs.getString(KEY_LANE_ORDER, null)
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
            val valid = raw != null && raw.size == 4 && raw.toSet() == setOf(1, 2, 3, 4)
            return if (valid) raw else listOf(1, 2, 3, 4)
        }

    /** Per-slot rotation (0/90/180/270). Stored in the same display order as [laneOrder]. */
    val laneRotations: List<Int>
        get() {
            val raw = prefs.getString(KEY_LANE_ROTATIONS, null)
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
            val valid = raw != null && raw.size == 4 && raw.all { it % 90 == 0 }
            return if (valid) raw.map { ((it % 360) + 360) % 360 } else listOf(0, 0, 0, 0)
        }

    /** Per-slot labels. Before calibration the generic 视角1-4 names are used. */
    val laneLabels: List<String>
        get() {
            val raw = prefs.getString(KEY_LANE_LABELS, null)
                ?.split("\u001F")
            if (raw != null && raw.size == 4 && raw.all { it.isNotBlank() }) return raw
            return if (calibrated) {
                CALIBRATED_LABELS_EN.zip(CALIBRATED_LABELS_ZH).map { (en, zh) -> AppLanguage.text(en, zh) }
            } else {
                (1..4).map { AppLanguage.text("View {0}", "视角{0}", it) }
            }
        }

    val calibrated: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATED, false)

    /** Product display preference only; recorded MP4 data is never altered. */
    val lensMode: FourLaneLensMode
        get() = prefs.getString(KEY_LENS_MODE, null)
            ?.let { stored ->
                runCatching { FourLaneLensMode.valueOf(stored) }.getOrNull()
            }
            ?: FourLaneLensMode.FISHEYE

    val fisheyeCorrection: FisheyeCorrectionConfig
        get() = FisheyeCorrectionConfig(
            targetFovDegrees = prefs.getFloat(
                KEY_CORRECTION_FOV,
                FisheyeCorrectionConfig.DEFAULT_FOV_DEGREES,
            ),
            cropZoom = prefs.getFloat(
                KEY_CORRECTION_ZOOM,
                FisheyeCorrectionConfig.DEFAULT_CROP_ZOOM,
            ),
            centerX = prefs.getFloat(
                KEY_CORRECTION_CENTER_X,
                FisheyeCorrectionConfig.DEFAULT_CENTER_X,
            ),
            centerY = prefs.getFloat(
                KEY_CORRECTION_CENTER_Y,
                FisheyeCorrectionConfig.DEFAULT_CENTER_Y,
            ),
        ).sanitized()

    fun setSegmentSeconds(value: Int) {
        if (value in SEGMENT_OPTIONS) prefs.edit().putInt(KEY_SEGMENT_SECONDS, value).apply()
    }

    fun setStorageLimitBytes(value: Long) {
        setInternalStorageLimitBytes(value)
    }

    fun setInternalStorageLimitBytes(value: Long) {
        if (value in STORAGE_OPTIONS) {
            prefs.edit().putLong(KEY_INTERNAL_STORAGE_LIMIT_BYTES, value).apply()
        }
    }

    fun setRecordingStoragePreference(value: RecordingStoragePreference) {
        prefs.edit().putString(KEY_RECORDING_STORAGE_PREFERENCE, value.name).apply()
    }

    fun setUsbQuotaBytes(value: Long) {
        if (UsbRecordingQuotaPolicy.isValidQuota(value)) {
            prefs.edit().putLong(KEY_USB_QUOTA_BYTES, value).apply()
        }
    }

    fun setMinFreeBytes(value: Long) {
        if (value in RESERVE_OPTIONS) prefs.edit().putLong(KEY_MIN_FREE_BYTES, value).apply()
    }

    fun setAutoCleanupEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEANUP, value).apply()
    }

    fun setPreviewWhileRecordingEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_WHILE_RECORDING, value).apply()
    }

    fun setAutoStartRecordingEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START_RECORDING, value).apply()
    }

    fun setDeveloperModeEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, value).apply()
    }

    fun setCameraMapping(role: RecordingSourceRole, value: String) {
        val safe = value.trim()
        if (safe.isEmpty()) return
        val key = when (role) {
            RecordingSourceRole.SURROUND -> KEY_SURROUND_CAMERA_MAPPING
            RecordingSourceRole.CABIN -> KEY_CABIN_CAMERA_MAPPING
            RecordingSourceRole.IR -> KEY_IR_CAMERA_MAPPING
        }
        prefs.edit()
            .putString(key, safe)
            .putInt(KEY_CAMERA_MAPPING_REVISION, cameraMappingRevision + 1)
            .apply()
    }

    fun acknowledgeSourceConflictWarning() {
        prefs.edit().putBoolean(KEY_SOURCE_CONFLICT_WARNING_ACK, true).apply()
    }

    fun setTimeLapseMultiplier(value: Int) {
        if (value in TimeLapsePolicy.MULTIPLIERS) {
            prefs.edit().putInt(KEY_TIME_LAPSE_MULTIPLIER, value).apply()
        }
    }

    fun setLensMode(value: FourLaneLensMode) {
        prefs.edit().putString(KEY_LENS_MODE, value.name).apply()
    }

    fun setFisheyeCorrection(value: FisheyeCorrectionConfig) {
        val safe = value.sanitized()
        prefs.edit()
            .putFloat(KEY_CORRECTION_FOV, safe.targetFovDegrees)
            .putFloat(KEY_CORRECTION_ZOOM, safe.cropZoom)
            .putFloat(KEY_CORRECTION_CENTER_X, safe.centerX)
            .putFloat(KEY_CORRECTION_CENTER_Y, safe.centerY)
            .apply()
    }

    fun resetFisheyeCorrection() {
        prefs.edit()
            .remove(KEY_CORRECTION_FOV)
            .remove(KEY_CORRECTION_ZOOM)
            .remove(KEY_CORRECTION_CENTER_X)
            .remove(KEY_CORRECTION_CENTER_Y)
            .apply()
    }

    /** Saves an explicit order/labels/rotations tuple and marks calibration done. */
    fun saveCalibration(order: List<Int>, labels: List<String>, rotations: List<Int>) {
        if (order.size != 4 || order.toSet() != setOf(1, 2, 3, 4)) return
        if (labels.size != 4 || labels.any { it.isBlank() }) return
        if (rotations.size != 4 || rotations.any { it % 90 != 0 }) return
        prefs.edit()
            .putString(KEY_LANE_ORDER, order.joinToString(","))
            .putString(KEY_LANE_LABELS, labels.joinToString("\u001F"))
            .putString(KEY_LANE_ROTATIONS, rotations.joinToString(","))
            .putBoolean(KEY_CALIBRATED, true)
            .apply()
    }

    fun resetCalibration() {
        prefs.edit()
            .remove(KEY_LANE_ORDER)
            .remove(KEY_LANE_ROTATIONS)
            .remove(KEY_LANE_LABELS)
            .putBoolean(KEY_CALIBRATED, false)
            .apply()
    }

    /** Display label for display slot 0..3. */
    fun laneLabel(slot: Int): String =
        laneLabels.getOrElse(slot) { "视角${slot + 1}" }
}
