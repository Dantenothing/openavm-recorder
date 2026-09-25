package com.dante.zeekrcheck

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun FeatureRow(title: String, detail: String, rawTitle: Boolean = false, rawDetail: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { UiText(title, raw = rawTitle, fontWeight = FontWeight.SemiBold); UiText(detail, raw = rawDetail, fontSize = 12.sp, color = AssistantMuted, modifier = Modifier.padding(top = 4.dp)) }
        UiText("›", fontSize = 23.sp, color = AssistantMuted)
    }
}

@Composable internal fun PreferenceEditor(initial: ComfortPreferences, onClose: () -> Unit, onSave: (ComfortPreferences) -> Unit) {
    var p by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onClose, title = { UiText("智能备车设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UiText("目标温度 ${p.target}°C", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Slider(value = p.target.toFloat(), onValueChange = { p = p.copy(target = it.toInt()) }, valueRange = 18f..28f, steps = 9)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                UiText("允许按需开启")
                TextButton(onClick = { val all = !(p.steeringHeat && p.seatHeat && p.seatVentilation); p = p.copy(steeringHeat = all, seatHeat = all, seatVentilation = all) }) { UiText("全选 / 取消") }
            }
            CheckPreference("方向盘加热", p.steeringHeat) { p = p.copy(steeringHeat = it) }
            CheckPreference("座椅加热", p.seatHeat) { p = p.copy(seatHeat = it) }
            CheckPreference("座椅通风", p.seatVentilation) { p = p.copy(seatVentilation = it) }
            UiText("可以三项全选。偏热时选择通风，偏冷时选择加热；车温过期时本次仅请求目标温度的空调。保存不会启动车辆。", fontSize = 12.sp, color = AssistantMuted)
            CheckPreference("同时照顾两张前排座椅", p.bothSeats) { p = p.copy(bothSeats = it) }
            UiText(if (p.bothSeats) "两前排" else "仅左前座椅；首次请核对本车轮位对应", fontSize = 11.sp, color = AssistantMuted)
            UiText("最长运行 ${p.minutes} 分钟", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(5,15,30).forEach { min -> FilterChip(p.minutes == min, { p = p.copy(minutes = min) }, label = { UiText("${min}分") }) } }
            CheckPreference("舒适后自动结束（立即备车）", p.finishWhenComfortable) { p = p.copy(finishWhenComfortable = it) }
            UiText("舱温稳定后，还会检查本次座椅处理。预约备车保持至出发；接管或手动调节后退出自动策略。关闭结果以车辆回传为准。", fontSize = 11.sp, color = AssistantMuted)
            UiText("座椅使用低档短时预热。方向盘可在空调面板手动开关，自动加热待实车定时验证后启用。", fontSize = 11.sp, color = AssistantMuted)
            UiText("自动备车使用舒适目标温度。LO / HI 可在空调面板手动使用，暂不参与后台自动调节。", fontSize = 11.sp, color = AssistantMuted)
            UiText("断网时以车辆自身计时为准，不能保证恰好在出发时停止。",fontSize=11.sp,color=AssistantMuted)
            UiText("已有预约使用各自的偏好快照。", fontSize = 11.sp, color = AssistantMuted)
        }
    }, confirmButton = { Button(onClick = { onSave(p) }, modifier = Modifier.testTag("save_comfort_preferences")) { UiText("保存备车偏好") } }, dismissButton = { TextButton(onClick = onClose) { UiText("取消") } })
}

