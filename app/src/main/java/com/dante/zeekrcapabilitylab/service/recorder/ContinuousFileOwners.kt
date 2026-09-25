package com.dante.zeekrcapabilitylab.service.recorder

/** The only long-lived product file ledger: active, prepared, and one retiring native owner.
 * A returned close call or an error is insufficient to remove a file without its native ack. */
internal class ContinuousFileOwners<T : Any> {
    private val owners = linkedMapOf<Int, T>()
    private var lastNumber = 0
    @Synchronized fun admit(number: Int, value: T) {
        check(number > lastNumber && owners.values.none { it === value }) { "PRODUCT_FILE_ID_REUSED" }
        check(owners.size < 3) { "PRODUCT_OUTPUT_OWNER_LIMIT" }
        owners[number] = value; lastNumber = number
    }
    @Synchronized fun release(value: T, nativeReleased: Boolean) {
        check(nativeReleased) { "PRODUCT_FILE_RELEASE_UNCONFIRMED" }
        val number = owners.entries.singleOrNull { it.value === value }?.key ?: error("PRODUCT_FILE_NOT_OWNED")
        owners.remove(number)
    }
    @Synchronized fun snapshot(): List<T> = owners.values.toList()
}

internal object ContinuousFileTiming {
    /** Empty muxer EOS marker establishes the last sample duration, without sending codec EOS. */
    fun durationUs(firstPtsUs: Long, lastPtsUs: Long, endExclusivePtsUs: Long): Long {
        require(firstPtsUs >= 0 && lastPtsUs >= firstPtsUs && endExclusivePtsUs > lastPtsUs) { "PRODUCT_FILE_TAIL_INVALID" }
        return endExclusivePtsUs - firstPtsUs
    }
}

internal object ProductContinuousPolicy {
    fun blocksUsbFallback(sharedInput: Boolean, phase: CameraRecoveryPhase): Boolean = sharedInput ||
        phase in setOf(CameraRecoveryPhase.FINALIZING, CameraRecoveryPhase.WAITING_CAMERA,
            CameraRecoveryPhase.RESUMING, CameraRecoveryPhase.PROBATION)

    fun eligible(config: RecorderConfig) = config.sharedInputRecordingEnabled && supportsSource(config.source)

    fun supportsSource(source: SessionSourceSnapshot) =
        source.sourceRole == RecordingSourceRole.SURROUND &&
        source.layoutKind == RecordingLayoutKind.FOUR_LANE_V1 &&
        source.profile.size.width == 1280 && source.profile.size.height == 5140 && source.laneLayout?.let {
            it.originalWidth == 1280 && it.originalHeight == 5140 && it.lanes.size == 4 &&
                it.lanes.map { lane -> lane.lane }.toSet() == setOf(1, 2, 3, 4)
        } == true
}
