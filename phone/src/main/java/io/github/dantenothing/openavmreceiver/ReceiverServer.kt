package io.github.dantenothing.openavmreceiver

import android.content.Context
import android.os.Build
import io.github.dantenothing.avmtransfer.protocol.ApiError
import io.github.dantenothing.avmtransfer.protocol.DiscoveryReply
import io.github.dantenothing.avmtransfer.protocol.HealthResponse
import io.github.dantenothing.avmtransfer.protocol.PairRequest
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import io.github.dantenothing.avmtransfer.protocol.UploadCompleteRequest
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ReceiverServerState(
    val running: Boolean = false,
    val addresses: List<String> = emptyList(),
    val activeRequests: Int = 0,
    val lastMessage: String = "",
)

object ReceiverServer {
    private const val MAX_HEADER_BYTES = 32 * 1024
    private const val MAX_BODY_BYTES = TransferProtocol.CHUNK_SIZE + 512 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val _state = MutableStateFlow(ReceiverServerState())
    val state = _state.asStateFlow()
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        _state.value = ReceiverServerState(running = true, addresses = addresses())
        pool.execute { tcpLoop(context.applicationContext) }
        pool.execute { udpLoop() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        serverSocket = null
        udpSocket = null
        _state.value = _state.value.copy(running = false, activeRequests = 0)
    }

    fun refreshAddresses() { _state.value = _state.value.copy(addresses = addresses()) }

    private fun tcpLoop(context: Context) {
        try {
            ServerSocket(TransferProtocol.PORT).use { server ->
                serverSocket = server
                while (running.get()) {
                    val socket = try { server.accept() } catch (_: Throwable) { break }
                    pool.execute { handle(context, socket) }
                }
            }
        } catch (t: Throwable) {
            _state.value = _state.value.copy(lastMessage = "Server error: ${t.message}")
        } finally {
            serverSocket = null
        }
    }

