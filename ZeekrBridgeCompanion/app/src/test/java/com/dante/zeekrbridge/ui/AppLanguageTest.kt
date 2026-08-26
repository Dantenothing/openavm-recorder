package com.dante.zeekrbridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun storedValuesRemainCompatibleWithV210() {
        assertEquals(PhoneLanguageMode.SYSTEM, PhoneLanguageMode.fromStored("system"))
        assertEquals(PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.fromStored("zh-CN"))
        assertEquals(PhoneLanguageMode.ENGLISH, PhoneLanguageMode.fromStored("en"))
        assertEquals(PhoneLanguageMode.SYSTEM, PhoneLanguageMode.fromStored("unknown"))
    }

    @Test
    fun explicitModeOverridesSystemLocale() {
        assertTrue(PhoneLanguage.usesChinese(PhoneLanguageMode.SIMPLIFIED_CHINESE, "en"))
        assertFalse(PhoneLanguage.usesChinese(PhoneLanguageMode.ENGLISH, "zh"))
    }

    @Test
    fun systemModeFollowsChineseLocaleOnly() {
        assertTrue(PhoneLanguage.usesChinese(PhoneLanguageMode.SYSTEM, "zh"))
        assertFalse(PhoneLanguage.usesChinese(PhoneLanguageMode.SYSTEM, "en"))
    }
}
