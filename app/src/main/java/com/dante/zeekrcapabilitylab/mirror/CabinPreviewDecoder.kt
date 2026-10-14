package com.dante.zeekrcapabilitylab.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer

/** One bounded, optional playback decoder. Never opens a camera, changes an encoder or stops a run.
 * A stuck vendor decoder retains its slot and Surface; repeated taps cannot create more workers. */
internal class CabinPreviewDecoder private constructor(private val stream: CabinPreviewBuffer,
    private val surface: Surface, private val failed: (String) -> Unit, private val released: () -> Unit) {
    private val thread = HandlerThread("openavm-cabin-display")
    private lateinit var handler: Handler
    @Volatile private var closing = false
    @Volatile private var active = false
    @Volatile var cleanupConfirmed = false; private set
    @Volatile var renderedFrames = 0L; private set
    @Volatile var lastWorkAtMs = SystemClock.elapsedRealtime(); private set
    private var wasActive = false
    private var codec: MediaCodec? = null
    private var revision = -1L
    private var cursor = 0L

    private fun start() { thread.start(); handler = Handler(thread.looper); handler.post(pump) }
    fun setActive(value: Boolean) { active = value }
    fun close() {
        if (closing) return
        closing = true
        handler.removeCallbacks(pump)
        handler.post { finish() }
    }

    private val pump = object : Runnable {
        override fun run() {
            if (closing) return
            lastWorkAtMs = SystemClock.elapsedRealtime()
            try {
                val enabled = active
                if (enabled && !wasActive) { codec?.flush(); cursor = 0 }
                wasActive = enabled
                if (enabled) decode() else drain(render = false)
            } catch (error: Exception) {
                failed("CABIN_DISPLAY_UNAVAILABLE:${error.javaClass.simpleName}")
                closing = true; finish(); return
            }
            if (!closing) handler.postDelayed(this, if (active) 16 else 100)
        }
    }

    private fun decode() {
        val batch = stream.read(cursor)
        if (batch.closed) { closing = true; finish(); return }
        val format = batch.format ?: return
        val updated = batch.updatedAtMs ?: return
        if (SystemClock.elapsedRealtime() - updated > 2_500) { drain(render = false); return }
        if (batch.frames.isEmpty()) { drain(render = true); return }
        if (revision != batch.revision || codec == null) {
            closeCodec()
            val next = MediaCodec.createDecoderByType(format.mime)
            codec = next // Own partial configure/start failure too.
            val media = MediaFormat.createVideoFormat(format.mime, format.width, format.height)
            format.csd.forEachIndexed { i, bytes -> media.setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
            next.configure(media, surface, null, 0); next.start()
            revision = batch.revision; cursor = 0
        } else if (batch.reset && cursor != 0L) { codec!!.flush(); cursor = 0 }
        val samples = batch.frames
        val decoder = checkNotNull(codec)
        for (sample in samples.take(8)) {
            if (!active || closing) break
            val index = decoder.dequeueInputBuffer(0)
            if (index < 0) break
            val input = checkNotNull(decoder.getInputBuffer(index))
            input.clear(); check(input.remaining() >= sample.bytes.size)
            input.put(sample.bytes)
            decoder.queueInputBuffer(index, 0, sample.bytes.size, sample.ptsUs, 0)
            cursor = sample.sequence
        }
        drain(render = true)
    }

    private fun drain(render: Boolean) {
        val decoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        var newest = -1
        try {
            for (attempt in 0 until 16) {
                val index = decoder.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (index < 0) continue
                if (newest >= 0) decoder.releaseOutputBuffer(newest, false)
                newest = index
            }
        } finally {
            if (newest >= 0) {
                val visible = render && active && !closing
                decoder.releaseOutputBuffer(newest, visible)
                if (visible) renderedFrames++
            }
        }
    }

    private fun closeCodec() {
        val previous = codec ?: return
        runCatching { previous.stop() }
        previous.release() // A failed release retains ownership, including the output Surface.
        codec = null
    }

    private fun finish() {
        if (cleanupConfirmed) return
        try {
            closeCodec()
            surface.release()
            released()
            cleanupConfirmed = true
            synchronized(Companion) { if (owner === this) owner = null }
            thread.quitSafely()
        } catch (error: Exception) {
            failed("CABIN_DISPLAY_CLEANUP_PENDING:${error.javaClass.simpleName}")
        }
    }

    companion object {
        private var owner: CabinPreviewDecoder? = null
        @Synchronized fun isIdle() = owner == null
        @Synchronized fun create(stream: CabinPreviewBuffer, surface: Surface,
            failed: (String) -> Unit, released: () -> Unit): CabinPreviewDecoder? {
            if (owner != null) return null
            return CabinPreviewDecoder(stream, surface, failed, released).also { owner = it; it.start() }
        }
    }
}
