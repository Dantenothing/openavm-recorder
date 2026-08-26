package com.dante.zeekrbridge.sound

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Result of re-reading a generated WAV file. */
data class WavPcmInfo(
    val valid: Boolean,
    val reason: String? = null,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitsPerSample: Int = 0,
    val dataBytes: Long = 0,
    val totalBytes: Long = 0,
) {
    val durationMs: Long
        get() = if (valid && channels > 0 && sampleRate > 0) {
            dataBytes / (channels * 2L) * 1000L / sampleRate
        } else {
            0L
        }
}

/**
 * Writes little-endian 16-bit PCM RIFF/WAVE. A placeholder header is written
 * first, samples are streamed, then the real sizes are patched in after close.
 */
object WavPcmWriter {
    const val HEADER_SIZE = 44L

    fun writeWavFile(
        file: File,
        channels: Int,
        sampleRate: Int,
        block: (WavPcmSink) -> Unit,
    ): Long {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        require(sampleRate in 1..384000) { "invalid sample rate" }
        file.parentFile?.mkdirs()
        val dataBytes: Long
        try {
            FileOutputStream(file).use { raw ->
                writeHeader(raw, channels, sampleRate, 0L)
                val sink = WavPcmSink(raw, channels)
                block(sink)
                sink.flush()
                dataBytes = sink.dataBytes
            }
            patchHeader(file, channels, sampleRate, dataBytes)
            return dataBytes
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw t
        }
    }

    fun writeHeader(out: OutputStream, channels: Int, sampleRate: Int, dataBytes: Long) {
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }
        require(sampleRate in 1..384000) { "invalid sample rate" }
        require(dataBytes >= 0) { "negative data size" }
        require(dataBytes % 2 == 0L) { "16-bit PCM data must have an even byte count" }
        val bits = 16
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate.toLong() * blockAlign
        val riffSize = 4L + (8L + 16L) + (8L + dataBytes)
        writeAscii(out, "RIFF")
        writeLe32(out, riffSize)
        writeAscii(out, "WAVE")
        writeAscii(out, "fmt ")
        writeLe32(out, 16L)
        writeLe16(out, 1) // PCM
        writeLe16(out, channels)
        writeLe32(out, sampleRate.toLong())
        writeLe32(out, byteRate)
        writeLe16(out, blockAlign)
        writeLe16(out, bits)
        writeAscii(out, "data")
        writeLe32(out, dataBytes)
    }

    private fun patchHeader(file: File, channels: Int, sampleRate: Int, dataBytes: Long) {
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.seek(4)
            writeLe32(raf, 36L + dataBytes)
            raf.seek(22)
            writeLe16(raf, channels)
            raf.seek(24)
            writeLe32(raf, sampleRate.toLong())
            raf.seek(28)
            writeLe32(raf, sampleRate.toLong() * channels * 2L)
            raf.seek(32)
            writeLe16(raf, channels * 2)
            raf.seek(40)
            writeLe32(raf, dataBytes)
            raf.fd.sync()
        } finally {
            raf.close()
        }
    }

    private fun writeAscii(out: OutputStream, s: String) {
        out.write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun writeLe16(out: OutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
    }

    private fun writeLe32(out: OutputStream, v: Long) {
        out.write((v and 0xff).toInt())
        out.write(((v shr 8) and 0xff).toInt())
        out.write(((v shr 16) and 0xff).toInt())
        out.write(((v shr 24) and 0xff).toInt())
    }

    private fun writeLe16(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xff)
        raf.write((v shr 8) and 0xff)
    }

    private fun writeLe32(raf: RandomAccessFile, v: Long) {
        raf.write((v and 0xff).toInt())
        raf.write(((v shr 8) and 0xff).toInt())
        raf.write(((v shr 16) and 0xff).toInt())
        raf.write(((v shr 24) and 0xff).toInt())
    }
}

/** Streaming sink converting clamped float frames to little-endian 16-bit PCM. */
class WavPcmSink(
    private val out: OutputStream,
    val channels: Int,
) {
    var dataBytes = 0L
        private set

    private val bytes = ByteArray(4096 * channels * 2)
    private var used = 0

    fun writeFrames(frames: FloatArray, count: Int) {
        val total = count * channels
        for (i in 0 until total) {
            val v = frames[i]
            val s = when {
                v >= 1f -> 32767
                v <= -1f -> -32768
                else -> (v * 32767f).roundToInt().coerceIn(-32768, 32767)
            }
            bytes[used++] = (s and 0xff).toByte()
            bytes[used++] = ((s shr 8) and 0xff).toByte()
            if (used == bytes.size) {
                out.write(bytes)
                used = 0
            }
        }
        dataBytes += total.toLong() * 2L
    }

    fun flush() {
        if (used > 0) {
            out.write(bytes, 0, used)
            used = 0
        }
        out.flush()
    }
}

