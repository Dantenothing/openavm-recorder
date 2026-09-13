package com.dante.zeekrbridge.player

import org.junit.Assert.*
import org.junit.Test

class InlinePlaybackStateTest {
    @Test fun openingAClipAutoplaysWhenVisible() {
        assertTrue(InlinePlaybackIntent().shouldPlay)
        assertFalse(InlinePlaybackIntent(foreground = false).shouldPlay)
    }

    @Test fun exportAndBackgroundPauseTemporarilyWithoutLosingIntent() {
        val opened = InlinePlaybackIntent()
        val exporting = opened.copy(obstructed = true)
        val background = exporting.copy(foreground = false)
        assertFalse(exporting.shouldPlay)
        assertFalse(background.copy(obstructed = false).shouldPlay)
        assertTrue(background.copy(obstructed = false, foreground = true).shouldPlay)
    }

    @Test fun returningFromAnotherWindowDoesNotUndoManualPauseOrReplayAnEndedClip() {
        val paused = InlinePlaybackIntent(wantsToPlay = false)
        assertFalse(paused.copy(obstructed = true).copy(obstructed = false).shouldPlay)
        assertFalse(paused.copy(foreground = false).copy(foreground = true).shouldPlay)
    }

    @Test fun aSkippedMissingFileCannotShiftTheSelectedSegment() {
        assertEquals(1, playableIndexForId(listOf("first", "third"), "third"))
        assertNull(playableIndexForId(listOf("first", "third"), "missing-second"))
        assertNull(playableIndexForId(emptyList(), "first"))
    }

    @Test fun squareAndLandscapeFramesUseTheirOwnHeight() {
        assertEquals(VideoFrameSize(360f, 360f), fitVideoFrame(360f, 420f, 1f))
        assertEquals(VideoFrameSize(360f, 202.5f), fitVideoFrame(360f, 420f, 16f / 9f))
    }

    @Test fun portraitAndRotatedLayoutsFitWithoutStretching() {
        val portrait = fitVideoFrame(360f, 400f, 9f / 16f)
        assertEquals(VideoFrameSize(225f, 400f), portrait)
        assertEquals(VideoFrameSize(160f, 160f), fitVideoFrame(720f, 160f, 1f))
        assertEquals(VideoFrameSize(300f, 300f), fitVideoFrame(300f, 400f, Float.NaN))
        assertEquals(VideoFrameSize(0f, 0f), fitVideoFrame(0f, 400f, 1f))
    }
}
