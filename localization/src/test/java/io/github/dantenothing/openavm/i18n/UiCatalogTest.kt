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
    @Test fun dateAndEmergencyActionsAreBundledInEveryLanguage() {
        val messages = listOf("All dates" to "全部日期", "Today" to "今天", "Yesterday" to "昨天",
            "Choose date" to "选择日期", "Filtered by recording start date" to "按录像开始日期筛选",
            "No recordings for this date and category" to "这个日期和分类下暂无录像",
            "SOS" to "紧急", "Save emergency video" to "保存紧急视频",
            "Marking emergency video…" to "正在标记紧急视频…",
            "Available during normal recording" to "普通录像时可保存紧急视频")
        UiLanguage.entries.forEach { language -> messages.forEach { (en, zh) ->
            assertNotNull("${language.tag}: $en", UiStrings.catalog(language).getProperty(UiStrings.key(en, zh)))
        } }
    }
    @Test fun unknownTechnicalTextFallsBackWithoutBreakingSubstitution() {
        assertEquals("Technical code: \u2068AVM-42\u2069", UiStrings.text(UiLanguage.ARABIC, "Technical code: {0}", "代码：{0}", "AVM-42"))
    }
    @Test fun returnSetupAndWakeFeedbackNeverFallBackToEnglish() {
        val messages = listOf(
            "When you return" to "回车后的行为", "Ready when you return" to "回车后，按你的习惯恢复",
            "Full floating window" to "完整悬浮窗", "Also start recording" to "同时自动开始录像",
            "Save settings" to "保存设置", "Waiting for the camera to wake…" to "正在等待相机就绪…",
            "Camera not ready. Try again shortly." to "相机尚未就绪，请稍后重试。",
            "4 · When you return" to "4 · 回车后自动恢复",
            "5 · Keep an important moment" to "5 · 保存重要时刻",
            "6 · Find and manage videos" to "6 · 查找与管理录像",
            "7 · Phone, sounds and help" to "7 · 手机、音效与帮助",
        )
        UiLanguage.entries.forEach { language -> messages.forEach { (en, zh) ->
            assertNotNull("${language.tag}: $en", UiStrings.catalog(language).getProperty(UiStrings.key(en, zh)))
            if (language != UiLanguage.ENGLISH) assertNotEquals("${language.tag}: $en", en, UiStrings.text(language, en, zh))
        } }
    }
}
