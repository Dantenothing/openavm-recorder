package com.dante.zeekrbridge.core

/** User-facing vehicle identity. Android's reported model remains diagnostic-only. */
object VehicleIdentityPolicy {
    const val DEFAULT_VEHICLE_NAME = "ZEEKR 7X"

    fun displayName(reportedName: String?): String {
        val name = reportedName?.trim().orEmpty()
        if (name.isBlank()) return DEFAULT_VEHICLE_NAME
        return if (name.lowercase() in disguisedAppLabModels) DEFAULT_VEHICLE_NAME else name
    }

    private val disguisedAppLabModels = setOf(
        "pixel 3a",
        "sdk_gphone_x86",
        "sdk_gphone_x86_64",
        "generic",
    )
}
