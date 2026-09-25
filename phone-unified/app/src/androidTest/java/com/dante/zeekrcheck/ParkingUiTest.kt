package com.dante.zeekrcheck

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.dante.zeekrcheck.core.*
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Synthetic rendering only, with no vehicle client or live settings changes. */
class ParkingUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-09-20T06:00:00Z").toEpochMilli()
    @Test fun parkedAndSpeedStayVisibleButAgeExpiryRemovesRecentClaim() {
        val probe = Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(now), Json.parseToJsonElement("""{
            "updateTime":$now,"basicVehicleStatus":{"speed":"0","usageMode":"2"},
            "additionalVehicleStatus":{"drivingSafetyStatus":{"electricParkBrakeStatus":"1","centralLockingStatus":"1"}}}
        """))
        val clock = mutableLongStateOf(now)
        compose.setContent { AssistantTheme { ParkingStatusCard(ParkingEvidence.from(probe), clock.longValue) } }
        compose.onNodeWithTag("parking_state").assertTextEquals("已驻车 · Parked")
        compose.onNodeWithTag("parking_speed").assertTextEquals("车速 0 km/h")
        compose.onNodeWithTag("parking_source").assertTextContains("整体车况", substring = true)
        compose.runOnIdle { clock.longValue += 180_001 }
        compose.onNodeWithTag("parking_state").assertTextEquals("上次记录：已驻车 · Parked")
        compose.onNodeWithTag("parking_freshness").assertTextContains("暂不自动操作", substring = true)
    }
    @Test fun missingReadDoesNotInventParkedOrZeroSpeed() {
        compose.setContent { AssistantTheme { ParkingStatusCard(null, now) } }
        compose.onNodeWithTag("parking_state").assertTextEquals("等待读取原厂驻车状态")
        compose.onNodeWithTag("parking_speed").assertTextEquals("车速未返回")
        compose.onNodeWithTag("parking_source").assertTextEquals("车况时间未提供")
    }
}
