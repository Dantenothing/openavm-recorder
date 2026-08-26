package com.dante.zeekrbridge.server

import com.dante.zeekrbridge.core.SecureCompare

/**
 * HELLO identity rule: the payload carDeviceId must match, in constant time,
 * the device authenticated by the handshake Authorization header. A mismatch
 * must never touch any other paired device's lastSeen state.
 */
object WsIdentity {
    fun matches(expectedCarId: String?, claimedCarId: String?): Boolean =
        expectedCarId != null && SecureCompare.equals(expectedCarId, claimedCarId)
}
