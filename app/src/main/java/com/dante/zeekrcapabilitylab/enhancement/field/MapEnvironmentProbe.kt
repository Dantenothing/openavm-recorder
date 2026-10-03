package com.dante.zeekrcapabilitylab.enhancement.field

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.*

/** Read-only package presence, not a claim that a map SDK or Google account works. No network calls. */
internal object MapEnvironmentProbe {
    fun inspect(context: Context): JsonObject = buildJsonObject {
        put("scope", "PACKAGE_PRESENCE_ONLY")
        put("androidSdk", Build.VERSION.SDK_INT)
        put("googleMapRuntime", "NOT_TESTED")
        put("networkRequests", 0)
        put("coordinatesIncluded", false)
        put("packages", buildJsonArray {
            for (id in listOf("com.google.android.gms", "com.android.vending", "com.google.android.apps.maps")) {
                add(buildJsonObject {
                    put("package", id)
                    try {
                        @Suppress("DEPRECATION")
                        val info = context.packageManager.getPackageInfo(id, 0)
                        put("status", if (info.applicationInfo?.enabled == true) "PRESENT_ENABLED" else "PRESENT_DISABLED")
                        put("version", info.versionName?.take(80))
                    } catch (_: PackageManager.NameNotFoundException) {
                        put("status", "NOT_FOUND")
                    } catch (_: RuntimeException) {
                        put("status", "UNKNOWN")
                    }
                })
            }
        })
    }
}
