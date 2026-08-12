package com.dante.zeekrcapabilitylab.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test
    fun systemModeFollowsProvidedSystemLanguage() {
        assertTrue(AppLanguage.usesChinese(AppLanguageMode.SYSTEM, "zh"))
        assertFalse(AppLanguage.usesChinese(AppLanguageMode.SYSTEM, "en"))
    }

    @Test
    fun explicitModesOverrideSystemLanguage() {
        assertTrue(AppLanguage.usesChinese(AppLanguageMode.SIMPLIFIED_CHINESE, "en"))
        assertFalse(AppLanguage.usesChinese(AppLanguageMode.ENGLISH, "zh"))
    }

    @Test
    fun unknownStoredValueFallsBackToSystem() {
        assertTrue(AppLanguageMode.fromStored("unknown") == AppLanguageMode.SYSTEM)
        assertTrue(AppLanguageMode.fromStored(null) == AppLanguageMode.SYSTEM)
    }
}
