package com.dante.zeekrcapabilitylab.diagnostic

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.service.recorder.ContinuousFormatFacts
import kotlinx.serialization.json.*

/** The same bounded, typed projection for recorder and away clipboard reports. */
internal object CodecFormatReport {
    const val MAX_GROUPS = 6
    const val MAX_BYTES = 10 * 1024
    private val failureEvents = setOf("RECORDER_CONTINUOUS_FIRST_FAILURE", "RECORDER_CONTINUOUS_FAILED")
    data class Row(val event: String, val epochMs: Long, val elapsedMs: Long, val facts: Map<String, String>, val identity: String)
    fun row(event: ProbeEvent) = Row(event.eventName, event.epochMs, event.elapsedRealtimeMs, event.payload,
        "${event.epochMs}/${event.sequence}/${event.eventName}")
    fun row(event: AwayRow) = Row(event.event, event.wall, event.elapsed, event.facts,
        "${event.process}/${event.wall}/${event.elapsed}/${event.event}/${event.facts["recordingSessionId"]}/${event.facts["channelId"]}/" +
            ContinuousFormatFacts.sanitize(event.facts).toString())

    fun relevant(event: String, facts: Map<String, String>) = event == ContinuousFormatFacts.EVENT ||
        event in failureEvents && facts["formatDecision"] in ContinuousFormatFacts.decisions

    fun format(facts: Map<String, String>, compact: Boolean = false): JsonObject? {
        val safe = ContinuousFormatFacts.sanitize(facts)
        val decision = safe["formatDecision"] ?: return null
        return buildJsonObject {
            put("decision", decision)
            put("notifications", safe["formatNotifications"]?.toLongOrNull())
            put("compatibleRepeats", safe["formatCompatibleRepeats"]?.toLongOrNull())
            put("observedAtElapsedMs", safe["formatObservedElapsedMs"]?.toLongOrNull())
            put("cutPending", safe["formatCutPending"]?.toBooleanStrictOrNull())
            put("fieldsOmitted", safe["formatFieldsOmitted"]?.toLongOrNull() ?: 0)
            if (compact) put("fieldDetailsOmittedForSize", true)
            put(if (compact) "fieldKinds" else "fields", buildJsonObject {
                safe.forEach { (key, value) -> if (key.startsWith("formatField_")) {
                    val row = ContinuousFormatFacts.decode(value) ?: return@forEach
                    if (compact) put(key.removePrefix("formatField_"), row[0])
                    else put(key.removePrefix("formatField_"), buildJsonObject {
                        put("kind", row[0])
                        put("beforePresent", row[1].toBooleanStrictOrNull()); put("afterPresent", row[2].toBooleanStrictOrNull())
                        put("beforeBytes", row[3].toIntOrNull()); put("afterBytes", row[4].toIntOrNull())
                    })
                } }
            })
        }
    }

    private fun key(row: Row): String {
        val correlation = ProcessExitReport.sessionCorrelation(row.facts["recordingSessionId"])
        val channel = ProcessExitReport.channel(row.facts["channelId"])
        return if (correlation != null && channel != "UNKNOWN") "$correlation/$channel" else row.identity
    }

    private fun groups(rows: List<Row>): List<List<Row>> = rows.asSequence()
        .filter { relevant(it.event, it.facts) && format(it.facts) != null }
        .distinctBy { it.identity }.sortedWith(compareBy<Row> { it.epochMs }.thenBy { it.elapsedMs })
        .groupBy(::key).values.sortedBy { it.last().epochMs }.takeLast(MAX_GROUPS)

    private fun meaningful(group: List<Row>): Row? = group.lastOrNull { row ->
        val facts = ContinuousFormatFacts.sanitize(row.facts)
        facts.keys.any { it.startsWith("formatField_") } ||
            facts["formatDecision"] in setOf("INCOMPLETE", "INCOMPATIBLE", "ROLLOVER_PENDING", "ROLLOVER_APPLIED", "ROLLOVER_CANCELLED")
    }

    fun retained(rows: List<Row>): Set<String> = groups(rows).flatMap {
        listOfNotNull(it.first().identity, it.last().identity, meaningful(it)?.identity)
    }.toSet()

    fun report(rows: List<Row>): JsonObject {
        val groups = groups(rows).toMutableList()
        var compact = false
        var omitted = 0
        fun edge(row: Row) = buildJsonObject {
            put("event", row.event); put("atEpochMs", row.epochMs); put("atElapsedMs", row.elapsedMs)
            put("format", format(row.facts, compact) ?: JsonNull)
        }
        fun render() = buildJsonObject {
            put("scope", "RETAINED_FORMAT_OBSERVATIONS_PER_SESSION_AND_CHANNEL")
            put("historyMayBeIncomplete", true); put("groupsOmittedForSize", omitted)
            put("groups", buildJsonArray { groups.forEach { group -> add(buildJsonObject {
                put("sessionCorrelation", ProcessExitReport.sessionCorrelation(group.first().facts["recordingSessionId"]))
                put("channelId", ProcessExitReport.channel(group.first().facts["channelId"]))
                put("firstAvailable", edge(group.first())); put("latestAvailable", edge(group.last()))
                put("latestMeaningfulChange", meaningful(group)?.let(::edge) ?: JsonNull)
            }) } })
        }
        var result = render()
        if (result.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            compact = true; result = render() // Preserve every field label/kind before dropping older groups.
        }
        while (groups.isNotEmpty() && result.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            groups.removeAt(0); omitted++; result = render()
        }
        return result
    }
}