@Composable internal fun CheckPreference(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { changed(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = changed); UiText(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable internal fun OperationStatus(model: CheckViewModel) {
    val state by model.assistant.state.collectAsStateWithLifecycle()
    val operating by model.operating.collectAsStateWithLifecycle()
    val climate by model.climateState.collectAsStateWithLifecycle()
    val preparationNeedsCheck=PreparationControl.needsCheck(state.activePreparation)
    if (state.operationMessage.isNotEmpty() || preparationNeedsCheck) Surface(color = androidx.compose.ui.graphics.Color(0xFFE7EFEA), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UiText(state.operationMessage.ifEmpty { state.activePreparation?.status.orEmpty() }, fontSize = 13.sp, modifier = Modifier.testTag("operation_status"))
            if (operating) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!operating && !climate.queue.active && (state.operationPending || climate.queue.halted || preparationNeedsCheck)) TextButton(onClick = model::acknowledgeOperation) { UiText("我已核对结果，继续操作") }
        }
    }
}

@Composable internal fun BodyControlPanel(action: String, model: CheckViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val running by model.operating.collectAsStateWithLifecycle()
    val assistant by model.assistant.state.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<BodyAction?>(null) }
    val choices = when (action) {
        "lock" -> listOf(BodyAction.LOCK, BodyAction.UNLOCK)
        "guard" -> listOf(BodyAction.SENTRY_ON, BodyAction.SENTRY_OFF)
        "windows" -> listOf(BodyAction.WINDOWS_OPEN, BodyAction.WINDOWS_CLOSE)
        "trunk" -> listOf(BodyAction.TRUNK_UNLOCK, BodyAction.TRUNK_LOCK)
        "port" -> listOf(BodyAction.PORT_OPEN, BodyAction.PORT_CLOSE)
        else -> listOf(BodyAction.HORN)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiText(when (action) {
            "find" -> "发送一次鸣笛请求，不循环响铃；声音次数由原厂车辆执行。"
            "trunk" -> "远程按钮发送尾门解锁请求；若未抬起，请按车尾按键。锁定不会电动关闭尾门。"
            "port" -> "打开或关闭充电口盖。"
            "lock" -> "选择明确的目标；未知车况不会被当作相反状态。"
            "guard" -> "开关原厂哨兵，与 OpenAVM 录像分别显示。"
            else -> "请确认车旁无人、没有障碍物，再选择打开或关闭。"
        }, fontSize = 13.sp, color = AssistantMuted)
        choices.forEach { choice ->
            Button(onClick = { if (choice == BodyAction.HORN) model.bodyAction(choice) else confirmation = choice }, enabled = state.connected && !state.busy && !running && !assistant.operationPending,
                modifier = Modifier.fillMaxWidth().testTag("body_${choice.name}")) { UiText(choice.title) }
        }
        OperationStatus(model)
        TextButton(onClick = model::refreshOverview, enabled = state.connected && !state.busy && !running) { UiText("刷新车辆状态") }
    }
    confirmation?.let { target -> AlertDialog(onDismissRequest = { confirmation = null }, title = { UiText(target.title) },
        text = { UiText("将发送到当前所选车辆。确认周边环境适合此次操作。") }, confirmButton = {
            Button(onClick = { confirmation = null; model.bodyAction(target) }) { UiText("确认${target.title}") }
        }, dismissButton = { TextButton(onClick = { confirmation = null }) { UiText("取消") } }) }
}

