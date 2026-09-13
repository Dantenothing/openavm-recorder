package com.dante.zeekrcapabilitylab.sentry

/** Evidence supplied by a future exact-owned storage adapter, not by a detector. */
data class SentryAssetCommitProof(
    val assetToken: String,
    val eventId: String,
    val sentrySessionId: String,
    val exactJournalOwnershipVerified: Boolean = false,
    val muxerStopSucceeded: Boolean = false,
    val syncedAndClosed: Boolean = false,
    val rereadVerified: Boolean = false,
    val sidecarCommitted: Boolean = false,
    val firstSampleSync: Boolean = false,
    val monotonicPts: Boolean = false,
    val singleFormatEpoch: Boolean = false,
    val decoded: Boolean = false,
    val sampleCount: Int = 0,
    val observedByteCount: Long = 0,
    val observedSha256: String? = null,
    val observedFirstPtsUs: Long? = null,
    val observedLastPtsUs: Long? = null,
)

data class SentryMediaGap(val startPtsUs: Long, val endPtsUs: Long)
data class SentryCoverageProof(
    val firstTriggerPtsUs: Long,
    val lastTriggerPtsUs: Long,
    val clockMappingVerified: Boolean = false,
    val gaps: List<SentryMediaGap> = emptyList(),
)
data class SentryManifestValidation(val errors: List<String>) {
    val canPublishManifest get() = errors.isEmpty()
}

/** S2B preparation only. No production writer, storage mutation or feature activation. */
object SentryEventIntegrity {
    private val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val sha = Regex("[0-9a-fA-F]{64}")

    fun validate(manifest: SentryEventManifest, proofs: List<SentryAssetCommitProof>, coverage: SentryCoverageProof): SentryManifestValidation {
        val errors = linkedSetOf<String>()
        fun need(value: Boolean, reason: String) { if (!value) errors += reason }
        need(manifest.schemaVersion == 1, "UNKNOWN_SCHEMA")
        need(uuid.matches(manifest.eventId) && uuid.matches(manifest.sentrySessionId), "INVALID_EVENT_IDENTITY")
        need(manifest.triggerTypes.isNotEmpty(), "MISSING_TRIGGER")
        need((manifest.severity == SentrySeverity.HARD) == (SentryTriggerType.TRUSTED_PHYSICAL in manifest.triggerTypes), "HARD_SEVERITY_REQUIRES_TRUSTED_PHYSICAL")
        need(manifest.involvedLanes.all { it in 0..3 }, "INVALID_LANE")
        need(manifest.armedAtEpochMs >= 0 && manifest.armedAtEpochMs <= manifest.eventStartedAtEpochMs &&
            manifest.eventStartedAtEpochMs <= manifest.firstTriggerAtEpochMs && manifest.firstTriggerAtEpochMs <= manifest.eventEndedAtEpochMs,
            "INVALID_EVENT_WALL_TIMELINE")
        need(listOf(manifest.preRollRequestedMs, manifest.preRollAchievedMs, manifest.postRollRequestedMs, manifest.postRollAchievedMs).all { it >= 0 },
            "NEGATIVE_COVERAGE")
        need(manifest.endReason.isNotBlank() && manifest.endReason.length <= 160 && manifest.coverageNotes.size <= 32 &&
            manifest.coverageNotes.all { it.length <= 256 }, "INVALID_COVERAGE_METADATA")
        val assets = manifest.assets
        need(assets.size <= 16, "ASSET_COUNT_LIMIT")
        need(assets.map { it.ordinal } == assets.indices.toList(), "ORDINALS_MUST_START_AT_ZERO_AND_BE_CONTIGUOUS")
        need(assets.map { it.assetToken }.distinct().size == assets.size, "DUPLICATE_ASSET")
        need(proofs.size == assets.size && proofs.map { it.assetToken }.toSet() == assets.map { it.assetToken }.toSet(), "PROOF_SET_MISMATCH")
        assets.forEach { asset ->
            need(uuid.matches(asset.assetToken) && asset.byteCount > 0 && asset.sha256?.matches(sha) == true, "INVALID_ASSET_METADATA")
            need(asset.firstMediaPtsUs >= 0 && asset.lastMediaPtsUs > asset.firstMediaPtsUs, "INVALID_ASSET_TIMELINE")
            val proof = proofs.singleOrNull { it.assetToken == asset.assetToken }
            if (proof == null) { errors += "MISSING_OR_DUPLICATE_ASSET_PROOF"; return@forEach }
            need(proof.eventId == manifest.eventId && proof.sentrySessionId == manifest.sentrySessionId && proof.exactJournalOwnershipVerified, "ASSET_OWNERSHIP_UNPROVEN")
            need(proof.muxerStopSucceeded && proof.syncedAndClosed && proof.rereadVerified && proof.sidecarCommitted, "ASSET_COMMIT_INCOMPLETE")
            need(proof.firstSampleSync && proof.monotonicPts && proof.singleFormatEpoch && proof.decoded && proof.sampleCount > 1, "ASSET_PLAYABILITY_UNPROVEN")
            need(proof.observedByteCount == asset.byteCount && proof.observedSha256 != null &&
                proof.observedSha256.equals(asset.sha256, true), "ASSET_CONTENT_MISMATCH")
            need(proof.observedFirstPtsUs == asset.firstMediaPtsUs && proof.observedLastPtsUs == asset.lastMediaPtsUs, "ASSET_PTS_MISMATCH")
        }
        need(assets.zipWithNext().all { (a, b) -> a.lastMediaPtsUs < b.firstMediaPtsUs }, "OVERLAPPING_OR_REORDERED_ASSETS")
        need(coverage.firstTriggerPtsUs >= 0 && coverage.lastTriggerPtsUs >= coverage.firstTriggerPtsUs, "INVALID_TRIGGER_TIMELINE")
        need(coverage.gaps.size <= 32 && coverage.gaps.all { it.startPtsUs >= 0 && it.endPtsUs > it.startPtsUs } &&
            coverage.gaps.zipWithNext().all { (a, b) -> a.endPtsUs <= b.startPtsUs }, "INVALID_GAP_TIMELINE")
        if (errors.isNotEmpty()) return SentryManifestValidation(errors.toList())

        fun coveredUs(from: Long, until: Long): Long {
            if (until <= from) return 0
            return assets.sumOf { asset ->
                val lo = maxOf(from, asset.firstMediaPtsUs)
                val hi = minOf(until, asset.lastMediaPtsUs)
                if (hi <= lo) 0 else (hi - lo) - coverage.gaps.sumOf { gap ->
                    (minOf(hi, gap.endPtsUs) - maxOf(lo, gap.startPtsUs)).coerceAtLeast(0)
                }
            }
        }
        val pre = assets.firstOrNull()?.let { coveredUs(it.firstMediaPtsUs, coverage.firstTriggerPtsUs) / 1000 } ?: 0
        val post = assets.lastOrNull()?.let { coveredUs(coverage.lastTriggerPtsUs, it.lastMediaPtsUs) / 1000 } ?: 0
        need(pre == manifest.preRollAchievedMs && post == manifest.postRollAchievedMs, "COVERAGE_NOT_SUPPORTED_BY_ASSETS")
        when (manifest.state) {
            SentryPersistenceState.COMPLETE, SentryPersistenceState.AWAITING_TRANSFER -> {
                need(assets.isNotEmpty(), "NO_PLAYABLE_ASSET")
                need(coverage.clockMappingVerified, "CLOCK_CALIBRATION_REQUIRED")
                need(coverage.gaps.isEmpty(), "COMPLETE_EVENT_HAS_GAP")
                need(pre >= manifest.preRollRequestedMs && post >= manifest.postRollRequestedMs, "REQUESTED_COVERAGE_NOT_MET")
                need(assets.isNotEmpty() && coverage.firstTriggerPtsUs >= assets.first().firstMediaPtsUs &&
                    coverage.lastTriggerPtsUs <= assets.last().lastMediaPtsUs, "TRIGGER_OUTSIDE_ASSET_COVERAGE")
            }
            SentryPersistenceState.PARTIAL -> {
                need(assets.isNotEmpty(), "NO_PLAYABLE_PARTIAL_ASSET")
                need(manifest.coverageNotes.isNotEmpty(), "PARTIAL_REASON_REQUIRED")
            }
            SentryPersistenceState.FAILED_STORAGE -> {
                need(assets.isEmpty() && pre == 0L && post == 0L, "RETAIN_VERIFIED_ASSETS_AS_PARTIAL")
            }
        }
        return SentryManifestValidation(errors.toList())
    }
}

