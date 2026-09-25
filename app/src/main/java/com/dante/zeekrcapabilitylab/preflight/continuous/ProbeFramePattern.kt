package com.dante.zeekrcapabilitylab.preflight.continuous

/** Independent CPU fixture and image marker parser. Never infers an unreadable ID from PTS. */
internal object ProbeFramePattern {
    const val MARKER_BITS = 56
    const val MARKER_LEFT = 16
    const val MARKER_TOP = 8
    const val MARKER_HEIGHT = 16
    data class Identity(val nonce: Int, val frame: Int, val region: Int)

    fun marker(nonce: Int, frame: Int, region: Int): ByteArray {
        require(nonce in 0..65535 && frame in 0..65535 && region in 0..2)
        val bytes = byteArrayOf(0xa7.toByte(), (nonce ushr 8).toByte(), nonce.toByte(),
            (frame ushr 8).toByte(), frame.toByte(), region.toByte(), 0)
        bytes[6] = crc8(bytes, 6).toByte()
        return bytes
    }

    fun bit(bytes: ByteArray, index: Int): Int = (bytes[index / 8].toInt() ushr (7 - index % 8)) and 1
    fun cellWidth(width: Int): Int = ((width - 32) / MARKER_BITS).also { require(it >= 4) }

    fun parse(luma: IntArray): Identity? {
        if (luma.size != MARKER_BITS || luma.any { it !in 0..255 || it in 81..175 }) return null
        val bytes = ByteArray(7)
        for (index in luma.indices) if (luma[index] >= 176) {
            bytes[index / 8] = (bytes[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
        }
        if ((bytes[0].toInt() and 255) != 0xa7 || crc8(bytes, 6) != (bytes[6].toInt() and 255)) return null
        val nonce = ((bytes[1].toInt() and 255) shl 8) or (bytes[2].toInt() and 255)
        val frame = ((bytes[3].toInt() and 255) shl 8) or (bytes[4].toInt() and 255)
        val region = bytes[5].toInt() and 255
        if (region !in 0..2) return null
        return Identity(nonce, frame, region)
    }

    fun readFrame(nonce: Int, columns: List<IntArray>): Int? {
        if (columns.size != 3) return null
        val identities = columns.map { parse(it) ?: return null }
        if (identities.withIndex().any { (region, id) -> id.nonce != nonce || id.region != region }) return null
        return identities.first().frame.takeIf { id -> identities.all { it.frame == id } }
    }

    private fun crc8(bytes: ByteArray, count: Int): Int {
        var crc = 0
        for (index in 0 until count) {
            crc = crc xor (bytes[index].toInt() and 255)
            repeat(8) { crc = if (crc and 128 != 0) ((crc shl 1) xor 7) and 255 else (crc shl 1) and 255 }
        }
        return crc
    }

    /** A separate CPU oracle; the production GLSL does not call this function or the layout mapper. */
    fun rgbAtInput(x: Int, y: Int, layout: StripRepackLayout, nonce: Int, frame: Int): Int {
        val region = y / layout.stripHeight
        val localY = y % layout.stripHeight
        val cell = cellWidth(layout.input.width)
        if (x >= MARKER_LEFT && x < MARKER_LEFT + MARKER_BITS * cell && localY in MARKER_TOP until MARKER_TOP + MARKER_HEIGHT) {
            return if (bit(marker(nonce, frame, region), (x - MARKER_LEFT) / cell) == 1) 0xffffff else 0
        }
        if (y >= layout.input.height - 20) return 0x10dcdc
        val r = 32 + (x / 32 % 6) * 32
        val g = 32 + (y / 32 % 6) * 32
        val b = 64 + region * 64
        return (r shl 16) or (g shl 8) or b
    }

    /** Encoded-space reference deliberately uses its own integer formula, not toInput(). */
    fun rgbAtEncoded(x: Int, y: Int, layout: StripRepackLayout, nonce: Int, frame: Int): Int {
        val sourceY = (x / layout.input.width) * layout.stripHeight + y
        return if (sourceY >= layout.input.height) 0 else rgbAtInput(x % layout.input.width, sourceY, layout, nonce, frame)
    }
}
