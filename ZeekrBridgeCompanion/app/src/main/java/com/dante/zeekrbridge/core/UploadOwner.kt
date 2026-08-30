package com.dante.zeekrbridge.core

/** Owner rule shared by HTTP and Bluetooth upload handlers. */
object UploadOwner {
    fun isOwner(session: UploadSession, authenticatedCarId: String?): Boolean =
        SecureCompare.equals(session.request.carId, authenticatedCarId)
}
