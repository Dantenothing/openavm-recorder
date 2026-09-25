package com.dante.zeekrbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Notifications return to the application that owns the stores and services. */
object OpenAvmHost {
    const val DESTINATION = "openavm_destination"
    fun openIntent(context: Context, destination: String): Intent {
        require(destination in setOf("media", "connection"))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        return launch.setData(Uri.parse("openavm://phone/$destination")).putExtra(DESTINATION,destination)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    @Suppress("DEPRECATION")
    fun version(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName,0)
        val code = if(Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "${info.versionName} ($code)"
    }
}
