package com.dante.zeekrcapabilitylab.ui.product

enum class GalleryTransferAction {
    ENQUEUE,
    OPEN_PHONE,
}

object GalleryTransferActionPolicy {
    val menuEnabled: Boolean = true

    fun action(connected: Boolean): GalleryTransferAction =
        if (connected) GalleryTransferAction.ENQUEUE else GalleryTransferAction.OPEN_PHONE
}
