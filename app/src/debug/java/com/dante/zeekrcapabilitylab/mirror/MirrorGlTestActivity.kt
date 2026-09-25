package com.dante.zeekrcapabilitylab.mirror

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Debug-only graphics fixture; absent from the release APK. */
class MirrorGlTestActivity : Activity() {
    lateinit var host: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
    }
}
