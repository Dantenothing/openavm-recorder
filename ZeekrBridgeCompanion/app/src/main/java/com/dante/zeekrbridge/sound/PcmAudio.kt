package com.dante.zeekrbridge.sound

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Source PCM access abstraction; implementations stream from files or arrays. */
interface PcmInput {
    val sampleRate: Int
    val channels: Int
    val frameCount: Long

    /** Reads up to [count] interleaved frames starting at [startFrame] into [out]. */
    fun readFrames(startFrame: Long, count: Int, out: FloatArray): Int

    fun close() {}
}

/** In-memory PCM input for tests and small sources. */
class ArrayPcmInput(
    override val sampleRate: Int,
    override val channels: Int,
    val samples: FloatArray,
) : PcmInput {
    override val frameCount: Long = samples.size.toLong() / channels

    override fun readFrames(startFrame: Long, count: Int, out: FloatArray): Int {
        if (startFrame >= frameCount || count <= 0) return 0
        val available = min(count.toLong(), frameCount - startFrame).toInt()
        val src = (startFrame * channels).toInt()
        System.arraycopy(samples, src, out, 0, available * channels)
        return available
    }
}

/** Streaming little-endian 16-bit PCM file input; constant memory. */
class FilePcmInput(
    private val file: File,
    override val sampleRate: Int,
    override val channels: Int,
    override val frameCount: Long,
) : PcmInput, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private var closed = false

    override fun readFrames(startFrame: Long, count: Int, out: FloatArray): Int {
        if (closed || startFrame >= frameCount || count <= 0) return 0
        val bytesPerFrame = channels * 2
        val frames = min(count.toLong(), frameCount - startFrame).toInt()
        raf.seek(startFrame * bytesPerFrame)
        val bytes = ByteArray(frames * bytesPerFrame)
        val read = raf.read(bytes)
        if (read <= 0) return 0
        val actualFrames = read / bytesPerFrame
        var bi = 0
        var fi = 0
        val end = actualFrames * channels
        while (fi < end) {
            val lo = bytes[bi++].toInt() and 0xff
            val hi = bytes[bi++].toInt() and 0xff
            val s = (lo or (hi shl 8)).toShort()
            out[fi++] = s / 32768f
        }
        return actualFrames
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { raf.close() }
        }
    }
}

data class AudioEditParams(
    val startFrame: Long,
    val endFrame: Long,
    val gain: Double = 1.0,
    val normalize: Boolean = false,
    val normalizeTarget: Double = 0.89,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val outputChannels: Int = 1,
)

data class ProcessedSound(
    val sampleRate: Int,
    val channels: Int,
    val outFrames: Long,
    val dataBytes: Long,
    val measuredPeak: Double,
    val info: WavPcmInfo,
)

/**
 * Sample-level editor pipeline: trim -> channel convert -> resample to 48 kHz
 * -> gain (higher precision, saturation before write) -> optional peak
 * normalization -> linear fades -> little-endian 16-bit PCM WAV.
 *
 * Both passes stream with fixed buffers; a selection is never held in memory.
 */
object PcmProcessor {
    const val OUT_RATE = AudioResampler.TARGET_RATE
    private const val OUT_CHUNK = 4096

