package io.github.dantenothing.openavm.i18n

import org.junit.Assert.*
import org.junit.Test

class UiCatalogTest {
    @Test fun everyBundledLanguageHasTheSameKeysAndSubstitutionTokens() {
        val english = UiStrings.catalog(UiLanguage.ENGLISH)
        val chinese = UiStrings.catalog(UiLanguage.SIMPLIFIED_CHINESE)
        assertTrue("Catalog resources must be present on the runtime classpath", english.size >= 900)
        val token = Regex("\\{\\d+\\}")
        UiLanguage.entries.forEach { language ->
            val entries = UiStrings.catalog(language)
            assertEquals(language.tag, english.keys, entries.keys)
            entries.forEach { (key, value) ->
                assertTrue("${language.tag}:$key", value.toString().isNotBlank())
                val reference = if (language == UiLanguage.SIMPLIFIED_CHINESE || language == UiLanguage.TRADITIONAL_CHINESE) chinese else english
                assertEquals("${language.tag}:$key", token.findAll(reference[key].toString()).map { it.value }.toSet(), token.findAll(value.toString()).map { it.value }.toSet())
            }
        }
    }
    @Test fun actualUiMessagesResolveToEveryRequestedLanguage() {
        val expected = mapOf(
            UiLanguage.ENGLISH to "Start recording",
            UiLanguage.SIMPLIFIED_CHINESE to "开始录像",
            UiLanguage.TRADITIONAL_CHINESE to "開始錄影",
            UiLanguage.THAI to "เริ่มบันทึก",
            UiLanguage.VIETNAMESE to "Bắt đầu quay",
            UiLanguage.ARABIC to "بدء التسجيل",
        )
        expected.forEach { (language, value) -> assertEquals(value, UiStrings.text(language, "Start recording", "开始录像")) }
    }
    @Test fun unknownTechnicalTextFallsBackWithoutBreakingSubstitution() {
        assertEquals("Technical code: \u2068AVM-42\u2069", UiStrings.text(UiLanguage.ARABIC, "Technical code: {0}", "代码：{0}", "AVM-42"))
    }
}
