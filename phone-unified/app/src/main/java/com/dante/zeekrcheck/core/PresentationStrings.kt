package com.dante.zeekrcheck.core

/** Presentation only. Stored telemetry, error codes and command targets keep their canonical values.
 * The catalogue also covers historical operation messages saved by earlier Chinese-only versions.
 * Unknown text is preserved, never replaced by a generic success or hidden error.
 */
object PresentationStrings {
    val english: Map<String, String> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/ui-en.tsv")).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                val pair = line.split('\t', limit = 2)
                require(pair.size == 2 && pair[1].isNotBlank())
                pair[0] to pair[1]
            }
        }
    }
    private val han = Regex("[\\u3400-\\u9fff]+")
    fun render(value: String, chinese: Boolean): String {
        if (chinese || !han.containsMatchIn(value)) return value
        english[value]?.let { return it }
        return han.replace(value) { match -> english[match.value]?.let { " $it " } ?: match.value }
            .replace("，", ", ").replace("。", ". ").replace("：", ": ").replace("；", "; ")
            .replace("（", " (").replace("）", ") ").replace("、", ", ")
            .replace("“", "\"").replace("”", "\"").replace("‘", "'").replace("’", "'")
            .replace("？", "? ").replace("！", "! ")
            .replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" +([,.;:!?])"), "$1").trim()
    }
}
