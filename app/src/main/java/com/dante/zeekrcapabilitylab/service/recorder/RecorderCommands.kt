package com.dante.zeekrcapabilitylab.service.recorder

import kotlinx.serialization.json.Json

object RecorderCommands {
    private const val ACTION_PREFIX = "io.github.dantenothing.openavmrecorder.recorder.action"

    const val ACTION_START = "$ACTION_PREFIX.START"
    const val ACTION_STOP = "$ACTION_PREFIX.STOP"
    const val ACTION_BOOKMARK = "$ACTION_PREFIX.BOOKMARK"
    const val ACTION_RETRY = "$ACTION_PREFIX.RETRY"
    const val ACTION_SET_PREVIEW_OUTPUT = "$ACTION_PREFIX.SET_PREVIEW_OUTPUT"
    const val EXTRA_CONFIG_JSON = "recorderConfigJson"
    const val EXTRA_PREVIEW_SURFACE = "recorderPreviewSurface"
    const val EXTRA_PREVIEW_ENABLED = "recorderPreviewEnabled"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
