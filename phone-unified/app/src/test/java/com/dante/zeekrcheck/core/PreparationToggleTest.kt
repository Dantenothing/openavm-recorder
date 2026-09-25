package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PreparationToggleTest {
    private val now = 1_790_000_000_000L
    private fun session() = PreparationSession("synthetic", now - 30_000, now + 900_000,
        ComfortPreferences(), listOf(ClimateChannel.AC), phase = PreparationPhase.RUNNING,
        lastSource = now)

    @Test fun runningButtonExplainsThatTheSameButtonStopsPreparation() {
        for (phase in listOf(PreparationPhase.ACCEPTED, PreparationPhase.RUNNING, PreparationPhase.COOLING,
            PreparationPhase.WARMING, PreparationPhase.READY)) {
            assertTrue(phase.name, PreparationProgress.button(session().copy(phase = phase), now).contains("再点停止"))
        }
    }

    @Test fun repeatedTapStopsTheDisplayedSessionAndNeverRestartsAnEndedOne() {
        val start = PreparationControl.shown(null, "synthetic", now)
        assertEquals(PreparationTap.START, PreparationControl.decide(start, null, "synthetic", now))
        val running = session()
        assertEquals(PreparationTap.STALE, PreparationControl.decide(start, running, "synthetic", now))
        val stop = PreparationControl.shown(running, "synthetic", now)
        assertEquals(PreparationTap.STOP, PreparationControl.decide(stop, running, "synthetic", now))
        val stopping = PreparationControl.requestStop(running, now)
        assertEquals(PreparationPhase.STOPPING, PreparationProgress.phase(stopping, now))
        assertEquals(PreparationTap.OBSERVE, PreparationControl.decide(stop, stopping, "synthetic", now))
        val ended = running.copy(finished = true, phase = PreparationPhase.HANDED_OVER)
        assertEquals(PreparationTap.STALE, PreparationControl.decide(stop, ended, "synthetic", now))
        assertEquals(PreparationTap.STALE, PreparationControl.decide(stop, running.copy(createdAt = now + 1), "synthetic", now))
        assertEquals(PreparationTap.STALE, PreparationControl.decide(stop, running, "another-vehicle", now))
        assertEquals(PreparationTap.START, PreparationControl.decide(PreparationControl.shown(ended, "synthetic", now), ended, "synthetic", now))
    }

    @Test fun readingTapCanCancelWithoutAWriteAndSessionIdentitySurvivesTheSendingTransition() {
        val reading = session().copy(phase = PreparationPhase.READING)
        val shown = PreparationControl.shown(reading, "synthetic", now)
        val cancelled = PreparationControl.requestStop(reading, now)
        assertTrue(cancelled.finished); assertFalse(cancelled.stopRequested)
        assertEquals("一键备车", PreparationControl.label(cancelled, now))
        val sending = reading.copy(started = now, phase = PreparationPhase.SENDING)
        assertEquals(PreparationTap.STOP, PreparationControl.decide(shown, sending, "synthetic", now))
        assertEquals(sending.createdAt, PreparationSession.parse(sending.json())!!.createdAt)
    }

    @Test fun stopDuringSendingKeepsItsIntentAndRequiresPostStopOffEvidence() {
        val queued = PreparationControl.requestStop(session().copy(phase = PreparationPhase.SENDING), now)
        assertTrue(queued.stopRequested); assertFalse(queued.finished)
        assertEquals("正在结束…", PreparationControl.label(queued, now))
        assertEquals(PreparationTap.OBSERVE, PreparationControl.decide(
            PreparationControl.shown(queued, "synthetic", now), queued, "synthetic", now))
        fun off(at: Long) = ClimateSnapshot(acOn = false, sourceTime = Instant.ofEpochMilli(at), fetchedAt = Instant.ofEpochMilli(at))
        val accepted = queued.copy(phase = PreparationPhase.STOPPING)
        assertFalse(PreparationProgress.observe(accepted, off(now + 1_000), now + 1_000).finished)
        val sent = accepted.copy(stopSentAt = now + 2_000)
        val beforeStop = PreparationProgress.observe(sent, off(now + 1_000), now + 3_000)
        assertFalse(beforeStop.finished)
        val confirmed = PreparationProgress.observe(beforeStop, off(now + 3_000), now + 3_000)
        assertTrue(confirmed.finished); assertEquals(PreparationPhase.STOPPED, confirmed.phase)
        assertEquals("一键备车", PreparationControl.label(confirmed, now + 3_000))
    }

    @Test fun stopTurnsOffOnlyThisPreparationsChannelsAndDoesNotChangeOtherPreferences() {
        val running = session().copy(channels = listOf(ClimateChannel.AC, ClimateChannel.FRONT_RIGHT))
        val stop = PreparationControl.stopCommand(running)
        assertEquals(listOf(ClimateChannel.AC, ClimateChannel.FRONT_RIGHT), stop.targets.map { it.channel })
        assertTrue(stop.targets.all { it.value == 0 })
        assertEquals(running.preferences, PreparationControl.requestStop(running, now).preferences)
        assertFalse(stop.body().contains("SH.11")); assertFalse(stop.body().contains("SH.19"))
    }

    @Test fun processDeathAndUnknownResultsNeverTurnTheButtonIntoARetryOrReplay() {
        val queued = PreparationControl.requestStop(session(), now)
        val recovered = PreparationProgress.interrupted(PreparationSession.parse(queued.json())!!)
        assertEquals(PreparationPhase.UNKNOWN, recovered.phase)
        assertEquals(PreparationTap.OBSERVE, PreparationControl.decide(
            PreparationControl.shown(recovered, "synthetic", now), recovered, "synthetic", now))
        assertEquals(PreparationTap.STALE, PreparationControl.decide(null, session(), "synthetic", now))
        val sent = queued.copy(phase = PreparationPhase.STOPPING, stopSentAt = now)
        assertEquals(sent, PreparationProgress.interrupted(PreparationSession.parse(sent.json())!!))
    }

    @Test fun explicitAcknowledgementReleasesAnUnknownTaskWithoutDismissingAnActiveStop() {
        val unknown=session().copy(phase=PreparationPhase.UNKNOWN,finished=true,stopRequested=true)
        assertTrue(PreparationControl.needsCheck(unknown))
        val staleStop="stop:${unknown.createdAt}"
        val cleared=PreparationControl.acknowledge(unknown)
        assertNull(cleared)
        assertEquals(PreparationTap.STALE,PreparationControl.decide(staleStop,cleared,"synthetic",now))
        assertEquals(PreparationTap.START,PreparationControl.decide(PreparationControl.shown(cleared,"synthetic",now),cleared,"synthetic",now))
        val stopping=PreparationControl.requestStop(session(),now)
        assertFalse(PreparationControl.needsCheck(stopping))
        assertEquals(stopping,PreparationControl.acknowledge(stopping))
    }
}
