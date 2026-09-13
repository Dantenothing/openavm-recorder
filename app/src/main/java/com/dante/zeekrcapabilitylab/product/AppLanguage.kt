package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.dantenothing.openavm.i18n.UiLanguage
import io.github.dantenothing.openavm.i18n.UiStrings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguageMode(val storedValue: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
    TRADITIONAL_CHINESE("zh-TW"),
    THAI("th"),
    VIETNAMESE("vi"),
    ARABIC("ar"),
    ;

    companion object {
        fun fromStored(value: String?): AppLanguageMode =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/** Product-language preference. SYSTEM is the default and follows the head unit. */
object AppLanguage {
    private const val PREFS = "zeekr_product_language"
    private const val KEY_MODE = "mode"

    private var preferences: SharedPreferences? = null
    private val _mode = MutableStateFlow(AppLanguageMode.SYSTEM)
    val mode: StateFlow<AppLanguageMode> = _mode.asStateFlow()
    private var selectedMode by mutableStateOf(AppLanguageMode.SYSTEM)
    private var systemLocale by mutableStateOf(Locale.getDefault())
    val language: UiLanguage get() = UiLanguage.resolve(selectedMode.storedValue, systemLocale)
    val locale: Locale get() = language.locale
    fun updateSystemLocale(locale: Locale) { systemLocale = locale }
    fun label(mode: AppLanguageMode): String = if (mode == AppLanguageMode.SYSTEM)
        text("Follow system", "跟随系统") else UiLanguage.resolve(mode.storedValue).nativeName

    @Synchronized
    fun init(context: Context) {
        if (preferences != null) return
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mode.value = AppLanguageMode.fromStored(preferences?.getString(KEY_MODE, null))
        selectedMode = _mode.value
        updateSystemLocale(context.resources.configuration.locales[0])
    }

    fun setMode(context: Context, mode: AppLanguageMode) {
        init(context)
        preferences?.edit()?.putString(KEY_MODE, mode.storedValue)?.apply()
        _mode.value = mode
        selectedMode = mode
    }

    fun usesChinese(
        mode: AppLanguageMode = _mode.value,
        systemLanguage: String = Locale.getDefault().language,
    ): Boolean = when (mode) {
        AppLanguageMode.SYSTEM -> systemLanguage.startsWith("zh", ignoreCase = true)
        AppLanguageMode.SIMPLIFIED_CHINESE -> true
        AppLanguageMode.TRADITIONAL_CHINESE -> true
        else -> false
    }

    fun text(en: String, zh: String, vararg args: Any?): String = UiStrings.text(language, en, zh, *args)
}
