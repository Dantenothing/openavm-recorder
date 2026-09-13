package io.github.dantenothing.openavm.i18n

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UiLanguageTest {
    @Test fun explicitChoicesAndOldPreferenceValuesAreStable() {
        UiLanguage.entries.forEach { assertEquals(it, UiLanguage.resolve(it.tag, Locale.JAPAN)) }
        assertEquals(UiLanguage.ENGLISH, UiLanguage.resolve("en", Locale.CHINA))
        assertEquals(UiLanguage.SIMPLIFIED_CHINESE, UiLanguage.resolve("zh-CN", Locale.TAIWAN))
    }
    @Test fun systemChineseHonorsScriptBeforeRegion() {
        for (tag in listOf("zh-Hant", "zh-TW", "zh-HK", "zh-MO", "zh-Hant-CN")) {
            assertEquals(tag, UiLanguage.TRADITIONAL_CHINESE, UiLanguage.resolve("system", Locale.forLanguageTag(tag)))
        }
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans-HK")) {
            assertEquals(tag, UiLanguage.SIMPLIFIED_CHINESE, UiLanguage.resolve("system", Locale.forLanguageTag(tag)))
        }
    }
    @Test fun systemFallbackAndDirection() {
        assertEquals(UiLanguage.ENGLISH, UiLanguage.resolve("system", Locale.JAPAN))
        assertEquals(UiLanguage.THAI, UiLanguage.resolve("system", Locale.forLanguageTag("th-TH")))
        assertEquals(UiLanguage.VIETNAMESE, UiLanguage.resolve("system", Locale.forLanguageTag("vi-VN")))
        assertEquals(UiLanguage.ARABIC, UiLanguage.resolve(null, Locale.forLanguageTag("ar-AU")))
        assertEquals(listOf(UiLanguage.ARABIC), UiLanguage.entries.filter { it.isRtl })
    }
    @Test fun argumentsCanReorderAndCannotInjectFormatTokens() {
        assertEquals("B / A", UiStrings.format("{1} / {0}", false, "A", "B"))
        assertEquals("100% {1}", UiStrings.format("{0}", false, "100% {1}"))
        assertEquals("\u2068clip-01.mp4\u2069", UiStrings.format("{0}", true, "clip-01.mp4"))
    }
    @Test fun duplicateEnglishWordsCanRetainDifferentChineseContexts() {
        assertNotEquals(UiStrings.key("Record", "录像"), UiStrings.key("Record", "记录"))
    }
}
