package com.dante.zeekrcheck

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dante.zeekrcheck.core.WidgetPinAttempt
import com.dante.zeekrcheck.core.WidgetPinPhase
import kotlinx.coroutines.delay

internal interface WidgetPinPlatform {
    fun existingIds(provider: ComponentName): Set<Int>
    fun supported(): Boolean
    fun request(provider: ComponentName): Boolean
    fun openHome()
}

internal class AndroidWidgetPinPlatform(private val context: Context) : WidgetPinPlatform {
    private val manager = AppWidgetManager.getInstance(context)
    override fun existingIds(provider: ComponentName) = manager.getAppWidgetIds(provider).toSet()
    override fun supported() = manager.isRequestPinAppWidgetSupported
    override fun request(provider: ComponentName) = manager.requestPinAppWidget(provider, null, null)
    override fun openHome() {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable internal fun WidgetPinActions(provider: ComponentName, size: String, platform: WidgetPinPlatform) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var phase by rememberSaveable(provider) { mutableStateOf<WidgetPinPhase?>(null) }
    var beforeIds by rememberSaveable(provider) { mutableStateOf<IntArray?>(null) }
    var startedAt by rememberSaveable(provider) { mutableLongStateOf(0L) }
    var showHelp by rememberSaveable(provider) { mutableStateOf(false) }

    LaunchedEffect(provider, startedAt, lifecycle, platform) {
        if (phase == null || beforeIds == null) return@LaunchedEffect
        // Recheck after a system confirmation or manual desktop addition. Poll only in the foreground.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                val currentPhase = phase ?: break
                val attempt = WidgetPinAttempt(beforeIds?.toSet(), startedAt, currentPhase)
                val ids = runCatching { platform.existingIds(provider) }.getOrNull()
                phase = attempt.observe(ids ?: (beforeIds ?: intArrayOf()).toSet(), SystemClock.elapsedRealtime()).phase
                if (phase != WidgetPinPhase.WAITING) break
                delay(500)
            } while (true)
        }
    }
    LaunchedEffect(startedAt, phase) {
        when (phase) {
            WidgetPinPhase.NOT_CONFIRMED, WidgetPinPhase.UNSUPPORTED, WidgetPinPhase.FAILED -> showHelp = true
            WidgetPinPhase.ADDED -> showHelp = false
            else -> Unit
        }
    }

    phase?.let {
        UiText(when (it) {
            WidgetPinPhase.WAITING -> "请在桌面弹窗中确认添加"
            WidgetPinPhase.ADDED -> "已添加到桌面"
            WidgetPinPhase.NOT_CONFIRMED -> "尚未检测到新卡片，可从桌面手动添加。"
            WidgetPinPhase.UNSUPPORTED -> "当前桌面不支持直接添加，请从桌面添加。"
            WidgetPinPhase.FAILED -> "未能打开添加窗口，请重试或从桌面添加。"
        }, color = AssistantGreen, modifier = Modifier.testTag("widget_pin_status"))
    }
    Button(onClick = {
        showHelp = false
        beforeIds = runCatching { platform.existingIds(provider).toIntArray() }.getOrNull()
        startedAt = SystemClock.elapsedRealtime()
        phase = if (beforeIds == null) WidgetPinPhase.FAILED else runCatching {
            if (!platform.supported()) WidgetPinPhase.UNSUPPORTED
            else if (platform.request(provider)) WidgetPinPhase.WAITING else WidgetPinPhase.UNSUPPORTED
        }.getOrDefault(WidgetPinPhase.FAILED)
    }, enabled = phase != WidgetPinPhase.WAITING,
        modifier = Modifier.fillMaxWidth().testTag("widget_add")) {
        UiText(if (phase == WidgetPinPhase.WAITING) ui("等待桌面确认") else "${ui("添加到桌面")} · $size", raw = true)
    }
    TextButton(onClick = { showHelp = true }, modifier = Modifier.testTag("widget_manual_help")) {
        UiText("没有弹窗？查看手动添加方法")
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false },
        modifier = Modifier.testTag("widget_manual_dialog"),
        title = { UiText("从桌面添加卡片") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UiText("部分手机的桌面会忽略应用发起的添加请求。你也可以直接从桌面添加。")
                UiText("1 · ${ui("回到桌面，长按空白处。")}", raw = true)
                UiText("2 · ${ui("选择「小组件」或「插件」，找到 OpenAVM。")}", raw = true)
                UiText("3 · $size · ${ui("把卡片拖到桌面的空白位置。")}", raw = true)
            }
        }, confirmButton = {
            TextButton(onClick = {
                runCatching { platform.openHome() }.onSuccess { showHelp = false }
                    .onFailure { phase = WidgetPinPhase.FAILED }
            }, modifier = Modifier.testTag("widget_go_home")) { UiText("去桌面添加") }
        }, dismissButton = { TextButton(onClick = { showHelp = false }) { UiText("返回") } })
}
