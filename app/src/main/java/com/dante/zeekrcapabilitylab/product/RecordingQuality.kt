package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.util.Utils

/** Encoder requests; dimensions and camera mapping never change with a preset. */
enum class RecordingQuality(val fps: Int, val numerator: Int, val denominator: Int) {
    ORIGINAL(30, 1, 1), BALANCED(20, 5, 7), ECONOMY(15, 1, 2);
    fun bitrate(original: Int): Int = (original.toLong() * numerator / denominator).toInt().coerceAtLeast(1_000_000)
    fun label() = when (this) {
        ORIGINAL -> Utils.t("Original", "原画")
        BALANCED -> Utils.t("Balanced", "均衡")
        ECONOMY -> Utils.t("Save space", "节省空间")
    }
}

class RecordingQualityStore(context: Context) {
    private val prefs = context.getSharedPreferences("recording_quality_v1", Context.MODE_PRIVATE)
    fun get(role: RecordingSourceRole): RecordingQuality = runCatching {
        RecordingQuality.valueOf(prefs.getString(role.name, RecordingQuality.ORIGINAL.name)!!)
    }.getOrDefault(RecordingQuality.ORIGINAL)
    fun set(role: RecordingSourceRole, quality: RecordingQuality) { prefs.edit().putString(role.name, quality.name).apply() }
}
