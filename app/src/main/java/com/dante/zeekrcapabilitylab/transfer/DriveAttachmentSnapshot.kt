package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A fixed, consented, per-segment copy. Retrying never silently rebuilds a different payload. */
internal object DriveAttachmentSnapshot {
    private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(context: Context, id: String): File {
        require(ProtocolValidation.validIdentity(id))
        val dir = File(context.noBackupFilesDir, "phone-drive-snapshots")
        check(dir.isDirectory || dir.mkdirs())
        return File(dir, "$id.json").also { require(it.canonicalFile.parentFile == dir.canonicalFile) }
    }

    fun prepare(context: Context, task: TransferTask): DriveDataDescriptor {
        val sidecar = task.sidecarJson ?: error("Recording timing metadata is missing")
        val session = json.parseToJsonElement(sidecar).jsonObject["recordingSessionId"]?.jsonPrimitive?.content
            ?: error("Recording session is missing")
        require(VehicleTelemetryStore.validId(session)) { "Recording session is invalid" }
        val gps = RecordingTelemetry.store(context).load(session)
        val vehicle = RecordingVehicleTelemetry.store(context).load(session)
        val report = if (task.driveSelection == null) SessionRecorderEvidence.collect(context, session, sidecar, gps, vehicle) else null
        val bytes = DriveDataCodec.encode(build(sidecar, gps, vehicle, task.driveSelection).copy(recorderReport = report))
        val descriptor = requireNotNull(DriveDataCodec.descriptor(bytes))
        requireNotNull(DriveDataCodec.verify(bytes, descriptor, sidecar))
        val target = file(context, task.id)
        val dir = requireNotNull(target.parentFile)
        check(dir.listFiles().orEmpty().filter { it.isFile && it != target }.sumOf { it.length() } + bytes.size <= MAX_CACHE_BYTES) {
            "Drive-data transfer cache is full. Clear finished transfers and retry."
        }
        val partial = File(target.path + ".partial")
        try {
            FileOutputStream(partial).use { it.write(bytes); it.fd.sync() }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
        return descriptor
    }

    fun read(context: Context, task: TransferTask): ByteArray {
        val descriptor = requireNotNull(task.driveData) { "Drive-data snapshot is missing" }
        val target = file(context, task.id)
        check(target.isFile && target.length() == descriptor.sizeBytes && target.length() <= DriveDataCodec.MAX_BYTES) {
            "Drive-data snapshot is missing or changed. Queue the video again."
        }
        val bytes = ByteArray(descriptor.sizeBytes.toInt())
        java.io.DataInputStream(target.inputStream()).use { input -> input.readFully(bytes); check(input.read() == -1) }
        check(DriveDataCodec.verify(bytes, descriptor, task.sidecarJson) != null) { "Drive-data snapshot failed verification" }
        return bytes
    }

    fun remove(context: Context, id: String) { runCatching { file(context, id).delete() } }

    internal fun build(sidecarJson: String, gps: TelemetryTrack?, vehicle: VehicleTelemetryTrack?, selection: DriveDataSelection? = null): DriveDataBundle {
        require(selection?.valid() != false) { "Invalid drive-data selection" }
        require(sidecarJson.toByteArray(Charsets.UTF_8).size <= TransferProtocol.MAX_SIDECAR_BYTES)
        val sidecar = json.parseToJsonElement(sidecarJson).jsonObject
        val session = sidecar["recordingSessionId"]?.jsonPrimitive?.content ?: error("Recording session is missing")
        val start = sidecar["startedAtElapsedRealtimeMs"]?.jsonPrimitive?.longOrNull ?: error("Recording start time is missing")
        val end = sidecar["stoppedAtElapsedRealtimeMs"]?.jsonPrimitive?.longOrNull ?: error("Recording end time is missing")
        require(start >= 0 && end > start) { "Recording time window is invalid" }
        require(sidecar["recordingMode"]?.jsonPrimitive?.content == "NORMAL" &&
            (sidecar["timeLapseMultiplier"]?.jsonPrimitive?.intOrNull ?: 1) == 1) { "Drive-data transfer currently supports normal recordings only" }
        val windows = mutableListOf<DriveDataWindow>()
        selection?.windows.orEmpty().sortedBy { it.startElapsedMs }.forEach { window ->
            val last = windows.lastOrNull()
            if (last != null && window.startElapsedMs <= last.endElapsedMs)
                windows[windows.lastIndex] = last.copy(endElapsedMs = maxOf(last.endElapsedMs, window.endElapsedMs))
            else windows += window
        }
        fun sameWindow(a: Long, b: Long) = windows.isEmpty() || windows.any { it.contains(a) && it.contains(b) }
        var previousGps: Long? = null
        val points = gps?.takeIf { it.header.sessionId == session && selection?.gps != false }?.points.orEmpty()
            .filter { it.elapsedMs >= start && it.elapsedMs < end && selection?.contains(it.elapsedMs) != false }
            .map { p ->
                val gap = previousGps?.let { !sameWindow(it, p.elapsedMs) } == true
                previousGps = p.elapsedMs
                DriveGpsSample(p.elapsedMs, p.epochMs, p.latitude, p.longitude, p.accuracyM,
                    p.provider, p.speedMps, p.speedAccuracyMps, p.breakBefore || gap)
            }
        var previousVehicle: Long? = null
        val samples = vehicle?.takeIf { it.header.sessionId == session && selection?.vehicle != false }?.samples.orEmpty()
            .filter { it.elapsedMs >= start && it.elapsedMs < end && selection?.contains(it.elapsedMs) != false }
            .map { sample ->
                val gap = previousVehicle?.let { !sameWindow(it, sample.elapsedMs) } == true
                previousVehicle = sample.elapsedMs
                DriveVehicleSample(sample.elapsedMs, sample.epochMs,
                sample.values.filterValues { it.readElapsedMs >= start && it.readElapsedMs < end && sameWindow(it.readElapsedMs, sample.elapsedMs) }.mapValues { (_, field) ->
                    DriveVehicleValue(field.readElapsedMs, field.number, field.text, field.status)
                }, sample.breakBefore || gap) }
        val anchor = sidecar["videoClockAnchor"]?.takeUnless { it == JsonNull }?.let {
            json.decodeFromJsonElement<VideoClockAnchor>(it).also { value -> require(value.valid()) }
        }
        return DriveDataBundle(schemaVersion = 2, recordingSessionId = session, segmentStartedElapsedMs = start, segmentEndedElapsedMs = end,
            alignment = if (anchor != null) DriveDataCodec.ANCHORED_ALIGNMENT else DriveDataCodec.ALIGNMENT,
            gps = points, vehicle = samples, videoClockAnchor = anchor)
    }
}
