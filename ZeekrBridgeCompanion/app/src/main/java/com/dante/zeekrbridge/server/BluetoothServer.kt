package com.dante.zeekrbridge.server

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.dante.zeekrbridge.core.BtAuthGate
import com.dante.zeekrbridge.core.BtJson
import com.dante.zeekrbridge.core.BtProtocol
import com.dante.zeekrbridge.core.MergeOutcome
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.PairRequest
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.SecureCompare
import com.dante.zeekrbridge.core.ServerLog
import com.dante.zeekrbridge.core.ShaHex
import com.dante.zeekrbridge.core.UploadCreateRequest
import com.dante.zeekrbridge.core.UploadSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

data class BluetoothServerState(
    val running: Boolean = false,
    val connectedCars: Int = 0,
    val lastClient: String? = null,
    val lastError: String? = null,
)

@SuppressLint("MissingPermission")
object BluetoothServer {
    const val BT_CHUNK_SIZE = 32 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(BluetoothServerState())
    val state: StateFlow<BluetoothServerState> = _state.asStateFlow()

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile
    private var accepting = false
    private val connections = CopyOnWriteArrayList<BtConnection>()

    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) {
        // The legacy RFCOMM protocol has no v2 identity binding. Do not expose a bypass.
        _state.value = BluetoothServerState(lastError = "SECURE_TRANSPORT_REQUIRED")
    }

    fun stop(context: Context) {
        try {
            serverSocket?.close()
        } catch (t: Throwable) {
            // Ignore.
        }
        serverSocket = null
        accepting = false
        connections.forEach { it.close() }
        connections.clear()
        _state.value = _state.value.copy(running = false, connectedCars = 0)
        ServerLog.log("BT_SERVER_STOPPED")
    }

    internal fun remove(connection: BtConnection) {
        connections.remove(connection)
        _state.value = _state.value.copy(connectedCars = connections.size)
    }
}

