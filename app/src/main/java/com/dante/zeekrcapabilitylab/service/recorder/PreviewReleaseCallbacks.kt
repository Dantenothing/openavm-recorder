package com.dante.zeekrcapabilitylab.service.recorder

import java.util.IdentityHashMap

/** Confined to the recorder worker. A failed native release does not prove consumer safety. */
class PreviewReleaseCallbacks<T : Any>(private val releaseSurface: (T) -> Unit) {
    private val callbacks = IdentityHashMap<T, () -> Unit>()
    fun track(surface: T, afterRelease: () -> Unit) {
        check(!callbacks.containsKey(surface))
        callbacks[surface] = afterRelease
    }
    fun release(surface: T) {
        releaseSurface(surface)
        callbacks.remove(surface)?.invoke()
    }
}
