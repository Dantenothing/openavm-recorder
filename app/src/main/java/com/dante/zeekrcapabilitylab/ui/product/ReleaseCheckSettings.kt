package com.dante.zeekrcapabilitylab.ui.product

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.update.*
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.*

@Composable
fun ReleaseCheckSettings() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("updates_v1", 0) }
    val scope = rememberCoroutineScope()
    var beta by remember { mutableStateOf(BuildConfig.EXPERIMENTAL_TOOLS_ENABLED && prefs.getBoolean("beta", false)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf<ReleaseOffer?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (BuildConfig.EXPERIMENTAL_TOOLS_ENABLED) {
            Text(Utils.t("Include Beta releases", "包含 Beta 测试版"), Modifier.padding(top = 14.dp))
            Switch(beta, { beta = it; prefs.edit().putBoolean("beta", it).apply(); message = ""; offer = null }, enabled = !busy)
        }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; message = Utils.t("Checking releases…", "正在检查版本…")
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { runCatching { ReleaseCheck.check(BuildConfig.VERSION_NAME, beta) } }
                    result.fold({ offer = it; message = if (it == null) Utils.t("No newer release in this channel.", "此渠道暂无更新版本。") else "" },
                        { message = Utils.t("Could not check releases. Try again later or open GitHub.", "无法检查版本，请稍后重试或打开 GitHub。") })
                } finally { busy = false }
            }
        }) { Text(Utils.t("Check for updates", "检查更新")) }
    }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    offer?.let { value ->
        AlertDialog(onDismissRequest = { offer = null }, title = { Text(value.release.tag_name) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(value.release.body.take(8000))
                Text(Utils.t("Download opens the official ARM64 car APK in your browser. Install it using the head unit's installer; recordings and settings are retained.",
                    "下载会在浏览器打开官方 ARM64 车机安装包，请用车机安装器完成更新，录像及设置会保留。"))
            } },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value.asset.browser_download_url))) }
                    .onFailure { message = Utils.t("No browser is available to open this page.", "没有可用于打开此页面的浏览器。") }
                offer = null
            }) { Text(Utils.t("Download APK", "下载安装包")) } },
            dismissButton = { TextButton(onClick = { offer = null }) { Text(Utils.t("Close", "关闭")) } })
    }
}
