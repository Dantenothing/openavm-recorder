package com.dante.zeekrbridge.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 computed with a fixed-size buffer; never loads the whole file. */
object StreamingSha256 {
    const val BUFFER_SIZE = 64 * 1024

    fun hash(file: File): String =
        file.inputStream().use { hash(it) }

    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun hashOrUnavailable(file: File): String =
        try {
            hash(file)
        } catch (t: Throwable) {
            "UNAVAILABLE"
        }
}
