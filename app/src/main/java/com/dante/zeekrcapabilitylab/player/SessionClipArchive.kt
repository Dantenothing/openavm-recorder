package com.dante.zeekrcapabilitylab.player

import android.content.Context
import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Portable local archive. Data keeps its source clock; the manifest maps video zero to that clock. */
internal object SessionClipArchive {
    fun manifest(timeline: SessionPlaybackTimeline, channel: String, fromMs: Long, toMs: Long,
        extraLeadMs: Long, durationMs: Long, gps: Boolean, vehicle: Boolean): String {
        require(fromMs >= extraLeadMs && toMs > fromMs && durationMs > 0)
        return buildJsonObject {
            put("format", "OPENAVM_CLIP_ARCHIVE"); put("schemaVersion", 1)
            put("sourceSessionId", timeline.sessionId); put("channelId", channel)
            put("videoFile", "clip.mp4"); put("durationMs", durationMs)
            put("requestedFromSessionMs", fromMs); put("requestedToSessionMs", toMs)
            put("videoZeroSourceElapsedMs", timeline.originElapsedMs + fromMs - extraLeadMs)
            put("keyframeLeadMs", extraLeadMs)
            put("alignment", "SOURCE_SESSION_APPROX_MILLISECONDS")
            put("timingNote", "Video zero may precede the selection at a keyframe. Data retains source elapsed milliseconds and is limited to the requested interval. No sensor-time or frame-exact synchronization claim.")
            if (gps) put("gpsFile", "gps.jsonl")
            if (vehicle) put("vehicleFile", "vehicle.jsonl")
        }.toString()
    }

    fun write(context: Context, output: OutputStream, clip: SessionClipResult, timeline: SessionPlaybackTimeline,
        channel: String, fromMs: Long, toMs: Long, gps: Boolean, vehicle: Boolean, checkActive: () -> Unit) {
        val start = timeline.originElapsedMs + fromMs
        val end = timeline.originElapsedMs + toMs
        val gpsRows = if (gps) RecordingTelemetry.store(context).load(timeline.sessionId)?.points.orEmpty()
            .filter { it.elapsedMs in start until end } else emptyList()
        val vehicleRows = if (vehicle) RecordingVehicleTelemetry.store(context).load(timeline.sessionId)?.samples.orEmpty()
            .filter { it.elapsedMs in start until end }.map { sample -> sample.copy(values = sample.values.filterValues { it.readElapsedMs in start until end }) } else emptyList()
        check(!gps || gpsRows.isNotEmpty()) { "CLIP_NO_GPS_IN_RANGE" }
        check(!vehicle || vehicleRows.any { it.values.isNotEmpty() }) { "CLIP_NO_HUD_IN_RANGE" }
        ZipOutputStream(output).use { zip ->
            zip.setLevel(0)
            fun text(name: String, value: String) { checkActive(); zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            text("manifest.json", manifest(timeline, channel, fromMs, toMs, clip.extraLeadMs, clip.durationMs, gps, vehicle))
            zip.putNextEntry(ZipEntry("clip.mp4"))
            clip.file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { checkActive(); val count = input.read(buffer); if (count < 0) break; zip.write(buffer, 0, count) }
            }
            zip.closeEntry()
            if (gps) { zip.putNextEntry(ZipEntry("gps.jsonl")); gpsRows.forEach { checkActive(); zip.write((Json.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }; zip.closeEntry() }
            if (vehicle) { zip.putNextEntry(ZipEntry("vehicle.jsonl")); vehicleRows.forEach { checkActive(); zip.write((Json.encodeToString(it) + "\n").toByteArray(Charsets.UTF_8)) }; zip.closeEntry() }
            checkActive()
        }
    }
}
