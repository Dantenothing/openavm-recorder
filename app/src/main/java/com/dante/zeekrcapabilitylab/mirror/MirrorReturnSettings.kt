package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dante.zeekrcapabilitylab.BuildConfig

/** Explicit user preference, scoped to this installation. Saving never dispatches a camera command. */
class MirrorReturnSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mirror_return_v1", Context.MODE_PRIVATE)
    private val legacy = context.applicationContext.getSharedPreferences("mirror_presentation_v1", Context.MODE_PRIVATE)
    private val installation = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.firstInstallTime}:${info.lastUpdateTime}:${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}"
    }.getOrNull()

    val selected: MirrorReturnMode get() = runCatching {
        MirrorReturnMode.valueOf(prefs.getString("mode", null) ?: if (legacy.getBoolean("retain_on_screen_off_trial", true)) "LOGO" else "OFF")
    }.getOrDefault(MirrorReturnMode.LOGO)
    val needsReview get() = MirrorReturnPromptPolicy.needsReview(BuildConfig.MIRROR_RETURN_ENABLED,
        installation, prefs.getString("accepted_installation", null))
    val effective get() = MirrorReturnPromptPolicy.effectiveMode(BuildConfig.MIRROR_RETURN_ENABLED, selected, needsReview)
    internal val choice get() = MirrorReturnChoice(effective, prefs.getLong("revision", 0), !needsReview)

    fun save(mode: MirrorReturnMode) {
        prefs.edit().putString("mode", mode.name).putString("accepted_installation", installation)
            .putLong("revision", prefs.getLong("revision", 0) + 1).apply()
    }
}
