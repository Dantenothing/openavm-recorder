package com.dante.zeekrcapabilitylab.preflight.continuous

import android.opengl.*
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.dante.zeekrcapabilitylab.preflight.continuous.SharedInputFramePool.Reader

/** Diagnostic driver for the source-agnostic OES receiver and ownership pool.
 * Synthetic EGL producer and input are paced on this owner thread. Encoder/display consumers
 * have separate shared contexts and threads. Display targets are offscreen, not real UI windows.
 */
internal class ProbeSharedInputGl(private val layout: StripRepackLayout, private val nonce: Int,
    private val plannedFrames: Int, private val cancelled: () -> Boolean,
    private val onEncoderSubmission: (Long) -> Unit = {}) : ProbeGlProducer {
    private val root = ProbeEglNode()
    private val source = ProbeEglNode(root)
    private val receiver = SharedOesFrameInput(layout.input.width, layout.input.height)
    private val sourceDraw = ProbeGlDraw()
    private val rootDraw = ProbeGlDraw()
    private var generator = 0
    private val pool = SharedInputFramePool()
    private val slots = Array(pool.capacity) { ProbeGlTarget(layout.input.width, layout.input.height) }
    private val writeFences = LongArray(pool.capacity)
    private var geometryTarget: ProbeGlTarget? = null
    private var writing: SharedInputFramePool.Write? = null
    private val failure = AtomicReference<Throwable?>()
    private val encodedSubmissions = AtomicInteger()
    private val activeWorkers = AtomicInteger()
    private val maximumWorkers = AtomicInteger()
    private val readers = ArrayList<Consumer>() // owned by coordinator; wrappers retained even after failed cleanup
    private var encoderWorker: Consumer? = null
    private var displayB: Consumer? = null
    private var detachedB = false
    private var rejoinedB = false
    private var detachedWhileLeased = false
    private var sourceEnded = false
    private var ending = false
    private var publications = 0
    private var maximumReserveWaitMs = 0L
    private var lastSourceTimestamp = 0L
    private var limits: JsonObject = obj()
    private var geometryDifference: JsonObject? = null
    override var producerConfirmed = false; private set
    override var released = false; private set

    override fun initialize() {
        check(layout.input.width.toLong() * layout.input.height * 4 * pool.capacity <= 128L * 1024 * 1024) { "SHARED_POOL_MEMORY_BUDGET" }
        root.initialize()
        val size = IntArray(1); val viewport = IntArray(2)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewport, 0)
        for (raster in listOf(layout.input, layout.encoded)) check(raster.width <= size[0] && raster.height <= size[0] &&
            raster.width <= viewport[0] && raster.height <= viewport[1]) { "SHARED_GL_RASTER_LIMIT" }
        limits = obj("maxTexture" to size[0], "maxViewport" to viewport.toList(), "renderer" to GLES20.glGetString(GLES20.GL_RENDERER),
            "glVersion" to GLES20.glGetString(GLES20.GL_VERSION))
        slots.forEach { it.allocate() }; receiver.initialize()
        source.initialize(receiver.surface); generator = sourceDraw.program(ProbeSyntheticGl.GENERATE)
    }

    private fun sourceAndReceive(frame: Int, timestampNs: Long, pressure: Boolean) {
        checkHealthy()
        source.current(); sourceDraw.source(generator, layout, nonce, frame, pressure)
        ProbeGlDraw.checkGl(); source.swap(timestampNs)
        root.current()
        val deadline = SystemClock.elapsedRealtime() + 500
        while (receiver.acquireLatest() != timestampNs) {
            checkHealthy()
            check(receiver.timestampNs < timestampNs) { "OES_UNEXPECTED_SOURCE_TIMESTAMP" }
            check(SystemClock.elapsedRealtime() < deadline) { "OES_INPUT_TIMEOUT" }; Thread.sleep(1)
        }
        check(timestampNs > lastSourceTimestamp) { "OES_SOURCE_NOT_MONOTONIC" }
        lastSourceTimestamp = timestampNs
    }

    override fun geometry(): JsonObject {
        sourceAndReceive(37, 1_000_000L, false)
        receiver.copyTo(slots[0])
        val target = ProbeGlTarget(layout.encoded.width, layout.encoded.height).also { geometryTarget = it }
        target.allocate()
        val repack = rootDraw.program(ProbeSyntheticGl.REPACK)
        rootDraw.repack(repack, layout, slots[0].texture, target.framebuffer)
        val pixels = ByteBuffer.allocateDirect(layout.encoded.width * layout.encoded.height * 4)
        GLES20.glReadPixels(0, 0, layout.encoded.width, layout.encoded.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        ProbeGlDraw.checkGl()
        val size = layout.encoded
        val tail = layout.input.height - 2 * layout.stripHeight
        val ys = ((0 until size.height step 31) + listOf(0, 7, 8, 15, 23, 24, size.height - 1, tail - 21, tail - 20, tail - 1, tail))
            .filter { it in 0 until size.height }.distinct()
        val cell = ProbeFramePattern.cellWidth(layout.input.width)
        val xs = ((0 until size.width step 29) + listOf(0, layout.input.width - 1, layout.input.width, 2 * layout.input.width - 1,
            2 * layout.input.width, size.width - 1) + (0..2).flatMap { col -> (0 until 56).map { col * layout.input.width + 16 + it * cell + cell / 2 } }).distinct()
        var count = 0
        for (y in ys) for (x in xs) {
            val expected = ProbeFramePattern.rgbAtEncoded(x, y, layout, nonce, 37)
            val offset = ((size.height - 1 - y) * size.width + x) * 4
            for (channel in 0..2) {
                val actual = pixels[offset + channel].toInt() and 255
                val wanted = expected shr ((2 - channel) * 8) and 255
                if (kotlin.math.abs(actual - wanted) > 2) {
                    geometryDifference = obj("x" to x,"y" to y,"channel" to channel,"expected" to wanted,"actual" to actual)
                    error("OES_REPACK_ORACLE_MISMATCH")
                }
            }
            count++
        }
        target.close(); geometryTarget = null
        return obj("status" to "PASS", "samples" to count, "fullRasterReadback" to true,
            "oracle" to "INDEPENDENT_CPU_INTEGER", "paddingAndTail" to "CHECKED", "route" to "EGL_BUFFERQUEUE_OES_MATRIX_RGBA_REPACK",
            "source" to listOf(layout.input.width, layout.input.height), "output" to listOf(size.width, size.height),
            "sourceTransform" to receiver.transformSnapshot(), "limits" to limits)
    }

    override fun attach(surface: Surface) {
        encoderWorker = startReader(Reader.ENCODER, surface, false, false)
        startReader(Reader.DISPLAY_A, null, false, true)
        displayB = startReader(Reader.DISPLAY_B, null, true, false)
    }
    private fun startReader(reader: Reader, surface: Surface?, slow: Boolean, hold: Boolean): Consumer {
        check(!ending && !cancelled()) { "TEST_CANCELLED" }
        val worker = Consumer(reader, surface, slow, hold)
        readers += worker; worker.start()
        check(worker.ready.await(4, TimeUnit.SECONDS)) { "SHARED_READER_START_TIMEOUT" }; checkHealthy()
        return worker
    }
    override fun submit(frame: Int, ptsUs: Long) {
        check(!ending)
        checkHealthy()
        if (!detachedB && frame >= plannedFrames / 2 && displayB?.hasLease == true) {
            detachedWhileLeased = true; detachedB = true
            pool.detach(Reader.DISPLAY_B); displayB?.stopRequested = true
        }
        if (detachedB && !rejoinedB && frame >= plannedFrames * 3 / 4 && displayB?.closed == true && displayB?.isAlive == false) {
            pool.attach(Reader.DISPLAY_B)
            displayB = startReader(Reader.DISPLAY_B, null, false, false); rejoinedB = true
        }
        root.current()
        val start = SystemClock.elapsedRealtime()
        var write = pool.reserve()
        while (write == null) {
            checkHealthy(); check(SystemClock.elapsedRealtime() - start < 500) { "SHARED_POOL_BACKPRESSURE" }
            Thread.sleep(1); write = pool.reserve()
        }
        writing = write
        maximumReserveWaitMs = maxOf(maximumReserveWaitMs, SystemClock.elapsedRealtime() - start)
        if (writeFences[write.slot] != 0L) {
            ProbeGlDraw.waitFence(writeFences[write.slot]); GLES30.glDeleteSync(writeFences[write.slot]); writeFences[write.slot] = 0L
        }
        // Nonzero, monotonic synthetic source clock; encoded PTS remains zero-based.
        sourceAndReceive(frame, (ptsUs + 1_000_000L) * 1000, true)
        receiver.copyTo(slots[write.slot]); writeFences[write.slot] = ProbeGlDraw.fence()
        pool.publish(write, frame, receiver.timestampNs); writing = null; publications++
    }
    private fun checkHealthy() {
        check(!cancelled()) { "TEST_CANCELLED" }
        failure.get()?.let { throw IllegalStateException("SHARED_INPUT_FAILED", it) }
    }

    private inner class Consumer(val reader: Reader, private val surface: Surface?, val slow: Boolean, val holdOnce: Boolean) :
        Thread("p1-shared-${reader.name.lowercase()}") {
        val ready = CountDownLatch(1)
        val node = ProbeEglNode(root)
        val draw = ProbeGlDraw()
        val image = if (reader == Reader.ENCODER) null else ProbeGlTarget(512, 512)
        val marker = if (reader == Reader.ENCODER) null else ProbeGlTarget(168, 1)
        private var lease: SharedInputFramePool.Read? = null
        private var fence = 0L
        @Volatile var stopRequested = false
        @Volatile var closed = false
        @Volatile var hasLease = false
        @Volatile var frames = 0
        @Volatile var holds = 0
        @Volatile var encoderProgressDuringHold = 0
        @Volatile var sourceProgressDuringHold = 0
        @Volatile var readFences = 0
        private val frameIds = ArrayList<Int>()
        private val inputPts = ArrayList<String>()
        private val submitTimes = ArrayList<Long>()
        private val markerBytes = ByteBuffer.allocateDirect(168 * 4)
        override fun run() {
            val currentWorkers = activeWorkers.incrementAndGet()
            maximumWorkers.updateAndGet { maxOf(it, currentWorkers) }
            try {
                node.initialize(surface)
                val shader = draw.program(if (reader == Reader.ENCODER) ProbeSyntheticGl.REPACK else DISPLAY)
                val markerShader = if (reader != Reader.ENCODER) draw.program(MARKERS) else 0
                image?.allocate(); marker?.allocate(); ready.countDown()
                var last = -1
                while ((!stopRequested || reader == Reader.ENCODER && pool.pendingEncoder() > 0) && failure.get() == null) {
                    val read = pool.acquire(reader, last)
                    if (read == null) { if (stopRequested) break; Thread.sleep(1); continue }
                    lease = read; hasLease = true
                    ProbeGlDraw.waitFence(writeFences[read.slot])
                    if (slow) Thread.sleep(120)
                    if (holdOnce && holds == 0 && read.frame >= plannedFrames / 4) {
                        val beforeEncoder = encodedSubmissions.get(); val beforeSource = pool.latestFrame()
                        Thread.sleep(350)
                        encoderProgressDuringHold = encodedSubmissions.get() - beforeEncoder
                        sourceProgressDuringHold = pool.latestFrame() - beforeSource; holds++
                    }
                    val texture = slots[read.slot].texture
                    if (reader == Reader.ENCODER) {
                        draw.repack(shader, layout, texture, 0); ProbeGlDraw.checkGl()
                        node.swap(read.timestampNs - 1_000_000_000L)
                        onEncoderSubmission((read.timestampNs - 1_000_000_000L)/1000)
                        encodedSubmissions.incrementAndGet()
                    } else {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, checkNotNull(image).framebuffer)
                        GLES20.glViewport(0, 0, 512, 512); GLES20.glUseProgram(shader)
                        bindTexture(shader, texture); draw.quad(shader)
                        checkMarkers(markerShader, texture, read.frame)
                    }
                    fence = ProbeGlDraw.fence(); ProbeGlDraw.waitFence(fence)
                    GLES30.glDeleteSync(fence); fence = 0L; readFences++
                    pool.release(read, true); lease = null; hasLease = false
                    last = read.frame; frames++
                    synchronized(frameIds) { frameIds += last; inputPts += read.timestampNs.toString(); submitTimes += SystemClock.elapsedRealtimeNanos() }
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) }
            finally {
                ready.countDown()
                try {
                    node.finish() // terminal only; a failed native close keeps this wrapper strongly owned
                    lease?.let { pool.release(it, true); lease = null; hasLease = false }
                    if (fence != 0L) { GLES30.glDeleteSync(fence); fence = 0L }
                    if (node.usable) { draw.close(); marker?.close(); image?.close() }
                    node.close(); closed = true
                } catch (t: Throwable) { failure.compareAndSet(null, t) }
                activeWorkers.decrementAndGet()
            }
        }
        private fun bindTexture(program: Int, texture: Int) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "source"), 0)
        }
        private fun checkMarkers(program: Int, texture: Int, expected: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, checkNotNull(marker).framebuffer)
            GLES20.glViewport(0, 0, 168, 1); GLES20.glUseProgram(program); bindTexture(program, texture)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "inputSize"), layout.input.width.toFloat(), layout.input.height.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "strip"), layout.stripHeight.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "cell"), ProbeFramePattern.cellWidth(layout.input.width).toFloat())
            draw.quad(program)
            markerBytes.clear(); GLES20.glReadPixels(0, 0, 168, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, markerBytes)
            ProbeGlDraw.checkGl()
            val columns = (0..2).map { region -> IntArray(56) { bit -> markerBytes[(region * 56 + bit) * 4].toInt() and 255 } }
            check(ProbeFramePattern.readFrame(nonce, columns) == expected) { "SHARED_READER_IMAGE_ID_MISMATCH" }
        }
        fun report(): JsonObject = synchronized(frameIds) {
            val gaps = submitTimes.zipWithNext { a, b -> (b - a) / 1e6 }
            obj("reader" to reader.name, "slow" to slow, "closed" to closed, "frames" to frames, "holds" to holds,
                "encoderProgressDuringHold" to encoderProgressDuringHold, "sourceProgressDuringHold" to sourceProgressDuringHold,
                "readFencesAcknowledged" to readFences, "imageIdentityCheck" to if (reader == Reader.ENCODER) "COLD_FILE_DECODE" else "RGB_MARKER_STRIP_EVERY_READ",
                "frameIds" to frameIds.toList(), "sourceTimestampsNs" to inputPts.toList(), "maximumReadCompleteGapMs" to gaps.maxOrNull())
        }
    }

    override fun endProducer() {
        if (producerConfirmed) return
        ending = true
        source.finish(); sourceEnded = true
        root.finish()
        writing?.let { pool.cancelWrite(it, true); writing = null }
        pool.stopPublishing()
        readers.forEach { it.stopRequested = true }
        readers.forEach { it.join(5_000) }
        check(readers.all { !it.isAlive && it.closed } && pool.outstanding() == 0) { "SHARED_READERS_CLEANUP_UNCONFIRMED" }
        // No consumer can submit more codec input once this acknowledgement is set.
        producerConfirmed = true
    }
    override fun close() {
        endProducer()
        if (source.usable) { source.current(); sourceDraw.close() }
        source.close()
        if (root.usable) {
            root.current(); receiver.close(sourceEnded)
            for (i in slots.indices) {
                if (writeFences[i] != 0L) { ProbeGlDraw.waitFence(writeFences[i]); GLES30.glDeleteSync(writeFences[i]); writeFences[i] = 0L }
                slots[i].close()
            }
            geometryTarget?.close(); geometryTarget = null; rootDraw.close()
        }
        root.close(); released = true
    }
    override fun evidence(): JsonObject {
        val healthy = failure.get() == null && publications == plannedFrames && encodedSubmissions.get() == plannedFrames
        val a = readers.firstOrNull { it.reader == Reader.DISPLAY_A }
        val pressure = healthy && a?.holds == 1 && a.encoderProgressDuringHold >= 3 && a.sourceProgressDuringHold >= 3 &&
            detachedB && rejoinedB && detachedWhileLeased && readers.filter { it.reader != Reader.ENCODER }.all { it.frames >= 3 } &&
            producerConfirmed && maximumWorkers.get() <= 3
        return obj("scope" to "SYNTHETIC_OES_SHARED_CONTEXT_OFFSCREEN_READERS", "status" to if (healthy && pressure) "PASS" else "INCOMPLETE",
            "cameraOpened" to false, "realWindowTested" to false, "sourceScheduling" to "PACED_PRODUCER_WITH_SERIAL_OES_RECEIVE",
            "receivedAndPublished" to publications, "encoderSubmissions" to encodedSubmissions.get(), "sourceMatrixApplied" to true,
            "sourceTransform" to receiver.transformSnapshot(), "firstGeometryDifference" to geometryDifference,
            "oesPolls" to receiver.polls, "oesNotifications" to receiver.notifications.get(), "lastSourceTimestampNs" to lastSourceTimestamp.toString(),
            "poolSlots" to pool.capacity, "poolRgbaBytes" to layout.input.width.toLong() * layout.input.height * 4 * pool.capacity,
            "otherDriverAndCodecMemoryBytes" to null, "readerQuotaEach" to 1, "maximumConcurrentWorkers" to maximumWorkers.get(),
            "maximumReserveWaitMs" to maximumReserveWaitMs, "detachRequestedWhileLeased" to detachedWhileLeased,
            "displayDetached" to detachedB, "displayRejoined" to rejoinedB, "readers" to readers.map { it.report() },
            "nativeFailureType" to failure.get()?.javaClass?.simpleName,
            "nativeFailureReason" to failure.get()?.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) },
            "allReadersClosed" to readers.all { it.closed }, "outstandingLeases" to pool.outstanding())
    }
    companion object {
        private const val DISPLAY = """
            precision highp float; uniform sampler2D source;
            void main(){gl_FragColor=texture2D(source,gl_FragCoord.xy/vec2(512.0,512.0));}
        """
        private const val MARKERS = """
            precision highp float; uniform sampler2D source; uniform vec2 inputSize; uniform float strip; uniform float cell;
            void main(){float index=floor(gl_FragCoord.x); float region=floor(index/56.0); float bit=mod(index,56.0);
                float sx=16.0+bit*cell+floor(cell/2.0); float sy=region*strip+16.0;
                gl_FragColor=texture2D(source,vec2((sx+0.5)/inputSize.x,1.0-(sy+0.5)/inputSize.y));}
        """
    }
}
