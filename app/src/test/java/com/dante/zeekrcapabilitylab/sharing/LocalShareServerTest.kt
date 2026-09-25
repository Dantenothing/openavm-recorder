package com.dante.zeekrcapabilitylab.sharing

import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class LocalShareServerTest {
    private val token = "0123456789abcdefghijklmnop"
    private val labels = SharePageLabels("Downloads", "Keep this page open", "MP4", "JSON")
    private fun server(asset: ShareAsset = asset(), time: () -> Long = { System.nanoTime() / 1_000_000 },
                       environment: () -> Boolean = { true }, closed: () -> Unit = {}): LocalShareServer =
        LocalShareServer(InetAddress.getByName("127.0.0.1"), token, listOf(asset), labels,
            nowMs = time, environmentValid = environment, onClosed = closed)

    private fun asset(size: Long = 10, valid: () -> Boolean = { true },
                      open: () -> SeekableByteChannel = { Bytes(size) }) =
        ShareAsset("video1", "<test>录像.mp4", size, "\"immutable-version1\"", valid = valid, open = open)

    private class Bytes(private val length: Long) : SeekableByteChannel {
        private var at = 0L
        private var opened = true
        override fun read(dst: ByteBuffer): Int {
            if (at >= length) return -1
            val count = minOf(dst.remaining().toLong(), length - at).toInt()
            repeat(count) { dst.put(('0'.code + (at++ % 10).toInt()).toByte()) }
            return count
        }
        override fun position() = at
        override fun position(newPosition: Long): SeekableByteChannel { at = newPosition; return this }
        override fun size() = length
        override fun isOpen() = opened
        override fun close() { opened = false }
        override fun write(src: ByteBuffer): Int = error("Read only")
        override fun truncate(size: Long): SeekableByteChannel = error("Read only")
    }

    private data class Reply(val status: Int, val header: String, val body: String)
    private fun request(server: LocalShareServer, path: String = "/s/$token/video1", method: String = "GET",
                        extra: String = "", host: String = "127.0.0.1:${server.port}"): Reply =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: $host\r\n$extra\r\n".toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val parts = response.split("\r\n\r\n", limit = 2)
            Reply(parts.first().split(' ')[1].toInt(), parts.first(), parts.getOrElse(1) { "" })
        }

    @Test fun getHeadSuffixAndIfRangeUseTheSameRepresentation() {
        server().use { server ->
            assertEquals("0123456789", request(server).body)
            val head = request(server, method = "HEAD", extra = "Range: bytes=4-6\r\n")
            assertEquals(200, head.status)
            assertEquals("", head.body)
            assertTrue(head.header.contains("Content-Length: 10\r\n"))
            val suffix = request(server, extra = "Range: bytes=-3\r\nIf-Range: \"immutable-version1\"\r\n")
            assertEquals(206, suffix.status)
            assertEquals("789", suffix.body)
            assertTrue(suffix.header.contains("Content-Range: bytes 7-9/10"))
            val changed = request(server, extra = "Range: bytes=4-6\r\nIf-Range: \"old\"\r\n")
            assertEquals(200, changed.status)
            assertEquals("0123456789", changed.body)
            assertEquals(416, request(server, extra = "Range: bytes=10-\r\n").status)
        }
    }

    @Test fun rangesAboveFourGiBAreStreamedWithoutAllocatingTheFile() {
        server(asset(5L * 1024 * 1024 * 1024)).use { server ->
            val reply = request(server, extra = "Range: bytes=4294967296-4294967301\r\n")
            assertEquals(206, reply.status)
            assertEquals("678901", reply.body)
            assertTrue(reply.header.contains("Content-Length: 6\r\n"))
        }
    }

    @Test fun pathsTokensHostAndMethodsDoNotExposeOtherResources() {
        server().use { server ->
            assertEquals(404, request(server, "/s/wrong/video1").status)
            assertEquals(404, request(server, "/s/$token/../video1").status)
            assertEquals(404, request(server, "/s/$token/%2e%2e/video1").status)
            assertEquals(403, request(server, host = "attacker.example").status)
            assertEquals(405, request(server, method = "POST").status)
            assertEquals(400, request(server, extra = "Host: localhost\r\n").status)
            val page = request(server, "/s/$token/")
            assertTrue(page.body.contains("&lt;test&gt;"))
            assertFalse(page.body.contains("<test>"))
            assertTrue(page.header.contains("Referrer-Policy: no-referrer"))
        }
    }

    @Test fun changedSourceIsRejectedBeforeOpening() {
        val opened = AtomicBoolean()
        server(asset(valid = { false }, open = { opened.set(true); Bytes(10) })).use { server ->
            assertEquals(409, request(server).status)
            assertFalse(opened.get())
        }
    }

    @Test fun twoBlockedDownloadsCannotAdmitAThirdFileReader() {
        val reading = CountDownLatch(2)
        val unblock = CountDownLatch(1)
        val settled = CountDownLatch(1)
        val server = server(asset(open = {
            object : SeekableByteChannel by Bytes(10) {
                override fun read(dst: ByteBuffer): Int {
                    reading.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                    return -1
                }
            }
        }), closed = settled::countDown)
        val sockets = mutableListOf<Socket>()
        try {
            repeat(2) {
                val socket = Socket("127.0.0.1", server.port)
                sockets += socket
                socket.getOutputStream().write("GET /s/$token/video1 HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n".toByteArray())
            }
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            assertEquals(503, request(server).status)
            assertEquals(200, request(server, method = "HEAD").status)
        } finally { server.close(); unblock.countDown(); sockets.forEach { it.close() } }
        assertTrue(settled.await(3, TimeUnit.SECONDS))
    }

    @Test fun slowHeadersCannotConsumeConnectionsIndefinitely() {
        val time = AtomicLong(100)
        server(time = time::get).use { server ->
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /".toByteArray())
                val deadline = System.nanoTime() + 2_000_000_000
                while (server.connectionCount == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(1, server.connectionCount)
                time.set(10_100)
                assertEquals(-1, socket.getInputStream().read())
                assertTrue(server.isActive)
            }
        }
    }

    @Test fun sleepTimeExpiryAndNetworkChangeCloseTheSession() {
        val time = AtomicLong(100)
        val closed = CountDownLatch(1)
        val server = server(time = time::get, closed = closed::countDown)
        try {
            assertEquals(200, request(server).status)
            time.set(100 + 15 * 60_000)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertFalse(server.isActive)
        } finally { server.close() }
        val network = AtomicBoolean(true)
        val changed = CountDownLatch(1)
        server(environment = network::get, closed = changed::countDown).use {
            network.set(false)
            assertTrue(changed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun cancelledBlockedReaderRetainsLeaseUntilChannelActuallyCloses() {
        val reading = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val channelClosed = AtomicBoolean(false)
        val server = server(asset(open = {
            object : SeekableByteChannel by Bytes(10) {
                override fun read(dst: ByteBuffer): Int {
                    reading.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                    return -1
                }
                override fun close() { channelClosed.set(true) }
            }
        }), closed = closed::countDown)
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.getOutputStream().write("GET /s/$token/video1 HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n".toByteArray())
                assertTrue(reading.await(3, TimeUnit.SECONDS))
                server.close()
                assertFalse(server.isActive)
                assertFalse(server.isSettled)
                assertEquals(1, server.connectionCount)
                assertEquals(1, closed.count)
                unblock.countDown()
                assertTrue(closed.await(3, TimeUnit.SECONDS))
                assertTrue(channelClosed.get())
                assertTrue(server.isSettled)
            }
        } finally { unblock.countDown(); server.close() }
    }
}
