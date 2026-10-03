package com.dante.zeekrcapabilitylab.enhancement.field

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dantenothing.drivemap.RoadMapDiagnostics
import kotlinx.serialization.json.*

/** Local package presence and map outcomes. Collecting this summary makes no network calls. */
internal object MapEnvironmentProbe {
    fun inspect(context: Context): JsonObject = buildJsonObject {
        put("scope", "LOCAL_ENVIRONMENT_AND_MAP_RUNTIME_SUMMARY")
        put("androidSdk", Build.VERSION.SDK_INT)
        put("googleMapRuntime", "NOT_TESTED")
        put("probeNetworkRequests", 0)
        put("coordinatesIncluded", false)
        put("recordedMapPlayback", buildJsonObject {
            RoadMapDiagnostics.snapshot().forEach { (name, value) -> put(name, value) }
        })
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