    fun process(
        input: PcmInput,
        params: AudioEditParams,
        cancel: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
        out: File,
    ): ProcessedSound {
        validateSelection(input, params)
        require(params.outputChannels == 1 || params.outputChannels == 2) { "output channels must be 1 or 2" }
        val selFrames = params.endFrame - params.startFrame
        val outFrames = AudioResampler.outputFrameCount(selFrames, input.sampleRate, OUT_RATE)
        if (outFrames <= 0) {
            throw SoundInputException("选区为空或超出范围，请调整开始/结束位置", "EMPTY_SELECTION")
        }
        val measuredPeak = scanPeak(input, params, selFrames, outFrames, cancel)
        val normalizeGain = if (params.normalize && measuredPeak > 1e-9) {
            params.normalizeTarget / measuredPeak
        } else {
            1.0
        }
        val gain = max(0.0, params.gain) * normalizeGain
        val (fadeIn, fadeOut) = fadeFrameCounts(params, outFrames)

        val dataBytes = WavPcmWriter.writeWavFile(out, params.outputChannels, OUT_RATE) { sink ->
            writeSelection(
                input = input,
                params = params,
                gain = gain,
                fadeInFrames = fadeIn,
                fadeOutFrames = fadeOut,
                selFrames = selFrames,
                outFrames = outFrames,
                cancel = cancel,
                progress = progress,
                sink = sink,
            )
        }
        val info = WavPcmValidator.validateWavFile(out)
        if (!info.valid) {
            runCatching { out.delete() }
            throw SoundInputException("输出校验失败：${info.reason ?: "INVALID"}", "VERIFY_FAILED")
        }
        if (info.dataBytes != dataBytes) {
            runCatching { out.delete() }
            throw SoundInputException("输出校验失败：数据长度不一致", "VERIFY_FAILED")
        }
        return ProcessedSound(
            sampleRate = OUT_RATE,
            channels = params.outputChannels,
            outFrames = outFrames,
            dataBytes = dataBytes,
            measuredPeak = measuredPeak,
            info = info,
        )
    }

    fun validateSelection(input: PcmInput, params: AudioEditParams) {
        val start = params.startFrame
        val end = params.endFrame
        if (start < 0 || end > input.frameCount || start >= end) {
            throw SoundInputException("选区为空或超出范围，请调整开始/结束位置", "EMPTY_SELECTION")
        }
    }

    private fun scanPeak(
        input: PcmInput,
        params: AudioEditParams,
        selFrames: Long,
        outFrames: Long,
        cancel: () -> Boolean,
    ): Double {
        var peak = 0.0
        val sink = object : OutputConsumer {
            override fun onChunk(out: FloatArray, frames: Int) {
                for (i in 0 until frames * params.outputChannels) {
                    val v = out[i]
                    val a = if (v < 0f) -v else v
                    if (a > peak) peak = a.toDouble()
                }
            }
        }
        streamOutput(input, params, gain = 1.0, fadeInFrames = 0, fadeOutFrames = 0, selFrames = selFrames, outFrames = outFrames, cancel = cancel, progress = { _, _ -> }, consumer = sink)
        return peak
    }

    private fun writeSelection(
        input: PcmInput,
        params: AudioEditParams,
        gain: Double,
        fadeInFrames: Long,
        fadeOutFrames: Long,
        selFrames: Long,
        outFrames: Long,
        cancel: () -> Boolean,
        progress: (Long, Long) -> Unit,
        sink: WavPcmSink,
    ) {
        streamOutput(
            input = input,
            params = params,
            gain = gain,
            fadeInFrames = fadeInFrames,
            fadeOutFrames = fadeOutFrames,
            selFrames = selFrames,
            outFrames = outFrames,
            cancel = cancel,
            progress = progress,
            consumer = object : OutputConsumer {
                override fun onChunk(out: FloatArray, frames: Int) {
                    sink.writeFrames(out, frames)
                }
            },
        )
    }

    private interface OutputConsumer {
        fun onChunk(out: FloatArray, frames: Int)
    }