@SuppressLint("MissingPermission")
internal class BtConnection(
    private val socket: BluetoothSocket,
    private val server: BluetoothServer,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val auth = BtAuthGate()

    fun run() {
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            var expectingChunk = false
            var chunkRequestId: String? = null
            var chunkIndex = -1
            var chunkUploadId: String? = null
            var chunkDeclaredSize = 0
            while (true) {
                val frame = BtProtocol.readFrame(input) ?: break
                when {
                    expectingChunk && frame.first == BtProtocol.TYPE_BINARY -> {
                        expectingChunk = false
                        val requestId = chunkRequestId
                        val uploadId = chunkUploadId
                        if (requestId == null || uploadId == null) continue
                        val session = UploadSessionStore.get(uploadId)
                        if (session == null) {
                            reply(output, "CHUNK_ACK", requestId, mapOf("ok" to "false", "error" to "unknown upload"))
                            continue
                        }
                        if (frame.second.size != chunkDeclaredSize) {
                            reply(
                                output,
                                "CHUNK_ACK",
                                requestId,
                                mapOf(
                                    "ok" to "false",
                                    "error" to "length mismatch declared=$chunkDeclaredSize actual=${frame.second.size}",
                                ),
                            )
                            continue
                        }
                        val result = UploadSessionStore.storeChunk(session, chunkIndex, frame.second)
                        val fields = if (result.ok) {
                            mapOf("index" to chunkIndex.toString(), "ok" to "true")
                        } else {
                            mapOf("ok" to "false", "error" to (result.error ?: "store failed"))
                        }
                        reply(output, "CHUNK_ACK", requestId, fields)
                    }
                    frame.first == BtProtocol.TYPE_JSON -> {
                        val text = String(frame.second, Charsets.UTF_8)
                        val obj = json.parseToJsonElement(text).jsonObject
                        val type = obj["type"]?.toString()?.trim('"') ?: continue
                        val requestId = obj["requestId"]?.toString()?.trim('"')
                        if (BtAuthGate.requiresAuth(type) && !auth.authenticated) {
                            reply(
                                output,
                                unauthorizedReplyType(type),
                                requestId,
                                mapOf("ok" to "false", "error" to "unauthorized"),
                            )
                            continue
                        }
                        when (type) {
                            "PAIR" -> {
                                val request = PairRequest(
                                    code = obj["code"]?.toString()?.trim('"') ?: "",
                                    deviceName = obj["deviceName"]?.toString()?.trim('"') ?: "",
                                    carDeviceId = obj["carDeviceId"]?.toString()?.trim('"') ?: "",
                                )
                                val pair = PairingManager.pair(request)
                                if (pair == null) {
                                    reply(output, "PAIR_FAIL", requestId, mapOf("error" to "invalid code"))
                                } else {
                                    reply(
                                        output,
                                        "PAIR_OK",
                                        requestId,
                                        mapOf(
                                            "token" to pair.token,
                                            "phoneDeviceId" to pair.phoneDeviceId,
                                            "deviceName" to pair.deviceName,
                                        ),
                                    )
                                }
                            }
                            "HELLO" -> {
                                val token = obj["token"]?.toString()?.trim('"')
                                val bearer = BtJson.normalizeBearer(token)
                                val device = if (bearer != null) PairingManager.authenticate(bearer) else null
                                if (device == null) {
                                    reply(output, "HELLO_ACK", requestId, mapOf("ok" to "false", "error" to "unauthorized"))
                                    socket.close()
                                    return
                                }
                                val claimedCarId = obj["carDeviceId"]?.toString()?.trim('"')
                                if (!WsIdentity.matches(device.carDeviceId, claimedCarId)) {
                                    ServerLog.log(
                                        "BT_HELLO_IDENTITY_MISMATCH expected=${device.carDeviceId.take(8)} " +
                                            "claimed=${claimedCarId?.take(8) ?: ""}",
                                    )
                                    reply(output, "HELLO_ACK", requestId, mapOf("ok" to "false", "error" to "identity mismatch"))
                                    socket.close()
                                    return
                                }
                                reply(output, "HELLO_ACK", requestId, mapOf("ok" to "true"))
                                auth.authenticate(device)
                                ServerLog.log("BT_HELLO car=${device.carDeviceId} name=${device.name}")
                            }
                            "HEARTBEAT" -> reply(output, "HEARTBEAT_ACK", requestId, mapOf("ok" to "true"))
                            "UPLOAD_CREATE" -> {
                                val request = UploadCreateRequest(
                                    fileName = obj["fileName"]?.toString()?.trim('"') ?: "unknown",
                                    mimeType = obj["mimeType"]?.toString()?.trim('"') ?: "application/octet-stream",
                                    sizeBytes = obj["sizeBytes"]?.toString()?.toLongOrNull() ?: 0L,
                                    sha256 = obj["sha256"]?.toString()?.trim('"') ?: "",
                                    carId = auth.device()?.carDeviceId ?: "",
                                )
                                val session = try {
                                    UploadSessionStore.create(request, BluetoothServer.BT_CHUNK_SIZE)
                                } catch (e: IllegalArgumentException) {
                                    reply(output, "UPLOAD_CREATED", requestId, mapOf("ok" to "false", "error" to (e.message ?: "invalid upload")))
                                    continue
                                }
                                ServerLog.log(
                                    "BT_UPLOAD_CREATED id=${session.uploadId.take(8)} " +
                                        "file=${session.request.fileName} chunks=${session.totalChunks}",
                                )
                                reply(
                                    output,
                                    "UPLOAD_CREATED",
                                    requestId,
                                    mapOf(
                                        "uploadId" to session.uploadId,
                                        "chunkSize" to session.chunkSize.toString(),
                                        "totalChunks" to session.totalChunks.toString(),
                                    ),
                                )
                            }
                            "UPLOAD_STATUS" -> {
                                val uploadId = obj["uploadId"]?.toString()?.trim('"') ?: continue
                                val session = UploadSessionStore.get(uploadId)
                                if (session == null) {
                                    val error = if (UploadSessionStore.isCorrupt(uploadId)) {
                                        "upload session data corrupted"
                                    } else {
                                        "unknown upload"
                                    }
                                    reply(output, "UPLOAD_STATUS", requestId, mapOf("ok" to "false", "error" to error))
                                    continue
                                }
                                if (!SecureCompare.equals(session.request.carId, auth.device()?.carDeviceId)) {
                                    reply(output, "UPLOAD_STATUS", requestId, mapOf("ok" to "false", "error" to "forbidden"))
                                    continue
                                }
                                val received = UploadSessionStore.receivedChunks(session)
                                reply(
                                    output,
                                    "UPLOAD_STATUS",
                                    requestId,
                                    mapOf(
                                        "uploadId" to uploadId,
                                        "receivedChunks" to received.joinToString(","),
                                        "totalChunks" to session.totalChunks.toString(),
                                        "chunkSize" to session.chunkSize.toString(),
                                        "status" to session.status,
                                        "sha256" to (session.completedSha256 ?: session.request.sha256),
                                    ),
                                )
                            }
                            "CHUNK" -> {
                                val uploadId = obj["uploadId"]?.toString()?.trim('"')
                                val index = obj["index"]?.toString()?.toIntOrNull() ?: -1
                                val declared = obj["size"]?.toString()?.toIntOrNull() ?: -1
                                val session = uploadId?.let { UploadSessionStore.get(it) }
                                val valid = session != null &&
                                    SecureCompare.equals(session.request.carId, auth.device()?.carDeviceId) &&
                                    declared in 0..BluetoothServer.BT_CHUNK_SIZE &&
                                    UploadSessionStore.expectedChunkSize(session, index) == declared
                                if (!valid) {
                                    reply(
                                        output,
                                        "CHUNK_ACK",
                                        requestId,
                                        mapOf(
                                            "ok" to "false",
                                            "error" to "invalid chunk index=$index size=$declared",
                                        ),
                                    )
                                    expectingChunk = false
                                    continue
                                }
                                expectingChunk = true
                                chunkRequestId = requestId
                                chunkIndex = index
                                chunkUploadId = uploadId
                                chunkDeclaredSize = declared
                            }
                            "UPLOAD_COMPLETE" -> {
                                val uploadId = obj["uploadId"]?.toString()?.trim('"') ?: continue
                                val sha = obj["sha256"]?.toString()?.trim('"') ?: ""
                                val session = UploadSessionStore.get(uploadId)
                                if (session == null) {
                                    reply(output, "UPLOAD_RESULT", requestId, mapOf("ok" to "false", "error" to "unknown upload"))
                                    continue
                                }
                                if (!SecureCompare.equals(session.request.carId, auth.device()?.carDeviceId)) {
                                    reply(output, "UPLOAD_RESULT", requestId, mapOf("ok" to "false", "error" to "forbidden"))
                                    continue
                                }
                                val outcome = ReceivedStore.completeUpload(uploadId, sha, session.request.fileName)
                                if (outcome.file == null && outcome.sha256 == null) {
                                    reply(
                                        output,
                                        "UPLOAD_RESULT",
                                        requestId,
                                        mapOf(
                                            "ok" to "false",
                                            "error" to if (outcome.error == MergeOutcome.ERR_SHA) {
                                                "sha mismatch"
                                            } else {
                                                outcome.error ?: "merge failed"
                                            },
                                        ),
                                    )
                                    continue
                                }
                                if (outcome.file == null || outcome.sha256 != ShaHex.normalize(sha)) {
                                    ServerLog.log("BT_UPLOAD_COMPLETED file=${session.request.fileName} ok=false")
                                    reply(
                                        output,
                                        "UPLOAD_RESULT",
                                        requestId,
                                        mapOf(
                                            "ok" to "false",
                                            "sha256" to (outcome.sha256 ?: ""),
                                            "error" to (outcome.error ?: "hash mismatch"),
                                        ),
                                    )
                                    continue
                                }
                                ServerLog.log("BT_UPLOAD_COMPLETED file=${outcome.file?.name} ok=true")
                                reply(
                                    output,
                                    "UPLOAD_RESULT",
                                    requestId,
                                    mapOf(
                                        "ok" to "true",
                                        "sha256" to outcome.sha256.orEmpty(),
                                        "path" to outcome.file?.absolutePath.orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            ServerLog.log("BT_CONNECTION_ERROR ${t.message}")
        } finally {
            try {
                socket.close()
            } catch (t: Throwable) {
                // Ignore.
            }
            server.remove(this)
            ServerLog.log("BT_DISCONNECTED")
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    private fun reply(output: OutputStream, type: String, requestId: String?, fields: Map<String, String>) {
        val obj = BtJson.reply(type, requestId, fields)
        BtProtocol.writeFrame(output, BtProtocol.TYPE_JSON, obj.toString().toByteArray(Charsets.UTF_8))
    }

    private fun unauthorizedReplyType(type: String): String = when (type) {
        "HEARTBEAT" -> "HEARTBEAT_ACK"
        "CHUNK" -> "CHUNK_ACK"
        "UPLOAD_CREATE" -> "UPLOAD_CREATED"
        "UPLOAD_COMPLETE" -> "UPLOAD_RESULT"
        else -> type
    }
}
