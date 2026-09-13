package com.dante.zeekrbridge.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import io.github.dantenothing.openavm.i18n.UiLanguage
import io.github.dantenothing.openavm.i18n.UiStrings

enum class PhoneLanguageMode(val storedValue: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
    TRADITIONAL_CHINESE("zh-TW"),
    THAI("th"),
    VIETNAMESE("vi"),
    ARABIC("ar");

    companion object {
        fun fromStored(value: String?): PhoneLanguageMode =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

object PhoneLanguage {
    private const val PREFS_NAME = "zeekr_phone_language"
    private const val KEY_MODE = "mode"

    private var appContext: Context? = null

    var mode by mutableStateOf(PhoneLanguageMode.SYSTEM)
        private set
    private var systemLocale by mutableStateOf(Locale.getDefault())
    val language: UiLanguage get() = UiLanguage.resolve(mode.storedValue, systemLocale)
    val locale: Locale get() = language.locale
    fun updateSystemLocale(locale: Locale) { systemLocale = locale }
    fun label(mode: PhoneLanguageMode): String = if (mode == PhoneLanguageMode.SYSTEM)
        text("Follow system", "跟随系统") else UiLanguage.resolve(mode.storedValue).nativeName

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mode = PhoneLanguageMode.fromStored(prefs.getString(KEY_MODE, null))
        updateSystemLocale(context.resources.configuration.locales[0])
    }

    fun selectMode(newMode: PhoneLanguageMode) {
        mode = newMode
        appContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_MODE, newMode.storedValue)
            ?.apply()
    }

    fun usesChinese(
        selectedMode: PhoneLanguageMode = mode,
        systemLanguage: String = Locale.getDefault().language,
    ): Boolean = when (selectedMode) {
        PhoneLanguageMode.SIMPLIFIED_CHINESE -> true
        PhoneLanguageMode.TRADITIONAL_CHINESE -> true
        PhoneLanguageMode.SYSTEM -> systemLanguage.equals("zh", ignoreCase = true)
        else -> false
    }

    fun text(en: String, zh: String, vararg args: Any?): String = UiStrings.text(language, en, zh, *args)
}

fun t(en: String, zh: String, vararg args: Any?): String = PhoneLanguage.text(en, zh, *args)
