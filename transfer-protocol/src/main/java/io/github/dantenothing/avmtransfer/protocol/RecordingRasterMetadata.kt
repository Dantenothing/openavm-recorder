package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.json.*

/** Read the explicit storage contract before presenting a product recording. Never infer repacking. */
sealed interface RecordingRasterMetadata {
    data object Original : RecordingRasterMetadata
    data class Repacked(val contract: StripRepackContract) : RecordingRasterMetadata
    data class Rejected(val reason: String) : RecordingRasterMetadata

    fun trackError(width: Int, height: Int): String? = when {
        this is Rejected -> reason
        width <= 0 || height <= 0 -> "VIDEO_TRACK_SIZE_UNAVAILABLE"
        this is Repacked && !contract.matchesTrack(width, height) -> "RASTER_TRACK_SIZE_MISMATCH"
        else -> null
    }

    fun receiverError(capabilities: List<String>): String? = when (this) {
        Original -> null
        is Rejected -> reason
        is Repacked -> if (StripRepackContract.TRANSFER_CAPABILITY in capabilities) null
            else "RECEIVER_RASTER_UPGRADE_REQUIRED"
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(sidecar: String?): RecordingRasterMetadata {
            if (sidecar == null) return Original
            if (sidecar.toByteArray(Charsets.UTF_8).size > TransferProtocol.MAX_SIDECAR_BYTES)
                return Rejected("SIDECAR_TOO_LARGE")
            return runCatching {
                val root = json.parseToJsonElement(sidecar).jsonObject
                if (root["kind"]?.jsonPrimitive?.content == "OPENAVM_CAMERA_DIAGNOSTIC_LAYOUT")
                    return Rejected("DIAGNOSTIC_VIDEO_NOT_PRODUCT_RECORDING")
                val value = root["rasterLayout"]
                if (value == null || value == JsonNull) return Original
                val contract = json.decodeFromJsonElement(StripRepackContract.serializer(), value)
                contract.validate().firstOrNull()?.let { return Rejected(it) }
                // Camera profile and lane coordinates always remain in the logical source raster.
                for (size in listOf(root["profile"]?.jsonObject?.get("size"), root["laneLayout"])) {
                    if (size == null || size == JsonNull) continue
                    val obj = size.jsonObject
                    val width = obj["width"] ?: obj["originalWidth"]
                    val height = obj["height"] ?: obj["originalHeight"]
                    if (width?.jsonPrimitive?.intOrNull != contract.inputWidth ||
                        height?.jsonPrimitive?.intOrNull != contract.inputHeight)
                        return Rejected("RASTER_SOURCE_SIZE_MISMATCH")
                }
                Repacked(contract)
            }.getOrElse { Rejected("INVALID_RASTER_METADATA") }
        }
    }
}

/** File coverage on the continuous encoder timeline; end is exclusive, not wall-clock close time. */
@kotlinx.serialization.Serializable
data class ContinuousSegmentTimeline(
    @kotlinx.serialization.Required val version: Int = 1,
    val runId: String,
    val firstPtsUs: Long,
    val lastPtsUs: Long,
    val endExclusivePtsUs: Long,
    val frames: Long,
    val startsWithKeyFrame: Boolean,
) {
    fun validate(): List<String> = buildList {
        if (version != 1) add("UNSUPPORTED_TIMELINE_VERSION")
        if (runId.isBlank() || runId.length > 128) add("INVALID_TIMELINE_RUN")
        if (firstPtsUs < 0 || lastPtsUs < firstPtsUs || endExclusivePtsUs <= lastPtsUs)
            add("INVALID_TIMELINE_RANGE")
        if (frames <= 0 || (frames == 1L && firstPtsUs != lastPtsUs) ||
            (frames > 1L && firstPtsUs == lastPtsUs)) add("INVALID_TIMELINE_FRAME_COUNT")
        if (!startsWithKeyFrame) add("FILE_DOES_NOT_START_WITH_KEYFRAME")
    }
}
