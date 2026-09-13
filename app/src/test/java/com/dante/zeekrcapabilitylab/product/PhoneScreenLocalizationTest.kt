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

        val violations = chineseOutsideLanguageBoundary(source.readText())

        assertTrue(
            "Hard-coded Chinese copy bypasses AppLanguage:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    private fun chineseOutsideLanguageBoundary(source: String): List<String> {
        val violations = linkedSetOf<String>()
        val lines = source.lines()
        val utilsCallDepths = ArrayDeque<Int>()
        var parenthesisDepth = 0
        var inString = false
        var escaped = false
        var lineNumber = 1
        var index = 0

        while (index < source.length) {
            val char = source[index]
            if (char == '\n') {
                lineNumber++
                escaped = false
                index++
                continue
            }
            if (inString) {
                if (char in '\u3400'..'\u9fff' && utilsCallDepths.isEmpty()) {
                    violations += "$lineNumber: ${lines.getOrElse(lineNumber - 1) { "" }.trim()}"
                }
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                index++
                continue
            }
            if (source.startsWith("Utils.t(", index)) {
                parenthesisDepth++
                utilsCallDepths.addLast(parenthesisDepth)
                index += "Utils.t(".length
                continue
            }
            when (char) {
                '"' -> inString = true
                '(' -> parenthesisDepth++
                ')' -> {
                    if (utilsCallDepths.lastOrNull() == parenthesisDepth) {
                        utilsCallDepths.removeLast()
                    }
                    parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                }
            }
            index++
        }
        return violations.toList()
    }
}
