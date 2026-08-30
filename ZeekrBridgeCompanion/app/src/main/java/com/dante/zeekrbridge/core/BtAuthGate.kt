package com.dante.zeekrbridge.core

/**
 * Per-connection Bluetooth authentication state. A connection must successfully
 * complete HELLO before HEARTBEAT or any UPLOAD_* operation is allowed; PAIR
 * and HELLO are the only unauthenticated entry points.
 */
class BtAuthGate {
    private var device: PairedDevice? = null

    val authenticated: Boolean get() = device != null

    fun device(): PairedDevice? = device

    fun authenticate(device: PairedDevice) {
        this.device = device
    }

    fun clear() {
        device = null
    }

    companion object {
        private val AUTH_REQUIRED = setOf(
            "HEARTBEAT",
            "UPLOAD_CREATE",
            "UPLOAD_STATUS",
            "CHUNK",
            "UPLOAD_COMPLETE",
        )

        fun requiresAuth(type: String): Boolean = type in AUTH_REQUIRED
    }
}
