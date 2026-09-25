package com.dante.zeekrcheck

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in local inspection. Reads only, and never exports account, token, VIN, or coordinates. */
class UnifiedSavedStateDiagnosticTest {
    @Test fun exportRestoredSettingsSummary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectSavedState")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val state=AssistantStore.get(context).state.value
        val overview=OverviewStore.get(context).state.value
        val summary=JSONObject().put("savedSessionReadable",SecureSessionStore(context).load()!=null)
            .put("protocolReadable",SecureConfigStore(context).load()!=null)
            .put("homeConfigured",state.home!=null).put("homeRadiusMeters",state.homeRadius)
            .put("awayGuardEnabled",state.guardEnabled).put("automationPaused",state.paused)
            .put("homeGuardEnabled",state.homeGuardEnabled).put("homeManualHold",state.homeGuard.held)
            .put("homeCloseAttemptAt",state.homeGuard.attemptedAt).put("homeFirstConfirmationAt",state.homeGuard.firstFetch)
            .put("homeLastConfirmationAt",state.homeGuard.latestFetch).put("homeFollowUps",state.homeGuard.followUps)
            .put("sentryState",OverviewStore.get(context).state.value.readings["sentry"]?.value)
            .put("latestAutoHomeResult",state.history.firstOrNull { it.title == "到家自动关闭哨兵" }?.result)
            .put("parkingMileageBaselinePresent",state.parkingGuard.journey.baseline != null)
            .put("parkingJourneyPending",state.parkingGuard.journey.pending(System.currentTimeMillis()))
            .put("parkingJourneyConfirmedAt",state.parkingGuard.journey.confirmedAt)
            .put("parkingJourneyMessage",state.parkingGuard.journey.message)
            .put("parkingPaused",state.parkingGuard.paused).put("parkingPauseOrigin",state.parkingGuard.pauseOrigin.name)
            .put("widgetSyncEnabled",state.widgetSyncEnabled).put("departurePlans",state.plans.size)
            .put("separateTemperatureRefresh",true)
            .put("temperatureTask",state.temperatureUpdate?.let { s -> JSONObject()
                .put("combined",s.combined).put("phase",s.phase.name).put("message",s.message)
                .put("ownsAc",s.ownsAc).put("startSent",s.startSent).put("stopSent",s.stopSent)
                .put("source",s.source).put("temperature",s.temperature).put("finishedAt",s.finishedAt)
            })
            .put("preparationFinished",state.activePreparation?.finished)
            .put("preparation",state.activePreparation?.let { s -> JSONObject()
                .put("phase",s.phase.name).put("finished",s.finished).put("status",s.status)
                .put("started",s.started).put("deadline",s.deadline).put("endedAt",s.endedAt)
                .put("target",s.preferences.target).put("minutes",s.preferences.minutes)
                .put("initialTemperature",s.initialTemperature).put("lastTemperature",s.lastTemperature)
                .put("lastSource",s.lastSource).put("remoteRunningObserved",s.remoteRunningObserved)
                .put("airReady",s.thermal?.airReady)
            })
            .put("lastAutomaticGuardAttemptAt",state.parkingGuard.attemptedAt)
            .put("guardMessage",state.guardMessage)
            .put("operationPending",state.operationPending).put("operationMessage",state.operationMessage)
            .put("pendingBodyAction",state.pendingBodyAction?.name)
            .put("recentControlHistory",JSONArray().also { entries ->
                state.history.filter { it.title in setOf("找车鸣笛一次", "到家自动关闭哨兵", "离家自动开启哨兵", "解锁尾门", "锁定尾门") }
                    .take(12).forEach { entries.put(JSONObject().put("at",it.at).put("title",it.title).put("result",it.result)) }
            })
            .put("telemetry",JSONObject().also { readings ->
                listOf("parking_state","vehicle_speed","cabin_temperature","lock","sentry","trunk").forEach { key ->
                    overview.readings[key]?.let { reading -> readings.put(key, JSONObject().put("value",reading.value)
                        .put("source",reading.source).put("fetched",reading.fetched).put("readable",reading.readable)) }
                }
            })
            .put("locationVerified",state.location?.verified==true)
            .put("distanceFromHomeMeters",state.location?.let { location -> state.home?.let { home -> location.distance(home).toInt() } })
        File(context.filesDir,"unified-saved-state-diagnostic.json").writeText(summary.toString())
    }
}
