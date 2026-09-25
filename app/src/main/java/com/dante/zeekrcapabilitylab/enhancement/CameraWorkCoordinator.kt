package com.dante.zeekrcapabilitylab.enhancement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CameraWorkState(val owner: String? = null, val kind: String = "", val message: String = "", val error: String? = null) {
    val active get() = owner != null
}

/** Serializes manually started auxiliary camera work against product preview/recording. */
object CameraWorkCoordinator {
    private val mutable = MutableStateFlow(CameraWorkState())
    val state = mutable.asStateFlow()
    @Synchronized fun claim(kind: String): String? {
        if (mutable.value.active) return null
        val owner = UUID.randomUUID().toString()
        mutable.value = CameraWorkState(owner, kind)
        return owner
    }
    @Synchronized fun publish(owner: String, message: String, error: String? = null) {
        if (mutable.value.owner == owner) mutable.value = mutable.value.copy(message = message, error = error)
    }
    /** Only the resource owner calls this after all actual native releases. */
    @Synchronized fun finish(owner: String) { if (mutable.value.owner == owner) mutable.value = CameraWorkState() }
}
