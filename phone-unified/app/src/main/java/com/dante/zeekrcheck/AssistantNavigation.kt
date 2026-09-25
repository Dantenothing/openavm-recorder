package com.dante.zeekrcheck

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.delay
import java.time.Instant

@Composable fun AssistantApp(model: CheckViewModel = assistantModel(), initialDestination: String? = null, navigationRequest: Int = 0) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val store = remember { OverviewStore.get(context) }
    val overview by store.state.collectAsStateWithLifecycle()
    val assistant by model.assistant.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("home") }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var lab by rememberSaveable { mutableStateOf(false) }
    var connectionSetup by rememberSaveable { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    var nameEditor by remember { mutableStateOf(false) }
    var mediaDetail by remember { mutableStateOf(false) }
    var guideReturn by rememberSaveable { mutableStateOf(false) }
    val guidePrefs = remember { BeginnerGuide.preferences(context) }
    val access by CloudAccess.state.collectAsStateWithLifecycle()
    val canControl = access.ready && access.session && !state.busy && overview.vehicleKey != null && (state.connected || state.sessionSaved)
    val displayName = if (overview.vehicleKey == null && overview.nickname == VehicleOverview().nickname) ui(overview.nickname) else overview.nickname
    // Session restoration starts asynchronously. Do not mistake its initial empty state for a new install.
    LaunchedEffect(state.busy, state.sessionSaved) {
        if (!state.busy && !guidePrefs.getBoolean("introduced", false)) {
            guidePrefs.edit().putBoolean("introduced", true).apply()
            if (!state.sessionSaved && overview.vehicleKey == null && initialDestination == null &&
                page == null && tab == "home" && !lab) page = "guide"
        }
    }
    fun backToPage() { page = if (guideReturn) "guide" else null; guideReturn = false }
    var initialReadRequested by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val preparation=assistant.activePreparation?.takeIf { it.vehicleKey==overview.vehicleKey }
    val preparationNow = maxOf(now.toEpochMilli(),System.currentTimeMillis())
    val thermal = ThermalPresentation.from(overview, preparation, Instant.ofEpochMilli(preparationNow))
    val scroll = rememberScrollState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            DepartureScheduler.sync(context); if (state.connected) WidgetRefreshService.request(context, interactive = false)
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tab, page) { scroll.scrollTo(0); mediaDetail = false }
    LaunchedEffect(navigationRequest) {
        if(navigationRequest>0) {
            lab=initialDestination=="cloud"
            if (lab) connectionSetup = true
            tab=if(initialDestination in setOf("media","connection")) "media" else "home"
            page=initialDestination?.takeIf { it=="connection" }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    LaunchedEffect(state.connected, state.busy) {
        if (state.connected && !state.busy && !initialReadRequested) {
            initialReadRequested = true
            model.resumePreparation()
            if ((overview.refreshedAt ?: 0) < System.currentTimeMillis() - 60_000) model.syncOverview(SyncReason.APP)
        }
    }
    if (!lab) AssistantSessionEffects(model)
    BackHandler(lab || page != null || tab != "home") { if (lab) { lab = false; if (guideReturn) backToPage() } else if (page != null) backToPage() else tab = "home" }
    val action: (String) -> Unit = { if (it in CardControl.actions) CardActionService.start(context, it,
        if(it=="prepare") PreparationControl.shown(preparation,overview.vehicleKey,preparationNow) else null)
        else context.startActivity(VehicleWidgetProvider.actionIntent(context, it)) }
    AssistantTheme {
        if (lab) Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextButton(onClick = { lab = false; if (guideReturn) backToPage() }) { UiText(if (connectionSetup && !state.configReady) "稍后设置，返回 OpenAVM" else "‹ 返回 OpenAVM") }
            Box(Modifier.weight(1f)) { CheckApp(model, setupOnly = connectionSetup) }
        } else Scaffold(containerColor = AssistantPaper, bottomBar = {
            if(!mediaDetail && page != "guide") NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(Triple("home", "首页", R.drawable.ic_widget_home), Triple("vehicle", "车辆", R.drawable.ic_widget_car),
                    Triple("media", "影像", com.dante.zeekrbridge.R.drawable.ic_openavm_library), Triple("automation", "自动化", R.drawable.ic_widget_clock), Triple("more", "更多", R.drawable.ic_widget_grid)).forEach { (id, title, icon) ->
                    NavigationBarItem(selected = tab == id, onClick = { tab = id; page = null; guideReturn = false }, icon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
                        label = { UiText(title, fontSize = 11.sp) }, modifier = Modifier.testTag("nav_$id"))
                }
            }
        }) { padding ->
            if (page == "guide") Box(Modifier.fillMaxSize().padding(padding)) {
                BeginnerGuideScreen(state.connected, assistant.home != null, { destination ->
                        when (destination) {
                            "preferences" -> preferences = true
                            "account" -> { guideReturn = true; connectionSetup = true; lab = true }
                            else -> { guideReturn = true; page = destination }
                        }
                    }, { page = null; guideReturn = false }, { BeginnerGuide.complete(context); tab = "home"; page = null; guideReturn = false },
                        initialStep = BeginnerGuide.position(context), onStepChanged = { BeginnerGuide.savePosition(context, it) })
            } else if((page ?: tab) in OpenAvmIntegration.destinations) Box(Modifier.fillMaxSize().padding(padding)) {
                OpenAvmDestination(page ?: tab,mediaDetail,{mediaDetail=it},{backToPage()}, { destination ->
                    if(destination=="media") {tab="media";page=null} else page=destination
                })
            } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (page != null) TextButton(onClick = { backToPage() }, contentPadding = PaddingValues(0.dp)) { UiText("‹ 返回") }
                    Column(Modifier.weight(1f)) {
                        UiText("OPENAVM", fontSize = 10.sp, letterSpacing = 1.5.sp, color = AssistantGreen)
                        UiText(when (page ?: tab) { "home" -> if (state.configReady) displayName else "OpenAVM"; "vehicle" -> "我的车辆"; "automation" -> "提前准备"; "guard" -> "停车守护";
                            "history" -> "操作记录"; "background" -> "后台与自动化"; "prepare" -> "本次备车"; "places" -> "停车位置与家"; "appearance" -> "车辆外观"; "quickcheck" -> "离车检查"; "charge" -> "充电与电量"; "widgets" -> "桌面卡片"; "rule" -> "守护规则"; "guide" -> "新手指南"; "language" -> "语言 / Language"; "new_phone" -> "换手机使用"; else -> "我的助手" },
                            raw = (page ?: tab) == "home", fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (page == null && tab == "home" && state.configReady) TextButton(onClick = { nameEditor = true }, modifier = Modifier.testTag("edit_vehicle_name")) { UiText("编辑") }
                    if (state.configReady && (page ?: tab) in setOf("home", "vehicle")) {
                    ManualRefreshButton(overview.refreshing(Instant.ofEpochMilli(preparationNow)), canControl, model::refreshOverview)
                    }
                }
                if (!state.connected && (page ?: tab) in setOf("home", "vehicle", "automation", "guard", "rule", "charge", "quickcheck") && ((page ?: tab) != "home" || state.configReady)) AssistantCard {
                    UiText(if (state.busy) state.stage else "连接你的车辆", fontWeight = FontWeight.Bold)
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else Button(onClick = { if (state.sessionSaved) model.reconnect() else { connectionSetup = true; lab = true } }) { UiText(if (state.sessionSaved) "恢复保存的登录" else "连接账号") }
                    if (!state.sessionSaved) UiText("连接后显示你的车况。录像接收可在影像页单独设置。", fontSize = 13.sp, color = AssistantMuted)
                    state.message?.let { UiText(it, fontSize = 12.sp, color = AssistantMuted) }
                }
                when (if ((page ?: tab) == "home" && !state.configReady) "local_home" else page ?: tab) {
                    "local_home" -> {
                        OpenAvmSummaryCard(false, { page = "connection" }, { tab = "media" })
                        AssistantCard {
                            UiText("极氪云端（可选）", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            UiText(if (access.needsImport) "请重新导入连接配置并登录；录像和车机配对已保留，云端自动化已暂停。"
                                else "导入自己的连接配置并登录后，可使用车况、备车和车辆控制。", color = AssistantMuted)
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Button(onClick = { connectionSetup = true; lab = true }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("setup_cloud")) { UiText("设置云端连接") }
                        }
                        AssistantCard { FeatureRow("新手指南", "先用影像，云端连接可稍后设置") { page = "guide" } }
                    }
                    "home" -> {
                        if (guidePrefs.getInt("completed", 0) < BeginnerGuide.VERSION) AssistantCard {
                            FeatureRow("新手指南", "连接、备车、桌面卡片，一次弄明白") { page = "guide" }
                        }
                        if (overview.vehicleKey != null) {
                        AssistantCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    UiText(overview.readings["battery"]?.value ?: "— %", fontSize = 42.sp, fontWeight = FontWeight.Bold)
                                    UiText("预计续航 ${overview.readings["range"]?.value ?: "— km"}", fontSize = 12.sp, color = AssistantMuted)
                                    Spacer(Modifier.height(12.dp)); UiText(overview.cabinCaption(now), fontSize = 12.sp, color = AssistantMuted)
                                    val temperatureTask = assistant.temperatureUpdate?.takeIf { it.vehicleKey == overview.vehicleKey }
                                    CabinTemperatureReading(overview.cabin(now), temperatureTask, canControl, {
                                        if (temperatureTask?.expiredOwnership(System.currentTimeMillis()) == true)
                                            context.startActivity(VehicleWidgetProvider.actionIntent(context, "temperature_update"))
                                        else TemperatureUpdateService.request(context)
                                    }, preparationNow)
                                    UiText(overview.thermalLabel(now), fontSize = 12.sp, color = AssistantMuted)
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    VehicleArt(overview.vehicleKey, Modifier.fillMaxWidth().height(128.dp), thermal.ambient, thermal.airflow) { page = "appearance" }
                                    UiText(overview.motionLabel(now), fontSize = 12.sp, color = AssistantGreen)
                                }
                            }
                            if (thermal.label != overview.thermalLabel(now)) UiText(thermal.label, fontSize = 13.sp, color = AssistantGreen, modifier = Modifier.testTag("thermal_status"))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                UiText("车锁 · ${overview.readings["lock"]?.value ?: "未知"}", fontSize = 12.sp)
                                UiText("哨兵 · ${overview.readings["sentry"]?.value ?: "未知"}", fontSize = 12.sp)
                            }
                            UiText(overview.temperatureTimeLabel(now), fontSize = 10.sp, color = AssistantMuted)
                            if (assistant.temperatureUpdate?.let { it.vehicleKey == overview.vehicleKey && it.visible(preparationNow) } != true)
                                UiText("单独更新车温时可能短暂开启空调", fontSize = 10.sp, color = AssistantMuted)
                            TemperatureUpdatePanel(assistant.temperatureUpdate?.takeIf { it.vehicleKey == overview.vehicleKey },
                                state.connected && !state.busy, {}, preparationNow, showAction = false)
                            UiText("电量、车锁和哨兵为上次上报记录", fontSize = 10.sp, color = AssistantMuted)
                            if (assistant.temperatureUpdate?.let { it.vehicleKey == overview.vehicleKey && it.visible(preparationNow) } != true || overview.actionRunning(now))
                                PreparationProgress.message(preparation,overview.message,preparationNow)?.let { UiText(it, fontSize = 12.sp, color = AssistantGreen, modifier = Modifier.testTag("overview_message")) }
                            FeatureRow(HomeZone.label(assistant.location, assistant.home, assistant.homeRadius, System.currentTimeMillis()), assistant.parkingNote.ifBlank { "查看地图、地址与停车备注" }, rawDetail = assistant.parkingNote.isNotBlank()) { page = "places" }
                        }
                        AssistantCard {
                            UiText(preparation?.let { thermal.preparationTitle(it,preparationNow) } ?: "下一程，提前准备", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            preparation?.let { UiText(if(PreparationProgress.idle(it,preparationNow)) "上次备车：${PreparationProgress.title(PreparationProgress.phase(it,preparationNow))}" else thermal.preparationDetail(it,preparationNow),fontSize=14.sp,color=AssistantGreen) }
                            UiText("${overview.target}°C · 最长 ${assistant.preferences.minutes} 分钟", color = AssistantGreen)
                            assistant.plans.mapNotNull { it.next(now) }.minOrNull()?.let { UiText("下次出发 ${displayTime(it)}", fontSize = 12.sp) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PreparationActionButton(preparation,preparationNow,{ action("prepare") },enabled=canControl || preparation?.let { PreparationProgress.inProgress(it, preparationNow) } == true,modifier=Modifier.weight(1f),tone=CardAppearance.tone("prepare",overview,now,preparation))
                                OutlinedButton(onClick = { tab = "automation" }, modifier = Modifier.weight(1f)) { UiText("预约用车") }
                            }
                            TextButton(onClick={page="prepare"}) { UiText(if(preparation==null || PreparationProgress.inProgress(preparation,preparationNow)) "备车详情" else "上次备车详情") }
                            TextButton(onClick = { preferences = true }) { UiText("智能备车设置") }
                        }
                        AssistantCard {
                            FeatureRow("停车守护",assistant.guardMessage) {page="guard"}
                            FeatureRow("离车检查", "车锁、门窗、哨兵与数据时效一起核对") { page = "quickcheck" }
                        }
                        VehicleControls(action, canControl)
                        OperationStatus(model)
                        }
                        OpenAvmSummaryCard(state.connected,{page="connection"},{tab="media";page=null})
                    }
                    "vehicle" -> {
                        AssistantCard { UiText("车内舒适", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            UiText("通风与加热放在一起：点按切档，长按选档。", fontSize = 13.sp, color = AssistantMuted)
                            Button(onClick = { action("climate") }, enabled = canControl, modifier = Modifier.fillMaxWidth()) { UiText("空调与座椅") }
                        }
                        VehicleControls(action, canControl)
                        AssistantCard { FeatureRow("原厂哨兵与停车守护",assistant.guardMessage) {page="guard"} }
                        AssistantCard {
                            FeatureRow("充电与电量", "电量、充电遥测及现有预约") { page = "charge" }
                    FeatureRow("车辆位置", "地图与停车备注") { page = "places" }
                            FeatureRow("本车能力", "查看这辆车与此 guest 账号的实际证据") { connectionSetup = false; lab = true }
                            OverviewRows(overview, now)
                        }
                    }
                    "automation" -> AutomationScreen(model, { preferences = true }, { page = "history" }, { page = "rule" })
                    "prepare" -> AssistantCard { PreparationPanel(model) { preferences=true } }
                    "guard", "rule" -> GuardScreen(model, { action("guard") }, { page = "places" }, { page = "background" })
                    "background" -> BackgroundSettingsScreen(model) { page = "rule" }
                    "history" -> HistoryScreen(assistant)
                    "places" -> PlacesScreen(model)
                    "appearance" -> AppearanceScreen(overview.vehicleKey) { page = null }
                    "quickcheck" -> AssistantCard {
                        UiText("离车检查", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        listOf("lock" to "车锁", "sentry" to "原厂哨兵", "cabin_temperature" to "车内温度").forEach { (key, title) ->
                            val reading = overview.readings[key]
                            UiText("$title  ${reading?.value ?: "未知"}"); UiText(reading?.timeLabel(now) ?: "尚未取得", fontSize = 11.sp, color = AssistantMuted)
                        }
                        UiText("门窗与尾门到位状态尚未完成本车映射，不能据此显示‘全部正常’。", fontSize = 12.sp, color = AssistantMuted)
                        Button(onClick = { action("lock") }, enabled = canControl) { UiText("车锁控制") }; OutlinedButton(onClick = { action("windows") }, enabled = canControl) { UiText("车窗控制") }
                    }
                    "charge" -> AssistantCard {
                        UiText("电量 ${overview.readings["battery"]?.value ?: "未知"}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        state.report?.capabilities?.filter { it.endpoint in setOf(Endpoint.CHARGING, Endpoint.SOC_LIMIT, Endpoint.CHARGE_PLAN, Endpoint.TRAVEL_PLAN) }?.forEach { cap ->
                            UiText("${cap.title} · ${cap.value}", fontSize = 14.sp)
                        }
                        Button(onClick = model::checkVehicle, enabled = !state.busy && state.connected) { UiText("读取充电与预约状态") }
                        OutlinedButton(onClick = { action("port") }, enabled = canControl) { UiText("充电口控制") }
                    }
                    "widgets" -> WidgetChoices()
                    "language" -> LanguageSettings()
                    "new_phone" -> NewPhoneChecklist { BeginnerGuide.savePosition(context, 1); page = "guide" }
                    else -> AssistantMoreScreen(state.sessionSaved, assistant.home != null, displayName) { destination ->
                        when (destination) {
                            "preferences" -> preferences = true
                            "nickname" -> nameEditor = true
                            "account", "diagnostics" -> { connectionSetup = destination == "account"; lab = true }
                            else -> page = destination
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (preferences) PreferenceEditor(assistant.preferences.copy(target = overview.target), { preferences = false }) { prefs ->
            model.assistant.edit { it.copy(preferences = prefs) }; store.preferences(overview.nickname, prefs.target); preferences = false
        }
        if (nameEditor) RenameVehicleDialog(overview.nickname, { nameEditor = false }) { name ->
            store.rename(name); nameEditor = false
        }
    }
}

@Composable internal fun VehicleControls(action: (String) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val overview by OverviewStore.get(context).state.collectAsStateWithLifecycle()
    AssistantCard {
        UiText("车辆控制", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        listOf(listOf("lock" to "车锁", "windows" to "车窗"), listOf("trunk" to "尾门", "port" to "充电口")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (key, name) ->
                OutlinedButton(onClick = { action(key) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    if (key != "windows") { Icon(painterResource(VehicleWidgetProvider.controlIcon(key, overview)), null, Modifier.size(24.dp)); Spacer(Modifier.width(6.dp)) }
                    UiText(if (key == "windows") name else VehicleWidgetProvider.controlLabel(key, overview))
                }
            } }
        }
        OutlinedButton(onClick = { action("find") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { UiText("找车鸣笛一次") }
    }
}

@Composable internal fun GuardScreen(model: CheckViewModel, onControl: () -> Unit, onPlaces: () -> Unit, onBackground: () -> Unit) {
    val context = LocalContext.current; val state by model.assistant.state.collectAsStateWithLifecycle()
    val overview by OverviewStore.get(context).state.collectAsStateWithLifecycle()
    val connection by model.state.collectAsStateWithLifecycle()
    var radius by remember(state.homeRadius) { mutableFloatStateOf(state.homeRadius.toFloat()) }
    val parking = ParkingEvidence.from(connection.report?.probes?.lastOrNull { it.endpoint == Endpoint.STATUS })
    val parkingNow = parkingClock(parking?.fetched)
    AssistantCard {
        UiText("原厂哨兵", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        UiText(overview.readings["sentry"]?.value ?: "状态未知", fontSize = 32.sp, color = AssistantGreen)
        UiText(overview.readings["sentry"]?.timeLabel(Instant.now()) ?: "等待车辆数据", fontSize = 11.sp, color = AssistantMuted)
        Button(onClick = onControl, enabled = overview.vehicleKey != null && (connection.connected || connection.sessionSaved), modifier = Modifier.fillMaxWidth()) { UiText(when (overview.readings["sentry"]?.value) { "开启" -> "关闭哨兵"; "关闭" -> "开启哨兵"; else -> "读取哨兵状态" }) }
    }
    ParkingStatusCard(parking, parkingNow)
    HomeGuardSettingsCard(state, onEnabled = { enabled ->
        model.assistant.edit { it.copy(homeGuardEnabled = enabled,
            guardMessage = if (enabled) "到家关闭已启用 · 等待两次有效确认" else "到家自动关闭已停用") }
        AwayGuardService.schedule(context)
        if (enabled) AwayGuardService.trigger(context, SyncReason.HOME_CONFIRM)
    }, onResume = {
        overview.vehicleKey?.let(model.assistant::resumeHomeGuard)
        AwayGuardService.trigger(context, SyncReason.HOME_CONFIRM)
    })
    AssistantCard {
        UiText("离家停车自动守护", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        UiText("按车辆位置判断，不使用手机位置代替车辆。家周围 ${state.homeRadius} 米作为可信停车区，边缘另有 100 米定位缓冲。", fontSize = 12.sp, color = AssistantMuted)
        Slider(radius, { radius = it }, valueRange = 100f..1000f, steps = 17,
            onValueChangeFinished = { model.assistant.edit { it.copy(homeRadius = (radius.toInt()/50)*50) } })
        Switch(state.guardEnabled, { enabled -> model.assistant.edit { it.copy(guardEnabled = enabled, guardMessage = if (enabled) "等待读取位置与驻车证据" else "规则已停用") }; AwayGuardService.schedule(context) }, enabled = state.home?.verified == true)
        UiText(state.guardMessage, color = AssistantGreen, fontSize = 13.sp)
        ParkingJourneyStatus(state.parkingGuard.journey)
        if (state.parkingGuard.manualPaused) UiText("本次暂停会保留；解锁取物、重开 App 或手机重启都不会清除。", fontSize = 12.sp, color = AssistantMuted)
        OutlinedButton(onClick = {
            overview.vehicleKey?.let { key -> if (state.parkingGuard.paused) model.assistant.resumeGuard(key) else model.assistant.pauseGuard(key) }
        }, enabled = overview.vehicleKey != null, modifier = Modifier.testTag("guard_parking_pause")) {
            UiText(if (state.parkingGuard.paused) "恢复本次自动守护" else "本次不自动守护")
        }
        UiText("启用后约每 15 分钟检查，系统省电可能延迟。家外、原厂已驻车、非行驶模式、车速为零且已锁车时开启；车况须在 3 分钟内，位置须可信。本次手动暂停优先；到家关闭由上方独立开关控制。", fontSize = 11.sp, color = AssistantMuted)
        UiText("在本应用关闭哨兵会暂停本次守护。确认新行程后恢复；漏读行驶时可通过总里程增长和驻车复查补充判断。原厂 App 或车机的手动关闭，只有观察到本次停车中哨兵由开变关才能识别。", fontSize = 11.sp, color = AssistantMuted)
        OutlinedButton(onClick = onPlaces) { UiText(if (state.home == null) "设置家的位置" else "查看停车位置") }
        FeatureRow("后台与自动化", "查看实际检查时间、设置后台和运行只读自检", onClick = onBackground)
    }
}
