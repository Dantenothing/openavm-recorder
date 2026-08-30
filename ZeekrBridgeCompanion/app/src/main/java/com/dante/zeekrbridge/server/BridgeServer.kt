package com.dante.zeekrbridge.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.HttpRange
import com.dante.zeekrbridge.core.LanEndpointCandidate
import com.dante.zeekrbridge.core.LanEndpointPolicy
import com.dante.zeekrbridge.core.HttpRangeParser
import com.dante.zeekrbridge.core.OutboundOfferStore
import com.dante.zeekrbridge.core.Protocol
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.ReliableCommitResult
import com.dante.zeekrbridge.core.ReliableUploadStore
import com.dante.zeekrbridge.core.SecureCompare
import com.dante.zeekrbridge.core.ServerLog
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class BridgeServerState(
    val running: Boolean = false,
    val ip: String = "",
    val port: Int = Protocol.PORT,
    val requestCount: Int = 0,
    val lastClientIp: String? = null,
    val lastDiscoveryRequest: Long? = null,
    val lastDiscoveryReply: Long? = null,
    val connectedCars: Int = 0,
    val lastUpload: String? = null,
    val lastUploadSpeedBps: Long = 0,
    val endpointCandidates: List<LanEndpointCandidate> = emptyList(),
)

object BridgeServer {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(BridgeServerState())
    val state: StateFlow<BridgeServerState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var acceptThread: Thread? = null
    private var udpThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var context: Context? = null
    private var lastChunkAtMs = 0L

    private val wsConnections = ConcurrentHashMap.newKeySet<WsConnection>()

