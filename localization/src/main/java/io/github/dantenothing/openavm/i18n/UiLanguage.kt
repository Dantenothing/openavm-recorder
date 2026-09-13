package io.github.dantenothing.openavm.i18n

import java.util.Locale

enum class UiLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    SIMPLIFIED_CHINESE("zh-CN", "简体中文"),
    TRADITIONAL_CHINESE("zh-TW", "繁體中文"),
    THAI("th", "ไทย"),
    VIETNAMESE("vi", "Tiếng Việt"),
    ARABIC("ar", "العربية");

    val locale: Locale get() = Locale.forLanguageTag(tag)
    val isRtl: Boolean get() = this == ARABIC

    companion object {
        /** Explicit choices retain their old preference values; unknown system languages use English. */
        fun resolve(stored: String?, systemLocale: Locale = Locale.getDefault()): UiLanguage {
            entries.firstOrNull { it.tag == stored }?.let { return it }
            return fromLocale(systemLocale)
        }

        fun fromLocale(locale: Locale): UiLanguage = when (locale.language.lowercase(Locale.ROOT)) {
            "zh" -> when {
                locale.script.equals("Hant", ignoreCase = true) -> TRADITIONAL_CHINESE
                locale.script.equals("Hans", ignoreCase = true) -> SIMPLIFIED_CHINESE
                locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") -> TRADITIONAL_CHINESE
                else -> SIMPLIFIED_CHINESE
            }
            "th" -> THAI
            "vi" -> VIETNAMESE
            "ar" -> ARABIC
            else -> ENGLISH
        }
    }
}
