package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeekrSoundPresetTest {
    @Test
    fun auNzPresetUsesBothDocumentedDirectories() {
        assertEquals(
            listOf("Lock Status Tones", "解闭锁音效"),
            ZeekrSoundPreset.ZEEKR_7X_AUNZ.targetDirectoryNames,
        )
        assertEquals(5, ZeekrSoundPreset.ZEEKR_7X_AUNZ.maxWavFiles)
    }

    @Test
    fun zeekrSizeLimitIsStrictlyUnderOneMillionBytes() {
        val preset = ZeekrSoundPreset.ZEEKR_7X_AUNZ
        assertTrue(preset.acceptsSize(999_999L))
        assertFalse(preset.acceptsSize(1_000_000L))
    }

    @Test
    fun genericWavDoesNotImposeZeekrFolderOrSizeRules() {
        val preset = ZeekrSoundPreset.GENERIC_WAV
        assertTrue(preset.targetDirectoryNames.isEmpty())
        assertNull(preset.maxWavFiles)
        assertTrue(preset.acceptsSize(Long.MAX_VALUE))
    }

    @Test
    fun selectedPresetFolderIsUsedDirectly() {
        val result = SafSoundTargetResolver.resolve(
            targetDirectoryName = "Lock Status Tones",
            selectedDisplayName = "Lock Status Tones",
            selectedDocumentId = "1234-5678:Lock Status Tones",
        )

        assertEquals(SafSoundTargetAction.USE_SELECTED_TREE, result.action)
    }

    @Test
    fun selectedVolumeRootUsesExactlyOnePresetChild() {
        val result = SafSoundTargetResolver.resolve(
            targetDirectoryName = "Lock Status Tones",
            selectedDisplayName = "USB drive",
            selectedDocumentId = "1234-5678:",
        )

        assertEquals(SafSoundTargetAction.USE_OR_CREATE_TARGET_CHILD, result.action)
    }

    @Test
    fun volumeRootLabelMatchingPresetStillCreatesTheRequiredChild() {
        val result = SafSoundTargetResolver.resolve(
            targetDirectoryName = "Lock Status Tones",
            selectedDisplayName = "Lock Status Tones",
            selectedDocumentId = "1234-5678:",
        )

        assertEquals(SafSoundTargetAction.USE_OR_CREATE_TARGET_CHILD, result.action)
    }

    @Test
    fun unrelatedFolderIsRejectedInsteadOfCreatingNestedPresetFolder() {
        val result = SafSoundTargetResolver.resolve(
            targetDirectoryName = "Lock Status Tones",
            selectedDisplayName = "Music",
            selectedDocumentId = "1234-5678:Music",
        )

        assertEquals(SafSoundTargetAction.REJECT_AMBIGUOUS, result.action)
    }

    @Test
    fun bilingualModeAcceptsOnlyTheVolumeRoot() {
        assertEquals(
            SafSoundTargetAction.USE_SELECTED_TREE,
            SafSoundTargetResolver.resolveVolumeRoot("USB drive", "1234-5678:").action,
        )
        assertEquals(
            SafSoundTargetAction.REJECT_AMBIGUOUS,
            SafSoundTargetResolver.resolveVolumeRoot("Music", "1234-5678:Music").action,
        )
    }

    @Test
    fun genericWavAlwaysUsesTheSelectedTree() {
        val result = SafSoundTargetResolver.resolve(
            targetDirectoryName = null,
            selectedDisplayName = "Music",
            selectedDocumentId = "1234-5678:Music",
        )

        assertEquals(SafSoundTargetAction.USE_SELECTED_TREE, result.action)
    }

    @Test
    fun documentIdCanIdentifySelectedPresetFolderWhenDisplayNameIsUnavailable() {
        val result = SafSoundTargetResolver.resolve(
            targetDirectoryName = "解闭锁音效",
            selectedDisplayName = null,
            selectedDocumentId = "1234-5678:custom/解闭锁音效",
        )

        assertEquals(SafSoundTargetAction.USE_SELECTED_TREE, result.action)
    }

    @Test
    fun continueCurrentAudioReturnsFromDoneToReady() {
        assertEquals(
            SoundPhase.Ready,
            SoundEditorFlow.afterCompletion(SoundCompletionAction.CONTINUE_CURRENT, hasImportedAudio = true),
        )
    }

    @Test
    fun makeAnotherSoundReturnsToImportStep() {
        assertEquals(
            SoundPhase.Idle,
            SoundEditorFlow.afterCompletion(SoundCompletionAction.START_ANOTHER, hasImportedAudio = true),
        )
    }

    @Test
    fun missingImportedAudioCannotReturnToEditor() {
        assertEquals(
            SoundPhase.Idle,
            SoundEditorFlow.afterCompletion(SoundCompletionAction.CONTINUE_CURRENT, hasImportedAudio = false),
        )
    }
}
