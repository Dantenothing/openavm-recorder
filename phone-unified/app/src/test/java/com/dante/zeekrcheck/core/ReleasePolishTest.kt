package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReleasePolishTest {
    private val now = Instant.parse("2026-09-20T09:00:00Z")
    private fun snapshot(fields: String) = ClimateSnapshot.parse(Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now,
        Json.parseToJsonElement("""{"additionalVehicleStatus":{"climateStatus":{$fields}}}""")))

    @Test fun heatingLevelTwoIsNotVentilationOffAndUnknownIsNotOff() {
        val s = snapshot(""""drvHeatSts":2,"passHeatingSts":3,"drvVentSts":2,"drvVentDetail":7""")
        assertEquals(2, s.seat(ClimateChannel.HEAT_LEFT)); assertEquals(3, s.seat(ClimateChannel.HEAT_RIGHT))
        assertEquals(0, s.seat(ClimateChannel.FRONT_LEFT)); assertNull(s.frontRight)
        assertNull(snapshot(""""drvHeatSts":5,"passHeatingSts":-1""").heatLeft)
        assertNull(snapshot("").heatRight)
        assertEquals(0, snapshot(""""drvHeatSts":7,"passHeatingSts":0""").heatLeft)
    }
    @Test fun heatingConfirmationStillRequiresRecentMatchingVehicleEvidence() {
        val t = ClimateTarget(ClimateChannel.HEAT_RIGHT, 2)
        val s = snapshot(""""passHeatingSts":2""")
        assertFalse(s.confirms(t, now, now))
        assertFalse(s.copy(sourceTime = now.minusSeconds(1)).confirms(t, now, now))
        assertFalse(s.copy(sourceTime = now.plusSeconds(31)).confirms(t, now, now))
        assertTrue(s.copy(sourceTime = now).confirms(t, now, now))
        assertFalse(s.copy(sourceTime = now).confirms(t.copy(value = 1), now, now))
    }
    @Test fun heatingBodyUsesOfficialSeatKeyAndDoesNotTouchOtherControls() {
        val body = Json.parseToJsonElement(ClimateTarget(ClimateChannel.HEAT_RIGHT, 3, 15).body())
        val params = (body.at("setting.serviceParameters") as JsonArray).associate { it.at("key").text() to it.at("value").text() }
        assertEquals(mapOf("SH.19" to "true", "SH.19.level" to "3", "SH.19.duration" to "15", "operation" to "4"), params)
    }
    @Test fun newSeatModeReplacesOnlyUnsentOppositeModeOnSameSeat() = runTest {
        val sent = mutableListOf<ClimateTarget>()
        val queue = ClimateQueue(this) { target, _ -> sent += target; ClimateResult.MATCHED }
        queue.submit(ClimateTarget(ClimateChannel.FRONT_LEFT, 3))
        queue.submit(ClimateTarget(ClimateChannel.FRONT_RIGHT, 1))
        queue.submit(ClimateTarget(ClimateChannel.HEAT_LEFT, 2))
        advanceUntilIdle()
        assertEquals(listOf(ClimateChannel.FRONT_RIGHT, ClimateChannel.HEAT_LEFT), sent.map { it.channel })
    }
    @Test fun switchingModesWhileSendingWaitsForPriorConfirmationWithoutReplay() = runTest {
        val gate = CompletableDeferred<ClimateResult>()
        val sent = mutableListOf<ClimateTarget>()
        val queue = ClimateQueue(this) { target, _ -> sent += target; if (sent.size == 1) gate.await() else ClimateResult.MATCHED }
        queue.submit(ClimateTarget(ClimateChannel.FRONT_LEFT, 3)); advanceTimeBy(601); runCurrent()
        queue.submit(ClimateTarget(ClimateChannel.HEAT_LEFT, 1)); queue.submit(ClimateTarget(ClimateChannel.HEAT_LEFT, 3))
        assertEquals(1, sent.size)
        gate.complete(ClimateResult.MATCHED); advanceUntilIdle()
        assertEquals(listOf(ClimateTarget(ClimateChannel.FRONT_LEFT, 3), ClimateTarget(ClimateChannel.HEAT_LEFT, 3)), sent)
    }
    @Test fun translatingStoredMessagesDoesNotChangeCanonicalControlTargetsOrProtocol() {
        val original = "读取失败 · 网络或服务不可达 · 未发送操作"
        val translated = PresentationStrings.render(original, false)
        assertEquals("Read failed · Network or service unavailable · No action sent", translated)
        assertEquals(original, PresentationStrings.render(original, true))
        val before = CardControl.target("lock", "已锁")
        PresentationStrings.render("已锁", false)
        assertEquals(before, CardControl.target("lock", "已锁"))
        assertNull(CardControl.target("lock", "Locked"))
        assertEquals("10 Example Road", PresentationStrings.render("10 Example Road", false))
        assertEquals("自取名字", PresentationStrings.render("自取名字", false))
    }
    @Test fun catalogueHasTranslationsForEveryCurrentChineseLiteralFragment() {
        val source = java.io.File("src/main/java/com/dante/zeekrcheck")
        assertTrue(source.isDirectory)
        val literal = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val han = Regex("[\\u3400-\\u9fff]+")
        val missing = source.walkTopDown().filter { it.extension == "kt" && it.name != "UiText.kt" }.flatMap { file ->
            file.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
                .filter { it.contains('"') }.flatMap { line -> han.findAll(line).map { match -> match.value }.toList() }.asSequence()
        }.toSet() - PresentationStrings.english.keys
        assertEquals("Untranslated source fragments", emptySet<String>(), missing)
    }
}
