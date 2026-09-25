package com.dante.zeekrcapabilitylab.mirror

/** Empty foreground host for emulator-only tests; never starts a camera or product screen. */
class MirrorSleepTestHostActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(225, 234, 231)))
    }
}
