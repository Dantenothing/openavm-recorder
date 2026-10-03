package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.json.*

object RecordingSessionCompatibility {
    const val CAPABILITY = "parallel-session-v1"
    fun required(sidecar: String?): Boolean {
        if (sidecar == null || sidecar.length > TransferProtocol.MAX_SIDECAR_BYTES) return false
        return runCatching {
            val root = Json.parseToJsonElement(sidecar).jsonObject
            root["channelId"]?.jsonPrimitive?.contentOrNull in SessionPlaybackTimeline.CHANNELS &&
                root["recordingMode"]?.jsonPrimitive?.contentOrNull == "NORMAL" &&
                root["sessionStartedElapsedMs"]?.jsonPrimitive?.longOrNull?.let { it >= 0 } == true
        }.getOrDefault(false)
    }
    fun supported(sidecar: String?, receiverFormats: List<String>): Boolean = !required(sidecar) || CAPABILITY in receiverFormats
}
