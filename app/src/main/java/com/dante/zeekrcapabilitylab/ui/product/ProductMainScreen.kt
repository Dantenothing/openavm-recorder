package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dante.zeekrcapabilitylab.product.AppLanguage

private data class ProductNavItem(
    val route: String,
    val icon: ImageVector,
    val labelEn: String,
    val labelZh: String,
)

private val productNavItems = listOf(
    ProductNavItem("record", Icons.Default.PlayArrow, "Record", "录像"),
    ProductNavItem("events", Icons.Default.List, "Library", "记录"),
    ProductNavItem("phone", Icons.Default.Phone, "Phone / Tools", "手机 / 工具"),
    ProductNavItem("settings", Icons.Default.Settings, "Settings", "设置"),
)

/**
 * Product shell for the head unit: four fixed tabs (录像/记录/手机/设置).
 */
@Composable
fun ProductMainScreen(openReturnSettings: Boolean = false, onReturnSettingsHandled: () -> Unit = {}) {
    // Reading this StateFlow makes every product screen recompose immediately
    // after the user changes language; camera/recorder services are untouched.
    val languageMode by AppLanguage.mode.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val returnSettings = remember(context) { com.dante.zeekrcapabilitylab.mirror.MirrorReturnSettings(context) }
    var showReturnSetup by remember { mutableStateOf(returnSettings.needsReview) }
    if (showReturnSetup) {
        // Do not mount the home page's automatic preview/permission effects behind this decision.
        MirrorReturnSetupDialog(returnSettings, installationPrompt = true, onDismiss = {},
            onSaved = { showReturnSetup = false; onReturnSettingsHandled() })
        return
    }
    if (openReturnSettings) MirrorReturnSetupDialog(returnSettings, installationPrompt = false,
        onDismiss = onReturnSettingsHandled, onSaved = onReturnSettingsHandled)
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "record"

    Scaffold(
        bottomBar = {
            NavigationBar {
                productNavItems.forEach { item ->
                    val label = AppLanguage.text(item.labelEn, item.labelZh)
                    NavigationBarItem(
                        selected = currentRoute == item.route ||
                            (item.route == "settings" && currentRoute in setOf("usb_storage", "usb_probe")),
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = false
                                }
                                launchSingleTop = true
                                restoreState = false
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = label) },
                        label = {
                            Text(label, fontSize = 14.sp)
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "record",
            modifier = Modifier.padding(padding),
        ) {
            composable("record") { RecordScreen() }
            composable("events") { EventsScreen() }
            composable("phone") { PhoneScreen() }
            composable("settings") {
                SettingsScreen(onOpenUsbManagement = { navController.navigate("usb_storage") })
            }
            composable("usb_storage") {
                UsbManagementScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDiagnostics = { navController.navigate("usb_probe") },
                )
            }
            composable("usb_probe") {
                UsbProbeScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
