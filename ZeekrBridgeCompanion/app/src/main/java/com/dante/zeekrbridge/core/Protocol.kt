package com.dante.zeekrbridge.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object Protocol {
    const val SERVICE_NAME = "zeekr-bridge"
    const val VERSION = 1
    const val PORT = 8766
    const val UDP_PORT = 8766
    const val DISCOVERY_REQUEST = "ZEekr Bridge discovery request"
    const val NSD_TYPE = "_zeekrbridge._tcp."
    const val CHUNK_SIZE = 4 * 1024 * 1024
    const val PAIR_CODE_TTL_MS = 5 * 60_000L
    const val MAX_UPLOAD_TOTAL_CHUNKS = 1_000_000
}

@Serializable
data class DiscoveryReply(
    val service: String,
    val version: Int,
    val deviceName: String,
    val ip: String,
    val port: Int,
    val pairingId: String,
)

@Serializable
data class PairRequest(
    val code: String,
    val deviceName: String,
    val carDeviceId: String,
)

@Serializable
data class PairResponse(
    val token: String,
    val phoneDeviceId: String,
    val deviceName: String,
)

@Serializable
data class UploadCreateRequest(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val carId: String,
)

@Serializable
data class UploadCreateResponse(
    val uploadId: String,
    val chunkSize: Int,
    val totalChunks: Int,
)

@Serializable
data class UploadStatusResponse(
    val uploadId: String,
    val receivedChunks: List<Int> = emptyList(),
    val totalChunks: Int = 0,
    val status: String = "PENDING",
    val chunkSize: Int = 0,
    val sha256: String = "",
)

@Serializable
data class UploadCompleteRequest(
    val sha256: String,
    val fileName: String,
)

@Serializable
data class UploadCompleteResponse(
    val uploadId: String,
    val ok: Boolean,
    val sha256: String,
    val path: String,
)

@Serializable
data class WsEnvelope(
    val type: String,
    val sequence: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
)

object WsType {
    const val HELLO = "HELLO"
    const val HEARTBEAT = "HEARTBEAT"
    const val UPLOAD_STATUS = "UPLOAD_STATUS"
    const val FILE_OFFER = "FILE_OFFER"
    const val PHONE_TO_CAR_EVENT = "PHONE_TO_CAR_EVENT"
    const val CAR_CONTROL = "CAR_CONTROL"
    const val ERROR = "ERROR"

    // V2 product protocol (phone -> car)
    const val LIST_RECORDINGS = "LIST_RECORDINGS"
    const val REQUEST_UPLOAD = "REQUEST_UPLOAD"
    const val DELETE_RECORDING = "DELETE_RECORDING"
    const val CONTROL_UPLOAD = "CONTROL_UPLOAD"
    const val CAR_STATUS = "CAR_STATUS"
    const val SET_RECORDER_CONFIG = "SET_RECORDER_CONFIG"
    const val SYNC_PROTECTED = "SYNC_PROTECTED"

    // V2 product protocol (car -> phone)
    const val RECORDING_CATALOG = "RECORDING_CATALOG"
    const val UPLOAD_QUEUED = "UPLOAD_QUEUED"
    const val RECORDING_DELETED = "RECORDING_DELETED"
    const val CAR_STATUS_REPLY = "CAR_STATUS_REPLY"
}
