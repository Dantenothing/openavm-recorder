package com.dante.zeekrcapabilitylab.sharing

/** Extended from our Companion HttpRangeParser. One RFC 9110 byte range, no multipart. */
sealed interface ByteRange {
    data class Full(val size: Long) : ByteRange
    data class Partial(val start: Long, val end: Long) : ByteRange {
        val length: Long get() = end - start + 1
    }
    data class Unsatisfiable(val size: Long) : ByteRange
}

object ByteRanges {
    fun resolve(header: String?, size: Long, ifRange: String? = null, etag: String = ""): ByteRange {
        require(size >= 0)
        // Ignore Range for empty representations and mismatched/unsupported validators.
        if (size == 0L || (ifRange != null && (ifRange != etag || !etag.startsWith('"'))))
            return ByteRange.Full(size)
        val text = header?.trim() ?: return ByteRange.Full(size)
        if (!text.startsWith("bytes=", ignoreCase = true)) return ByteRange.Full(size)
        val spec = text.substring(6).trim()
        if (',' in spec || spec.length > 1024) return ByteRange.Full(size)
        val parts = spec.split('-')
        if (parts.size != 2) return ByteRange.Full(size)
        val first = parts[0].trim(); val last = parts[1].trim()
        if ((first.isEmpty() && last.isEmpty()) ||
            first.any { it !in '0'..'9' } || last.any { it !in '0'..'9' }) return ByteRange.Full(size)
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull() ?: Long.MAX_VALUE
            if (suffix == 0L) return ByteRange.Unsatisfiable(size)
            return ByteRange.Partial((size - suffix).coerceAtLeast(0), size - 1)
        }
        val start = first.toLongOrNull() ?: return ByteRange.Unsatisfiable(size)
        if (start >= size) return ByteRange.Unsatisfiable(size)
        val end = if (last.isEmpty()) size - 1 else minOf(last.toLongOrNull() ?: Long.MAX_VALUE, size - 1)
        return if (end < start) ByteRange.Unsatisfiable(size) else ByteRange.Partial(start, end)
    }
}
