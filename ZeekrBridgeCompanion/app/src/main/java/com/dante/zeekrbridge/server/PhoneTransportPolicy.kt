package com.dante.zeekrbridge.server

import io.github.dantenothing.avmtransfer.protocol.PhoneSecurityProtocol

object PhoneTransportPolicy {
    fun publicRequest(method: String, path: String): Boolean = method == "GET" &&
        (path == "/health" || path == PhoneSecurityProtocol.IDENTITY_PATH)
}
