package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.content.SharedPreferences
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.V5ReleaseDefaults
import com.dante.zeekrcapabilitylab.util.Utils

enum class MirrorQuickAction {
    RESTORE, OPEN_APP, SAVE_CLIP, MENU;
    fun label() = when(this) {
        RESTORE -> Utils.t("Restore mirror", "展开后视镜")
        OPEN_APP -> "OpenAVM"
        SAVE_CLIP -> Utils.t("Save clip", "保存片段")
        MENU -> Utils.t("Mirror actions", "后视镜操作")
    }
}

data class MirrorPanel(val lane: Int, val rotation: Int = 0, val mirrored: Boolean = false,
    val viewport: MirrorViewport = MirrorViewport(), val fovDegrees: Float = 110f)

object MirrorLayoutPolicy {
    fun triple(left: Int, rear: Int, right: Int): List<Int>? =
        listOf(left, rear, right).takeIf { it.all { lane -> lane in 1..4 } && it.distinct().size == 3 }
    fun panelAt(fraction: Float): Int = when { fraction < 0.25f -> 0; fraction < 0.75f -> 1; else -> 2 }
    fun gridAt(x: Float, y: Float): Int = (if (y < 0.5f) 0 else 2) + if (x < 0.5f) 0 else 1
    fun grid(lanes: List<Int>): List<Int>? = lanes.takeIf { it.size == 4 && it.toSet() == setOf(1, 2, 3, 4) }
    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    fun dockSide(x: Int, windowWidth: Int, screenWidth: Int, threshold: Int): Int = when {
        x <= threshold -> -1
        x + windowWidth >= screenWidth - threshold -> 1
        else -> 0
    }
}