@Composable internal fun PreparationPanel(model: CheckViewModel, onPreferences: () -> Unit) {
    val context=LocalContext.current
    val assistant by model.assistant.state.collectAsStateWithLifecycle()
    val overview by OverviewStore.get(LocalContext.current).state.collectAsStateWithLifecycle()
    val running by model.operating.collectAsStateWithLifecycle()
    val account by model.state.collectAsStateWithLifecycle()
    val session = assistant.activePreparation?.takeIf { it.vehicleKey==overview.vehicleKey }
    val now = parkingClock(session?.started)
    val thermal = ThermalPresentation.from(overview,session,Instant.ofEpochMilli(now))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UiText("${overview.target}°C", fontSize = 46.sp, fontWeight = FontWeight.Bold)
        UiText("先读取车温，再选择允许的舒适功能 · 最长 ${assistant.preferences.minutes} 分钟", color = AssistantMuted, fontSize = 13.sp)
        UiText("目标是希望达到的车内温度；是否达标以实际测量为准，不能仅凭运行时间判断。",color=AssistantMuted,fontSize=12.sp)
        if (session != null) {
            UiText(thermal.preparationTitle(session,now), color = AssistantGreen, fontWeight = FontWeight.SemiBold)
            UiText((if(PreparationProgress.idle(session,now)) "上次备车：" else "") + thermal.preparationDetail(session,now),color=AssistantGreen,fontSize=14.sp)
            UiText("${if(PreparationProgress.idle(session,now)) "上次" else "本次"}目标 ${session.preferences.target}°C · 计划截止 ${displayTime(Instant.ofEpochMilli(ComfortPolicy.endAt(session)))}", fontSize = 12.sp, color = AssistantMuted)
            session.thermal?.let { thermal ->
                if(thermal.note.isNotBlank()) UiText(thermal.note,fontSize=12.sp,color=AssistantMuted)
                thermal.tasks.forEach { task ->
                    UiText("${task.channel.title} · ${when(task.phase) {
                        SurfacePhase.WAITING -> "临近出发时开始"
                        SurfacePhase.SENT -> "已发送，等待运行回传"
                        SurfacePhase.RUNNING -> if(task.channel==ClimateChannel.STEERING) "方向盘加热已回传，舒适程度按规则估计"
                            else if(task.coolBelow!=null) "正在通风，处理座椅余热" else "低档预热中"
                        SurfacePhase.STOPPING -> "正在结束，等待关闭回传"
                        SurfacePhase.DONE -> "本次处理已完成规则评估"
                        SurfacePhase.LIMITED -> task.reason.ifBlank { "本次已结束，舒适程度未完全核实" }
                        SurfacePhase.SKIPPED -> task.reason
                    }}",fontSize=12.sp,color=AssistantMuted)
                }
                if(thermal.tasks.isNotEmpty()) UiText("座椅舒适程度按运行记录估计，未测量表面温度。",fontSize=11.sp,color=AssistantMuted)
            }
        }
        PreparationActionButton(session,now,{ CardActionService.start(context,"prepare",PreparationControl.shown(session,overview.vehicleKey,now)) },
            enabled=account.connected && (PreparationProgress.inProgress(session,now) || (!running && !account.busy && !assistant.operationPending)),
            modifier=Modifier.fillMaxWidth())
        if(!PreparationProgress.inProgress(session,now)) OutlinedButton(onClick={ model.prepareNow(15) },
            enabled=account.connected && !running && !account.busy && !assistant.operationPending && !PreparationControl.needsCheck(session),
            modifier=Modifier.fillMaxWidth().testTag("departure_in_15")) { UiText("15 分钟后用车") }
        TextButton(onClick = onPreferences) { UiText("修改下次备车偏好") }
        OperationStatus(model)
    }
}

@Composable internal fun PreparationActionButton(session: PreparationSession?, now: Long, onClick: () -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier, tone: CardTone? = null) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.testTag("start_preparation"),
        colors=if(tone==null) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
            containerColor=androidx.compose.ui.graphics.Color(tone.background),contentColor=androidx.compose.ui.graphics.Color(tone.foreground))) {
        UiText(PreparationControl.label(session,now))
    }
}

