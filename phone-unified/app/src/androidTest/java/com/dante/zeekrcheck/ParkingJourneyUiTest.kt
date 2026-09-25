package com.dante.zeekrcheck

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.dante.zeekrcheck.core.ParkingJourney
import org.junit.Rule
import org.junit.Test

class ParkingJourneyUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun waitingEvidenceDoesNotClaimNewTrip() {
        compose.setContent { AssistantTheme { Column { ParkingJourneyStatus(ParkingJourney()) } } }
        compose.onNodeWithTag("parking_journey_status").assertTextEquals("等待有效停车里程，补充识别漏读的行程")
    }
    @Test fun confirmedTripAndManualChoiceHaveDistinctFeedback() {
        compose.setContent { AssistantTheme { Column { ParkingJourneyStatus(ParkingJourney().manualChoice(1)) } } }
        compose.onNodeWithTag("parking_journey_status").assertTextContains("本次手动选择已保留", substring = true)
    }
}
