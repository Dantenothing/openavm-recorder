package com.dante.zeekrbridge.sound

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Android PCM encoding constants are numeric here so the conversion is JVM-testable. */
internal object DecodedPcm {
    fun to16Bit(bytes: ByteArray, encoding: Int, channels: Int): ByteArray {
        require(channels in 1..2)
        val sampleBytes = when (encoding) { 2 -> 2; 3 -> 1; 4 -> 4
            else -> throw SoundInputException("不支持解码器的 PCM 格式", "PCM_FORMAT_UNSUPPORTED") }
        require(bytes.size % (sampleBytes * channels) == 0) { "Incomplete PCM frame" }
        if (encoding == 2) return bytes
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(bytes.size / sampleBytes * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) {
            val value = if (encoding == 3) ((input.get().toInt() and 255) - 128) shl 8 else {
                val sample = input.float
                when { !sample.isFinite() -> 0; sample >= 1f -> 32767; sample <= -1f -> -32768
                    else -> (sample * 32767f).roundToInt() }
            }
            output.putShort(value.toShort())
        }
        return output.array()
    }
}

data class SoundDecodeLimits(
    val maxDurationMs: Long = Long.MAX_VALUE,
    val maxPcmBytes: Long = Long.MAX_VALUE,
    val keepFreeBytes: Long = 0,
) {
    fun check(durationMs: Long, pcmBytes: Long) {
        if (durationMs > maxDurationMs || pcmBytes > maxPcmBytes)
            throw SoundInputException("素材过长，请先截取较短的音乐或视频", "SOURCE_TOO_LONG")
    }
    companion object {
        val VEHICLE = SoundDecodeLimits(30 * 60_000L, 512L * 1024 * 1024, 64L * 1024 * 1024)
    }
}
