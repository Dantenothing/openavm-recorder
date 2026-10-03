package com.dante.zeekrcapabilitylab.telemetry

import android.content.Context
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files

/** Explicit export only, called on the journal worker. Coordinates and raw readings never leave this helper. */
object RecordingTelemetryDiagnostics {
    private const val MAX_SESSIONS = 3
    private const val MAX_DIRECTORY_ENTRIES = 256

    fun inspect(context: Context): JsonObject = inspect(
        File(context.noBackupFilesDir, "recording-telemetry"),
        File(context.noBackupFilesDir, "recording-vehicle-telemetry"),
    ) { CameraRecordingService.isRunning() || RecorderCommandPolicy.isActive(CameraRecordingService.state.value.status) }

    /** Pure file entry point for bounds/redaction checks. Recheck activity before every journal load. */
    internal fun inspect(gpsDirectory: File, vehicleDirectory: File, isRecording: () -> Boolean = { false }): JsonObject {
        fun active() = runCatching(isRecording).getOrDefault(true)
        fun skipped() = envelope("SKIPPED_RECORDING_ACTIVE")
        if (active()) return skipped()
        val sessions = linkedMapOf<String, Long>()
        var scanLimited = false
        var scanErrors = 0
        var skippedOversize = 0
        for (directory in listOf(gpsDirectory, vehicleDirectory)) {
            if (active()) return skipped()
            if (!directory.isDirectory) continue
            runCatching {
                Files.newDirectoryStream(directory.toPath()).use { entries ->
                    var examined = 0
                    for (path in entries) {
                        if (++examined > MAX_DIRECTORY_ENTRIES) { scanLimited = true; break }
                        val file = path.toFile()
                        if (file.extension != "jsonl" || !VehicleTelemetryStore.validId(file.nameWithoutExtension) ||
                            !file.isFile || file.canonicalFile.parentFile != directory.canonicalFile) continue
                        if (file.length() > VehicleTelemetryStore.MAX_FILE_BYTES) { skippedOversize++; continue }
                        sessions[file.nameWithoutExtension] = maxOf(sessions[file.nameWithoutExtension] ?: 0, file.lastModified())
                    }
                }
            }.onFailure { scanErrors++ }
        }
        val latest = sessions.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(MAX_SESSIONS)
        val gpsStore = TelemetryStore(gpsDirectory)
        val vehicleStore = VehicleTelemetryStore(vehicleDirectory)
        val summaries = ArrayList<JsonObject>()
        for ((sessionId, _) in latest) {
            if (active()) return skipped()
            val gps = gpsStore.load(sessionId)
            if (active()) return skipped()
            val vehicle = vehicleStore.load(sessionId)
            if (active()) return skipped()
            summaries += buildJsonObject {
                put("sessionId", sessionId)
                put("gps", buildJsonObject {
                    put("status", if (gps == null) "NO_READABLE_HISTORY" else "AVAILABLE")
                    if (gps != null) {
                        put("pointCount", gps.points.size)
                        put("durationMs", duration(gps.points.firstOrNull()?.elapsedMs, gps.points.lastOrNull()?.elapsedMs))
                        put("providers", buildJsonArray { gps.points.map { it.provider }.distinct().sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        put("locationSpeedCount", gps.points.count { it.speedMps != null })
                        put("unknownSpeedCount", gps.points.count { it.speedMps == null })
                        put("breakBoundaryCount", gps.points.count { it.breakBefore })
                    }
                })
                put("vehicle", buildJsonObject {
                    put("status", if (vehicle == null) "NO_READABLE_HISTORY" else "AVAILABLE")
                    if (vehicle != null) {
                        put("sampleCount", vehicle.samples.size)
                        put("durationMs", duration(vehicle.samples.firstOrNull()?.elapsedMs, vehicle.samples.lastOrNull()?.elapsedMs))
                        put("breakBoundaryCount", vehicle.samples.count { it.breakBefore })
                        put("emptyBoundaryCount", vehicle.samples.count { it.values.isEmpty() })
                        put("validCounts", buildJsonObject { for (key in VehicleTelemetryPolicy.keys) {
                            put(key, vehicle.samples.count { sample -> sample.values[key]?.let { it.status == "OK" && VehicleTelemetryPolicy.validValue(key, it) } == true })
                        } })
                        put("unknownCounts", buildJsonObject { for (key in VehicleTelemetryPolicy.keys) {
                            put(key, vehicle.samples.count { sample -> sample.values[key]?.let { it.status == "OK" && VehicleTelemetryPolicy.validValue(key, it) } != true })
                        } })
                        put("pedalSources", buildJsonObject {
                            put("scale", "UNVERIFIED_RAW_NO_PERCENTAGE")
                            for ((key, id) in listOf("brake_depth" to "0x00101300", "throttle_depth" to "0x00101400")) {
                                put(key, buildJsonObject {
                                    put("sourceId", id)
                                    put("observedCount", vehicle.samples.count { key in it.values })
                                    put("rawAvailableCount", vehicle.samples.count { sample -> sample.values[key]?.let {
                                        it.status == "PROVISIONAL_RAW" && VehicleTelemetryPolicy.validValue(key, it)
                                    } == true })
                                    put("unavailableCount", vehicle.samples.count { sample -> sample.values[key]?.let {
                                        it.status == "PROVISIONAL_RAW" && VehicleTelemetryPolicy.validValue(key, it)
                                    } != true })
                                })
                            }
                        })
                    }
                })
            }
        }
        return buildJsonObject {
            envelope(if (summaries.isEmpty()) "NO_HISTORY" else "OK").forEach { (key, value) -> put(key, value) }
            put("directoryScanLimited", scanLimited)
            put("directoryReadErrors", scanErrors)
            put("oversizeFilesSkipped", skippedOversize)
            put("sessions", buildJsonArray { summaries.forEach { add(it) } })
        }
    }

    private fun duration(first: Long?, last: Long?): Long = if (first == null || last == null || last < first) 0 else last - first
    private fun envelope(status: String) = buildJsonObject {
        put("schemaVersion", 1)
        put("format", "OPENAVM_RECORDING_TELEMETRY_SUMMARY")
        put("status", status)
        put("coordinatesIncluded", false)
        put("rawReadingsIncluded", false)
        put("measurementTimeVerified", false)
    }
}
