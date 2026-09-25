package com.dante.zeekrbridge.core

import android.content.Context
import com.dante.zeekrbridge.ui.PhoneLanguage

data class PairingResult(val ok: Boolean, val message: String, val carDeviceId: String? = null)

/** Old QR payloads do not establish server identity. Fail closed without any network I/O. */
object PhonePairingClient {
    @Suppress("UNUSED_PARAMETER")
    suspend fun pairWithCar(context: Context, payload: PairingPayload.Parsed, timeoutMs: Int = 10_000): PairingResult =
        PairingResult(false, PhoneLanguage.text(
            "Update both apps, then compare the full fingerprint and pair on the car screen.",
            "请更新两端，在车机核对完整指纹后使用六位码安全配对。"))
}
