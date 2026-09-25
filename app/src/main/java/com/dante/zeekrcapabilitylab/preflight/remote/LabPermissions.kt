package com.dante.zeekrcapabilitylab.preflight.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dante.zeekrcapabilitylab.preflight.obj

/** A missing settings screen is different from a denied permission or a tested feature. */
internal object LabPermissions {
    fun read(context:Context)=obj("observedEpochMs" to System.currentTimeMillis(),"sdk" to Build.VERSION.SDK_INT,
        "installPermission" to query {context.packageManager.canRequestPackageInstalls()},
        "overlayPermission" to query {Settings.canDrawOverlays(context)},
        "installSettingsResolution" to query {Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:"+context.packageName)).resolveActivity(context.packageManager)!=null},
        "overlaySettingsResolution" to query {Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:"+context.packageName)).resolveActivity(context.packageManager)!=null},
        "settingsResolutionMeaning" to "QUERY_ONLY_PACKAGE_VISIBILITY_MAY_LIMIT_RESULTS",
        "installFunctionalTest" to "NOT_RUN","overlayFunctionalTest" to "NOT_RUN",
        "requiredForRemoteExperiments" to emptyList<String>())
    private fun query(block:()->Boolean)=runCatching(block).fold(
        {obj("value" to it,"query" to "RETURNED")},{obj("value" to null,"query" to "FAILED","errorType" to it.javaClass.simpleName)})
}
