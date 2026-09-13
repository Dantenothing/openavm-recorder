package com.dante.zeekrcapabilitylab.util

import android.os.SystemClock
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.dante.zeekrcapabilitylab.product.AppLanguage

object Utils {
    fun nowEpochMs(): Long = System.currentTimeMillis()

    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun formatEpoch(epochMs: Long?): String =
        epochMs?.let { synchronized(dateFormat) { dateFormat.format(Date(it)) } } ?: "-"

    fun formatDuration(ms: Long?): String {
        if (ms == null) return "-"
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun shortHash(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8)).take(16)

    fun normalize(value: String?): String =
        value?.trim()?.lowercase(Locale.US)?.replace(Regex("\\s+"), " ") ?: ""

    fun durationBucket(durationMs: Long?): String = when {
        durationMs == null || durationMs <= 0L -> "unknown"
        durationMs < 60_000L -> "0-60s"
        durationMs < 180_000L -> "1-3m"
        durationMs < 300_000L -> "3-5m"
        durationMs < 600_000L -> "5-10m"
        durationMs < 1_800_000L -> "10-30m"
        durationMs < 3_600_000L -> "30-60m"
        else -> "60m+"
    }

    fun trackFingerprint(
        packageName: String?,
        title: String?,
        artist: String?,
        durationMs: Long?,
        mediaId: String?,
    ): String {
        val parts = listOf(
            normalize(packageName),
            normalize(title),
            normalize(artist),
            durationBucket(durationMs),
            normalize(mediaId),
        )
        return parts.joinToString("|")
    }

    fun csvEscape(value: String?): String {
        if (value == null) return ""
        val v = value.replace("\"", "\"\"")
        return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) "\"$v\"" else v
    }

    fun t(en: String, zh: String, vararg args: Any?): String =
        AppLanguage.text(en, zh, *args)
}

inline fun <T> tryOrNull(block: () -> T): T? =
    try {
        block()
    } catch (t: Throwable) {
        null
    }
