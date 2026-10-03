package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannels
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelHealth
import kotlinx.serialization.json.*
import com.dante.zeekrcapabilitylab.telemetry.TelemetryTrack
import com.dante.zeekrcapabilitylab.telemetry.VehicleTelemetryTrack
import io.github.dantenothing.avmtransfer.protocol.*
import java.io.File
import java.nio.file.Files

/** Runs on transfer IO. Never passes raw field-test logs or arbitrary error strings to the phone. */
internal object SessionRecorderEvidence {
    fun collect(context: Context, sessionId: String, currentSidecar: String,
        gps: TelemetryTrack?, vehicle: VehicleTelemetryTrack?, selectedSidecars: List<SegmentSidecar> = emptyList()): DriveRecorderReport {
        val metadata = LinkedHashMap<String, SegmentSidecar>()
        fun add(raw: String) {
            if (raw.toByteArray(Charsets.UTF_8).size > TransferProtocol.MAX_SIDECAR_BYTES) return
            val item = runCatching { SegmentSidecarIO.json.decodeFromString<SegmentSidecar>(raw) }.getOrNull() ?: return
            if (item.recordingSessionId == sessionId && metadata.size < 2_000) metadata[item.file] = item
        }
        add(currentSidecar)
        selectedSidecars.take(2_000).filter { it.recordingSessionId == sessionId }.forEach { metadata[it.file] = it }
        // Include queued USB snapshots as well as internal cabin/surround files. No USB mount scan.
        TransferRepository.tasks.value.take(2_000).forEach { it.sidecarJson?.let(::add) }
        val directory = File(context.filesDir, "recordings/segments")
        if (directory.isDirectory) runCatching {
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                var examined = 0
                for (path in stream) {
                    if (++examined > 8_000 || metadata.size >= 2_000) break
                    val file = path.toFile()
                    if (file.name.endsWith(".sidecar.json") && file.isFile &&
                        file.canonicalFile.parentFile == directory.canonicalFile && file.length() in 1..TransferProtocol.MAX_SIDECAR_BYTES.toLong()) {
                        add(file.readText(Charsets.UTF_8))
                    }
                }
            }
        }
        val runtime = runCatching {
            val observed = RecordingChannels.inspect(context, sessionId)["channels"] as? JsonArray
            observed.orEmpty().take(3).mapNotNull { value ->
                runCatching { Json.decodeFromJsonElement<RecordingChannelHealth>(value) }.getOrNull()
                    ?.takeIf { it.recordingSessionId == sessionId }?.let(::runtimeReport)
            }.distinctBy { it.channelId }
        }.getOrDefault(emptyList())
        return build(BuildConfig.VERSION_NAME, sessionId, metadata.values.toList(), gps, vehicle).copy(runtimeChannels = runtime,
            sourceRevision = BuildConfig.GIT_SHA.takeIf { it.matches(Regex("[a-fA-F0-9]{7,64}")) })
    }

    private fun runtimeReport(value: RecordingChannelHealth): DriveChannelRuntimeReport? = DriveChannelRuntimeReport(
        channelId = value.channelId, status = value.status, capturedFrames = value.capturedFrames,
        encodedFrames = value.encodedFrames, writtenFrames = value.writtenFrames, completedFiles = value.completedFiles,
        cleanupPending = value.cleanupPending, cleanupUnconfirmed = value.cleanupUnconfirmed,
        storageKind = when { value.storageKind.contains("USB") -> "USB"; value.storageKind.contains("INTERNAL") -> "INTERNAL"; else -> "UNKNOWN" },
        errorCategory = value.errorCode?.let { when {
            "TIMEOUT" in it || "NO_WRITTEN" in it -> "START_TIMEOUT"
            "RELEASE" in it || "CLEANUP" in it -> "RESOURCE_RELEASE"
            "ENCODER" in it || "WRITER" in it -> "ENCODER_OR_WRITER"
            "CAMERA" in it || "SOURCE_UNAVAILABLE" == it -> "CAMERA_UNAVAILABLE"
            else -> "UNKNOWN"
        } }).takeIf { it.valid() }

    internal fun build(version: String, sessionId: String, sidecars: List<SegmentSidecar>,
        gps: TelemetryTrack?, vehicle: VehicleTelemetryTrack?): DriveRecorderReport {
        val items = sidecars.take(2_000).filter { it.recordingSessionId == sessionId }
        val channels = items.groupBy { SessionPlaybackTimeline.channelForRole(it.sourceRole.name) }
            .filterKeys { it != null }.map { (channel, files) ->
                DriveChannelReport(channel!!,
                    finalizedFiles = files.count { it.result == SegmentSidecar.RESULT_SUCCESS && !it.provisional },
                    failedFiles = files.count { it.result == SegmentSidecar.RESULT_FAILED },
                    provisionalFiles = files.count { it.provisional },
                    savedBytes = files.sumOf { it.fileBytes.coerceIn(0, TransferProtocol.MAX_FILE_BYTES) }.coerceAtMost(64L * 1024 * 1024 * 1024 * 1024),
                    observedFrames = files.mapNotNull { it.frameStats?.count }.takeIf { it.isNotEmpty() }?.sum()?.coerceIn(0, 1_000_000_000L),
                    mediaDurationMs = files.sumOf { it.actualTrack?.durationMs?.coerceIn(0, SessionPlaybackTimeline.MAX_SESSION_MS) ?: 0 }
                        .coerceAtMost(SessionPlaybackTimeline.MAX_SESSION_MS * 3),
                    anchoredFiles = files.count { it.videoClockAnchor?.valid() == true },
                    observationAnchoredFiles = files.count { it.videoClockAnchor?.let { a -> a.valid() && a.source != "CAMERA_REALTIME" } == true },
                    approximateFiles = files.count { it.videoClockAnchor == null })
            }
        val points = gps?.takeIf { it.header.sessionId == sessionId }?.points.orEmpty()
        val observations = vehicle?.takeIf { it.header.sessionId == sessionId }?.samples.orEmpty()
        val pedals = listOf("brake_depth", "throttle_depth").map { key ->
            val values = observations.mapNotNull { it.values[key] }
            val raw = values.mapNotNull { value -> value.number?.takeIf {
                value.status == "PROVISIONAL_RAW" && value.text == null && it.isFinite() && it in -1_000_000.0..1_000_000.0
            } }
            DrivePedalReport(key, values.size, raw.size, raw.minOrNull(), raw.maxOrNull())
        }
        return DriveRecorderReport(appVersion = version, sessionCorrelation = DriveRecorderReport.correlation(sessionId), channels = channels,
            gpsSamples = points.size, vehicleSamples = observations.size,
            gpsBreaks = points.count { it.breakBefore }, vehicleBreaks = observations.count { it.breakBefore },
            pedals = pedals, pedalRawSamples = observations.count { row -> row.values.any { (key, value) ->
                key in setOf("brake_depth", "throttle_depth") && value.status == "PROVISIONAL_RAW" } })
            .also { require(it.valid()) }
    }
}
