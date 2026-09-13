package com.dante.zeekrbridge.sound

enum class SoundPurpose { UNLOCK, LOCK }

enum class ZeekrSoundPreset(
    val targetDirectoryNames: List<String>,
    val maxWavFiles: Int?,
    val enforceUnderBytes: Long?,
) {
    ZEEKR_7X_AUNZ(
        targetDirectoryNames = listOf("Lock Status Tones", "解闭锁音效"),
        maxWavFiles = 5,
        enforceUnderBytes = 1_000_000L,
    ),
    GENERIC_WAV(
        targetDirectoryNames = emptyList(),
        maxWavFiles = null,
        enforceUnderBytes = null,
    ),
    ;

    val isZeekrCompatible: Boolean get() = this != GENERIC_WAV

    fun acceptsSize(bytes: Long): Boolean = enforceUnderBytes?.let { bytes < it } ?: true
}
