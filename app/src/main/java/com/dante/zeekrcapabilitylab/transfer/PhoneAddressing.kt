package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.DiscoveryReply
import io.github.dantenothing.avmtransfer.protocol.HealthResponse
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import java.net.URI

data class PhoneAddress(val host: String, val port: Int) {
    val displayValue: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

object PhoneAddressParser {
    fun parse(raw: String, defaultPort: Int = TransferProtocol.PORT): Result<PhoneAddress> = runCatching {
        require(defaultPort in 1..65_535)
        val input = raw.trim()
        require(input.isNotEmpty()) { "手机地址不能为空" }
        val uri = URI(if ("://" in input) input else "http://$input")
        require(uri.scheme.equals("http", ignoreCase = true)) { "手机地址只支持局域网 HTTP" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "手机地址格式无效，请输入 IP 或 IP:端口"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
            "手机地址不应包含网页路径"
        }
        val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        require(host.isNotEmpty()) { "手机地址格式无效，请输入 IP 或 IP:端口" }
        val port = if (uri.port == -1) defaultPort else uri.port
        require(port in 1..65_535) { "手机端口必须介于 1 和 65535 之间" }
        PhoneAddress(host, port)
    }
}

object DiscoveryTargetPolicy {
    private const val LIMITED_BROADCAST = "255.255.255.255"
    private const val KNOWN_ZEEKR_HOTSPOT_HOST = "192.0.0.2"

    fun healthCandidates(savedHost: String?, gateways: Collection<String>): List<String> =
        buildList {
            savedHost?.takeIf(::isUsableIpv4)?.let(::add)
            add(KNOWN_ZEEKR_HOTSPOT_HOST)
            gateways.filter(::isUsableIpv4).forEach(::add)
        }.distinct()

    fun targets(
        savedHost: String?,
        gateways: Collection<String>,
        directedBroadcasts: Collection<String>,
    ): List<String> =
        buildList {
            addAll(healthCandidates(savedHost, gateways))
            directedBroadcasts.filter(::isUsableIpv4).forEach(::add)
            add(LIMITED_BROADCAST)
        }.distinct()

    fun directedBroadcast(ipv4: String, prefixLength: Int): String? {
        if (prefixLength !in 1..30) return null
        val octets = ipv4.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val address = octets.fold(0L) { value, octet -> (value shl 8) or octet.toLong() }
        val hostBits = 32 - prefixLength
        val hostMask = (1L shl hostBits) - 1L
        val broadcast = address or hostMask
        return (3 downTo 0).joinToString(".") { shift -> ((broadcast shr (shift * 8)) and 0xff).toString() }
    }

    private fun isUsableIpv4(value: String): Boolean {
        val octets = value.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] !in setOf(0, 127) && octets[0] < 224
    }
}

object PhoneReconnectPolicy {
    fun migrate(
        saved: PhoneEndpoint,
        discovery: DiscoveryReply,
        health: HealthResponse,
    ): PhoneEndpoint? {
        if (health.service != TransferProtocol.SERVICE || health.phoneDeviceId != saved.phoneId) return null
        if (discovery.service != TransferProtocol.SERVICE || discovery.port !in 1..65_535) return null
        return saved.copy(
            host = discovery.ip,
            port = discovery.port,
            phoneName = health.deviceName,
        )
    }
}