enum class SentryOwnershipEvidence { UNBOUND, NOT_INSPECTED, MATCHED, MISMATCHED }
enum class SentryRecoveryProbe { NOT_RUN, VERIFIED_PLAYABLE, UNVERIFIED }
enum class SentryRecoveryAction {
    LEAVE_UNCLAIMED, WAIT_FOR_STORAGE, INSPECT_EXACT_URI, PROBE_EXACT_URI,
    RETAIN_UNTRUSTED, KEEP_VERIFIED_PARTIAL, KEEP_VERIFIED_COMPLETE,
}
data class SentryRecoveryFacts(
    val ownership: SentryOwnershipEvidence,
    val storageAvailable: Boolean,
    val probe: SentryRecoveryProbe = SentryRecoveryProbe.NOT_RUN,
    val completeEventManifestReverified: Boolean = false,
)

/** Produces read/retain decisions only; it cannot delete, scan a volume or restart capture. */
object SentryAssetRecoveryPolicy {
    fun decide(facts: SentryRecoveryFacts): SentryRecoveryAction = when {
        facts.ownership in setOf(SentryOwnershipEvidence.UNBOUND, SentryOwnershipEvidence.MISMATCHED) -> SentryRecoveryAction.LEAVE_UNCLAIMED
        !facts.storageAvailable -> SentryRecoveryAction.WAIT_FOR_STORAGE
        facts.ownership == SentryOwnershipEvidence.NOT_INSPECTED -> SentryRecoveryAction.INSPECT_EXACT_URI
        facts.probe == SentryRecoveryProbe.NOT_RUN -> SentryRecoveryAction.PROBE_EXACT_URI
        facts.probe == SentryRecoveryProbe.UNVERIFIED -> SentryRecoveryAction.RETAIN_UNTRUSTED
        facts.completeEventManifestReverified -> SentryRecoveryAction.KEEP_VERIFIED_COMPLETE
        else -> SentryRecoveryAction.KEEP_VERIFIED_PARTIAL
    }
}
