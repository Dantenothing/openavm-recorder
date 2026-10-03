package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.Serializable

/** Closed, coordinate-free evidence copied only with an explicitly selected drive attachment.
 * No arbitrary messages, paths, IDs, facts map or vendor payload are accepted on this channel. */
@Serializable
data class DriveRecorderReport(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val sourceRevision: String? = null,
    val sessionCorrelation: String,
    val snapshotScope: String = "FINALIZED_FILES_AT_TRANSFER",
    val channels: List<DriveChannelReport> = emptyList(),
    val runtimeChannels: List<DriveChannelRuntimeReport> = emptyList(),
    /** A bounded snapshot of locally available/queued files, not an exhaustive disk audit. */
    val inventoryComplete: Boolean = false,
    val gpsSamples: Int = 0,
    val vehicleSamples: Int = 0,
    val gpsBreaks: Int = 0,
    val vehicleBreaks: Int = 0,
    val pedalRawSamples: Int = 0,
    val pedals: List<DrivePedalReport> = emptyList(),
    val sensorMeasurementTiming: String = "GETTER_OBSERVATION_ONLY",
    val hardwareAcceptance: String = "NOT_ASSERTED",
    val cleanupOutcome: String = "NOT_OBSERVED_BY_FILE_SNAPSHOT",
) {
    companion object {
        fun correlation(recordingSessionId: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(recordingSessionId.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
    fun valid(): Boolean = schemaVersion == 1 && appVersion.matches(Regex("[A-Za-z0-9._+-]{1,80}")) &&
        (sourceRevision == null || sourceRevision.matches(Regex("[a-fA-F0-9]{7,64}"))) &&
        sessionCorrelation.matches(Regex("[a-f0-9]{16,64}")) && snapshotScope == "FINALIZED_FILES_AT_TRANSFER" &&
        channels.size <= 3 && channels.map { it.channelId }.distinct().size == channels.size && channels.all { it.valid() } &&
        runtimeChannels.size <= 3 && runtimeChannels.map { it.channelId }.distinct().size == runtimeChannels.size && runtimeChannels.all { it.valid() } &&
        listOf(gpsSamples, vehicleSamples, gpsBreaks, vehicleBreaks, pedalRawSamples).all { it in 0..1_000_000 } &&
        pedals.size <= 2 && pedals.map { it.key }.distinct().size == pedals.size && pedals.all { it.valid() } &&
        sensorMeasurementTiming == "GETTER_OBSERVATION_ONLY" && hardwareAcceptance == "NOT_ASSERTED" &&
        cleanupOutcome in setOf("CONFIRMED", "PENDING", "NOT_OBSERVED_BY_FILE_SNAPSHOT")
}

/** Bounded raw ranges aid the next hardware review; no percentage or calibrated unit is implied. */
@Serializable
data class DrivePedalReport(
    val key: String,
    val observations: Int,
    val rawAvailable: Int,
    val rawMin: Double? = null,
    val rawMax: Double? = null,
    val scale: String = "UNVERIFIED_RAW_NO_PERCENTAGE",
) {
    fun valid(): Boolean = key in setOf("brake_depth", "throttle_depth") && observations in 0..1_000_000 &&
        rawAvailable in 0..observations && scale == "UNVERIFIED_RAW_NO_PERCENTAGE" &&
        if (rawAvailable == 0) rawMin == null && rawMax == null
        else rawMin != null && rawMax != null && rawMin.isFinite() && rawMax.isFinite() &&
            rawMin in -1_000_000.0..1_000_000.0 && rawMax in rawMin..1_000_000.0
}

@Serializable
data class DriveChannelRuntimeReport(
    val channelId: String,
    val status: String,
    val capturedFrames: Long,
    val encodedFrames: Long,
    val writtenFrames: Long,
    val completedFiles: Long,
    val cleanupPending: Boolean,
    val cleanupUnconfirmed: Boolean,
    val storageKind: String,
    val errorCategory: String? = null,
) {
    fun valid(): Boolean = channelId in SessionPlaybackTimeline.CHANNELS && status in
        setOf("STARTING", "RECEIVING", "WRITING", "STOPPING", "STOPPED", "UNAVAILABLE", "INTERRUPTED") &&
        listOf(capturedFrames, encodedFrames, writtenFrames).all { it in 0..1_000_000_000L } && completedFiles in 0..20_000 &&
        storageKind in setOf("INTERNAL", "USB", "UNKNOWN") && errorCategory in
        setOf(null, "CAMERA_UNAVAILABLE", "RESOURCE_RELEASE", "ENCODER_OR_WRITER", "START_TIMEOUT", "UNKNOWN")
}

@Serializable
data class DriveChannelReport(
    val channelId: String,
    val finalizedFiles: Int,
    val failedFiles: Int,
    val provisionalFiles: Int,
    val savedBytes: Long,
    val observedFrames: Long? = null,
    val mediaDurationMs: Long,
    val anchoredFiles: Int,
    val observationAnchoredFiles: Int,
    val approximateFiles: Int,
) {
    fun valid(): Boolean = channelId in SessionPlaybackTimeline.CHANNELS &&
        listOf(finalizedFiles, failedFiles, provisionalFiles, anchoredFiles, observationAnchoredFiles, approximateFiles)
            .all { it in 0..20_000 } && savedBytes in 0..(64L * 1024 * 1024 * 1024 * 1024) &&
        (observedFrames == null || observedFrames in 0..1_000_000_000L) && mediaDurationMs in 0..SessionPlaybackTimeline.MAX_SESSION_MS * 3
}
