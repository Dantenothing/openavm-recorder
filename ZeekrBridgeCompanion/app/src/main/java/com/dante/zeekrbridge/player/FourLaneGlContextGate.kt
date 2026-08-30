package com.dante.zeekrbridge.player

/**
 * Prevents external GL texture creation before GLSurfaceView has made an EGL
 * context current. GLSurfaceView runs queued events before onSurfaceCreated(),
 * so queueEvent alone is not a sufficient readiness guarantee.
 */
internal class FourLaneGlContextGate {
    private var contextReady = false
    private var textureRequestPending = false

    /** Returns true only when texture creation may run immediately. */
    fun requestTextureCreation(): Boolean {
        textureRequestPending = true
        return contextReady
    }

    /** Returns true when an earlier request must now be fulfilled. */
    fun onGlContextCreated(): Boolean {
        contextReady = true
        return textureRequestPending
    }

    fun onTextureCreated() {
        textureRequestPending = false
    }
}
