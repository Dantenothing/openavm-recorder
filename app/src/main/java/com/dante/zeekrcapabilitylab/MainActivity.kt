package com.dante.zeekrcapabilitylab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dante.zeekrcapabilitylab.ui.product.ProductMainScreen
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    companion object {
        private val _currentState = MutableStateFlow("CREATED")
        val currentState: StateFlow<String> = _currentState.asStateFlow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _currentState.value = "CREATED"
        setContent {
            ZeekrCapabilityLabTheme {
                ProductMainScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        _currentState.value = "STARTED"
    }

    override fun onResume() {
        super.onResume()
        _currentState.value = "RESUMED"
    }

    override fun onPause() {
        super.onPause()
        _currentState.value = "PAUSED"
    }

    override fun onStop() {
        super.onStop()
        _currentState.value = "STOPPED"
    }

    override fun onDestroy() {
        super.onDestroy()
        _currentState.value = "DESTROYED"
    }
}
