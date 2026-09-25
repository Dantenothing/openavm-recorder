package com.dante.zeekrcapabilitylab.sharing

import java.io.InputStream
import java.io.IOException
import java.util.Locale

class ShareHttpError(val status: Int) : IOException("HTTP $status")

data class ShareHttpRequest(val method: String, val target: String, val headers: Map<String, String>) {
    val headOnly: Boolean get() = method == "HEAD"

    companion object {
        /** Deliberately limited to browser GET/HEAD, one request per connection. */
        fun read(input: InputStream): ShareHttpRequest {
            var total = 0
            fun line(): String {
                val out = StringBuilder()
                while (true) {
                    val value = input.read()
                    if (value < 0 || ++total > 16_384 || out.length >= 4096) throw ShareHttpError(400)
                    if (value == 13) {
                        if (input.read() != 10 || ++total > 16_384) throw ShareHttpError(400)
                        return out.toString()
                    }
                    if (value == 10 || value < 32 && value != 9 || value > 126) throw ShareHttpError(400)
                    out.append(value.toChar())
                }
            }
            val request = line().split(' ')
            if (request.size != 3 || request[2] != "HTTP/1.1" || !request[1].startsWith('/') ||
                request[1].length > 2048 || request[1].any { it <= ' ' || it == '#' }) throw ShareHttpError(400)
            if (request[0] !in setOf("GET", "HEAD")) throw ShareHttpError(405)
            val headers = linkedMapOf<String, String>()
            while (true) {
                val text = line()
                if (text.isEmpty()) break
                val colon = text.indexOf(':')
                if (colon <= 0 || headers.size >= 64) throw ShareHttpError(400)
                val key = text.substring(0, colon)
                if (key.any { !it.isLetterOrDigit() && it != '-' }) throw ShareHttpError(400)
                val normalized = key.lowercase(Locale.ROOT)
                if (headers.put(normalized, text.substring(colon + 1).trim()) != null) throw ShareHttpError(400)
            }
            if (headers["host"].isNullOrBlank() || headers.containsKey("transfer-encoding") ||
                headers["content-length"]?.let { it != "0" } == true || headers.containsKey("expect"))
                throw ShareHttpError(400)
            return ShareHttpRequest(request[0], request[1], headers)
        }
    }
}
