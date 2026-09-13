package com.dante.zeekrcapabilitylab.sound

import com.dante.zeekrbridge.sound.*
import io.github.dantenothing.avmtransfer.protocol.SoundTransferProtocol
import io.github.dantenothing.avmtransfer.protocol.SoundTransferValidation

data class VehicleSoundEdit(
    val startFrame: Long,
    val endFrame: Long,
    val volumePercent: Int = 100,
    val normalize: Boolean = false,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val loop: Boolean = false,
) {
    fun parameters() = AudioEditParams(startFrame, endFrame, gain = volumePercent / 100.0,
        normalize = normalize, fadeInMs = fadeInMs, fadeOutMs = fadeOutMs, outputChannels = 1)
    fun outputBytes(rate: Int): Long = 44 + AudioResampler.outputFrameCount((endFrame - startFrame).coerceAtLeast(0), rate) * 2
    fun validFor(meta: PcmMeta): Boolean = startFrame >= 0 && endFrame in (startFrame + 1)..meta.frameCount &&
        volumePercent in 0..200 && fadeInMs >= 0 && fadeOutMs >= 0 && outputBytes(meta.sampleRate) < SoundTransferProtocol.MAX_WAV_BYTES

    companion object {
        fun defaults(meta: PcmMeta) = VehicleSoundEdit(0, minOf(meta.frameCount, meta.sampleRate * 5L))
        fun secondsToFrame(seconds: Double, meta: PcmMeta): Long? =
            seconds.takeIf { it.isFinite() && it >= 0 && it <= meta.frameCount.toDouble() / meta.sampleRate }
                ?.let { (it * meta.sampleRate).toLong().coerceAtMost(meta.frameCount) }
    }
}

internal object VehicleSoundNames {
    fun available(raw: String, purpose: SoundPurpose, existing: Collection<String>): String {
        val stem = SoundFileNames.cleanBase(raw)?.take(130) ?: "sound"
        val suffix = if (purpose == SoundPurpose.LOCK) "lock" else "unlock"
        val used = existing.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        for (index in 0..1000) {
            val name = "$stem-$suffix" + (if (index == 0) "" else "-$index") + ".wav"
            if (name.lowercase(java.util.Locale.ROOT) !in used && SoundTransferValidation.cleanWavFileName(name) != null) return name
        }
        error("Cannot find an available sound name")
    }
}
