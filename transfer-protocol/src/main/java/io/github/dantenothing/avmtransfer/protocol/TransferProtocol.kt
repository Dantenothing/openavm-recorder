package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.Serializable

object TransferProtocol {
    const val SERVICE = "openavm-transfer"
    const val VERSION = 1
    const val PORT = 8766
    const val UDP_PORT = 8766
    const val DISCOVERY_REQUEST = "OPENAVM_DISCOVER_V1"
    const val HTTP_HEADER = "X-OpenAVM-Transfer"
    const val HTTP_HEADER_VALUE = "1"
    const val CHUNK_SIZE = 4 * 1024 * 1024
    const val PAIR_CODE_TTL_MS = 5 * 60_000L
    const val MAX_SIDECAR_BYTES = 256 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024 * 1024
    const val MAX_TOTAL_CHUNKS = 16_384
}

@Serializable
data class DiscoveryReply(
    val service: String = TransferProtocol.SERVICE,
    val version: Int = TransferProtocol.VERSION,
    val deviceName: String,
    val ip: String,
    val port: Int = TransferProtocol.PORT,
    val securityVersions: List<Int> = emptyList(),
    val tlsPort: Int? = null,
)

@Serializable
data class HealthResponse(
    val service: String = TransferProtocol.SERVICE,
    val version: Int = TransferProtocol.VERSION,
    val deviceName: String,
    val phoneDeviceId: String,
    /** Omitted by old receivers. Only advertise formats supported by playback AND export. */
    val recordingRasterLayouts: List<String> = emptyList(),
    val securityVersions: List<Int> = emptyList(),
    val tlsPort: Int? = null,
)

@Serializable
data class PairRequest(val code: String, val deviceName: String, val carDeviceId: String)

@Serializable
data class PairResponse(val token: String, val phoneDeviceId: String, val deviceName: String)

@Serializable
data class UploadCreateRequest(
    /** Stable car-side task id; makes create idempotent after a lost response. */
    val clientTransferId: String,
    val fileName: String,
    val mimeType: String = "video/mp4",
    val sizeBytes: Long,
    val sha256: String,
    val carId: String,
    /** Snapshot of the recording sidecar; it is committed beside the MP4. */
    val sidecarJson: String? = null,
)

@Serializable
data class UploadCreateResponse(val uploadId: String, val chunkSize: Int, val totalChunks: Int)

@Serializable
data class UploadStatusResponse(
    val uploadId: String,
    val receivedChunks: List<Int> = emptyList(),
    val totalChunks: Int,
    val status: String,
    val chunkSize: Int,
    val sha256: String,
)

@Serializable
data class UploadCompleteRequest(val sha256: String, val fileName: String)

@Serializable
data class UploadCompleteResponse(val uploadId: String, val ok: Boolean, val sha256: String, val fileName: String)

@Serializable
data class ApiError(val error: String)

enum class TransferTaskState {
    QUEUED,
    PREPARING,
    UPLOADING,
    COMMITTING,
    WAITING_RETRY,
    CANCEL_PENDING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

object ProtocolValidation {
    private val sha256 = Regex("^[0-9a-f]{64}$")
    private val identity = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun normalizedSha(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf(sha256::matches)
    fun validIdentity(raw: String?): Boolean = raw != null && identity.matches(raw)

    fun cleanFileName(raw: String?): String? {
        val name = raw?.trim()?.replace('\\', '/')?.substringAfterLast('/') ?: return null
        if (name.isBlank() || name == "." || name == ".." || name.length > 200) return null
        if (name.any { it == '\u0000' || it.code < 0x20 }) return null
        return name
    }

    fun totalChunks(sizeBytes: Long, chunkSize: Int): Int? {
        if (sizeBytes < 0 || sizeBytes > TransferProtocol.MAX_FILE_BYTES || chunkSize <= 0) return null
        val total = if (sizeBytes == 0L) 0L else ((sizeBytes - 1L) / chunkSize) + 1L
        return total.takeIf { it <= TransferProtocol.MAX_TOTAL_CHUNKS }?.toInt()
    }

    fun expectedChunkSize(sizeBytes: Long, chunkSize: Int, totalChunks: Int, index: Int): Int? {
        if (index !in 0 until totalChunks) return null
        if (index < totalChunks - 1) return chunkSize
        return (sizeBytes - (totalChunks - 1L) * chunkSize).toInt().takeIf { it in 1..chunkSize }
    }
}
