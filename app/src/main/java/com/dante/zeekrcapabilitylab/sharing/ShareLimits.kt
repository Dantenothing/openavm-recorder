package com.dante.zeekrcapabilitylab.sharing

data class ShareLimits(
    val maxFiles: Int = 64,
    val maxBytes: Long = 8L * 1024 * 1024 * 1024,
    val ttlMs: Long = 15 * 60_000L,
    val maxConnections: Int = 4,
    val maxDownloads: Int = 2,
    val headerTimeoutMs: Long = 10_000,
    val idleTimeoutMs: Long = 20_000,
) {
    init {
        require(maxFiles > 0 && maxBytes > 0 && ttlMs > 0)
        require(maxDownloads in 1..maxConnections && maxConnections <= 8)
        require(headerTimeoutMs > 0 && idleTimeoutMs > 0)
    }

    fun accepts(sizes: List<Long>): Boolean {
        if (sizes.isEmpty() || sizes.size > maxFiles) return false
        var remaining = maxBytes
        for (size in sizes) {
            if (size < 0 || size > remaining) return false
            remaining -= size
        }
        return true
    }
}
