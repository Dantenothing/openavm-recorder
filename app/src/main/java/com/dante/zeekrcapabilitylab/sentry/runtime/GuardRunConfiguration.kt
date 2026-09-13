package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig

/** Configuration provider for one explicit run; never persisted as restart authority. */
internal class GuardRunConfiguration(
    private val prepareCamera: ((RecorderConfig) -> GuardCameraCapabilities?)? = null,
    private val resolveFresh: () -> RecorderConfig?,
) {
    @Volatile
    var current: RecorderConfig? = null
        private set
    var camera: GuardCameraCapabilities? = null
        private set

    @Synchronized
    fun get(): RecorderConfig? {
        current?.let { return it }
        val config = resolveFresh() ?: return null
        val prepared = prepareCamera?.invoke(config)
        prepared?.requireMatches(config.source)
        camera = prepared
        current = config // Publish only after every required preparation step has succeeded.
        return config
    }
}
