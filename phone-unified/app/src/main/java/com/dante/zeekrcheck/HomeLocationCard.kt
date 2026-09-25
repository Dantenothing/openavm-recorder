package com.dante.zeekrcheck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@Composable internal fun HomeLocationCard(state: AssistantState, onSave: (CarLocation?, Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lookup = remember { PlaceLookup(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CarLocation>>(emptyList()) }
    var chosen by remember { mutableStateOf<CarLocation?>(null) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var radius by remember(state.homeRadius) { mutableFloatStateOf(state.homeRadius.toFloat()) }
    var remove by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    fun search() {
        searchJob?.cancel(); val revision = ++searchGeneration
        chosen = null; results = emptyList(); saved = false; error = null; searching = true
        val input = query
        searchJob = scope.launch {
            try { val matched = lookup.search(input); if (revision == searchGeneration) {
                results = matched; if (matched.isEmpty()) error = "没有找到匹配地址，请补充街道、地区或邮编"
            } }
            catch (_: TimeoutCancellationException) { if (revision == searchGeneration) error = "地址匹配超时，请重试或使用当前位置" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == searchGeneration) error = "地址匹配暂不可用，请检查网络或使用当前位置" }
            finally { if (revision == searchGeneration) searching = false }
        }
    }
    fun locate() {
        searchJob?.cancel(); ++searchGeneration; searching = false; results = emptyList(); chosen = null
        locating = true; error = null; saved = false
        scope.launch {
            try { chosen = lookup.phoneLocation() }
            catch (_: TimeoutCancellationException) { error = "手机定位超时，可以改用地址搜索" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.takeIf { it.startsWith("手机") || it.startsWith("请先") || it.startsWith("暂时") } ?: "无法取得手机位置，请使用地址搜索" }
            finally { locating = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else error = "未允许手机定位，仍可搜索地址或使用车辆位置"
    }
    AssistantCard {
        UiText("家的位置", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        UiText(state.home?.address?.ifBlank { "已保存家的位置" } ?: "还没有设置家", raw = !state.home?.address.isNullOrBlank(), color = AssistantGreen, modifier = Modifier.testTag("saved_home"))
        UiText(HomeZone.label(state.location, state.home, state.homeRadius, System.currentTimeMillis()), fontSize = 12.sp, color = AssistantMuted)
        if (state.location != null && state.home != null) UiText("${if (state.location.fresh(System.currentTimeMillis())) "车辆位置" else "上次位置"}距家约 ${state.location.distance(state.home).roundToInt()} 米", fontSize = 12.sp, color = AssistantMuted)
        OutlinedTextField(query, { query = it; searchJob?.cancel(); ++searchGeneration; searching = false; results = emptyList(); chosen = null; saved = false; error = null },
            label = { UiText("输入部分地址、街道或地区") }, placeholder = { UiText("门牌号 + 街道 + 地区") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("home_address_query"), enabled = !locating)
        Button(onClick = ::search, enabled = query.trim().length in 4..200 && !searching && !locating, modifier = Modifier.testTag("search_home")) {
            UiText(if (searching) "匹配中…" else "查找匹配地址")
        }
        UiText("选择匹配结果后保存，不需要手动输入坐标。地址匹配需要网络。", color = AssistantMuted, fontSize = 11.sp)
        results.forEachIndexed { index, candidate ->
            OutlinedButton(onClick = { chosen = candidate; saved = false }, modifier = Modifier.fillMaxWidth().testTag("home_candidate_$index")) { UiText(candidate.address, raw = true) }
        }
        OutlinedButton(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, enabled = !locating, modifier = Modifier.fillMaxWidth()) { UiText(if (locating) "正在读取手机位置…" else "使用手机当前位置") }
        state.location?.let { vehicle ->
            TextButton(onClick = { searchJob?.cancel(); ++searchGeneration; searching = false; results = emptyList(); chosen = vehicle.copy(address = vehicle.address.ifBlank { "车辆停车位置" }); saved = false }, enabled = !locating) {
                UiText(if (vehicle.fresh(System.currentTimeMillis())) "使用当前车辆位置" else "使用车辆上次上报的位置")
            }
        }
        chosen?.let { candidate ->
            HorizontalDivider()
            UiText(ui("待保存") + ": " + candidate.address, raw = true, fontWeight = FontWeight.SemiBold)
            UiText("确认这是家的位置；手机或车辆位置不一定相同。", fontSize = 12.sp, color = AssistantMuted)
            TextButton(onClick = {
                if (runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${candidate.latitude},${candidate.longitude}?q=${candidate.latitude},${candidate.longitude}"))) }.isFailure) error = "没有可用的地图应用"
            }) { UiText("在地图查看选中位置") }
        }
        UiText("家的范围 · ${radius.toInt()} 米", fontWeight = FontWeight.SemiBold)
        Slider(radius, { radius = (it / 50).roundToInt() * 50f; saved = false }, valueRange = 100f..1000f, steps = 17, modifier = Modifier.testTag("home_radius"))
        UiText("范围内都算在家，额外 100 米为定位缓冲区，也不会触发离家守护。位置过期时显示上次记录。", fontSize = 12.sp, color = AssistantMuted)
        error?.let { UiText(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (saved) UiText("已保存家的位置与范围", color = AssistantGreen, modifier = Modifier.testTag("home_saved"))
        Button(onClick = {
            val target = chosen ?: state.home ?: return@Button
            runCatching { onSave(target.copy(verified = true), radius.toInt()) }
                .onSuccess { chosen = null; results = emptyList(); query = ""; saved = true; error = null }
                .onFailure { error = "保存失败，请重试；原设置已保留" }
        }, enabled = !searching && !locating && (chosen != null || state.home != null), modifier = Modifier.fillMaxWidth().testTag("save_home")) { UiText("保存家的位置与范围") }
        if (state.home != null) TextButton(onClick = { remove = true }) { UiText("移除家的位置") }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { UiText("移除家的位置？") }, text = { UiText("离家停车自动守护也会停用。其他偏好和预约不受影响。") },
        confirmButton = { TextButton(onClick = { runCatching { onSave(null, radius.toInt()) }.onSuccess { chosen = null; saved = false; remove = false }.onFailure { error = "移除失败，请重试"; remove = false } }) { UiText("移除") } },
        dismissButton = { TextButton(onClick = { remove = false }) { UiText("取消") } })
}
