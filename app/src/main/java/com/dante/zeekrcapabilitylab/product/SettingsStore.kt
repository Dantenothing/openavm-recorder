package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.content.SharedPreferences
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.FrontCalibration
import com.dante.zeekrcapabilitylab.service.recorder.FrontCropPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind
import com.dante.zeekrcapabilitylab.service.recorder.NormalizedCropRect

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

        const val RESERVE_10_GB = 10L * 1024L * 1024L * 1024L
        const val RESERVE_20_GB = 20L * 1024L * 1024L * 1024L
        const val RESERVE_30_GB = 30L * 1024L * 1024L * 1024L

        const val DEFAULT_RETENTION_HOURS = 8

        const val KEY_SEGMENT_SECONDS = "segment_seconds"
        const val KEY_STORAGE_LIMIT_BYTES = "storage_limit_bytes"
        const val KEY_MIN_FREE_BYTES = "min_free_bytes"
        const val KEY_AUTO_CLEANUP = "auto_cleanup"
        const val KEY_RETENTION_HOURS = "retention_hours_v3"
        const val KEY_RECORDING_MODE = "recording_mode_v3"
        const val KEY_MODE_CONFIRMED = "recording_mode_confirmed_v3"
        const val KEY_SOURCE_CAMERA_ID = "recording_source_camera_id_v3"
        const val KEY_SOURCE_FINGERPRINT = "recording_source_fingerprint_v3"
        const val KEY_SOURCE_KIND = "recording_source_kind_v3"
        const val KEY_FRONT_LANE = "front_lane_v3"
        const val KEY_FRONT_ROTATION = "front_rotation_v3"
        const val KEY_FRONT_SOURCE_WIDTH = "front_source_width_v3"
        const val KEY_FRONT_SOURCE_HEIGHT = "front_source_height_v3"
        const val KEY_FRONT_SOURCE_FINGERPRINT = "front_source_fingerprint_v3"
        const val KEY_FRONT_CALIBRATION_VERSION = "front_calibration_version_v3"
        const val KEY_FRONT_CROP_LEFT = "front_crop_left_v3"
        const val KEY_FRONT_CROP_TOP = "front_crop_top_v3"
        const val KEY_FRONT_CROP_RIGHT = "front_crop_right_v3"
        const val KEY_FRONT_CROP_BOTTOM = "front_crop_bottom_v3"
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
        val RESERVE_OPTIONS = listOf(RESERVE_10_GB, RESERVE_20_GB, RESERVE_30_GB)
        val RETENTION_HOURS_OPTIONS = listOf(1, 2, 4, 8, 12, 24)

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

        fun sanitizeRetentionHours(value: Int): Int =
            value.takeIf { it in RETENTION_HOURS_OPTIONS } ?: DEFAULT_RETENTION_HOURS
    }

    val segmentSeconds: Int
        get() = prefs.getInt(KEY_SEGMENT_SECONDS, SEGMENT_1_MIN)
            .let { if (it in SEGMENT_OPTIONS) it else SEGMENT_1_MIN }

    val storageLimitBytes: Long
        get() = prefs.getLong(KEY_STORAGE_LIMIT_BYTES, STORAGE_15_GB)
            .let { if (it in STORAGE_OPTIONS) it else STORAGE_15_GB }

    val minFreeBytes: Long
        get() = prefs.getLong(KEY_MIN_FREE_BYTES, RESERVE_20_GB)
            .let { if (it in RESERVE_OPTIONS) it else RESERVE_20_GB }

    val autoCleanupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEANUP, true)

    val retentionHours: Int
        get() = prefs.getInt(KEY_RETENTION_HOURS, DEFAULT_RETENTION_HOURS)
            .let(::sanitizeRetentionHours)

    val recordingMode: RecordingMode?
        get() {
            if (!prefs.getBoolean(KEY_MODE_CONFIRMED, false)) return null
            return prefs.getString(KEY_RECORDING_MODE, null)
                ?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
        }

    val selectedCameraId: String?
        get() = prefs.getString(KEY_SOURCE_CAMERA_ID, null)?.takeIf { it.isNotBlank() }

    val sourceFingerprint: String?
        get() = prefs.getString(KEY_SOURCE_FINGERPRINT, null)?.takeIf { it.isNotBlank() }

    val sourceKind: RecordingSourceKind?
        get() = prefs.getString(KEY_SOURCE_KIND, null)
            ?.let { runCatching { RecordingSourceKind.valueOf(it) }.getOrNull() }

    val frontCalibration: FrontCalibration?
        get() {
            val fingerprint = prefs.getString(KEY_FRONT_SOURCE_FINGERPRINT, null) ?: return null
            val width = prefs.getInt(KEY_FRONT_SOURCE_WIDTH, 0)
            val height = prefs.getInt(KEY_FRONT_SOURCE_HEIGHT, 0)
            val lane = prefs.getInt(KEY_FRONT_LANE, 0)
            val rotation = prefs.getInt(KEY_FRONT_ROTATION, -1)
            val version = prefs.getInt(KEY_FRONT_CALIBRATION_VERSION, 0)
            val crop = NormalizedCropRect(
                left = prefs.getFloat(KEY_FRONT_CROP_LEFT, Float.NaN),
                top = prefs.getFloat(KEY_FRONT_CROP_TOP, Float.NaN),
                right = prefs.getFloat(KEY_FRONT_CROP_RIGHT, Float.NaN),
                bottom = prefs.getFloat(KEY_FRONT_CROP_BOTTOM, Float.NaN),
            ).takeIf { it.validate().isEmpty() } ?: return null
            return FrontCalibration(
                sourceFingerprint = fingerprint,
                sourceWidth = width,
                sourceHeight = height,
                frontLane = lane,
                crop = crop,
                rotationDegrees = rotation,
                calibrationVersion = version,
            ).takeIf { it.validate().isEmpty() }
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
            val chinese = AppLanguage.usesChinese()
            return if (calibrated) {
                if (chinese) CALIBRATED_LABELS_ZH else CALIBRATED_LABELS_EN
            } else {
                if (chinese) DEFAULT_LABELS_ZH else DEFAULT_LABELS_EN
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
        if (value in STORAGE_OPTIONS) prefs.edit().putLong(KEY_STORAGE_LIMIT_BYTES, value).apply()
    }

    fun setMinFreeBytes(value: Long) {
        if (value in RESERVE_OPTIONS) prefs.edit().putLong(KEY_MIN_FREE_BYTES, value).apply()
    }

    fun setAutoCleanupEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEANUP, value).apply()
    }

    fun setRetentionHours(value: Int) {
        if (value in RETENTION_HOURS_OPTIONS) {
            prefs.edit().putInt(KEY_RETENTION_HOURS, value).apply()
        }
    }

    fun setRecordingMode(value: RecordingMode) {
        if (recordingMode != value) clearRecordingSource()
        prefs.edit()
            .putString(KEY_RECORDING_MODE, value.name)
            .putBoolean(KEY_MODE_CONFIRMED, true)
            .apply()
    }

    fun clearRecordingSource() {
        prefs.edit()
            .remove(KEY_SOURCE_CAMERA_ID)
            .remove(KEY_SOURCE_FINGERPRINT)
            .remove(KEY_SOURCE_KIND)
            .apply()
        clearFrontCalibration()
    }

    fun confirmSource(cameraId: String, fingerprint: String, sourceKind: RecordingSourceKind) {
        if (cameraId.isBlank() || fingerprint.isBlank()) return
        val changed = selectedCameraId != cameraId || sourceFingerprint != fingerprint || this.sourceKind != sourceKind
        prefs.edit()
            .putString(KEY_SOURCE_CAMERA_ID, cameraId)
            .putString(KEY_SOURCE_FINGERPRINT, fingerprint)
            .putString(KEY_SOURCE_KIND, sourceKind.name)
            .apply()
        if (changed) clearFrontCalibration()
    }

    fun saveFrontCalibration(
        sourceSize: ProfileSize,
        lane: Int,
        rotationDegrees: Int,
        visuallyConfirmed: Boolean,
        expectedSourceFingerprint: String,
    ): Boolean {
        if (!visuallyConfirmed) return false
        val fingerprint = sourceFingerprint ?: return false
        if (fingerprint != expectedSourceFingerprint) return false
        val calibration = FrontCropPolicy.calibration(
            fingerprint = fingerprint,
            size = sourceSize,
            lane = lane,
            rotationDegrees = rotationDegrees,
        ) ?: return false
        prefs.edit()
            .putString(KEY_FRONT_SOURCE_FINGERPRINT, calibration.sourceFingerprint)
            .putInt(KEY_FRONT_SOURCE_WIDTH, calibration.sourceWidth)
            .putInt(KEY_FRONT_SOURCE_HEIGHT, calibration.sourceHeight)
            .putInt(KEY_FRONT_LANE, calibration.frontLane)
            .putInt(KEY_FRONT_ROTATION, calibration.rotationDegrees)
            .putInt(KEY_FRONT_CALIBRATION_VERSION, calibration.calibrationVersion)
            .putFloat(KEY_FRONT_CROP_LEFT, calibration.crop.left)
            .putFloat(KEY_FRONT_CROP_TOP, calibration.crop.top)
            .putFloat(KEY_FRONT_CROP_RIGHT, calibration.crop.right)
            .putFloat(KEY_FRONT_CROP_BOTTOM, calibration.crop.bottom)
            .apply()
        return true
    }

    fun clearFrontCalibration() {
        prefs.edit()
            .remove(KEY_FRONT_SOURCE_FINGERPRINT)
            .remove(KEY_FRONT_SOURCE_WIDTH)
            .remove(KEY_FRONT_SOURCE_HEIGHT)
            .remove(KEY_FRONT_LANE)
            .remove(KEY_FRONT_ROTATION)
            .remove(KEY_FRONT_CALIBRATION_VERSION)
            .remove(KEY_FRONT_CROP_LEFT)
            .remove(KEY_FRONT_CROP_TOP)
            .remove(KEY_FRONT_CROP_RIGHT)
            .remove(KEY_FRONT_CROP_BOTTOM)
            .apply()
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
        clearFrontCalibration()
    }

    /** Display label for display slot 0..3. */
    fun laneLabel(slot: Int): String =
        laneLabels.getOrElse(slot) { "视角${slot + 1}" }
}
