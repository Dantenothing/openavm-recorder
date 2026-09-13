package com.dante.zeekrcapabilitylab.sound

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme

class VehicleSoundEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZeekrCapabilityLabTheme { VehicleSoundEditorScreen(onBack = ::finish) } }
    }
}
