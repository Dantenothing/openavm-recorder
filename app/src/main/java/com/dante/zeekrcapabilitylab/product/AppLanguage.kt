package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguageMode(val storedValue: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
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

    @Synchronized
    fun init(context: Context) {
        if (preferences != null) return
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mode.value = AppLanguageMode.fromStored(preferences?.getString(KEY_MODE, null))
    }

    fun setMode(context: Context, mode: AppLanguageMode) {
        init(context)
        preferences?.edit()?.putString(KEY_MODE, mode.storedValue)?.apply()
        _mode.value = mode
    }

    fun usesChinese(
        mode: AppLanguageMode = _mode.value,
        systemLanguage: String = Locale.getDefault().language,
    ): Boolean = when (mode) {
        AppLanguageMode.SYSTEM -> systemLanguage.startsWith("zh", ignoreCase = true)
        AppLanguageMode.SIMPLIFIED_CHINESE -> true
        AppLanguageMode.ENGLISH -> false
    }

    fun text(en: String, zh: String): String = if (usesChinese()) zh else en
}
