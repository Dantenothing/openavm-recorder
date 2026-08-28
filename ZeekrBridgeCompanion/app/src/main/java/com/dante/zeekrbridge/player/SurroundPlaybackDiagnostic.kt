package com.dante.zeekrbridge.player

internal enum class SurroundOutputPath {
    GL_CROPPED,
    RAW_SURFACE,
}

internal enum class SurroundPlaybackBoundary {
    DECODER_OR_OUTPUT,
    SURFACE_TEXTURE_DELIVERY,
    GL_DRAW,
    RENDERED,
}

internal fun classifySurroundPlayback(
    outputPath: SurroundOutputPath,
    playerFirstFrames: Int,
    surfaceTextureFrames: Long,
    glFirstFrame: Boolean,
): SurroundPlaybackBoundary {
    if (playerFirstFrames <= 0) return SurroundPlaybackBoundary.DECODER_OR_OUTPUT
    if (outputPath == SurroundOutputPath.RAW_SURFACE) return SurroundPlaybackBoundary.RENDERED
    if (surfaceTextureFrames <= 0L) return SurroundPlaybackBoundary.SURFACE_TEXTURE_DELIVERY
    if (!glFirstFrame) return SurroundPlaybackBoundary.GL_DRAW
    return SurroundPlaybackBoundary.RENDERED
}
