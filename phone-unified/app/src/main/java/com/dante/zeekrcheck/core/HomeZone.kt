package com.dante.zeekrcheck.core

/** One distance policy for the UI, widgets and away guard. Never upgrades stale telemetry. */
object HomeZone {
    const val EDGE_BUFFER_METRES = 100
    enum class Area { HOME, NEAR_HOME, AWAY, UNKNOWN }
    fun area(point: CarLocation?, home: CarLocation?, radius: Int): Area {
        if (point == null || home?.verified != true) return Area.UNKNOWN
        val distance = point.distance(home)
        return when {
            distance <= radius -> Area.HOME
            distance <= radius + EDGE_BUFFER_METRES -> Area.NEAR_HOME
            else -> Area.AWAY
        }
    }
    fun permitsAwayCheck(point: CarLocation?, home: CarLocation?, radius: Int, now: Long) =
        point?.fresh(now) == true && area(point, home, radius) == Area.AWAY

    fun label(point: CarLocation?, home: CarLocation?, radius: Int, now: Long): String {
        if (point == null) return "车辆位置待读取"
        val place = when (area(point, home, radius)) {
            Area.HOME -> "家"
            Area.NEAR_HOME -> "家附近 · 按在家处理"
            else -> point.address.ifBlank { "道路名称待解析 · 点开地图" }
        }
        return place + if (!point.fresh(now)) " · 上次位置" else ""
    }
}