    private fun streamOutput(
        input: PcmInput,
        params: AudioEditParams,
        gain: Double,
        fadeInFrames: Long,
        fadeOutFrames: Long,
        selFrames: Long,
        outFrames: Long,
        cancel: () -> Boolean,
        progress: (Long, Long) -> Unit,
        consumer: OutputConsumer,
    ) {
        val inRate = input.sampleRate
        val inCh = input.channels
        val outCh = params.outputChannels
        val maxWindowFrames = (AudioResampler.sourceWindowForOutput(0, OUT_CHUNK.toLong(), inRate, OUT_RATE, selFrames)[1]).toInt() + 2
        val bufIn = FloatArray(maxWindowFrames * inCh)
        val bufOut = FloatArray(OUT_CHUNK * outCh)
        var produced = 0L
        while (produced < outFrames) {
            if (cancel()) throw SoundCancelledException()
            val n0 = produced
            val n1 = min(produced + OUT_CHUNK, outFrames)
            val window = AudioResampler.sourceWindowForOutput(n0, n1, inRate, OUT_RATE, selFrames)
            val windowStart = window[0]
            val windowCount = window[1].toInt()
            if (windowCount * inCh > bufIn.size) {
                throw IllegalStateException("resampler window overflow")
            }
            val read = input.readFrames(params.startFrame + windowStart, windowCount, bufIn)
            if (read < windowCount) {
                for (i in read * inCh until windowCount * inCh) bufIn[i] = 0f
            }
            var fi = 0
            for (n in n0 until n1) {
                val pos = n.toDouble() * inRate / OUT_RATE
                val i0 = pos.toLong()
                val frac = (pos - i0).toFloat()
                val i1 = min(i0 + 1L, selFrames - 1L)
                val b0 = ((i0 - windowStart) * inCh).toInt()
                val b1 = ((i1 - windowStart) * inCh).toInt()
                for (c in 0 until outCh) {
                    var acc: Float
                    if (inCh == 1) {
                        val s0 = bufIn[b0]
                        val s1 = if (b1 == b0) s0 else bufIn[b1]
                        acc = s0 + (s1 - s0) * frac
                    } else if (outCh == 2) {
                        val s0 = bufIn[b0 + c]
                        val s1 = if (b1 == b0) s0 else bufIn[b1 + c]
                        acc = s0 + (s1 - s0) * frac
                    } else {
                        val l0 = bufIn[b0]
                        val r0 = bufIn[b0 + 1]
                        val l1 = if (b1 == b0) l0 else bufIn[b1]
                        val r1 = if (b1 == b0) r0 else bufIn[b1 + 1]
                        acc = ((l0 + (l1 - l0) * frac) + (r0 + (r1 - r0) * frac)) / 2f
                    }
                    var v = (acc * gain).toFloat()
                    v *= fadeFactor(n, outFrames, fadeInFrames, fadeOutFrames)
                    bufOut[fi++] = v.coerceIn(-1f, 1f)
                }
            }
            consumer.onChunk(bufOut, (n1 - n0).toInt())
            produced = n1
            progress(produced, outFrames)
        }
    }

    private fun fadeFrameCounts(params: AudioEditParams, outFrames: Long): Pair<Long, Long> {
        var fadeIn = params.fadeInMs * OUT_RATE / 1000L
        var fadeOut = params.fadeOutMs * OUT_RATE / 1000L
        fadeIn = fadeIn.coerceIn(0L, outFrames)
        fadeOut = fadeOut.coerceIn(0L, outFrames)
        val total = fadeIn + fadeOut
        if (total > outFrames && outFrames > 0 && total > 0) {
            val scale = outFrames.toDouble() / total
            var newIn = (fadeIn * scale).roundToLong().coerceAtLeast(if (fadeIn > 0) 1L else 0L)
            var newOut = (fadeOut * scale).roundToLong().coerceAtLeast(if (fadeOut > 0) 1L else 0L)
            var overflow = newIn + newOut - outFrames
            while (overflow > 0 && newOut > 0) {
                newOut -= 1
                overflow -= 1
            }
            while (overflow > 0 && newIn > 0) {
                newIn -= 1
                overflow -= 1
            }
            fadeIn = newIn
            fadeOut = newOut
        }
        return fadeIn to fadeOut
    }

    private fun fadeFactor(n: Long, outFrames: Long, fadeIn: Long, fadeOut: Long): Float {
        if (fadeIn > 0 && n < fadeIn) {
            if (fadeIn == 1L) return 1f
            return (n.toFloat() / (fadeIn - 1).toFloat()).coerceIn(0f, 1f)
        }
        if (fadeOut > 0 && n >= outFrames - fadeOut) {
            if (fadeOut == 1L) return 0f
            return ((outFrames - 1L - n).toFloat() / (fadeOut - 1L).toFloat()).coerceIn(0f, 1f)
        }
        return 1f
    }
}
