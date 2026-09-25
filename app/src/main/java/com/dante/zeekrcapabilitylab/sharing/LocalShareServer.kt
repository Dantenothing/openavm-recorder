package com.dante.zeekrcapabilitylab.sharing

import java.io.Closeable
import java.io.EOFException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ShareAsset(
    val id: String,
    val name: String,
    val size: Long,
    val etag: String,
    val metadata: Boolean = false,
    val valid: () -> Boolean,
    val open: () -> SeekableByteChannel,
)

data class SharePageLabels(
    val title: String,
    val description: String,
    val download: String,
    val metadata: String,
)

/** Read-only, one-request HTTP connections. No recording/control routes or arbitrary file paths. */
class LocalShareServer(
    bindAddress: InetAddress,
    private val token: String,
    assets: List<ShareAsset>,
    private val labels: SharePageLabels,
    private val limits: ShareLimits = ShareLimits(),
    private val nowMs: () -> Long,
    private val environmentValid: () -> Boolean,
    private val onClosed: () -> Unit,
) : Closeable {
    private class Connection(val socket: Socket, now: Long) {
        val acceptedAt = now
        @Volatile var lastProgress = now
        @Volatile var headersRead = false
    }
    private val assets = assets.associateBy { it.id }
    private val active = AtomicBoolean(true)
    private val settled = AtomicBoolean(false)
    private val admission = Any()
    private val connections = ConcurrentHashMap<Socket, Connection>()
    private val downloads = Semaphore(limits.maxDownloads)
    private val workers = ThreadPoolExecutor(limits.maxConnections, limits.maxConnections, 0,
        TimeUnit.MILLISECONDS, SynchronousQueue(), { runnable ->
            Thread(runnable, "openavm-share-reader").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())
    private val clock = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "openavm-share-expiry").apply { isDaemon = true }
    }
    private val startedAt = nowMs()
    private val listener = ServerSocket()
    val port: Int get() = listener.localPort
    private lateinit var authority: String
    val url: String get() = "http://$authority/s/$token/"
    val isActive: Boolean get() = active.get()
    val isSettled: Boolean get() = settled.get()
    val connectionCount: Int get() = connections.size
    val expiresAtMs: Long get() = startedAt + limits.ttlMs

    init {
        require(token.matches(Regex("[A-Za-z0-9_-]{22,64}")))
        require(assets.size == this.assets.size && assets.size <= limits.maxFiles * 2)
        require(limits.accepts(assets.filterNot { it.metadata }.map { it.size }))
        require(assets.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.size >= 0 &&
            it.etag.matches(Regex("(?:W/)?\"[A-Za-z0-9_-]{1,128}\"")) })
        try {
            listener.reuseAddress = false
            listener.bind(java.net.InetSocketAddress(bindAddress, 0), limits.maxConnections)
            authority = "${bindAddress.hostAddress}:$port"
            // No catch-up burst when Android resumes a cached/sleeping process.
            clock.scheduleWithFixedDelay(::checkExpiry, 250, 250, TimeUnit.MILLISECONDS)
            Thread(::acceptLoop, "openavm-share-listener").apply { isDaemon = true; start() }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun usable(): Boolean = active.get() && nowMs() - startedAt in 0 until limits.ttlMs &&
        runCatching(environmentValid).getOrDefault(false)

    private fun checkExpiry() {
        if (!usable()) { close(); return }
        val now = nowMs()
        connections.values.forEach {
            if ((!it.headersRead && now - it.acceptedAt >= limits.headerTimeoutMs) ||
                now - it.lastProgress >= limits.idleTimeoutMs) runCatching { it.socket.close() }
        }
    }

    private fun acceptLoop() {
        try {
            while (active.get()) {
                val socket = listener.accept()
                synchronized(admission) {
                    if (!usable() || connections.size >= limits.maxConnections) {
                        socket.close()
                    } else {
                        val connection = Connection(socket, nowMs())
                        connections[socket] = connection
                        try { workers.execute { serve(connection) } }
                        catch (_: java.util.concurrent.RejectedExecutionException) {
                            connections.remove(socket); socket.close()
                        }
                    }
                }
            }
        } catch (_: java.io.IOException) {
            // Listener shutdown is also the cancellation mechanism.
        } finally { close() }
    }

    private fun serve(connection: Connection) {
        val socket = connection.socket
        var responseStarted = false
        var downloadHeld = false
        try {
            socket.soTimeout = limits.headerTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val request = ShareHttpRequest.read(socket.getInputStream().buffered(4096))
            connection.headersRead = true
            if (!usable()) throw ShareHttpError(410)
            if (request.headers["host"] != authority) throw ShareHttpError(403)
            val prefix = "/s/$token/"
            if (!request.target.startsWith(prefix)) throw ShareHttpError(404)
            val key = request.target.removePrefix(prefix)
            val output = socket.getOutputStream()
            if (key.isEmpty()) {
                val body = page().toByteArray(Charsets.UTF_8)
                responseStarted = true
                headers(output, 200, body.size.toLong(), "text/html; charset=utf-8")
                if (!request.headOnly) output.write(body)
            } else {
                val asset = assets[key] ?: throw ShareHttpError(404)
                if (!runCatching(asset.valid).getOrDefault(false)) throw ShareHttpError(409)
                val range = if (request.headOnly) ByteRange.Full(asset.size) else
                    ByteRanges.resolve(request.headers["range"], asset.size, request.headers["if-range"], asset.etag)
                if (range is ByteRange.Unsatisfiable) {
                    responseStarted = true
                    headers(output, 416, 0, "text/plain", mapOf("Content-Range" to "bytes */${asset.size}"))
                } else {
                    val start = (range as? ByteRange.Partial)?.start ?: 0L
                    val count = (range as? ByteRange.Partial)?.length ?: asset.size
                    val extra = linkedMapOf("Accept-Ranges" to "bytes", "ETag" to asset.etag,
                        "Content-Disposition" to disposition(asset.name))
                    if (range is ByteRange.Partial) extra["Content-Range"] = "bytes ${range.start}-${range.end}/${asset.size}"
                    val status = if (range is ByteRange.Partial) 206 else 200
                    val type = if (asset.metadata) "application/json; charset=utf-8" else "video/mp4"
                    if (request.headOnly) {
                        responseStarted = true
                        headers(output, status, count, type, extra)
                    } else {
                        if (!downloads.tryAcquire()) throw ShareHttpError(503)
                        downloadHeld = true
                        // The opener validates identity under the source's deletion/lease protocol.
                        asset.open().use { input ->
                            if (!usable() || !asset.valid() || input.size() != asset.size) throw ShareHttpError(409)
                            input.position(start)
                            responseStarted = true
                            headers(output, status, count, type, extra)
                            val bytes = ByteArray(64 * 1024)
                            var remaining = count
                            var checkedAt = nowMs()
                            while (remaining > 0) {
                                if (!usable() || socket.isClosed) throw java.io.IOException("Share ended")
                                val now = nowMs()
                                if (now - checkedAt >= 1000) {
                                    if (!asset.valid()) throw java.io.IOException("Source changed")
                                    checkedAt = now
                                }
                                val read = input.read(ByteBuffer.wrap(bytes, 0, minOf(bytes.size.toLong(), remaining).toInt()))
                                if (read <= 0) throw EOFException("Incomplete source")
                                output.write(bytes, 0, read)
                                remaining -= read
                                connection.lastProgress = nowMs()
                            }
                        }
                    }
                }
            }
            output.flush()
        } catch (error: Exception) {
            if (!responseStarted && !socket.isClosed) runCatching {
                headers(socket.getOutputStream(), (error as? ShareHttpError)?.status ?: 409, 0, "text/plain")
            }
        } finally {
            if (downloadHeld) downloads.release()
            runCatching { socket.close() }
            connections.remove(socket)
            settleIfClosed()
        }
    }

    private fun page(): String = buildString {
        append("<!doctype html><html dir='auto'><meta name='viewport' content='width=device-width,initial-scale=1'><meta charset='utf-8'><title>OpenAVM</title>")
        append("<style>body{background:#10151d;color:#f0f4fa;font:16px system-ui;max-width:760px;margin:36px auto;padding:0 20px}h1{font-size:30px}p{color:#b5c2d5;line-height:1.6}article{background:#1d2837;border-radius:14px;padding:18px;margin:14px 0;overflow-wrap:anywhere}a{display:inline-block;background:#8ed7e6;color:#102028;padding:10px 16px;border-radius:9px;text-decoration:none;margin:10px 8px 0 0}small{color:#b5c2d5}</style>")
        append("<h1>${html(labels.title)}</h1><p>${html(labels.description)}</p>")
        assets.values.forEach { asset ->
            append("<article><div>${html(asset.name)}</div><small>${asset.size / 1024} KiB</small><br>")
            append("<a href='./${asset.id}'>${html(if (asset.metadata) labels.metadata else labels.download)}</a></article>")
        }
        append("</html>")
    }

    private fun headers(out: OutputStream, status: Int, size: Long, type: String, extra: Map<String, String> = emptyMap()) {
        val reason = when (status) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 410 -> "Gone"; 416 -> "Range Not Satisfiable"
            else -> "Service Unavailable" }
        val text = buildString {
            append("HTTP/1.1 $status $reason\r\nContent-Length: $size\r\nContent-Type: $type\r\nConnection: close\r\n")
            append("Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'\r\n")
            if (status == 405) append("Allow: GET, HEAD\r\n")
            extra.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        synchronized(admission) {
            if (active.getAndSet(false)) {
                runCatching { listener.close() }
                connections.keys.forEach { runCatching { it.close() } }
                workers.shutdown()
                clock.shutdown()
            }
        }
        settleIfClosed()
    }

    private fun settleIfClosed() {
        synchronized(admission) {
            // A blocked source reader keeps its lease and prevents another share session.
            if (!active.get() && connections.isEmpty() && settled.compareAndSet(false, true)) onClosed()
        }
    }

    companion object {
        private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        private fun disposition(name: String): String {
            val safe = name.take(200).filter { it != '\r' && it != '\n' && it != '/' && it != '\\' }
            val ascii = safe.map { if (it in ' '..'~' && it != '"' && it != ';') it else '_' }.joinToString("")
            val encoded = URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }
    }
}
