package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.service.recorder.VideoTriggerMarker

object GuardVideoPresentation {
    const val FILE_PREFIX = "OpenAVM_Sentry_"
    fun assetName(id: String, number: Int): String {
        require(id.matches(Regex("[a-f0-9-]{36}")) && number in 1..8)
        return "$FILE_PREFIX$id-$number.mp4"
    }
    fun validAssetName(value: String): Boolean =
        value.matches(Regex("(?:OpenAVM_Sentry_)?[a-f0-9-]{36}-[1-8]\\.mp4"))

    fun markers(event: GuardEvent, asset: GuardAsset): List<VideoTriggerMarker> {
        if (asset.firstPtsUs < 0 || asset.lastPtsUs < asset.firstPtsUs) return emptyList()
        return event.triggers.asSequence()
            .filter { it.ptsUs in asset.firstPtsUs..asset.lastPtsUs }
            .map { VideoTriggerMarker((it.ptsUs - asset.firstPtsUs) / 1000, it.type, it.epochMs,
                it.lanes.filter { lane -> lane in 1..4 }.toSet()) }
            .filter { asset.actualDurationMs == null || it.positionMs < asset.actualDurationMs }
            .distinctBy { Triple(it.positionMs, it.type, it.epochMs) }
            .sortedBy { it.positionMs }.take(64).toList()
    }
}
