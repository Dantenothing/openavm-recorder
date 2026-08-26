package com.dante.zeekrbridge.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class PhoneLanguageMode(val storedValue: String) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en");

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

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mode = PhoneLanguageMode.fromStored(prefs.getString(KEY_MODE, null))
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
        PhoneLanguageMode.ENGLISH -> false
        PhoneLanguageMode.SYSTEM -> systemLanguage.equals("zh", ignoreCase = true)
    }

    fun text(en: String, zh: String): String = if (usesChinese()) zh else en
}

fun t(en: String, zh: String): String = PhoneLanguage.text(en, zh)
