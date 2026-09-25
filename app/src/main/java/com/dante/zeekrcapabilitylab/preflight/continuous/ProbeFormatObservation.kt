package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject

/** Whitelist avoids raw format strings, CSD buffers and vendor/private metadata. */
internal object ProbeFormatObservation {
    val keys=listOf("mime","width","height","frame-rate","bitrate","bitrate-mode","profile","level",
        "max-bframes","i-frame-interval","quality","complexity","color-format","color-standard",
        "color-range","color-transfer","latency","priority","operating-rate")
    fun capture(contains:(String)->Boolean,read:(String)->Any?):JsonObject {
        val values=keys.associateWith { key -> runCatching {
            if(!contains(key))obj("status" to "OMITTED","value" to null)
            else {
                val value=read(key)
                require(value is Int || value is Long || value is Float && value.isFinite() ||
                    value is Double && value.isFinite() || value is String && value.length<=128) { "UNSUPPORTED_FIELD_TYPE" }
                obj("status" to "REPORTED","value" to value)
            }
        }.getOrElse { obj("status" to "READ_ERROR","value" to null,"errorType" to it.javaClass.simpleName) } }
        return obj("status" to "OBSERVED","meaning" to "CODEC_REPORTED_FIELDS_NOT_MEASURED_BITRATE",
            "fields" to JsonObject(values),"missingValuesInferred" to false)
    }
}
