package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.sentry.ModeStamp

/** An adapter receives an upright, unencoded lane frame; ownership stays with the caller. */
interface LaneObjectDetector<in F> : AutoCloseable {
    val detectorVersion: String
    fun detect(uprightLaneFrame: F): List<ObjectDetection>
}

enum class LiveDetectorBlock {
    FEATURE_OFF, NOT_ARMED, ENCODER_NOT_QUALIFIED, DETECTOR_OUTPUT_NOT_QUALIFIED,
    MODEL_NOT_ACCEPTED, PROFILE_NOT_CALIBRATED, CLOCK_NOT_CALIBRATED,
}

/** Qualifications are explicit and bound to this camera transition, not inferred from free RAM. */
data class LiveDetectorProof(
    val stamp: ModeStamp,
    val autoFeatureEnabled: Boolean = false,
    val armed: Boolean = false,
    val qualifiedEncoderStamp: ModeStamp? = null,
    val qualifiedDetectorOutputStamp: ModeStamp? = null,
    val acceptedModelVersion: String? = null,
    val clockCalibrated: Boolean = false,
)

object LiveDetectorGate {
    fun blocks(proof: LiveDetectorProof, profile: RiskProfile, detectorVersion: String): Set<LiveDetectorBlock> = buildSet {
        if (!proof.autoFeatureEnabled) add(LiveDetectorBlock.FEATURE_OFF)
        if (!proof.armed) add(LiveDetectorBlock.NOT_ARMED)
        if (proof.qualifiedEncoderStamp != proof.stamp) add(LiveDetectorBlock.ENCODER_NOT_QUALIFIED)
        if (proof.qualifiedDetectorOutputStamp != proof.stamp) add(LiveDetectorBlock.DETECTOR_OUTPUT_NOT_QUALIFIED)
        if (detectorVersion.isBlank() || proof.acceptedModelVersion != detectorVersion) add(LiveDetectorBlock.MODEL_NOT_ACCEPTED)
        if (!profile.vehicleCalibrated) add(LiveDetectorBlock.PROFILE_NOT_CALIBRATED)
        if (!proof.clockCalibrated) add(LiveDetectorBlock.CLOCK_NOT_CALIBRATED)
    }
    fun <T> createIfReady(proof: LiveDetectorProof, profile: RiskProfile, detectorVersion: String, create: () -> T): T? =
        if (blocks(proof, profile, detectorVersion).isEmpty()) create() else null
}
