package com.dante.zeekrcapabilitylab.preflight.continuous

import android.view.Surface
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject

internal interface ProbeGlProducer {
    val producerConfirmed: Boolean
    val released: Boolean
    fun initialize()
    fun geometry(): JsonObject
    fun attach(surface: Surface)
    fun submit(frame: Int, ptsUs: Long)
    fun endProducer()
    fun close()
    fun evidence(): JsonObject = obj("scope" to "DIRECT_GL_NO_SHARED_INPUT")
}
