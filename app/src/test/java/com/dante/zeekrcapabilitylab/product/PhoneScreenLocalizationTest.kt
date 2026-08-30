package com.dante.zeekrcapabilitylab.product

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneScreenLocalizationTest {
    @Test
    fun chinesePhoneScreenCopyAlwaysUsesTheLanguageBoundary() {
        val source = sequenceOf(
            File("src/main/java/com/dante/zeekrcapabilitylab/ui/product/PhoneScreen.kt"),
            File("app/src/main/java/com/dante/zeekrcapabilitylab/ui/product/PhoneScreen.kt"),
        ).firstOrNull(File::isFile) ?: error("PhoneScreen.kt not found")

        val han = Regex("[\\u3400-\\u9fff]")
        val violations = source.readLines().mapIndexedNotNull { index, line ->
            if (han.containsMatchIn(line) && !line.contains("Utils.t(")) "${index + 1}: ${line.trim()}" else null
        }

        assertTrue(
            "Hard-coded Chinese copy bypasses AppLanguage:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
