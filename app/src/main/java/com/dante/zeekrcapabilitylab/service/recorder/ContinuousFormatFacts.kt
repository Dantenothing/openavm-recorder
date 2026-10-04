package com.dante.zeekrcapabilitylab.service.recorder

/** Fixed diagnostic schema. Never accepts raw MediaFormat keys, values, buffers or hashes. */
internal object ContinuousFormatFacts {
    const val EVENT = "RECORDER_CODEC_FORMAT"
    const val MAX_FIELDS = 24
    val decisions = setOf("INSTALL", "KEEP", "INCOMPLETE", "INCOMPATIBLE", "ROLLOVER_PENDING", "ROLLOVER_APPLIED", "ROLLOVER_CANCELLED")
    val fields = setOf("MIME", "WIDTH", "HEIGHT", "HDR_STATIC", "PROFILE", "LEVEL", "COLOR_STANDARD",
        "COLOR_RANGE", "COLOR_TRANSFER", "ROTATION", "CROP_LEFT", "CROP_TOP", "CROP_RIGHT", "CROP_BOTTOM",
        "SAR_WIDTH", "SAR_HEIGHT", "FORMAT_KEYS", "FORMAT_COPY", "FORMAT_TRANSITION") + (0..7).map { "CSD_$it" }
    val kinds = setOf("CHANGED", "MISSING", "TYPE_MISMATCH", "EMPTY", "INVALID", "OUT_OF_RANGE", "COPY_FAILED")
    private val counts = setOf("formatNotifications", "formatCompatibleRepeats", "formatObservedElapsedMs", "formatFieldsOmitted")
    val keys = counts + setOf("formatDecision", "formatCutPending") + fields.map { "formatField_$it" }

    fun observation(value: ContinuousFormatObservation, cutPending: Boolean?, elapsedMs: Long): Map<String, String> =
        sanitize(mapOf("formatDecision" to value.decision, "formatNotifications" to value.notificationCount.toString(),
            "formatCompatibleRepeats" to value.compatibleRepeats.toString(), "formatObservedElapsedMs" to elapsedMs.toString(),
            "formatCutPending" to (cutPending?.toString() ?: "UNKNOWN")) + differences(value.fields))

    fun differences(values: List<ContinuousTrackFieldEvidence>): Map<String, String> {
        val selected = values.asSequence().filter { it.field in fields && it.kind in kinds }
            .distinctBy { it.field }.take(MAX_FIELDS).toList()
        return selected.associate { row -> "formatField_${row.field}" to listOf(row.kind,
            row.beforePresent?.toString() ?: "UNKNOWN", row.afterPresent?.toString() ?: "UNKNOWN",
            row.beforeBytes?.takeIf { it >= 0 }?.toString() ?: "UNKNOWN",
            row.afterBytes?.takeIf { it >= 0 }?.toString() ?: "UNKNOWN").joinToString("|") } +
            mapOf("formatFieldsOmitted" to (values.size - selected.size).coerceAtLeast(0).toString())
    }

    /** Validate persisted facts again before either clipboard exporter sees them. */
    fun sanitize(facts: Map<String, String>): Map<String, String> = buildMap {
        facts["formatDecision"]?.takeIf { it in decisions }?.let { put("formatDecision", it) }
        counts.forEach { key -> facts[key]?.toLongOrNull()?.takeIf { it >= 0 }?.let { put(key, it.toString()) } }
        facts["formatCutPending"]?.toBooleanStrictOrNull()?.let { put("formatCutPending", it.toString()) }
        fields.asSequence().mapNotNull { field -> facts["formatField_$field"]?.let { field to it } }
            .filter { (_, value) -> decode(value) != null }.take(MAX_FIELDS)
            .forEach { (field, value) -> put("formatField_$field", value) }
    }

    fun decode(value: String): List<String>? {
        if (value.length > 100) return null
        val pieces = value.split('|')
        return pieces.takeIf { it.size == 5 && it[0] in kinds &&
            (1..2).all { i -> it[i] == "UNKNOWN" || it[i].toBooleanStrictOrNull() != null } &&
            (3..4).all { i -> it[i] == "UNKNOWN" || it[i].toIntOrNull()?.let { size -> size >= 0 } == true } }
    }
}
