package com.dante.zeekrbridge.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerLog {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "bridge-log.txt")
        // Retained logs from old APKs must not re-export old credentials.
        runCatching {
            listOfNotNull(file, File(context.filesDir, "openavm-share/bridge-log-export.txt")).filter { it.exists() }.forEach { saved ->
                val safe = if (saved.length() <= 512 * 1024) ConnectionLogRedactor.redact(saved.readText()) else "Older diagnostic log removed on security upgrade.\n"
                saved.writeText(safe)
            }
        }
    }

    fun log(line: String) {
        val full = "[${synchronized(dateFormat) { dateFormat.format(Date()) }}] ${ConnectionLogRedactor.redact(line)}"
        _lines.value = (_lines.value + full).takeLast(2000)
        try {
            file?.appendText(full + "\n")
        } catch (t: Throwable) {
            // Ignore.
        }
    }
}