@Composable internal fun DepartureEditor(preferences: ComfortPreferences, existing: DeparturePlan?, vehicleKey: String,
    onClose: () -> Unit, onSave: (DeparturePlan) -> Unit) {
    val now = ZonedDateTime.now()
    val initial = existing?.let { Instant.ofEpochMilli(it.departure).atZone(ZoneId.of(it.zone)) } ?: now.plusMinutes(30)
    var time by remember { mutableStateOf(initial.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var title by remember { mutableStateOf(existing?.title ?: "出发计划") }
    var days by remember { mutableStateOf(existing?.days ?: emptySet()) }
    var lead by remember { mutableIntStateOf(existing?.leadMinutes ?: 15) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsedTime = runCatching { LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrNull()
    val preview = parsedTime?.let { t ->
        val candidate = now.withHour(t.hour).withMinute(t.minute).withSecond(0).withNano(0)
        val departure = if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
        DeparturePlan(vehicleKey = vehicleKey, departure = departure.toInstant().toEpochMilli(), zone = now.zone.id, days = days, leadMinutes = lead, preferences = preferences).next(now.toInstant())
    }
    AlertDialog(onDismissRequest = onClose, title = { UiText(if (existing == null) "预约用车" else "修改预约") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(title, { title = it.take(30) }, label = { UiText("名称") }, singleLine = true)
            OutlinedTextField(time, { time = it.take(5) }, label = { UiText("出发时间 HH:mm") }, singleLine = true)
            UiText("${now.zone.id} · 未选重复时，使用下一个该时刻", fontSize = 11.sp, color = AssistantMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { (1..7).forEach { day ->
                Surface(onClick = { days = if (day in days) days - day else days + day }, color = if (day in days) AssistantGreen else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { UiText((if (com.dante.zeekrbridge.ui.PhoneLanguage.locale.language == "zh") "一二三四五六日"[day-1].toString() else listOf("M", "T", "W", "T", "F", "S", "S")[day-1]), color = if (day in days) androidx.compose.ui.graphics.Color.White else AssistantMuted, fontSize = 13.sp) }
                }
            } }
            UiText("提前多久开始备车", fontSize = 12.sp, color = AssistantMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(10,15,30).forEach { minutes -> FilterChip(lead == minutes, { lead = minutes }, label = { UiText("${minutes}分", maxLines = 1) }, modifier = Modifier.weight(1f)) } }
            UiText("本次保存 ${preferences.target}°C、最长 ${preferences.minutes} 分钟及当前舒适偏好。", fontSize = 12.sp)
            UiText(preview?.let { "下次出发 ${displayTime(it)}\n开始准备 ${displayTime(it.minusSeconds(lead * 60L))}" } ?: "请选一个留有准备时间的出发时刻", fontSize = 12.sp, color = AssistantGreen)
            UiText("由本手机执行，需要联网、有效登录和“闹钟与提醒”权限。延迟超过两分钟会跳过，不补发过时备车。", fontSize = 11.sp, color = AssistantMuted)
            error?.let { UiText(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = {
        val parsed = runCatching { LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrNull()
        if (parsed == null) error = "请输入有效时间，例如 08:30"
        else {
            var departure = ZonedDateTime.now().withHour(parsed.hour).withMinute(parsed.minute).withSecond(0).withNano(0)
            if (!departure.isAfter(ZonedDateTime.now())) departure = departure.plusDays(1)
            val plan = DeparturePlan(existing?.id ?: java.util.UUID.randomUUID().toString(), vehicleKey, title.ifBlank { "出发计划" }, departure.toInstant().toEpochMilli(), departure.zone.id,
                days, lead, preferences, enabled = true)
            if (plan.next(Instant.now()) == null) error = "距离出发不足 ${lead} 分钟，请改时间或缩短提前量" else onSave(plan)
        }
    }) { UiText("保存预约") } }, dismissButton = { TextButton(onClick = onClose) { UiText("取消") } })
}

@Composable internal fun AutomationScreen(model: CheckViewModel, onPreferences: () -> Unit, onHistory: () -> Unit, onGuard: () -> Unit) {
    val context = LocalContext.current
    val state by model.assistant.state.collectAsStateWithLifecycle()
    val overview by OverviewStore.get(context).state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf<DeparturePlan?>(null) }
    AssistantCard {
        if (state.plans.isNotEmpty() || state.guardEnabled || state.homeGuardEnabled) NotificationPermissionRow()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            UiText("出发计划", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { model.assistant.edit { it.copy(paused = !it.paused) }; DepartureScheduler.sync(context); AwayGuardService.schedule(context) }) { UiText(if (state.paused) "恢复自动化" else "暂停自动化") }
        }
        UiText(if (state.paused) "已暂停本机的新任务" else "出发前读取车温，再按本次偏好备车", fontSize = 12.sp, color = AssistantMuted)
        UiText("暂停会同时影响预约和自动守护，已经开始的备车需单独停止。", fontSize = 12.sp, color = AssistantMuted)
        if (state.plans.isNotEmpty() && !DepartureScheduler.permitted(context)) {
            UiText("预约已保存在本机；允许定时权限后才能安排执行。", fontSize = 12.sp, color = AssistantMuted)
            OutlinedButton(onClick = { if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { UiText("允许闹钟与提醒") }
        }
        if (state.plans.isEmpty()) UiText("还没有出发计划", color = AssistantMuted)
        state.plans.forEach { plan ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    UiText(plan.title, fontWeight = FontWeight.SemiBold)
                    UiText(plan.next(Instant.now())?.let { displayTime(it) } ?: "本次已完成 / 停用", fontSize = 15.sp)
                    UiText("提前 ${plan.leadMinutes} 分钟 · ${plan.preferences.target}°C · ${if (plan.days.isEmpty()) "仅一次" else "重复"}", fontSize = 11.sp, color = AssistantMuted)
                    UiText(plan.result, fontSize = 11.sp, color = AssistantMuted)
                }
                Switch(plan.enabled, { checked -> model.assistant.edit { s -> s.copy(plans = s.plans.map { if (it.id == plan.id) it.copy(enabled = checked) else it }) }; DepartureScheduler.sync(context) })
            }
            Row { TextButton(onClick = { selected = plan; editing = true }) { UiText("修改") }
                TextButton(onClick = { val next = plan.next(Instant.now()); if (next != null) { model.assistant.edit { s -> s.copy(plans = s.plans.map { if (it.id == plan.id) it.copy(handled = next.toEpochMilli(), result = "已跳过本次") else it }) }; DepartureScheduler.sync(context) } }) { UiText("跳过本次") }
                TextButton(onClick = { DepartureScheduler.cancel(context, plan.id); model.assistant.edit { s -> s.copy(plans = s.plans.filterNot { it.id == plan.id }) } }) { UiText("删除") } }
        }
        Button(onClick = { selected = null; editing = true }, enabled = overview.vehicleKey != null && state.plans.size < 20, modifier = Modifier.fillMaxWidth()) { UiText("＋ 添加出发计划") }
    }
    AssistantCard {
        FeatureRow("智能备车设置", "${state.preferences.target}°C · 允许范围与运行时长", onClick = onPreferences)
        FeatureRow("离家停车守护", state.guardMessage, onClick = onGuard)
        FeatureRow("操作记录", "查看受理、确认、跳过与失败原因", onClick = onHistory)
    }
    if (editing && overview.vehicleKey != null) DepartureEditor(selected?.preferences ?: state.preferences, selected, overview.vehicleKey!!, { editing = false }) { plan ->
        model.assistant.edit { it.copy(plans = it.plans.filterNot { old -> old.id == plan.id } + plan) }; DepartureScheduler.sync(context); editing = false
    }
}

@Composable internal fun HistoryScreen(state: AssistantState) {
    AssistantCard {
        UiText("操作记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (state.history.isEmpty()) UiText("还没有车控记录。保存偏好不会发送指令。", color = AssistantMuted)
        state.history.forEach { entry ->
            UiText(entry.title, fontWeight = FontWeight.SemiBold); UiText(entry.result, color = AssistantGreen, fontSize = 13.sp)
            UiText(displayTime(Instant.ofEpochMilli(entry.at)), fontSize = 11.sp, color = AssistantMuted); HorizontalDivider()
        }
    }
}

@Composable internal fun PlacesScreen(model: CheckViewModel) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val state by model.assistant.state.collectAsStateWithLifecycle(); val point = state.location
    var note by remember(state.parkingNote) { mutableStateOf(state.parkingNote) }; var resolving by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf(false) }
    HomeLocationCard(state) { home, radius ->
        model.assistant.edit { it.copy(home = home, homeRadius = radius,
            guardEnabled = it.guardEnabled && home != null, lastGuardSource = 0,
            homeGuardEnabled = it.homeGuardEnabled && home != null,
            guardMessage = if (home == null) "规则已停用" else "家的范围已更新，等待新位置") }
        AwayGuardService.schedule(context)
    }
    AssistantCard {
        UiText("车辆位置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        UiText(point?.address?.ifBlank { "已读到坐标，地址未解析" } ?: "还没有可用车辆位置", raw = !point?.address.isNullOrBlank(), color = AssistantGreen)
        UiText(point?.source?.let { displayTime(Instant.ofEpochMilli(it)) } ?: "来源时间未知", fontSize = 11.sp, color = AssistantMuted)
        if (point != null) {
            OutlinedButton(onClick = { mapError = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}(我的车辆)"))) }.isFailure }) { UiText("在地图查看位置") }
            if (mapError) UiText("没有可用的地图应用，请安装地图后再试。", fontSize = 12.sp, color = AssistantMuted)
            TextButton(onClick = {
                resolving = true
                scope.launch {
                    val address = withContext(Dispatchers.IO) { runCatching { @Suppress("DEPRECATION") Geocoder(context, Locale.getDefault()).getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()?.getAddressLine(0)?.take(160) }.getOrNull() }
                    model.assistant.edit { current -> if (current.location?.latitude == point.latitude && current.location?.longitude == point.longitude) current.copy(location = point.copy(address = address ?: "地址解析暂不可用")) else current }
                    resolving = false
                }
            }, enabled = !resolving) { UiText(if (resolving) "解析中…" else "解析街道地址") }
            CheckPreference("我已在地图核对过坐标位置", point.verified) { verified -> model.assistant.edit { it.copy(location = point.copy(verified = verified)) } }
        }
        TextButton(onClick = model::refreshOverview) { UiText("刷新车辆位置") }
    }
    AssistantCard {
        UiText("我的停车备注", fontWeight = FontWeight.Bold)
        OutlinedTextField(note, { note = it.take(80) }, label = { UiText("例如 B2 层 · C 区 12 号") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.assistant.edit { it.copy(parkingNote = note) } }) { UiText("保存备注") }
        UiText("家：${if (state.home == null) "未设置" else "已保存"} · 范围 ${state.homeRadius} 米", color = AssistantMuted, fontSize = 12.sp)
    }
}

@Composable internal fun NotificationPermissionRow() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { refresh++ }
    @Suppress("UNUSED_VARIABLE") val revision = refresh
    if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        UiText("通知未开启，达温和任务结果提醒可能不可见。", fontSize = 12.sp, color = AssistantMuted)
        Row {
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { request.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { UiText("允许任务通知") }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { UiText("通知设置") }
        }
    }
}

@Composable internal fun WidgetChoices(pinPlatform: WidgetPinPlatform? = null) {
    val context = LocalContext.current
    val platform = pinPlatform ?: remember(context) { AndroidWidgetPinPlatform(context) }
    var selected by rememberSaveable { mutableIntStateOf(2) }
    val sizes = listOf("2×2", "4×1", "4×2", "4×3")
    val providers = listOf(SquareVehicleWidgetProvider::class.java, StripVehicleWidgetProvider::class.java,
        CompactVehicleWidgetProvider::class.java, VehicleWidgetProvider::class.java)
    val configuration = LocalConfiguration.current
    val language = com.dante.zeekrbridge.ui.PhoneLanguage.mode
    val preview = remember(selected, language, configuration) {
        val now = Instant.now()
        val snapshot = VehicleOverview(vehicleKey = "widget-preview", nickname = "OpenAVM 7X", refreshedAt = now.toEpochMilli(), readings = mapOf(
            "battery" to OverviewReading("68 %", now.toEpochMilli(), now.toEpochMilli()),
            "range" to OverviewReading("412 km", now.toEpochMilli(), now.toEpochMilli()),
            "cabin_temperature" to OverviewReading("25.4 °C", now.toEpochMilli(), now.toEpochMilli()),
            "lock" to OverviewReading("已锁", now.toEpochMilli(), now.toEpochMilli()),
            "sentry" to OverviewReading("关闭", now.toEpochMilli(), now.toEpochMilli())))
        val remote = if (selected < 2) SmallVehicleWidget.views(context, snapshot, selected == 1, now, preview = true)
            else VehicleWidgetProvider.views(context, snapshot, selected == 2, now, locationLabel = "家", preview = true)
        val view = remote.apply(context, android.widget.FrameLayout(context))
        val density = context.resources.displayMetrics.density
        val width = ((if (selected == 0) 170 else 340) * density).toInt()
        val height = (listOf(200, 100, 230, 300)[selected] * density).toInt()
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }.asImageBitmap()
    }
    AssistantCard {
        UiText("选一张适合你的卡片", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sizes.forEachIndexed { index, size -> FilterChip(selected == index, { selected = index }, label = { UiText(size, raw = true) }, modifier = Modifier.weight(1f).testTag("widget_size_$index")) }
        }
        Surface(color = AssistantGreen.copy(alpha = 0.08f), shape = RoundedCornerShape(18.dp)) {
            Image(preview, ui("卡片外观预览"), modifier = Modifier.fillMaxWidth().height(if (selected < 2) 220.dp else 290.dp).padding(12.dp).testTag("widget_preview"), contentScale = ContentScale.Fit)
        }
        UiText("示例车况 · 添加后显示你绑定的车辆", fontSize = 12.sp, color = AssistantMuted)
        UiText(if (selected < 2) "备车、找车、车锁、哨兵，四个常用操作。" else "六个操作、车内温度与车辆状态。", fontSize = 14.sp)
        WidgetPinActions(ComponentName(context, providers[selected]), sizes[selected], platform)
        UiText("按钮直接执行，卡片显示进度；箭头打开应用。桌面网格和字体大小可能影响显示。", color = AssistantMuted, fontSize = 13.sp)
        WidgetBindingRows()
    }
}

@Composable private fun WidgetBindingRows() {
    val context = LocalContext.current
    val store = remember { AppearanceStore.get(context) }
    val appearances by store.state.collectAsStateWithLifecycle()
    val current by OverviewStore.get(context).state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf(false) }
    appearances.widgets.forEach { (id,binding) ->
        HorizontalDivider()
        UiText("卡片 #$id · ${appearances.names[binding.vehicleKey] ?: "绑定车辆"}",fontSize = 13.sp,fontWeight = FontWeight.SemiBold)
        UiText(if (binding.vehicleKey == current.vehicleKey) "绑定当前车辆" else "绑定另一辆车；切换到它后才能操作",fontSize = 11.sp,color = AssistantMuted)
        TextButton(onClick = { context.startActivity(Intent(context,AppearanceActivity::class.java).putExtra("vehicleKey",binding.vehicleKey)) }) { UiText("设置车辆外观与车牌隐私") }
        if (current.vehicleKey != null && current.vehicleKey != binding.vehicleKey) OutlinedButton(onClick = {
            runCatching { store.bind(id,current.vehicleKey!!,current.nickname); VehicleWidgetProvider.updateAll(context) }.onFailure { error = true }
        }) { UiText("把此卡片改绑当前车辆") }
    }
    if (error) UiText("卡片设置未能保存，请重试",color = MaterialTheme.colorScheme.error)
}
