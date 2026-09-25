package com.dante.zeekrcheck

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dante.zeekrbridge.OpenAvmRuntime
import com.dante.zeekrbridge.core.CarCatalogStore
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.PairedVehicleSummary
import com.dante.zeekrbridge.core.VehicleHomePolicy
import com.dante.zeekrbridge.core.VehicleConnectionStatus
import com.dante.zeekrbridge.server.BridgeServer
import kotlinx.coroutines.delay
import com.dante.zeekrbridge.ui.*

/** Initialized from the foreground host, not from cloud jobs/widgets that only need car control. */
object OpenAvmIntegration {
    val destinations = setOf("media","connection","media_tools","media_settings","media_lab","sentry_usb","sounds")
    fun initialize(context: Context) {
        val settings=context.getSharedPreferences("phone_product_settings",Context.MODE_PRIVATE)
        if(!settings.contains("auto_start_server")) settings.edit().putBoolean("auto_start_server",false).apply()
        OpenAvmRuntime.initialize(context)
    }
}

@Composable internal fun OpenAvmSummaryCard(cloudConnected: Boolean, onConnection: () -> Unit, onLibrary: () -> Unit) {
    val devices by PairingManager.devices.collectAsStateWithLifecycle()
    val online by CarCatalogStore.online.collectAsStateWithLifecycle()
    val server by BridgeServer.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis(), server.running) {
        while (server.running) { value = System.currentTimeMillis(); delay(5_000) }
    }
    val secureDevices = devices.filter { it.securityVersion == 2 && (server.identityFingerprint.isBlank() || it.phoneIdentityPin == server.identityFingerprint) }
    val connection = VehicleHomePolicy.resolve(
        secureDevices.map { PairedVehicleSummary(it.carDeviceId, it.name, it.lastSeen) }, online,
        server.running, emptyList(), server.startedAtEpochMs, now,
    )
    val files by ReceivedStore.files.collectAsStateWithLifecycle()
    AssistantCard {
        UiText("OpenAVM 影像",fontSize=18.sp,fontWeight=FontWeight.Bold)
        UiText(if(cloudConnected) "远程车控已连接" else "远程车控尚未连接",fontSize=12.sp,color=AssistantGreen)
        UiText(when {
            server.startFailure != null -> "录像接收启动失败 · 打开连接页重试"
            devices.isNotEmpty() && secureDevices.isEmpty() -> "录像连接需要安全重新配对"
            connection.status == VehicleConnectionStatus.CONNECTED -> "录像连接已就绪"
            connection.status == VehicleConnectionStatus.RECENT -> "最近与车机通信正常"
            devices.isEmpty() -> "录像连接尚未配对"
            server.running -> "接收已就绪 · 请在车机确认连接"
            else -> "录像接收已暂停"
        },fontSize=14.sp)
        UiText("${files.count { it.extension.equals("mp4",true) }} 个本机录像 · 车机未连接时也能回看",fontSize=12.sp,color=AssistantMuted)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onConnection,modifier=Modifier.weight(1f).testTag("openavm_connection")) { UiText("连接车机") }
            Button(onClick=onLibrary,modifier=Modifier.weight(1f).testTag("openavm_library")) { UiText("查看影像") }
        }
    }
}

/** Embeds real Companion destinations in the host's single navigation and application identity. */
@Composable internal fun OpenAvmDestination(destination: String, detailOpen: Boolean, onDetail: (Boolean) -> Unit,
                                            onBack: () -> Unit, onNavigate: (String) -> Unit) {
    // Reading mode makes changes from the media-language selector recompose this host section.
    PhoneLanguage.mode
    var connectionDetail by remember(destination) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("openavm_destination_$destination")) {
        if(!detailOpen && !connectionDetail && destination !in setOf("media_lab", "sentry_usb", "sounds")) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(destination!="media") TextButton(onClick=onBack,modifier=Modifier.testTag("openavm_back")) { UiText("‹ 返回") }
            else UiText("影像",fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(start=4.dp))
            Spacer(Modifier.weight(1f))
            if(destination!="connection") TextButton(onClick={onNavigate("connection")},modifier=Modifier.testTag("media_connection")) { UiText("连接车机") }
            if(destination!="media_tools") TextButton(onClick={onNavigate("media_tools")},modifier=Modifier.testTag("media_tools")) { UiText("工具") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when(destination) {
                "connection" -> VehicleScreen(onOpenLibrary={onNavigate("media")},onOpenLab={onNavigate("media_lab")},onDetailVisibilityChanged={connectionDetail=it})
                "media_tools" -> ToolboxScreen()
                "media_settings" -> PhoneSettingsScreen(onOpenLab={onNavigate("media_lab")},showLanguage=false)
                "media_lab" -> LabScreen(onBack=onBack)
                "sentry_usb" -> UsbSentryScreen(onBack=onBack)
                "sounds" -> SoundEditorScreen(onBack=onBack)
                else -> SessionMediaLibraryScreen(onDetailVisibilityChanged=onDetail)
            }
        }
    }
}
