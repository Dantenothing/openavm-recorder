package com.dante.zeekrbridge.server

import com.dante.zeekrbridge.core.UploadCreateRequest as LegacyCreateRequest
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest as OpenAvmCreateRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

data class AdaptedUploadRequest(
    val request: OpenAvmCreateRequest,
    val legacy: Boolean,
)

object UploadRequestAdapter {
    fun decode(json: Json, body: String, authenticatedCarId: String): AdaptedUploadRequest {
        val objectKeys = json.parseToJsonElement(body).jsonObject.keys
        return if ("clientTransferId" in objectKeys) {
            AdaptedUploadRequest(
                request = json.decodeFromString(OpenAvmCreateRequest.serializer(), body)
                    .copy(carId = authenticatedCarId),
                legacy = false,
            )
        } else {
            val legacy = json.decodeFromString(LegacyCreateRequest.serializer(), body)
            AdaptedUploadRequest(
                request = OpenAvmCreateRequest(
                    clientTransferId = "legacy-${legacy.sha256.lowercase()}",
                    fileName = legacy.fileName,
                    mimeType = legacy.mimeType,
                    sizeBytes = legacy.sizeBytes,
                    sha256 = legacy.sha256,
                    carId = authenticatedCarId,
                ),
                legacy = true,
            )
        }
    }
}
