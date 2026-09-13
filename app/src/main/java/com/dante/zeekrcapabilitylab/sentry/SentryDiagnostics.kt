package com.dante.zeekrcapabilitylab.sentry

import kotlinx.serialization.Serializable

/** Redacted scalar telemetry only: no frames, paths, camera IDs, volume IDs or credentials. */
@Serializable
data class SentryHealthSnapshot(
    val schemaVersion: Int = 1,
    val sampledAtMonotonicMs: Long,
    val runGeneration: Long,
    val transitionGeneration: Long,
    val encodedBytes: Long = 0,
    val encodedHighWaterBytes: Long = 0,
    val ringDurationUs: Long = 0,
    val pinnedBytes: Long = 0,
    val droppedGops: Long = 0,
    val codecDrainGapMs: Long = 0,
    val processPssKiB: Int = 0,
    val thermalStatus: Int? = null,
    val stopReason: SentryStopReason? = null,
)

class SentryDiagnosticHistory(private val capacity: Int = 120) {
    init { require(capacity in 1..1_000) }
    private val snapshots = ArrayDeque<SentryHealthSnapshot>()
    @Synchronized fun record(snapshot: SentryHealthSnapshot) {
        if (snapshots.size == capacity) snapshots.removeFirst()
        snapshots.addLast(snapshot)
    }
    @Synchronized fun snapshot(): List<SentryHealthSnapshot> = snapshots.toList()
}
