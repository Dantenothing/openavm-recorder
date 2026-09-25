package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.UploadCompleteRequest
import io.github.dantenothing.avmtransfer.protocol.UploadCompleteResponse
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest
import io.github.dantenothing.avmtransfer.protocol.UploadCreateResponse
import io.github.dantenothing.avmtransfer.protocol.UploadStatusResponse
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class HttpResult(val code: Int, val body: String)

object TransferHttp {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val active = ConcurrentHashMap<String, Call>()

    fun cancel(taskId: String) { active[taskId]?.cancel() }

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
        val response = execute(taskId, endpoint, "/api/uploads", "POST", payload.toRequestBody(JSON_MEDIA))
        if (response.code !in 200..299) error("Create failed (${response.code})")
        return json.decodeFromString(UploadCreateResponse.serializer(), response.body)
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

    fun delete(taskId: String, endpoint: PhoneEndpoint, uploadId: String): HttpResult = execute(taskId, endpoint, "/api/uploads/$uploadId", "DELETE", null)

    private fun execute(taskId: String, endpoint: PhoneEndpoint, path: String, method: String, body: okhttp3.RequestBody?): HttpResult {
        val transport = PhoneConnectionStore.transportFor(endpoint)
        val call = transport.newCall(path, method, body, authenticated = path != "/health")
        active[taskId] = call
        return try {
            call.execute().use { HttpResult(it.code, it.limitedText()) }
        } catch (t: Exception) {
            throw PhoneConnectionStore.reportFailure(transport.endpoint, t, transport)
        } finally { active.remove(taskId, call) }
    }

    private val JSON_MEDIA = "application/json".toMediaType()
    private val OCTET_MEDIA = "application/octet-stream".toMediaType()
}
