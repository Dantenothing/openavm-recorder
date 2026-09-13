package com.dante.zeekrcapabilitylab.service.recorder

import kotlinx.serialization.Serializable

/** The position is relative to this MP4's first encoded sample, not wall clock or requested pre-roll. */
@Serializable
data class VideoTriggerMarker(
    val positionMs: Long,
    val type: String,
    val epochMs: Long? = null,
    val lanes: Set<Int> = emptySet(),
)
