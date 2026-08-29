package com.dante.zeekrcapabilitylab.ui.product

enum class GalleryTransferAction {
    ENQUEUE,
}

object GalleryTransferActionPolicy {
    val menuEnabled: Boolean = true

    fun action(@Suppress("UNUSED_PARAMETER") connected: Boolean): GalleryTransferAction = GalleryTransferAction.ENQUEUE
}
