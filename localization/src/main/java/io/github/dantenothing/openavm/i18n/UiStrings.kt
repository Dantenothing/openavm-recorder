package io.github.dantenothing.openavm.i18n

import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Offline UTF-8 catalog shared by the head unit and companion. No network or model dependency. */
object UiStrings {
    private val keys = ConcurrentHashMap<Pair<String, String>, String>()
    private val catalogs = ConcurrentHashMap<UiLanguage, Properties>()
    private val argument = Regex("\\{(\\d+)\\}")

    fun key(en: String, zh: String): String = keys.getOrPut(en to zh) {
        MessageDigest.getInstance("SHA-256").digest((en + '\u0000' + zh).toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
    }

    fun catalog(language: UiLanguage): Properties = catalogs.getOrPut(language) {
        Properties().apply {
            UiStrings::class.java.getResourceAsStream("/openavm-i18n/${language.tag}.properties")
                ?.bufferedReader(Charsets.UTF_8)?.use { load(it) }
        }
    }

    fun text(language: UiLanguage, en: String, zh: String, vararg args: Any?): String {
        val template = when (language) {
            UiLanguage.ENGLISH -> en
            UiLanguage.SIMPLIFIED_CHINESE -> zh
            else -> catalog(language).getProperty(key(en, zh)) ?: en
        }
        return format(template, language.isRtl, *args)
    }

    /** Numbered tokens allow different word order without executing translated text as a format. */
    internal fun format(template: String, rtl: Boolean, vararg args: Any?): String =
        argument.replace(template) { match ->
            val index = match.groupValues[1].toIntOrNull()
            if (index == null || index !in args.indices) match.value
            else (args[index]?.toString() ?: "").let { if (rtl) "\u2068$it\u2069" else it }
        }
}
