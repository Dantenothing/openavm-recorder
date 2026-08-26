package com.dante.zeekrbridge.core

import java.io.File
import java.io.IOException
import java.io.InputStream

data class WavInfo(
    val valid: Boolean,
    val reason: String? = null,
    val format: Int = 0,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitsPerSample: Int = 0,
    val dataBytes: Long = 0,
    val hasDataChunk: Boolean = false,
    val totalBytes: Long = 0,
    val riffDeclaredSize: Long = 0,
)

/**
 * Pure RIFF/WAVE parser. Walks chunks (skipping unknown chunks and odd-byte
 * padding) without buffering chunk contents. Accepts only non-empty 16-bit
 * little endian PCM at 44100/48000 Hz with 1-2 channels, a consistent fmt
 * block, a data chunk and a consistent RIFF extent.
 */
object WavValidator {
    const val MAX_BYTES = 1024 * 1024L
    private val ACCEPTED_RATES = setOf(44100, 48000)
    private class TruncatedHeader : IOException("truncated chunk header")

    fun validateFile(file: File): WavInfo {
        val total = file.length()
        if (total > MAX_BYTES) return WavInfo(false, "FILE_TOO_LARGE", totalBytes = total)
        val info = file.inputStream().use { validate(it) }.copy(totalBytes = total)
        if (!info.valid) return info
        return if (info.riffDeclaredSize + 8 == total) {
            info
        } else {
            info.copy(valid = false, reason = "RIFF_SIZE_MISMATCH")
        }
    }

    fun validate(input: InputStream): WavInfo {
        var total = 0L

        fun readExactOrNull(n: Int): ByteArray? {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r == -1) {
                    if (off == 0) return null
                    throw TruncatedHeader()
                }
                off += r
            }
            total += n
            return buf
        }

        fun skipExact(n: Long): Boolean {
            var remaining = n
            val buf = ByteArray(4096)
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

        fun le32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or
                ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or
                ((b[off + 3].toInt() and 0xff) shl 24)

        fun le32u(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xff) or
                ((b[off + 1].toLong() and 0xff) shl 8) or
                ((b[off + 2].toLong() and 0xff) shl 16) or
                ((b[off + 3].toLong() and 0xff) shl 24)

        fun chunkSize(b: ByteArray): Long =
            (b[4].toLong() and 0xff) or
                ((b[5].toLong() and 0xff) shl 8) or
                ((b[6].toLong() and 0xff) shl 16) or
                ((b[7].toLong() and 0xff) shl 24)

        val header = readExactOrNull(12) ?: return WavInfo(false, "TRUNCATED")
        if (!header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) {
            return WavInfo(false, "NOT_RIFF")
        }
        val riffDeclared = le32u(header, 4)
        if (!header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) {
            return WavInfo(false, "NOT_WAVE")
        }

        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var byteRate = 0
        var blockAlign = 0
        var dataSize = 0L
        var hasData = false

        while (true) {
            val chunkHeader = try {
                readExactOrNull(8) ?: break
            } catch (t: TruncatedHeader) {
                return WavInfo(false, "TRUNCATED_CHUNK_HEADER")
            }
            val id = String(chunkHeader.copyOfRange(0, 4), Charsets.US_ASCII)
            val size = chunkSize(chunkHeader)
            when (id) {
                "fmt " -> {
                    if (size < 16) return WavInfo(false, "BAD_FMT_SIZE")
                    val fmt = try {
                        readExactOrNull(16) ?: return WavInfo(false, "TRUNCATED_FMT")
                    } catch (t: TruncatedHeader) {
                        return WavInfo(false, "TRUNCATED_FMT")
                    }
                    format = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4)
                    byteRate = le32(fmt, 8)
                    blockAlign = le16(fmt, 12)
                    bits = le16(fmt, 14)
                    if (size > 16 && !skipExact(size - 16)) return WavInfo(false, "TRUNCATED_FMT_EXTRA")
                }
                "data" -> {
                    hasData = true
                    dataSize = size
                    if (size <= 0L) {
                        return WavInfo(false, "EMPTY_DATA_CHUNK", format, channels, sampleRate, bits, 0L, true, total, riffDeclared)
                    }
                    if (!skipExact(size)) return WavInfo(false, "TRUNCATED_DATA", format, channels, sampleRate, bits, dataSize, true, total, riffDeclared)
                }
                else -> {
                    if (!skipExact(size)) return WavInfo(false, "TRUNCATED_CHUNK", format, channels, sampleRate, bits, dataSize, hasData, total, riffDeclared)
                }
            }
            if (size % 2 == 1L && !skipExact(1)) return WavInfo(false, "TRUNCATED_PADDING", format, channels, sampleRate, bits, dataSize, hasData, total, riffDeclared)
        }

        if (!hasData) return WavInfo(false, "NO_DATA_CHUNK", format, channels, sampleRate, bits)
        if (channels <= 0 || bits <= 0 || sampleRate <= 0) {
            return WavInfo(false, "FMT_INCONSISTENT", format, channels, sampleRate, bits, dataSize, true, total, riffDeclared)
        }
        val expectedByteRate = sampleRate.toLong() * channels * bits / 8
        val expectedBlockAlign = channels * bits / 8
        if (byteRate.toLong() != expectedByteRate || blockAlign != expectedBlockAlign) {
            return WavInfo(false, "FMT_INCONSISTENT", format, channels, sampleRate, bits, dataSize, true, total, riffDeclared)
        }
        if (riffDeclared + 8 != total) {
            return WavInfo(false, "RIFF_SIZE_MISMATCH", format, channels, sampleRate, bits, dataSize, true, total, riffDeclared)
        }
        return WavInfo(true, null, format, channels, sampleRate, bits, dataSize, true, total, riffDeclared)
    }

    fun isAcceptable(info: WavInfo): Boolean =
        info.valid &&
            info.format == 1 &&
            info.channels in 1..2 &&
            info.sampleRate in ACCEPTED_RATES &&
            info.bitsPerSample == 16 &&
            info.hasDataChunk &&
            info.dataBytes > 0

    fun toParams(info: WavInfo): WavParams? =
        if (isAcceptable(info)) {
            WavParams(
                format = info.format,
                channels = info.channels,
                sampleRate = info.sampleRate,
                bitsPerSample = info.bitsPerSample,
                dataBytes = info.dataBytes,
            )
        } else {
            null
        }
}