    fun start(context: Context) {
        if (_state.value.running) return
        this.context = context.applicationContext
        ReliableUploadStore.init(context.applicationContext)
        OutboundOfferStore.init(context.applicationContext)
        val endpoints = findIpv4Candidates()
        val ip = endpoints.firstOrNull()?.ipv4.orEmpty()
        serverSocket = try {
            ServerSocket(Protocol.PORT)
        } catch (t: Throwable) {
            ServerLog.log("TCP_SERVER_START_FAILED ${t.message}")
            return
        }
        udpSocket = try {
            DatagramSocket(Protocol.UDP_PORT)
        } catch (t: Throwable) {
            ServerLog.log("UDP_SOCKET_START_FAILED ${t.message}")
            null
        }
        _state.value = _state.value.copy(
            running = true,
            ip = ip,
            port = Protocol.PORT,
            endpointCandidates = endpoints,
        )
        PairingManager.newPairingCode()

        acceptThread = Thread {
            val ss = serverSocket
            while (ss != null && !ss.isClosed) {
                val socket = try {
                    ss.accept()
                } catch (t: Throwable) {
                    break
                }
                Thread { handleSocket(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }

        udpThread = Thread {
            val udp = udpSocket
            while (udp != null && !udp.isClosed) {
                try {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    udp.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text == Protocol.DISCOVERY_REQUEST || text == TransferProtocol.DISCOVERY_REQUEST) {
                        refreshAddresses()
                        _state.value = _state.value.copy(lastDiscoveryRequest = System.currentTimeMillis())
                        ServerLog.log("DISCOVERY_RECEIVED from ${packet.address?.hostAddress}")
                        val replyText = if (text == TransferProtocol.DISCOVERY_REQUEST) {
                            val reply = io.github.dantenothing.avmtransfer.protocol.DiscoveryReply(
                                deviceName = android.os.Build.MODEL,
                                ip = _state.value.ip,
                            )
                            json.encodeToString(io.github.dantenothing.avmtransfer.protocol.DiscoveryReply.serializer(), reply)
                        } else {
                            val reply = com.dante.zeekrbridge.core.DiscoveryReply(
                                service = Protocol.SERVICE_NAME,
                                version = Protocol.VERSION,
                                deviceName = android.os.Build.MODEL,
                                ip = _state.value.ip,
                                port = Protocol.PORT,
                                pairingId = PairingManager.currentCode(),
                            )
                            json.encodeToString(com.dante.zeekrbridge.core.DiscoveryReply.serializer(), reply)
                        }
                        val bytes = replyText.toByteArray(Charsets.UTF_8)
                        udp.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                        _state.value = _state.value.copy(lastDiscoveryReply = System.currentTimeMillis())
                    }
                } catch (t: Throwable) {
                    if (!udp.isClosed) ServerLog.log("UDP_ERROR ${t.message}")
                }
            }
        }.apply { isDaemon = true; start() }

        registerNsd(context)
        ServerLog.log("SERVER_STARTED ip=$ip port=${Protocol.PORT} code=${PairingManager.currentCode()}")
    }

    fun stop(context: Context) {
        try {
            serverSocket?.close()
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            udpSocket?.close()
        } catch (t: Throwable) {
            // Ignore.
        }
        serverSocket = null
        udpSocket = null
        wsConnections.forEach { it.close() }
        wsConnections.clear()
        unregisterNsd(context)
        _state.value = _state.value.copy(running = false, connectedCars = 0)
        ServerLog.log("SERVER_STOPPED")
    }

    fun sendToCars(type: String, payload: Map<String, String>) {
        val envelope = com.dante.zeekrbridge.core.WsEnvelope(
            type = type,
            sequence = System.currentTimeMillis(),
            payload = JsonObject(payload.mapValues { JsonPrimitive(it.value) }),
        )
        val text = json.encodeToString(com.dante.zeekrbridge.core.WsEnvelope.serializer(), envelope)
        wsConnections.forEach { it.sendText(text) }
    }

    fun connectedCars(): Int = wsConnections.size

    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            _state.value = _state.value.copy(
                requestCount = _state.value.requestCount + 1,
                lastClientIp = socket.inetAddress?.hostAddress,
            )
            val input = socket.getInputStream()
            val headerBlock = when (val read = readHeaderBlock(input)) {
                HeaderRead.Eof -> return
                HeaderRead.TooLarge -> {
                    respond(socket.getOutputStream(), 413, "application/json", """{"error":"header too large"}""")
                    return
                }
                is HeaderRead.Block -> read.bytes
            }
            val headerText = headerBlock.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = LinkedHashMap<String, String>()
            lines.drop(1).forEach { line ->
                if (line.isNotBlank()) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            if (target == "/control" && headers["upgrade"]?.equals("websocket", true) == true) {
                val ws = WsConnection(socket, headers)
                if (ws.handshake()) {
                    wsConnections += ws
                    _state.value = _state.value.copy(connectedCars = wsConnections.size)
                    com.dante.zeekrbridge.core.CarCatalogStore.setOnline(true)
                    ws.run()
                }
                wsConnections.remove(ws)
                _state.value = _state.value.copy(connectedCars = wsConnections.size)
                com.dante.zeekrbridge.core.CarCatalogStore.setOnline(wsConnections.isNotEmpty())
                return
            }
            val contentLength = headers["content-length"]?.toLongOrNull()
            if (contentLength != null && !HttpLimits.bodyAllowed(contentLength)) {
                respond(socket.getOutputStream(), 413, "application/json", """{"error":"body too large"}""")
                return
            }
            val body = if (contentLength != null && contentLength > 0) {
                if (contentLength > Int.MAX_VALUE) {
                    respond(socket.getOutputStream(), 413, "application/json", """{"error":"body too large"}""")
                    return
                }
                HttpBodyReader.readExact(input, contentLength.toInt())
                    ?: run {
                        respond(socket.getOutputStream(), 400, "application/json", """{"error":"invalid body length"}""")
                        return
                    }
            } else {
                ByteArray(0)
            }
            handleHttp(socket.getOutputStream(), method, target, headers, body)
        } catch (t: Throwable) {
            ServerLog.log("SOCKET_ERROR ${t.message}")
        } finally {
            try {
                socket.close()
            } catch (t: Throwable) {
                // Ignore.
            }
        }
    }

    private sealed class HeaderRead {
        object Eof : HeaderRead()
        object TooLarge : HeaderRead()
        data class Block(val bytes: ByteArray) : HeaderRead()
    }

    private fun readHeaderBlock(input: InputStream): HeaderRead {
        val buffer = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val b = input.read()
            if (b == -1) return HeaderRead.Eof
            buffer.write(b)
            if (buffer.size() > HttpLimits.MAX_HEADER_BYTES) return HeaderRead.TooLarge
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
        }
        return HeaderRead.Block(buffer.toByteArray())
    }

