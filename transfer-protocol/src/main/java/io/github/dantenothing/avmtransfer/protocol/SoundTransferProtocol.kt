package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.Serializable

object SoundTransferProtocol {
    const val SCHEMA_VERSION = 1
    const val PRESET_ZEEKR_7X_AUNZ = "ZEEKR_7X_AUNZ"
    const val PURPOSE_LOCK = "LOCK"
    const val PURPOSE_UNLOCK = "UNLOCK"
    const val MAX_WAV_BYTES = 1_000_000L
    val TARGET_DIRECTORIES = listOf("Lock Status Tones", "解闭锁音效")
}

@Serializable
data class SoundWavParameters(val format: Int, val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val dataBytes: Long)

@Serializable
data class SoundOfferMetadata(
    val schemaVersion: Int = SoundTransferProtocol.SCHEMA_VERSION,
    val offerId: String,
    val targetCarDeviceId: String,
    val targetCarName: String? = null,
    val fileName: String,
    val mimeType: String = "audio/wav",
    val sizeBytes: Long,
    val sha256: String,
    val wav: SoundWavParameters,
    val presetId: String = SoundTransferProtocol.PRESET_ZEEKR_7X_AUNZ,
    val purpose: String,
    val state: String = SoundOfferStates.QUEUED,
    val operationId: String? = null,
    val targetDescription: String? = null,
    val targetStorageUuid: String? = null,
    val errorCode: String? = null,
    val statusMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

@Serializable
data class SoundOfferListResponse(
    val schemaVersion: Int = SoundTransferProtocol.SCHEMA_VERSION,
    val offers: List<SoundOfferMetadata>,
)

@Serializable
data class SoundInstallStatusUpdate(
    val schemaVersion: Int = SoundTransferProtocol.SCHEMA_VERSION,
    val state: String,
    val operationId: String? = null,
    val targetDescription: String? = null,
    val targetStorageUuid: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

object SoundOfferStates {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val WAITING_FOR_USB = "WAITING_FOR_USB"
    const val WAITING_FOR_USB_SELECTION = "WAITING_FOR_USB_SELECTION"
    const val INSTALLING = "INSTALLING"
    const val COMPLETED = "COMPLETED"
    const val FAILED_RECOVERABLE = "FAILED_RECOVERABLE"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
    val terminal = setOf(COMPLETED, FAILED, CANCELLED)
}

object SoundTransferValidation {
    private val id = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val sha = Regex("^[a-fA-F0-9]{64}$")
    private val forbidden = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun validateOffer(offer: SoundOfferMetadata): String? = when {
        offer.schemaVersion != SoundTransferProtocol.SCHEMA_VERSION -> "SCHEMA_VERSION"
        !id.matches(offer.offerId) -> "OFFER_ID"
        !id.matches(offer.targetCarDeviceId) -> "TARGET_CAR"
        cleanWavFileName(offer.fileName) == null -> "FILE_NAME"
        offer.mimeType != "audio/wav" && offer.mimeType != "audio/x-wav" -> "MIME_TYPE"
        offer.sizeBytes < 45L || offer.sizeBytes >= SoundTransferProtocol.MAX_WAV_BYTES -> "SIZE"
        !sha.matches(offer.sha256) -> "SHA256"
        offer.presetId != SoundTransferProtocol.PRESET_ZEEKR_7X_AUNZ -> "PRESET"
        offer.purpose != SoundTransferProtocol.PURPOSE_LOCK && offer.purpose != SoundTransferProtocol.PURPOSE_UNLOCK -> "PURPOSE"
        offer.wav.format != 1 -> "WAV_FORMAT"
        offer.wav.channels !in 1..2 -> "CHANNELS"
        offer.wav.sampleRate != 44_100 && offer.wav.sampleRate != 48_000 -> "SAMPLE_RATE"
        offer.wav.bitsPerSample != 16 -> "BITS_PER_SAMPLE"
        offer.wav.dataBytes <= 0 || offer.wav.dataBytes >= offer.sizeBytes -> "DATA_BYTES"
        else -> null
    }

    fun cleanWavFileName(raw: String): String? {
        val name = raw.trim()
        if (name.length !in 5..180 || !name.endsWith(".wav", true)) return null
        if (forbidden.containsMatchIn(name) || name == "." || name == "..") return null
        if (name.endsWith(" ") || name.endsWith(".")) return null
        return name
    }
}
