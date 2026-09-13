package com.dante.zeekrcapabilitylab.sentry

import org.junit.Assert.*
import org.junit.Test

class SentryEventIntegrityTest {
    private val eventId = "11111111-1111-1111-1111-111111111111"
    private val sessionId = "22222222-2222-2222-2222-222222222222"
    private val token = "33333333-3333-3333-3333-333333333333"
    private val asset = SentryEventAsset(0, token, 0, 40_000_000, 1000, "ab".repeat(32))
    private fun manifest() = SentryEventManifest(eventId = eventId, sentrySessionId = sessionId, state = SentryPersistenceState.COMPLETE,
        severity = SentrySeverity.SOFT, triggerTypes = setOf(SentryTriggerType.MANUAL), involvedLanes = setOf(0, 1, 2, 3),
        armedAtEpochMs = 1000, eventStartedAtEpochMs = 1000, firstTriggerAtEpochMs = 31_000, eventEndedAtEpochMs = 41_000,
        preRollRequestedMs = 30_000, preRollAchievedMs = 30_000, postRollRequestedMs = 10_000, postRollAchievedMs = 10_000,
        coverageNotes = emptyList(), endReason = "POST_ROLL_COMPLETE", assets = listOf(asset))
    private fun proof() = SentryAssetCommitProof(token, eventId, sessionId, true, true, true, true, true, true, true, true, true,
        601, 1000, asset.sha256, 0, 40_000_000)
    private fun coverage() = SentryCoverageProof(30_000_000, 30_000_000, true)

    @Test fun manifestPublicationRequiresEveryAssetCommitAndPlaybackProof() {
        assertTrue(SentryEventIntegrity.validate(manifest(), listOf(proof()), coverage()).canPublishManifest)
        val invalid = listOf(proof().copy(exactJournalOwnershipVerified = false), proof().copy(muxerStopSucceeded = false),
            proof().copy(syncedAndClosed = false), proof().copy(rereadVerified = false), proof().copy(sidecarCommitted = false),
            proof().copy(firstSampleSync = false), proof().copy(monotonicPts = false), proof().copy(singleFormatEpoch = false),
            proof().copy(decoded = false), proof().copy(sampleCount = 1), proof().copy(eventId = sessionId),
            proof().copy(observedSha256 = "cd".repeat(32)), proof().copy(observedByteCount = 999), proof().copy(observedLastPtsUs = 39_000_000))
        invalid.forEach { assertFalse(it.toString(), SentryEventIntegrity.validate(manifest(), listOf(it), coverage()).canPublishManifest) }
    }

    @Test fun missingCoverageAndClockCannotBeLabelledComplete() {
        assertFalse(SentryEventIntegrity.validate(manifest(), listOf(proof()), coverage().copy(clockMappingVerified = false)).canPublishManifest)
        assertFalse(SentryEventIntegrity.validate(manifest().copy(postRollRequestedMs = 11_000), listOf(proof()), coverage()).canPublishManifest)
        assertFalse(SentryEventIntegrity.validate(manifest().copy(preRollAchievedMs = 31_000), listOf(proof()), coverage()).canPublishManifest)
        assertFalse(SentryEventIntegrity.validate(manifest().copy(state = SentryPersistenceState.AWAITING_TRANSFER), listOf(proof().copy(syncedAndClosed = false)), coverage()).canPublishManifest)
    }

    @Test fun knownGapMustReduceAchievedCoverageAndRemainPartial() {
        val gap = coverage().copy(gaps = listOf(SentryMediaGap(20_000_000, 21_000_000)))
        val partial = manifest().copy(state = SentryPersistenceState.PARTIAL, preRollAchievedMs = 29_000, coverageNotes = listOf("ONE_SECOND_GOP_GAP"))
        assertTrue(SentryEventIntegrity.validate(partial, listOf(proof()), gap).canPublishManifest)
        assertFalse(SentryEventIntegrity.validate(partial.copy(state = SentryPersistenceState.COMPLETE), listOf(proof()), gap).canPublishManifest)
        assertFalse(SentryEventIntegrity.validate(partial.copy(preRollAchievedMs = 30_000), listOf(proof()), gap).canPublishManifest)
        assertFalse(SentryEventIntegrity.validate(partial.copy(coverageNotes = emptyList()), listOf(proof()), gap).canPublishManifest)
    }

    @Test fun identityOrderingSeverityAndMissingAssetsAreChecked() {
        for (invalid in listOf(manifest().copy(eventId = "../other"), manifest().copy(assets = listOf(asset.copy(ordinal = 1))),
            manifest().copy(assets = listOf(asset, asset)), manifest().copy(severity = SentrySeverity.HARD),
            manifest().copy(firstTriggerAtEpochMs = 42_000), manifest().copy(involvedLanes = setOf(4)), manifest().copy(assets = emptyList()))) {
            assertFalse(SentryEventIntegrity.validate(invalid, listOf(proof()), coverage()).canPublishManifest)
        }
        val trusted = manifest().copy(severity = SentrySeverity.HARD, triggerTypes = setOf(SentryTriggerType.TRUSTED_PHYSICAL))
        assertTrue(SentryEventIntegrity.validate(trusted, listOf(proof()), coverage()).canPublishManifest)
    }

    @Test fun recoveryRequiresFreshOwnershipAndNeverPromotesAnInterruptedManifest() {
        for (ownership in listOf(SentryOwnershipEvidence.UNBOUND, SentryOwnershipEvidence.MISMATCHED)) {
            for (probe in SentryRecoveryProbe.entries) assertEquals(SentryRecoveryAction.LEAVE_UNCLAIMED,
                SentryAssetRecoveryPolicy.decide(SentryRecoveryFacts(ownership, true, probe, true)))
        }
        val facts = SentryRecoveryFacts(SentryOwnershipEvidence.MATCHED, true)
        assertEquals(SentryRecoveryAction.WAIT_FOR_STORAGE, SentryAssetRecoveryPolicy.decide(facts.copy(storageAvailable = false)))
        assertEquals(SentryRecoveryAction.INSPECT_EXACT_URI, SentryAssetRecoveryPolicy.decide(facts.copy(ownership = SentryOwnershipEvidence.NOT_INSPECTED)))
        assertEquals(SentryRecoveryAction.PROBE_EXACT_URI, SentryAssetRecoveryPolicy.decide(facts.copy(completeEventManifestReverified = true)))
        assertEquals(SentryRecoveryAction.RETAIN_UNTRUSTED, SentryAssetRecoveryPolicy.decide(facts.copy(probe = SentryRecoveryProbe.UNVERIFIED)))
        val playable = facts.copy(probe = SentryRecoveryProbe.VERIFIED_PLAYABLE)
        assertEquals(SentryRecoveryAction.KEEP_VERIFIED_PARTIAL, SentryAssetRecoveryPolicy.decide(playable))
        assertEquals(SentryRecoveryAction.KEEP_VERIFIED_COMPLETE, SentryAssetRecoveryPolicy.decide(playable.copy(completeEventManifestReverified = true)))
    }
}