    private fun handleHttp(out: OutputStream, method: String, target: String, headers: Map<String, String>, body: ByteArray) {
        try {
            val path = target.substringBefore('?')
            val auth = headers["authorization"]
            when {
                method == "GET" && path == "/health" -> {
                    if (headers[TransferProtocol.HTTP_HEADER.lowercase()] == TransferProtocol.HTTP_HEADER_VALUE) {
                        val response = io.github.dantenothing.avmtransfer.protocol.HealthResponse(
                            deviceName = android.os.Build.MODEL,
                            phoneDeviceId = PairingManager.phoneDeviceId.value,
                        )
                        respond(
                            out,
                            200,
                            "application/json",
                            json.encodeToString(io.github.dantenothing.avmtransfer.protocol.HealthResponse.serializer(), response),
                        )
                    } else {
                        respond(out, 200, "application/json", """{"status":"OK","service":"${Protocol.SERVICE_NAME}"}""")
                    }
                }
                method == "GET" && path.startsWith("/api/outbound/") -> {
                    handleOutboundGet(out, path, headers)
                }
                method == "POST" && path == "/api/pair" -> {
                    val request = json.decodeFromString(com.dante.zeekrbridge.core.PairRequest.serializer(), String(body, Charsets.UTF_8))
                    val response = PairingManager.pair(request)
                    if (response == null) respond(out, 401, "application/json", """{"error":"invalid code"}""")
                    else respond(out, 200, "application/json", json.encodeToString(com.dante.zeekrbridge.core.PairResponse.serializer(), response))
                }
                path == "/api/uploads" && method == "POST" -> {
                    val device = PairingManager.authenticate(auth)
                    if (device == null) {
                        respond(out, 401, "application/json", """{"error":"unauthorized"}""")
                        return
                    }
                    val adapted = UploadRequestAdapter.decode(
                        json,
                        String(body, Charsets.UTF_8),
                        device.carDeviceId,
                    )
                    val request = adapted.request
                    lastChunkAtMs = 0L
                    val session = ReliableUploadStore.create(request, device.carDeviceId)
                    if (session == null) {
                        respond(out, 400, "application/json", """{"error":"invalid upload"}""")
                        return
                    }
                    ServerLog.log(
                        "UPLOAD_CREATED id=${session.uploadId.take(8)} file=${request.fileName} " +
                            "size=${request.sizeBytes} chunks=${session.totalChunks}",
                    )
                    respond(
                        out,
                        200,
                        "application/json",
                        json.encodeToString(io.github.dantenothing.avmtransfer.protocol.UploadCreateResponse.serializer(), session),
                    )
                }
                path.startsWith("/api/uploads/") -> handleUpload(out, method, path, auth, body)
                else -> respond(out, 404, "text/plain", "not found")
            }
        } catch (t: Throwable) {
            ServerLog.log("HTTP_ERROR ${t.message}")
            respond(out, 500, "application/json", """{"error":"${t.message ?: "server error"}"}""")
        }
    }

