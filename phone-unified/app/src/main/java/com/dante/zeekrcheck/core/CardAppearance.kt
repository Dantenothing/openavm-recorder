package com.dante.zeekrcheck.core

import java.time.Instant

enum class CardTone(val background: Long, val foreground: Long) {
    NEUTRAL(0xFFFFFFFF,0xFF203532),
    ACTIVE(0xFF275C50,0xFFFFFFFF),
    ATTENTION(0xFFFFF0D7,0xFF82531E),
}

/** Color describes an observed state, never a tap or an accepted command. */
object CardAppearance {
    fun tone(action:String,overview:VehicleOverview,now:Instant,preparation:PreparationSession?=null):CardTone {
        if(action=="prepare" && preparation?.let { PreparationProgress.idle(it,now.toEpochMilli()) }==true) return CardTone.NEUTRAL
        if(action=="prepare" && preparation?.let { PreparationProgress.phase(it,now.toEpochMilli())==PreparationPhase.DEGRADED }==true) return CardTone.ATTENTION
        if(action=="prepare") return if(overview.acOn==true && ThermalPresentation.from(overview,preparation,now).airflow!=Airflow.NONE)
            CardTone.ACTIVE else CardTone.NEUTRAL
        val observed=overview.readings[CardControl.field(action)]?.takeIf { it.readable }?.value
        return when {
            action=="guard" && observed=="开启" -> CardTone.ACTIVE
            action=="lock" && observed=="未锁" -> CardTone.ATTENTION
            action in setOf("trunk","port") && observed=="打开" -> CardTone.ATTENTION
            else -> CardTone.NEUTRAL
        }
    }
}
