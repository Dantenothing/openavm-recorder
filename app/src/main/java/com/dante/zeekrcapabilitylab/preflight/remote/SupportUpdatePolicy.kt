package com.dante.zeekrcapabilitylab.preflight.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Private, same-package updates inside one expiring owner session; never replay an install. */
internal object SupportUpdatePolicy {
    const val MAX_APK = 24 * 1024 * 1024L
    const val INSTALL_WAIT_MS = 5 * 60 * 1000L
    val kinds = setOf("PREPARE_UPDATE", "INSTALL_UPDATE")
    val versions = 121L..199L
    val buildPattern = Regex("preflight-[0-9a-f]{20}")
    fun payload(p: JsonObject) {
        require(p.keys == setOf("sha256", "versionCode", "buildId", "bytes")) { "UPDATE_IDENTITY_INVALID" }
        require(LabPolicy.hash.matches(p.string("sha256")) && buildPattern.matches(p.string("buildId")) &&
            strictNumber(p,"versionCode") in versions && strictNumber(p,"bytes") in 1024L..MAX_APK) { "UPDATE_IDENTITY_INVALID" }
    }
    fun target(p: JsonObject, installedVersion: Long) {
        payload(p)
        require(p.number("versionCode") > installedVersion) { "APK_VERSION_MISMATCH" }
    }
    fun installed(pending: JsonObject, version: Long, build: String): Boolean =
        pending.string("phase") in setOf("COMMITTED", "WAITING_USER", "INSTALLED") &&
            LabPolicy.uuid.matches(pending.string("commandId")) &&
            (pending["target"] as? JsonObject)?.let { p ->
                runCatching { payload(p) }.isSuccess && p.number("versionCode") == version &&
                    p.string("buildId") == build && pending.number("fromVersion") < version
            } == true
    fun canResume(pending: JsonObject, version: Long, build: String, active: Boolean, updates: Boolean) =
        active && updates && installed(pending,version,build)
    fun idle(engine: JsonObject): Boolean = listOf("active","testActive","cameraBusy","blocked").all {
        engine[it] == JsonPrimitive(false)
    } && engine["pendingNativeOwners"] == JsonPrimitive(0)
}