    private fun udpLoop() {
        try {
            DatagramSocket(TransferProtocol.UDP_PORT).use { socket ->
                udpSocket = socket
                val buffer = ByteArray(1024)
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try { socket.receive(packet) } catch (_: Throwable) { break }
                    val request = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    if (request != TransferProtocol.DISCOVERY_REQUEST) continue
                    val ip = addresses().firstOrNull().orEmpty()
                    val payload = json.encodeToString(DiscoveryReply(deviceName = PairingStore.phoneName, ip = ip)).toByteArray()
                    socket.send(DatagramPacket(payload, payload.size, packet.address, packet.port))
                }
            }
        } catch (_: Throwable) {
            // Manual IP pairing remains available when a vendor network blocks broadcast.
        } finally { udpSocket = null }
    }

    private fun handle(context: Context, socket: Socket) {
        _state.value = _state.value.copy(activeRequests = _state.value.activeRequests + 1)
        socket.use { client ->
            client.soTimeout = 30_000
            try {
                val request = readRequest(BufferedInputStream(client.getInputStream()))
                route(request, client)
            } catch (t: Throwable) {
                respond(client, 400, json.encodeToString(ApiError(t.message ?: "bad request")))
            }
        }
        _state.value = _state.value.copy(activeRequests = (_state.value.activeRequests - 1).coerceAtLeast(0))
    }

    private fun route(request: HttpRequest, socket: Socket) {
        val path = request.path.substringBefore('?')
        when {
            request.method == "GET" && path == "/health" -> respond(socket, 200, json.encodeToString(
                HealthResponse(deviceName = PairingStore.phoneName, phoneDeviceId = PairingStore.phoneId),
            ))
            request.method == "POST" && path == "/api/pair" -> {
                val pair = runCatching { json.decodeFromString(PairRequest.serializer(), request.body.decodeToString()) }.getOrNull()
                val response = pair?.let(PairingStore::pair)
                if (response == null) respond(socket, 401, json.encodeToString(ApiError("invalid or expired pairing code")))
                else respond(socket, 200, json.encodeToString(response))
            }
            path.startsWith("/api/uploads") -> routeUpload(request, path, socket)
            else -> respond(socket, 404, json.encodeToString(ApiError("not found")))
        }
    }

    private fun routeUpload(request: HttpRequest, path: String, socket: Socket) {
        val carId = PairingStore.authenticate(request.headers["authorization"])
            ?: return respond(socket, 401, json.encodeToString(ApiError("unauthorized")))
        if (request.method == "POST" && path == "/api/uploads") {
            val create = runCatching { json.decodeFromString(UploadCreateRequest.serializer(), request.body.decodeToString()) }.getOrNull()
                ?: return respond(socket, 400, json.encodeToString(ApiError("invalid upload request")))
            val result = PhoneUploadStore.create(create, carId)
                ?: return respond(socket, 400, json.encodeToString(ApiError("upload rejected")))
            _state.value = _state.value.copy(lastMessage = "Receiving ${create.fileName}")
            return respond(socket, 200, json.encodeToString(result))
        }
        val parts = path.removePrefix("/api/uploads/").split('/').filter(String::isNotBlank)
        val uploadId = parts.firstOrNull()?.let { URLDecoder.decode(it, "UTF-8") }
            ?: return respond(socket, 404, json.encodeToString(ApiError("not found")))
        when {
            request.method == "GET" && parts.size == 1 -> {
                val status = PhoneUploadStore.status(uploadId, carId)
                    ?: return respond(socket, 404, json.encodeToString(ApiError("unknown upload")))
                respond(socket, 200, json.encodeToString(status))
            }
            request.method == "PUT" && parts.size == 3 && parts[1] == "chunks" -> {
                val index = parts[2].toIntOrNull()
                if (index == null || !PhoneUploadStore.storeChunk(uploadId, carId, index, request.body)) {
                    respond(socket, 400, json.encodeToString(ApiError("chunk rejected")))
                } else respond(socket, 200, "{\"ok\":true}")
            }
            request.method == "POST" && parts.size == 2 && parts[1] == "complete" -> {
                val complete = runCatching { json.decodeFromString(UploadCompleteRequest.serializer(), request.body.decodeToString()) }.getOrNull()
                    ?: return respond(socket, 400, json.encodeToString(ApiError("invalid complete request")))
                when (val result = PhoneUploadStore.complete(uploadId, carId, complete.sha256, complete.fileName)) {
                    is CommitResult.Success -> {
                        _state.value = _state.value.copy(lastMessage = "Received ${result.response.fileName}")
                        respond(socket, 200, json.encodeToString(result.response))
                    }
                    is CommitResult.Rejected -> respond(socket, result.code, json.encodeToString(ApiError(result.message)))
                }
            }
            request.method == "DELETE" && parts.size == 1 -> {
                val cancelled = PhoneUploadStore.cancel(uploadId, carId)
                if (cancelled) respond(socket, 200, "{\"ok\":true}")
                else respond(socket, 409, json.encodeToString(ApiError("already completed or not owner")))
            }
            else -> respond(socket, 404, json.encodeToString(ApiError("not found")))
        }
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest {
        val header = ByteArrayOutputStream()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)
        while (header.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw IllegalArgumentException("incomplete headers")
            header.write(value)
            matched = if (value.toByte() == marker[matched]) matched + 1 else if (value == 13) 1 else 0
            if (matched == marker.size) break
        }
        if (matched != marker.size) throw IllegalArgumentException("headers too large")
        val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val first = lines.first().split(' ')
        if (first.size < 2) throw IllegalArgumentException("bad request line")
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY_BYTES) throw IllegalArgumentException("body too large")
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) throw IllegalArgumentException("incomplete body")
            offset += count
        }
        return HttpRequest(first[0].uppercase(), first[1], headers, body)
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray()
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }
        socket.getOutputStream().run {
            write("HTTP/1.1 $code $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { iface ->
            iface.inetAddresses.toList().filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress }.mapNotNull { it.hostAddress }
        }.distinct()
    } catch (_: Throwable) { emptyList() }

    private data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
}