/** Full re-read validation of a generated WAV: header, sizes, duration, params. */
object WavPcmValidator {
    fun validateWavFile(file: File): WavPcmInfo {
        val total = file.length()
        if (total < WavPcmWriter.HEADER_SIZE) {
            return WavPcmInfo(false, "FILE_TOO_SMALL", totalBytes = total)
        }
        val info = file.inputStream().use { validate(it) }
        return if (info.valid && info.riffConsistent(total)) {
            info.copy(totalBytes = total)
        } else {
            info.copy(valid = false, reason = info.reason ?: "RIFF_SIZE_MISMATCH", totalBytes = total)
        }
    }

    fun validate(input: InputStream): WavPcmInfo {
        var total = 0L

        fun readExactOrNull(n: Int): ByteArray? {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r == -1) {
                    if (off == 0) return null
                    throw IOException("truncated header")
                }
                off += r
            }
            total += n
            return buf
        }

        fun skipExact(n: Long): Boolean {
            var remaining = n
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (r == -1) return false
                total += r
                remaining -= r
            }
            return true
        }

        fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

        fun le32u(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xff) or
                ((b[off + 1].toLong() and 0xff) shl 8) or
                ((b[off + 2].toLong() and 0xff) shl 16) or
                ((b[off + 3].toLong() and 0xff) shl 24)

        val header = readExactOrNull(12) ?: return WavPcmInfo(false, "TRUNCATED", totalBytes = total)
        if (!header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) {
            return WavPcmInfo(false, "NOT_RIFF", totalBytes = total)
        }
        val riffDeclared = le32u(header, 4)
        if (!header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) {
            return WavPcmInfo(false, "NOT_WAVE", totalBytes = total)
        }

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataSize = 0L
        var hasData = false
        var hasFmt = false

        while (true) {
            val chunk = try {
                readExactOrNull(8) ?: break
            } catch (t: IOException) {
                return WavPcmInfo(false, "TRUNCATED_CHUNK_HEADER", totalBytes = total)
            }
            val id = String(chunk.copyOfRange(0, 4), Charsets.US_ASCII)
            val size = le32u(chunk, 4)
            when (id) {
                "fmt " -> {
                    if (size < 16) return WavPcmInfo(false, "BAD_FMT_SIZE", totalBytes = total)
                    val fmt = readExactOrNull(16)
                        ?: return WavPcmInfo(false, "TRUNCATED_FMT", totalBytes = total)
                    format = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32u(fmt, 4).toInt()
                    bits = le16(fmt, 14)
                    hasFmt = true
                    if (size > 16 && !skipExact(size - 16)) {
                        return WavPcmInfo(false, "TRUNCATED_FMT_EXTRA", totalBytes = total)
                    }
                }
                "data" -> {
                    hasData = true
                    dataSize = size
                    if (!skipExact(size)) {
                        return WavPcmInfo(false, "TRUNCATED_DATA", totalBytes = total)
                    }
                }
                else -> {
                    if (!skipExact(size)) {
                        return WavPcmInfo(false, "TRUNCATED_CHUNK", totalBytes = total)
                    }
                }
            }
            if (size % 2 == 1L && !skipExact(1)) {
                return WavPcmInfo(false, "TRUNCATED_PADDING", totalBytes = total)
            }
        }

        if (!hasFmt) return WavPcmInfo(false, "NO_FMT", totalBytes = total)
        if (!hasData || dataSize <= 0L) return WavPcmInfo(false, "NO_DATA", totalBytes = total)
        if (format != 1) return WavPcmInfo(false, "NOT_PCM", totalBytes = total)
        if (bits != 16) return WavPcmInfo(false, "NOT_16BIT", totalBytes = total)
        if (channels != 1 && channels != 2) return WavPcmInfo(false, "BAD_CHANNELS", totalBytes = total)
        if (sampleRate <= 0) return WavPcmInfo(false, "BAD_RATE", totalBytes = total)
        if (riffDeclared + 8L != total) {
            return WavPcmInfo(false, "RIFF_SIZE_MISMATCH", totalBytes = total)
        }
        return WavPcmInfo(
            valid = true,
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bits,
            dataBytes = dataSize,
            totalBytes = total,
        )
    }

    private fun WavPcmInfo.riffConsistent(total: Long): Boolean =
        dataBytes + WavPcmWriter.HEADER_SIZE == total
}
