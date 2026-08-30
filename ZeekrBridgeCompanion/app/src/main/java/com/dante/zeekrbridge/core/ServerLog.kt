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
    }

    fun log(line: String) {
        val full = "[${synchronized(dateFormat) { dateFormat.format(Date()) }}] $line"
        _lines.value = (_lines.value + full).takeLast(2000)
        try {
            file?.appendText(full + "\n")
        } catch (t: Throwable) {
            // Ignore.
        }
    }
}