    private fun handleOutboundGet(out: OutputStream, path: String, headers: Map<String, String>) {
        val device = PairingManager.authenticate(headers["authorization"])
        if (device == null) {
            respond(out, 401, "application/json", """{"error":"unauthorized"}""")
            return
        }
        val offerId = URLDecoder.decode(path.removePrefix("/api/outbound/").substringBefore('/'), "UTF-8")
        val offer = OutboundOfferStore.get(offerId)
        val file = offer?.let { OutboundOfferStore.fileFor(offerId) }
        if (offer == null || file == null) {
            respond(out, 404, "application/json", """{"error":"unknown offer"}""")
            return
        }
        if (file.length() != offer.sizeBytes) {
            ServerLog.log("OUTBOUND_PAYLOAD_MISMATCH id=${offerId.take(8)} declared=${offer.sizeBytes} actual=${file.length()}")
            respond(out, 500, "application/json", """{"error":"payload mismatch"}""")
            return
        }
        val range = HttpRangeParser.parse(headers["range"], file.length())
        streamFile(out, file, range)
        _state.value = _state.value.copy(lastUpload = "outbound:${offer.fileName}")
        ServerLog.log("OUTBOUND_SERVED id=${offerId.take(8)} file=${offer.fileName} range=$range")
    }

    private fun streamFile(out: OutputStream, file: File, range: HttpRange) {
        val size = file.length()
        when (range) {
            is HttpRange.Full -> {
                writeStatus(out, 200, "OK")
                writeHeader(out, "Content-Type", "audio/wav")
                writeHeader(out, "Accept-Ranges", "bytes")
                writeHeader(out, "Content-Length", size.toString())
                out.write("\r\n".toByteArray())
                copyRange(out, file, 0, size)
            }
            is HttpRange.Partial -> {
                val length = range.end - range.start + 1
                writeStatus(out, 206, "Partial Content")
                writeHeader(out, "Content-Type", "audio/wav")
                writeHeader(out, "Accept-Ranges", "bytes")
                writeHeader(out, "Content-Length", length.toString())
                writeHeader(out, "Content-Range", "bytes ${range.start}-${range.end}/$size")
                out.write("\r\n".toByteArray())
                copyRange(out, file, range.start, length)
            }
            is HttpRange.Unsatisfiable -> {
                writeStatus(out, 416, "Range Not Satisfiable")
                writeHeader(out, "Content-Range", "bytes */$size")
                writeHeader(out, "Content-Length", "0")
                out.write("\r\n".toByteArray())
                out.flush()
            }
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, status: String) {
        out.write("HTTP/1.1 $code $status\r\n".toByteArray())
    }

    private fun writeHeader(out: OutputStream, name: String, value: String) {
        out.write("$name: $value\r\n".toByteArray())
    }

    private fun copyRange(out: OutputStream, file: File, start: Long, length: Long) {
        FileInputStream(file).use { input ->
            if (start > 0) {
                var remaining = start
                val skipBuf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val read = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    remaining -= read
                }
            }
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.flush()
        }
    }

