package com.dante.zeekrbridge.core

/** RFC-7233 byte-range handling. Only `bytes=start-` / `bytes=start-end` are supported. */
sealed class HttpRange {
    data class Full(val size: Long) : HttpRange()
    data class Partial(val start: Long, val end: Long) : HttpRange()
    data class Unsatisfiable(val size: Long) : HttpRange()
}

object HttpRangeParser {
    fun parse(header: String?, size: Long): HttpRange {
        if (size < 0) return HttpRange.Full(size)
        val h = header?.trim().orEmpty()
        if (!h.startsWith("bytes=", ignoreCase = true)) return HttpRange.Full(size)
        val spec = h.substringAfter('=').trim()
        val dash = spec.indexOf('-')
        if (dash <= 0) return HttpRange.Full(size)
        val start = spec.substring(0, dash).trim().toLongOrNull() ?: return HttpRange.Full(size)
        if (start < 0) return HttpRange.Full(size)
        if (start >= size) return HttpRange.Unsatisfiable(size)
        val endSpec = spec.substring(dash + 1).trim()
        val end = if (endSpec.isEmpty()) {
            size - 1
        } else {
            val parsed = endSpec.toLongOrNull() ?: return HttpRange.Full(size)
            minOf(parsed, size - 1)
        }
        if (end < start) return HttpRange.Unsatisfiable(size)
        return HttpRange.Partial(start, end)
    }
}
