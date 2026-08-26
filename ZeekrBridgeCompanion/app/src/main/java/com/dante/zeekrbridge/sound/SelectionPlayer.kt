package com.dante.zeekrbridge.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Streams the decoded 16-bit PCM file through AudioTrack with selection,
 * loop, seek, pause/resume and volume. Runs on its own daemon thread so a
 * blocking AudioTrack.write never blocks the UI.
 */
class SelectionPlayer(
    private val pcm: File,
    private val meta: PcmMeta,
) {
    @Volatile
    var startFrame = 0L

    @Volatile
    var endFrame = meta.frameCount

    @Volatile
    var loop = false

    @Volatile
    var volume = 1f

    @Volatile
    var onError: ((String) -> Unit)? = null

    @Volatile
    var onComplete: (() -> Unit)? = null

    private val position = AtomicLong(0L)
    @Volatile
    private var playing = false
    private var worker: Thread? = null
    private var track: AudioTrack? = null
    private val lock = Object()

    val currentFrame: Long get() = position.get()
    fun isPlaying(): Boolean = playing

    fun play(fromFrame: Long? = null) {
        stopInternal(keepThread = false)
        val from = (fromFrame ?: position.get()).coerceIn(startFrame, max(startFrame, endFrame - 1L))
        position.set(from)
        playing = true
        worker = thread(name = "selection-player", isDaemon = true) { runLoop(from) }
    }

    fun pause() {
        playing = false
        runCatching { track?.pause() }
    }

    fun resume() {
        if (playing) {
            runCatching { track?.play() }
            return
        }
        play(position.get())
    }

    fun seekTo(frame: Long) {
        val target = frame.coerceIn(startFrame, max(startFrame, endFrame - 1L))
        if (playing) {
            stopInternal(keepThread = false)
            position.set(target)
            playing = true
            worker = thread(name = "selection-player", isDaemon = true) { runLoop(target) }
        } else {
            position.set(target)
        }
    }

    fun release() {
        stopInternal(keepThread = true)
    }

    private fun stopInternal(keepThread: Boolean) {
        playing = false
        val t = worker
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            if (!keepThread) {
                try {
                    t.join(300)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        synchronized(lock) {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
            track = null
        }
        worker = null
    }

    private fun runLoop(fromFrame: Long) {
        val channelMask = if (meta.channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val bytesPerFrame = meta.channels * 2
        val minBuf = AudioTrack.getMinBufferSize(meta.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = max(minBuf, 128 * 1024)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(meta.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            playing = false
            onError?.invoke("无法创建播放器：${t.message ?: t.javaClass.simpleName}")
            return
        }
        synchronized(lock) { track = t }
        runCatching { t.setVolume(volume) }
        runCatching { t.play() }

        val chunkFrames = 8192
        val bytes = ByteArray(chunkFrames * bytesPerFrame)
        var pos = fromFrame
        position.set(pos)
        var completed = false
        try {
            RandomAccessFile(pcm, "r").use { raf ->
                while (playing && !Thread.currentThread().isInterrupted) {
                    val avail = endFrame - pos
                    if (avail <= 0) {
                        if (loop) {
                            pos = startFrame
                            continue
                        }
                        completed = true
                        break
                    }
                    val toRead = min(chunkFrames.toLong(), avail).toInt()
                    raf.seek(pos * bytesPerFrame)
                    val read = raf.read(bytes, 0, toRead * bytesPerFrame)
                    if (read <= 0) {
                        if (loop) {
                            pos = startFrame
                            continue
                        }
                        completed = true
                        break
                    }
                    val frames = read / bytesPerFrame
                    var written = 0
                    while (written < read && playing) {
                        val w = try {
                            t.write(bytes, written, read - written)
                        } catch (t2: Throwable) {
                            if (playing) onError?.invoke("播放中断：${t2.message ?: t2.javaClass.simpleName}")
                            return
                        }
                        if (w <= 0) {
                            if (playing) onError?.invoke("播放器写入失败")
                            return
                        }
                        written += w
                    }
                    pos += frames
                    position.set(pos)
                }
            }
        } catch (t: Throwable) {
            if (playing) onError?.invoke("播放失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            val wasPlaying = playing
            playing = false
            runCatching { t.pause() }
            synchronized(lock) {
                if (track === t) {
                    runCatching { t.release() }
                    track = null
                } else {
                    runCatching { t.release() }
                }
            }
            if (completed || wasPlaying) onComplete?.invoke()
        }
    }
}
