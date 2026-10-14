package com.dante.zeekrcapabilitylab.diagnostic

import android.content.Context
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelHealth
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelId
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannels
import com.dante.zeekrcapabilitylab.service.recorder.RecordingChannelJournal
import kotlinx.serialization.json.*
import java.io.OutputStream

/** One requested, bounded file from existing redacted snapshots. No hardware query or upload. */
internal object RecorderSupportReport {
    const val FORMAT = "OPENAVM_SUPPORT_REPORT"
    const val MAX_BYTES = 96 * 1024

    /** Call on IO. A failed section must not discard the other available evidence. */
    fun collect(context: Context): String = build(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
        System.currentTimeMillis(),
        section { Json.parseToJsonElement(ShortRecorderDiagnostics.collect(context)).jsonObject },
        section { AwayJournal.report(ShortRecorderDiagnostics.loadLastFault(context)) },
        RecordingChannels.state.value, retainedChannels(RecordingChannelJournal.read(context)),
        RecordingChannelJournal.readFailure(context))

    internal fun section(read: () -> JsonObject): JsonObject = try {
        buildJsonObject { put("available", true); put("data", read()) }
    } catch (_: Exception) {
        // Exception messages may contain paths or vendor data. Only export a fixed category.
        buildJsonObject { put("available", false); put("errorCode", "SNAPSHOT_UNAVAILABLE") }
    }

    internal fun build(version: String, versionCode: Int, nowMs: Long, recorder: JsonObject,
                       away: JsonObject, channels: List<RecordingChannelHealth>,
                       retained: JsonObject = buildJsonObject { put("readStatus", "NOT_COLLECTED") },
                       groupFailure: JsonObject = buildJsonObject { put("readStatus", "NOT_COLLECTED") }): String {
        val result = buildJsonObject {
            put("schemaVersion", 1); put("format", FORMAT)
            put("exportVersion", version); put("exportVersionCode", versionCode); put("exportedAtEpochMs", nowMs)
            put("scope", "RECENT_RUNTIME_AND_SAVED_FAULTS_NOT_SELECTED_VIDEO")
            put("recorder", recorder); put("away", away)
            put("runtimeChannels", channelRows(channels))
            put("runtimeChannelsScope", "CACHED_THIS_PROCESS_NOT_A_MEDIA_INVENTORY")
            put("retainedChannels", retained); put("lastGroupFailure", groupFailure)
            put("videoIncluded", false); put("coordinatesIncluded", false); put("accountDataIncluded", false)
        }.toString()
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "SUPPORT_REPORT_TOO_LARGE" }
        return result
    }

    internal fun retainedChannels(saved: RecordingChannelJournal.Saved): JsonObject = buildJsonObject {
        put("readStatus", saved.readStatus)
        put("scope", "SAVED_HISTORY_MAY_PREDATE_THIS_PROCESS_NOT_CURRENT_CAMERA_STATE")
        put("channels", channelRows(saved.channels))
    }

    private fun channelRows(channels: List<RecordingChannelHealth>): JsonArray = buildJsonArray {
        channels.filter { it.channelId in RecordingChannelId.all && ProcessExitReport.sessionCorrelation(it.recordingSessionId) != null }
            .takeLast(9).forEach { channel -> add(buildJsonObject {
                put("sessionCorrelation", ProcessExitReport.sessionCorrelation(channel.recordingSessionId))
                put("channelId", channel.channelId); put("status", code(channel.status))
                put("capturedFrames", channel.capturedFrames); put("encodedFrames", channel.encodedFrames)
                put("writtenFrames", channel.writtenFrames); put("completedFiles", channel.completedFiles)
                put("cleanupPending", channel.cleanupPending); put("cleanupUnconfirmed", channel.cleanupUnconfirmed)
                put("storageKind", code(channel.storageKind)); put("errorCode", code(channel.errorCode))
            }) }
    }

    /** Validate before touching the destination; the caller owns and closes its document stream. */
    fun write(text: String, output: OutputStream) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES)
        require(Json.parseToJsonElement(text).jsonObject["format"]?.jsonPrimitive?.content == FORMAT)
        output.write(bytes); output.flush()
    }

    private fun code(value: String?): String? = value?.let {
        it.takeIf { raw -> raw.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "UNKNOWN"
    }
}
