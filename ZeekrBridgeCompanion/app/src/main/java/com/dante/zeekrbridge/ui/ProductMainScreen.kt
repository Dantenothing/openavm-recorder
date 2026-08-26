package com.dante.zeekrbridge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp

private data class PhoneNavItem(val icon: ImageVector, val en: String, val zh: String)

/**
 * V2 product shell for the phone: four fixed bottom tabs (车辆/媒体库/工具箱/设置).
 * The legacy diagnostic UI lives behind 5 taps on the version number in 设置.
 */
@Composable
fun ProductMainScreen() {
    PhoneLanguage.mode
    var tab by remember { mutableIntStateOf(0) }
    var showLab by remember { mutableStateOf(false) }

    if (showLab) {
        LabScreen(onBack = { showLab = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val phoneNavItems = listOf(
                    PhoneNavItem(Icons.Default.Home, "Vehicle", "车辆"),
                    PhoneNavItem(Icons.Default.List, "Library", "媒体库"),
                    PhoneNavItem(Icons.Default.Build, "Toolbox", "工具箱"),
                    PhoneNavItem(Icons.Default.Settings, "Settings", "设置"),
                )
                phoneNavItems.forEachIndexed { index, item ->
                    val label = t(item.en, item.zh)
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.icon, contentDescription = label) },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                0 -> VehicleScreen()
                1 -> MediaLibraryScreen()
                2 -> ToolboxScreen()
                else -> PhoneSettingsScreen(onOpenLab = { showLab = true })
            }
        }
    }
}