    private fun handleUpload(out: OutputStream, method: String, path: String, auth: String?, body: ByteArray) {
        val device = PairingManager.authenticate(auth)
        if (device == null) {
            respond(out, 401, "application/json", """{"error":"unauthorized"}""")
            return
        }
        val parts = path.removePrefix("/api/uploads/").split("/")
        val uploadId = URLDecoder.decode(parts[0], "UTF-8")
        val status = ReliableUploadStore.status(uploadId, device.carDeviceId)
        if (status == null) {
            respond(out, 404, "application/json", """{"error":"unknown upload"}""")
            return
        }
        val legacy = ReliableUploadStore.isLegacy(uploadId, device.carDeviceId) == true
        when {
            method == "GET" && parts.size == 1 -> {
                respond(
                    out,
                    200,
                    "application/json",
                    json.encodeToString(io.github.dantenothing.avmtransfer.protocol.UploadStatusResponse.serializer(), status),
                )
            }
            method == "PUT" && parts.size == 3 && parts[1] == "chunks" -> {
                val index = parts[2].toIntOrNull() ?: run {
                    respond(out, 400, "application/json", """{"error":"bad chunk index"}""")
                    return
                }
                if (!ReliableUploadStore.storeChunk(uploadId, device.carDeviceId, index, body)) {
                    respond(out, 400, "application/json", """{"error":"chunk rejected"}""")
                    return
                }
                val now = System.currentTimeMillis()
                val speed = if (lastChunkAtMs > 0) {
                    body.size * 1000L / (now - lastChunkAtMs).coerceAtLeast(1L)
                } else {
                    0L
                }
                lastChunkAtMs = now
                _state.value = _state.value.copy(lastUploadSpeedBps = speed)
                _state.value = _state.value.copy(lastUpload = "chunk-$index upload=$uploadId")
                ServerLog.log("CHUNK_RECEIVED id=${uploadId.take(8)} index=$index bytes=${body.size}")
                respond(out, 200, "application/json", """{"ok":true}""")
            }
            method == "POST" && parts.size == 2 && parts[1] == "complete" -> {
                val request = json.decodeFromString(
                    io.github.dantenothing.avmtransfer.protocol.UploadCompleteRequest.serializer(),
                    String(body, Charsets.UTF_8),
                )
                when (
                    val outcome = ReliableUploadStore.complete(
                        uploadId,
                        device.carDeviceId,
                        request.sha256,
                        request.fileName,
                    )
                ) {
                    is ReliableCommitResult.Rejected -> {
                        respond(out, outcome.code, "application/json", """{"error":"${outcome.message}"}""")
                    }
                    is ReliableCommitResult.Success -> {
                        ReceivedStore.refresh()
                        ServerLog.log("UPLOAD_COMPLETED file=${outcome.response.fileName} ok=true")
                        _state.value = _state.value.copy(lastUpload = outcome.response.fileName)
                        val responseText = if (legacy) {
                            val file = File(ReceivedStore.receivedDir(), outcome.response.fileName)
                            val response = com.dante.zeekrbridge.core.UploadCompleteResponse(
                                uploadId = uploadId,
                                ok = true,
                                sha256 = outcome.response.sha256,
                                path = file.absolutePath,
                            )
                            json.encodeToString(com.dante.zeekrbridge.core.UploadCompleteResponse.serializer(), response)
                        } else {
                            json.encodeToString(
                                io.github.dantenothing.avmtransfer.protocol.UploadCompleteResponse.serializer(),
                                outcome.response,
                            )
                        }
                        respond(out, 200, "application/json", responseText)
                    }
                }
            }
            method == "DELETE" && parts.size == 1 -> {
                val cancelled = ReliableUploadStore.cancel(uploadId, device.carDeviceId)
                if (cancelled) {
                    respond(out, 200, "application/json", """{"ok":true}""")
                } else {
                    respond(out, 409, "application/json", """{"error":"already completed or not owner"}""")
                }
            }
            else -> respond(out, 404, "text/plain", "not found")
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            410 -> "Gone"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }

    private fun registerNsd(context: Context) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "ZeekrBridge-${android.os.Build.MODEL}"
                serviceType = Protocol.NSD_TYPE
                port = Protocol.PORT
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    ServerLog.log("NSD_REGISTERED ${serviceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    ServerLog.log("NSD_REGISTER_FAILED code=$errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    ServerLog.log("NSD_UNREGISTERED")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    ServerLog.log("NSD_UNREGISTER_FAILED code=$errorCode")
                }
            }
            nsdRegistration = listener
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            ServerLog.log("NSD_REGISTER_ERROR ${t.message}")
        }
    }

    private fun unregisterNsd(context: Context) {
        try {
            nsdManager?.unregisterService(nsdRegistration)
        } catch (t: Throwable) {
            // Ignore.
        }
        nsdManager = null
        nsdRegistration = null
    }

    fun refreshAddresses() {
        val endpoints = findIpv4Candidates()
        _state.value = _state.value.copy(
            ip = endpoints.firstOrNull()?.ipv4.orEmpty(),
            endpointCandidates = endpoints,
        )
    }

    private fun findIpv4Candidates(): List<LanEndpointCandidate> =
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { address ->
                            address.hostAddress?.let {
                                LanEndpointCandidate(it, iface.name.orEmpty())
                            }
                        }
                }
                .let(LanEndpointPolicy::ordered)
        } catch (t: Throwable) {
            emptyList()
        }

    fun storageUsed(): Long =
        try {
            ReceivedStore.receivedDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (t: Throwable) {
            0L
        }
}

