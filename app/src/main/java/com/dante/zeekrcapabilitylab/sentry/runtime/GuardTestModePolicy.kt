package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.CaptureMode
import com.dante.zeekrcapabilitylab.sentry.VehiclePresencePhase

/** Selection of a temporary, in-car capture test versus normal automatic presence. */
internal class GuardTestModePolicy {
    var override: VehiclePresencePhase? = null
        private set

    fun select(mode: CaptureMode?) {
        override = if (mode == CaptureMode.SENTRY) VehiclePresencePhase.AWAY_CONFIRMED else null
    }

    fun effective(actual: VehiclePresencePhase, appForeground: Boolean): VehiclePresencePhase {
        if (!appForeground) clear()
        return override ?: actual
    }
    fun clear() { override = null }
}
