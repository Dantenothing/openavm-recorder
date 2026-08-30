package com.dante.zeekrbridge.sound

/** User-facing filename sanitization for FAT/Windows and `.wav` suffix rules. */
object SoundFileNames {
    private const val MAX_BASE = 180
    private val FAT_BAD = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    fun cleanBase(raw: String?): String? {
        if (raw == null) return null
        var name = raw.trim().replace('\\', '/').substringAfterLast('/')
        name = name.map { c ->
            if (c.code < 0x20 || c in FAT_BAD) ' ' else c
        }.joinToString("")
        name = name.trim(' ', '.')
        name = name.replace(Regex("[ \\t]+"), " ")
        if (name.isEmpty() || name == "." || name == "..") return null
        val dot = name.lastIndexOf('.')
        var base = if (dot > 0) name.substring(0, dot) else name
        base = base.take(MAX_BASE).trimEnd(' ', '.')
        return base.ifBlank { null }
    }

    fun wavFileName(raw: String?, fallback: String = "sound.wav"): String {
        val base = cleanBase(raw)
            ?: cleanBase(fallback)
            ?: "sound"
        return "$base.wav"
    }
}
