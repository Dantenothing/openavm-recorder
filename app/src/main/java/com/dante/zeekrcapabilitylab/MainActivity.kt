package com.dante.zeekrcapabilitylab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.dante.zeekrcapabilitylab.ui.product.ProductMainScreen
import com.dante.zeekrcapabilitylab.diagnostic.VehicleAwayProbe
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private var showReturnSettings by mutableStateOf(false)

    companion object {
        private val _currentState = MutableStateFlow("CREATED")
        val currentState: StateFlow<String> = _currentState.asStateFlow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showReturnSettings = intent.getBooleanExtra("openavm.return_settings", false)
        _currentState.value = "CREATED"
        VehicleAwayProbe.recordActivityState("CREATED")
        setContent {
            ZeekrCapabilityLabTheme {
                ProductMainScreen(showReturnSettings, onReturnSettingsHandled = { showReturnSettings = false })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showReturnSettings = intent.getBooleanExtra("openavm.return_settings", false)
    }

    override fun onStart() {
        super.onStart()
        _currentState.value = "STARTED"
        VehicleAwayProbe.recordActivityState("STARTED")
    }

    override fun onResume() {
        super.onResume()
        _currentState.value = "RESUMED"
        VehicleAwayProbe.recordActivityState("RESUMED")
    }

    override fun onPause() {
        super.onPause()
        _currentState.value = "PAUSED"
        VehicleAwayProbe.recordActivityState("PAUSED")
    }

    override fun onStop() {
        super.onStop()
        _currentState.value = "STOPPED"
        VehicleAwayProbe.recordActivityState("STOPPED")
    }

    override fun onDestroy() {
        super.onDestroy()
        _currentState.value = "DESTROYED"
        VehicleAwayProbe.recordActivityState("DESTROYED")
    }
}
