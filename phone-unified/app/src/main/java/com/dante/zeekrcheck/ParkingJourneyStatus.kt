package com.dante.zeekrcheck

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dante.zeekrcheck.core.ParkingJourney

@Composable internal fun ParkingJourneyStatus(journey: ParkingJourney) {
    UiText("本次停车识别", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    UiText(journey.message, color = AssistantMuted, fontSize = 12.sp, modifier = Modifier.testTag("parking_journey_status"))
    UiText("漏掉行驶过程时，总里程至少增长 1 km，且间隔至少 1 分钟的两次查询均确认已驻车，才重新判断守护。只看到定位变化、解锁或哨兵关闭不会清除你的选择。", fontSize = 11.sp, color = AssistantMuted)
}
