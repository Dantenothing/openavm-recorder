package com.dante.zeekrcheck

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dante.zeekrcheck.core.ParkingEvidence
import kotlinx.coroutines.delay

@Composable internal fun ParkingStatusCard(evidence: ParkingEvidence?, now: Long) {
    AssistantCard {
        UiText("车辆驻车与行驶", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        UiText(evidence?.label(now) ?: "等待读取原厂驻车状态", fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("parking_state"))
        UiText(evidence?.speedLabel() ?: "车速未返回", fontSize = 14.sp, modifier = Modifier.testTag("parking_speed"))
        UiText(evidence?.timeLabel() ?: "车况时间未提供", fontSize = 12.sp, color = AssistantMuted,
            modifier = Modifier.testTag("parking_source"))
        UiText(if (evidence?.recent(now) == true) "近期车况 · 自动守护还会检查位置、车锁和本次暂停"
            else "车况时间未知或超过 3 分钟 · 暂不自动操作哨兵", fontSize = 12.sp, color = AssistantMuted,
            modifier = Modifier.testTag("parking_freshness"))
        if (evidence?.aggregateMotionTime == true)
            UiText("按原厂整体车况时间核对；不代表每项数据都单独在此时采样。", fontSize = 11.sp, color = AssistantMuted)
    }
}

/** Refresh only visible age labels; no network requests and no frozen 'recent' state on an open page. */
@Composable internal fun parkingClock(fetchedAt: Long?): Long {
    val now by produceState(System.currentTimeMillis(), fetchedAt) {
        while (true) { value = System.currentTimeMillis(); delay(5_000) }
    }
    return now
}