class WsConnection(
    private val socket: Socket,
    private val headers: Map<String, String>,
) {
    private val out = socket.getOutputStream()
    private val writer = WsFrameWriter(out)
    private val json = Json { ignoreUnknownKeys = true }
    private var authenticated = false
    private var device: com.dante.zeekrbridge.core.PairedDevice? = null

    fun handshake(): Boolean {
        val key = headers["sec-websocket-key"] ?: return false
        // Authenticate the Bearer token BEFORE sending 101: an unauthenticated
        // upgrade is rejected as plain HTTP, never as a live WebSocket.
        device = PairingManager.authenticate(headers["authorization"])
        authenticated = device != null
        if (!authenticated) {
            val body = """{"error":"unauthorized"}""".toByteArray()
            writer.write(
                (
                    "HTTP/1.1 401 Unauthorized\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            writer.write(body)
            ServerLog.log("WEBSOCKET_AUTH_FAILED ${socket.inetAddress?.hostAddress}")
            return false
        }
        val accept = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        val acceptB64 = Base64.getEncoder().encodeToString(accept)
        writer.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptB64\r\n\r\n"
                ).toByteArray(),
        )
        ServerLog.log("WEBSOCKET_CONNECTED ${socket.inetAddress?.hostAddress}")
        return true
    }

    fun run() {
        try {
            while (true) {
                val frame = WsCodec.readFrame(socket.getInputStream()) ?: break
                when (frame.opcode) {
                    0x8 -> break
                    0x9 -> writer.write(WsCodec.encodePong(frame.payload))
                    0x1 -> {
                        val text = String(frame.payload, Charsets.UTF_8)
                        try {
                            val envelope = json.decodeFromString(com.dante.zeekrbridge.core.WsEnvelope.serializer(), text)
                            when (envelope.type) {
                                com.dante.zeekrbridge.core.WsType.HELLO -> {
                                    val carId = envelope.payload["carDeviceId"]?.toString()?.trim('"') ?: ""
                                    if (!WsIdentity.matches(device?.carDeviceId, carId)) {
                                        ServerLog.log("WEBSOCKET_HELLO_IDENTITY_MISMATCH expected=${device?.carDeviceId?.take(8)}")
                                        socket.close()
                                        return
                                    }
                                    PairingManager.touch(device!!.carDeviceId)
                                }
                                com.dante.zeekrbridge.core.WsType.HEARTBEAT -> {
                                    // Connection liveness is tracked by socket state.
                                }
                                com.dante.zeekrbridge.core.WsType.CAR_CONTROL -> {
                                    ServerLog.log("CAR_CONTROL ${text.take(200)}")
                                }
                                com.dante.zeekrbridge.core.WsType.RECORDING_CATALOG ->
                                    com.dante.zeekrbridge.core.CarCatalogStore.onCatalog(envelope.payload)
                                com.dante.zeekrbridge.core.WsType.CAR_STATUS_REPLY ->
                                    com.dante.zeekrbridge.core.CarCatalogStore.onCarStatus(envelope.payload)
                                com.dante.zeekrbridge.core.WsType.UPLOAD_QUEUED ->
                                    com.dante.zeekrbridge.core.CarCatalogStore.onUploadQueued(envelope.payload)
                                com.dante.zeekrbridge.core.WsType.RECORDING_DELETED ->
                                    com.dante.zeekrbridge.core.CarCatalogStore.onRecordingDeleted(envelope.payload)
                            }
                        } catch (t: Throwable) {
                            // Ignore non-envelope messages.
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            ServerLog.log("WEBSOCKET_ERROR ${t.message}")
        } finally {
            try {
                writer.write(WsCodec.encodeClose(1000))
            } catch (t: Throwable) {
                // Ignore.
            }
            ServerLog.log("WEBSOCKET_DISCONNECTED")
        }
    }

    fun sendText(text: String) {
        try {
            writer.write(WsCodec.encodeText(text))
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (t: Throwable) {
            // Ignore.
        }
    }
}