/** Display preferences only: changing these never creates a recording permit. */
class MirrorPresentation private constructor(private val prefs: SharedPreferences, private val legacy: SettingsStore) {
    constructor(context: Context) : this(
        context.getSharedPreferences("mirror_presentation_v1", Context.MODE_PRIVATE), SettingsStore.get(context))
    init {
        V5ReleaseDefaults.applyMirror(prefs)
        if (!prefs.getBoolean("single_column_layout_v2", false)) {
            val previous = prefs.getInt("width", 720)
            prefs.edit().putInt("width", if (previous in 440..719) 720 else previous)
                .putBoolean("single_column_layout_v2", true).apply()
        }
    }
    var dockTap: MirrorQuickAction
        get() = runCatching { MirrorQuickAction.valueOf(prefs.getString("dock_tap", "RESTORE")!!) }.getOrDefault(MirrorQuickAction.RESTORE)
        set(value) { prefs.edit().putString("dock_tap", value.name).apply() }
    var dockHold: MirrorQuickAction
        get() = runCatching { MirrorQuickAction.valueOf(prefs.getString("dock_hold", "MENU")!!) }.getOrDefault(MirrorQuickAction.MENU)
        set(value) { prefs.edit().putString("dock_hold", value.name).apply() }
    var rightHandDrive: Boolean
        get() = prefs.getBoolean("right_hand_drive", true)
        set(value) { prefs.edit().putBoolean("right_hand_drive", value).apply() }
    var locked: Boolean
        get() = prefs.getBoolean("locked", false)
        set(value) { prefs.edit().putBoolean("locked", value).apply() }
    var dock: Int
        get() = prefs.getInt("dock", 0).coerceIn(-1, 1)
        set(value) { prefs.edit().putInt("dock", value.coerceIn(-1, 1)).apply() }
    var triple: Boolean
        get() = prefs.getBoolean("triple", false)
        set(value) { prefs.edit().putBoolean("triple", value).apply() }
    var grid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(value) { prefs.edit().putBoolean("grid", value).apply() }
    var controlsVisible: Boolean
        get() = prefs.getBoolean("controls_visible", true)
        set(value) { prefs.edit().putBoolean("controls_visible", value).apply() }
    var selectedLane: Int
        get() = prefs.getInt("lane", legacy.mirrorRearLane).takeIf { it in 1..4 } ?: legacy.mirrorRearLane
        set(value) { if (value in 1..4) prefs.edit().putInt("lane", value).apply() }
    var leftLane: Int
        get() = prefs.getInt("left_lane", V5ReleaseDefaults.LEFT_LANE).takeIf { it in 1..4 } ?: V5ReleaseDefaults.LEFT_LANE
        set(value) { if (value in 1..4) prefs.edit().putInt("left_lane", value).apply() }
    var rightLane: Int
        get() = prefs.getInt("right_lane", V5ReleaseDefaults.RIGHT_LANE).takeIf { it in 1..4 } ?: V5ReleaseDefaults.RIGHT_LANE
        set(value) { if (value in 1..4) prefs.edit().putInt("right_lane", value).apply() }
    var widthDp: Int
        get() = prefs.getInt("width", 720).coerceIn(300, 1400)
        set(value) { prefs.edit().putInt("width", value.coerceIn(300, 1400)).apply() }
    var controlsOnRight: Boolean
        get() = prefs.getBoolean("controls_on_right", true)
        set(value) { prefs.edit().putBoolean("controls_on_right", value).apply() }
    var resident: Boolean
        get() = prefs.getBoolean("resident", true)
        set(value) { prefs.edit().putBoolean("resident", value).apply() }
    var videoVisible: Boolean
        get() = prefs.getBoolean("video_visible", true)
        set(value) { prefs.edit().putBoolean("video_visible", value).apply() }
    fun lens(lane: Int): FourLaneLensMode = runCatching {
        FourLaneLensMode.valueOf(prefs.getString("lens:$lane", "STANDARD")!!)
    }.getOrDefault(FourLaneLensMode.STANDARD)
    fun setLens(lane: Int, mode: FourLaneLensMode) { if (lane in 1..4) prefs.edit().putString("lens:$lane", mode.name).apply() }
    fun directionLanes(): List<Int> = MirrorDirectionMapping.resolve(legacy.laneOrder, legacy.calibrated,
        prefs.getInt("front_lane", V5ReleaseDefaults.FRONT_LANE), legacy.mirrorRearLane, leftLane, rightLane)
    fun directionLabel(slot: Int): String = when (slot) {
        0 -> Utils.t("Front", "前"); 1 -> Utils.t("Rear", "后")
        2 -> Utils.t("Left", "左"); else -> Utils.t("Right", "右")
    }
    fun setDirection(slot: Int, lane: Int) {
        if (lane !in 1..4 || slot !in 0..3) return
        when (slot) {
            0 -> prefs.edit().putInt("front_lane", lane).apply()
            1 -> legacy.setMirrorRearLane(lane)
            2 -> leftLane = lane
            3 -> rightLane = lane
        }
    }
    val xFraction get() = MirrorLayoutPolicy.clamp(prefs.getFloat(if (rightHandDrive) "rx" else "lx", if (rightHandDrive) 1f else 0f))
    val yFraction get() = MirrorLayoutPolicy.clamp(prefs.getFloat(if (rightHandDrive) "ry" else "ly", 0.15f))
    fun position(x: Float, y: Float) { prefs.edit()
        .putFloat(if (rightHandDrive) "rx" else "lx", MirrorLayoutPolicy.clamp(x))
        .putFloat(if (rightHandDrive) "ry" else "ly", MirrorLayoutPolicy.clamp(y)).apply() }
    fun lanes(): List<Int>? = MirrorLayoutPolicy.triple(leftLane, legacy.mirrorRearLane, rightLane)
    fun panel(lane: Int): MirrorPanel {
        val slot = legacy.laneOrder.indexOf(lane)
        return MirrorPanel(lane,
            if (lane == legacy.mirrorRearLane) legacy.mirrorRotation else legacy.laneRotations.getOrElse(slot) { 0 },
            lane == legacy.mirrorRearLane && legacy.mirrorHorizontal, viewport(lane), fov(lane))
    }
    fun viewport(lane: Int, preset: Int = 0): MirrorViewport {
        val key = "$lane:$preset"
        val fallback = if (lane == legacy.mirrorRearLane && preset == 0) legacy.mirrorViewport else MirrorViewport()
        return MirrorViewport(prefs.getFloat("z:$key", fallback.zoom), prefs.getFloat("x:$key", fallback.centerX),
            prefs.getFloat("y:$key", fallback.centerY)).sanitized(legacy.fisheyeCorrection.cropZoom)
    }
    fun save(lane: Int, viewport: MirrorViewport, preset: Int = 0) {
        if (lane !in 1..4 || preset !in 0..2) return
        val value = viewport.sanitized(legacy.fisheyeCorrection.cropZoom)
        val key = "$lane:$preset"
        prefs.edit().putFloat("z:$key", value.zoom).putFloat("x:$key", value.centerX).putFloat("y:$key", value.centerY).apply()
    }
    fun hasPreset(lane: Int, preset: Int) = prefs.contains("z:$lane:$preset")
    fun wide(lane: Int, preset: Int = 0) = prefs.getBoolean("wide:$lane:$preset", false)
    fun setWide(lane: Int, value: Boolean, preset: Int = 0) { prefs.edit().putBoolean("wide:$lane:$preset", value).apply() }
    fun fov(lane: Int) = (legacy.fisheyeCorrection.targetFovDegrees + if (wide(lane)) 20f else 0f).coerceAtMost(140f)
}
