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
        val bytes = DriveDataCodec.encode(build(sidecar, RecordingTelemetry.store(context).load(session),
            RecordingVehicleTelemetry.store(context).load(session)))
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

    internal fun build(sidecarJson: String, gps: TelemetryTrack?, vehicle: VehicleTelemetryTrack?): DriveDataBundle {
        require(sidecarJson.toByteArray(Charsets.UTF_8).size <= TransferProtocol.MAX_SIDECAR_BYTES)
        val sidecar = json.parseToJsonElement(sidecarJson).jsonObject
        val session = sidecar["recordingSessionId"]?.jsonPrimitive?.content ?: error("Recording session is missing")
        val start = sidecar["startedAtElapsedRealtimeMs"]?.jsonPrimitive?.longOrNull ?: error("Recording start time is missing")
        val end = sidecar["stoppedAtElapsedRealtimeMs"]?.jsonPrimitive?.longOrNull ?: error("Recording end time is missing")
        require(start >= 0 && end > start) { "Recording time window is invalid" }
        require(sidecar["recordingMode"]?.jsonPrimitive?.content == "NORMAL" &&
            (sidecar["timeLapseMultiplier"]?.jsonPrimitive?.intOrNull ?: 1) == 1) { "Drive-data transfer currently supports normal recordings only" }
        val points = gps?.takeIf { it.header.sessionId == session }?.points.orEmpty()
            .filter { it.elapsedMs >= start && it.elapsedMs < end }
            .mapIndexed { index, p -> DriveGpsSample(p.elapsedMs, p.epochMs, p.latitude, p.longitude, p.accuracyM,
                p.provider, p.speedMps, p.speedAccuracyMps, p.breakBefore || index == 0) }
        val samples = vehicle?.takeIf { it.header.sessionId == session }?.samples.orEmpty()
            .filter { it.elapsedMs >= start && it.elapsedMs < end }
            .mapIndexed { index, sample -> DriveVehicleSample(sample.elapsedMs, sample.epochMs,
                sample.values.filterValues { it.readElapsedMs >= start && it.readElapsedMs < end }.mapValues { (_, field) ->
                    DriveVehicleValue(field.readElapsedMs, field.number, field.text, field.status)
                }, sample.breakBefore || index == 0) }
        return DriveDataBundle(recordingSessionId = session, segmentStartedElapsedMs = start, segmentEndedElapsedMs = end,
            gps = points, vehicle = samples)
    }
}
