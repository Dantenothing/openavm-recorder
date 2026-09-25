package com.dante.zeekrcapabilitylab.service.recorder

/** Historical protection is scoped to the active manual session, not the newest files on a drive. */
data class IncidentCandidate(val id: String, val sessionId: String?, val segment: Int, val eventId: String?)

object IncidentSelection {
    fun previous(candidates: List<IncidentCandidate>, sessionId: String, currentSegment: Int,
        eventId: String, count: Int = 2): Set<String> = candidates.asSequence()
        .filter { it.sessionId == sessionId && it.segment in 1 until currentSegment }
        .sortedByDescending { it.segment }.distinctBy { it.segment }.take(count.coerceIn(0, 2))
        .filter { it.eventId.isNullOrBlank() || it.eventId == eventId }.map { it.id }.toSet()
}
