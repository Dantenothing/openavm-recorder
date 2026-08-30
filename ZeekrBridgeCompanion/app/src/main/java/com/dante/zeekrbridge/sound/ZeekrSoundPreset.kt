package com.dante.zeekrbridge.sound

enum class SoundPurpose { UNLOCK, LOCK }

enum class ZeekrSoundPreset(
    val targetDirectoryName: String?,
    val maxWavFiles: Int?,
    val enforceUnderBytes: Long?,
) {
    ZEEKR_7X_AUNZ_OS_2_1(
        targetDirectoryName = "Lock Status Tones",
        maxWavFiles = 5,
        enforceUnderBytes = 1_000_000L,
    ),
    ZEEKR_7X_AUNZ_LEGACY(
        targetDirectoryName = "解闭锁音效",
        maxWavFiles = 5,
        enforceUnderBytes = 1_000_000L,
    ),
    GENERIC_WAV(
        targetDirectoryName = null,
        maxWavFiles = null,
        enforceUnderBytes = null,
    ),
    ;

    val isZeekrCompatible: Boolean get() = this != GENERIC_WAV

    fun acceptsSize(bytes: Long): Boolean = enforceUnderBytes?.let { bytes < it } ?: true
}
