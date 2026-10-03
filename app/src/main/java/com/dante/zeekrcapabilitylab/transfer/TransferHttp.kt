package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.UploadCompleteRequest
import io.github.dantenothing.avmtransfer.protocol.UploadCompleteResponse
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest
import io.github.dantenothing.avmtransfer.protocol.UploadCreateResponse
import io.github.dantenothing.avmtransfer.protocol.UploadStatusResponse
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class HttpResult(val code: Int, val body: String)
internal class TransferCancelledException : IOException("Transfer cancelled")

object TransferHttp {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class ActiveRequest(val call: Call, val method: String, val path: String)
    private val active = ConcurrentHashMap<String, ActiveRequest>()

    fun cancel(taskId: String) { active[taskId]?.call?.cancel() }
    internal fun isCreating(taskId: String) = active[taskId]?.let { it.method == "POST" && it.path == "/api/uploads" } == true

    fun health(taskId: String, endpoint: PhoneEndpoint): io.github.dantenothing.avmtransfer.protocol.HealthResponse {
        val response = execute(taskId, endpoint, "/health", "GET", null)
        check(response.code in 200..299) { "Phone capability check failed (${response.code})" }
        return json.decodeFromString(io.github.dantenothing.avmtransfer.protocol.HealthResponse.serializer(), response.body).also {
            if (it.phoneDeviceId != endpoint.phoneId || it.service != TransferProtocol.SERVICE) {
                throw PhoneSecurityException(PhoneSecurityError.PHONE_IDENTITY_CHANGED)
            }
        }
    }

    fun create(taskId: String, endpoint: PhoneEndpoint, request: UploadCreateRequest): UploadCreateResponse {
        val payload = json.encodeToString(UploadCreateRequest.serializer(), request)
        var created: UploadCreateResponse? = null
        val response = execute(taskId, endpoint, "/api/uploads", "POST", payload.toRequestBody(JSON_MEDIA)) { result ->
            if (result.code in 200..299) {
                created = json.decodeFromString(UploadCreateResponse.serializer(), result.body)
                TransferRepository.recordRemoteCreation(taskId, created!!)
            }
        }
        if (response.code !in 200..299) error("Create failed (${response.code})")
        return created ?: error("Missing create response")
    }

    fun status(taskId: String, endpoint: PhoneEndpoint, uploadId: String): UploadStatusResponse {
        val response = execute(taskId, endpoint, "/api/uploads/$uploadId", "GET", null)
        if (response.code !in 200..299) error("Status failed (${response.code})")
        return json.decodeFromString(UploadStatusResponse.serializer(), response.body)
    }

    fun chunk(taskId: String, endpoint: PhoneEndpoint, uploadId: String, index: Int, file: File, offset: Long, length: Int) {
        val bytes = ByteArray(length)
        RandomAccessFile(file, "r").use { source -> source.seek(offset); source.readFully(bytes) }
        val response = execute(taskId, endpoint, "/api/uploads/$uploadId/chunks/$index", "PUT", bytes.toRequestBody(OCTET_MEDIA))
        if (response.code !in 200..299) error("Chunk $index failed (${response.code})")
    }

    fun complete(taskId: String, endpoint: PhoneEndpoint, uploadId: String, sha: String, name: String): UploadCompleteResponse {
        val payload = json.encodeToString(UploadCompleteRequest.serializer(), UploadCompleteRequest(sha, name))
        val response = execute(taskId, endpoint, "/api/uploads/$uploadId/complete", "POST", payload.toRequestBody(JSON_MEDIA))
        if (response.code !in 200..299) error("Commit failed (${response.code})")
        return json.decodeFromString(UploadCompleteResponse.serializer(), response.body)
    }

    fun driveData(taskId: String, endpoint: PhoneEndpoint, uploadId: String, bytes: ByteArray) {
        require(bytes.size <= io.github.dantenothing.avmtransfer.protocol.DriveDataCodec.MAX_BYTES)
        val response = execute(taskId, endpoint, "/api/uploads/$uploadId/drive-data", "PUT", bytes.toRequestBody(OCTET_MEDIA))
        check(response.code in 200..299) { "Drive-data transfer failed (${response.code})" }
    }

    fun delete(taskId: String, endpoint: PhoneEndpoint, uploadId: String): HttpResult = execute(taskId, endpoint, "/api/uploads/$uploadId", "DELETE", null)

    private fun execute(taskId: String, endpoint: PhoneEndpoint, path: String, method: String, body: okhttp3.RequestBody?,
        onResponse: ((HttpResult) -> Unit)? = null): HttpResult {
        val transport = PhoneConnectionStore.transportFor(endpoint)
        val call = transport.newCall(path, method, body, authenticated = path != "/health")
        val registration = ActiveRequest(call, method, path)
        if (!TransferRepository.registerRequest(taskId, method, path) { active[taskId] = registration }) {
            call.cancel()
            throw TransferCancelledException()
        }
        return try {
            call.execute().use { HttpResult(it.code, it.limitedText()) }.also { onResponse?.invoke(it) }
        } catch (t: Exception) {
            if (call.isCanceled() && TransferRepository.get(taskId)?.let { !TransferCancellationPolicy.canUpload(it.state) } != false) {
                throw TransferCancelledException()
            }
            throw PhoneConnectionStore.reportFailure(transport.endpoint, t, transport)
        } finally { active.remove(taskId, registration) }
    }

    private val JSON_MEDIA = "application/json".toMediaType()
    private val OCTET_MEDIA = "application/octet-stream".toMediaType()
}
