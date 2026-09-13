package com.dante.zeekrbridge.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.R

private enum class PhoneTab(
    @DrawableRes val icon: Int,
    val en: String,
    val zh: String,
) {
    VEHICLE(R.drawable.ic_openavm_vehicle, "Vehicle", "车辆"),
    LIBRARY(R.drawable.ic_openavm_library, "Library", "媒体库"),
    TOOLS(0, "Tools", "工具箱"),
    SETTINGS(0, "Settings", "设置"),
}

/**
 * Four main destinations; video details use the whole content area with one back step.
 * The legacy diagnostic UI lives behind 5 taps on the version number in 设置.
 */
@Composable
fun ProductMainScreen() {
    PhoneLanguage.mode
    var tab by rememberSaveable { mutableStateOf(PhoneTab.VEHICLE) }
    var showLab by rememberSaveable { mutableStateOf(false) }
    var detailOpen by androidx.compose.runtime.remember { mutableStateOf(false) }

    if (showLab) {
        LabScreen(onBack = { showLab = false })
        return
    }

    Scaffold(
        bottomBar = {
            if (!detailOpen) Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                PhoneTab.entries.forEach { item ->
                    val label = t(item.en, item.zh)
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            when (item) {
                                PhoneTab.TOOLS -> Icon(Icons.Default.Build, contentDescription = label)
                                PhoneTab.SETTINGS -> Icon(Icons.Default.Settings, contentDescription = label)
                                else -> Icon(painterResource(item.icon), contentDescription = label)
                            }
                        },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
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
                PhoneTab.VEHICLE -> VehicleScreen(
                    onOpenLibrary = { tab = PhoneTab.LIBRARY },
                    onOpenLab = { showLab = true },
                )
                PhoneTab.LIBRARY -> SessionMediaLibraryScreen(onDetailVisibilityChanged = { detailOpen = it })
                PhoneTab.TOOLS -> ToolboxScreen()
                PhoneTab.SETTINGS -> PhoneSettingsScreen(onOpenLab = { showLab = true })
            }
        }
    }
}
