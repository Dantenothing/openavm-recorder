package com.dante.zeekrcapabilitylab.sound

import com.dante.zeekrcapabilitylab.transfer.*
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrbridge.sound.*
import io.github.dantenothing.avmtransfer.protocol.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class VehicleSoundInstallTest {
    @get:Rule val temp = TemporaryFolder()
    private fun wav(name: String, sample: Float = .2f): File = temp.newFile(name).also { file ->
        WavPcmWriter.writeWavFile(file, 1, 48000) { it.writeFrames(FloatArray(4800) { sample }, 4800) }
    }
    private fun target(root: File) = UsbExportTarget("test-volume", "test-usb", "Test USB", root.absolutePath, "content://test")
    private fun task(file: File, name: String = "new-lock.wav") = SoundRelayTask(
        offer = SoundOfferMetadata(offerId = UUID.randomUUID().toString(), targetCarDeviceId = "local-editor",
            fileName = name, sizeBytes = file.length(), sha256 = PhoneSoundRelay.sha256(file),
            wav = PhoneSoundRelay.parseWav(file), purpose = "LOCK", createdAt = 1),
    )
    private fun dirs(root: File) = SoundTransferProtocol.TARGET_DIRECTORIES.map { File(root, it).apply { mkdirs() } }

    @Test fun installsVerifiedCopiesWithoutAnyPhonePairing() {
        val root = temp.newFolder("usb")
        val payload = wav("input.wav")
        val task = task(payload)
        val result = ZeekrSoundInstaller.install(task, target(root), payload, allowReplace = false) { }
        assertTrue(result.message, result.ok)
        assertEquals(2, result.directories.count { it.finalVerified })
        dirs(root).forEach { assertEquals(task.offer.sha256, PhoneSoundRelay.sha256(File(it, task.offer.fileName))) }
    }
    @Test fun localInstallNeverOverwritesADifferentExistingSound() {
        val root = temp.newFolder("usb")
        val folders = dirs(root)
        val old = wav("old.wav", .7f)
        folders.forEach { old.copyTo(File(it, "new-lock.wav")) }
        val input = wav("input.wav")
        val result = ZeekrSoundInstaller.install(task(input), target(root), input, allowReplace = false) { }
        assertFalse(result.ok)
        folders.forEach { assertEquals(PhoneSoundRelay.sha256(old), PhoneSoundRelay.sha256(File(it, "new-lock.wav"))) }
    }
    @Test fun directoryLimitIsCheckedBeforeEitherFolderChanges() {
        val root = temp.newFolder("usb")
        val folders = dirs(root)
        repeat(5) { File(folders[1], "$it.wav").writeText("existing") }
        val input = wav("input.wav")
        val result = ZeekrSoundInstaller.install(task(input), target(root), input, allowReplace = false) { }
        assertFalse(result.ok)
        assertTrue(folders[0].listFiles()!!.isEmpty())
        assertEquals(5, folders[1].listFiles()!!.size)
    }
    @Test fun failureDuringSecondFolderRestoresBothOriginalFiles() {
        val root = temp.newFolder("usb")
        val folders = dirs(root)
        val old = wav("old.wav", .7f)
        folders.forEach { old.copyTo(File(it, "new-lock.wav")) }
        val input = wav("input.wav")
        var injected = false
        val result = ZeekrSoundInstaller.install(task(input), target(root), input) { states ->
            if (!injected && states[0].finalVerified && states[1].finalWriteStarted) {
                injected = true; error("Simulated unplug during second commit")
            }
        }
        assertTrue(injected); assertFalse(result.ok)
        folders.forEach { assertEquals(PhoneSoundRelay.sha256(old), PhoneSoundRelay.sha256(File(it, "new-lock.wav"))) }
    }
    @Test fun interruptedNewCopyIsRecoveredUsingTheSameOperation() {
        val root = temp.newFolder("usb")
        val folders = dirs(root)
        val input = wav("input.wav")
        var task = task(input)
        input.copyTo(File(folders[0], task.offer.fileName))
        task = task.copy(directories = folders.mapIndexed { i, folder -> SoundDirectoryObservation(folder.name,
            File(folder, task.offer.fileName).absolutePath, existedBefore = false, finalWriteStarted = i == 0) })
        val result = ZeekrSoundInstaller.install(task, target(root), input, allowReplace = false) { }
        assertTrue(result.message, result.ok)
        folders.forEach { assertEquals(task.offer.sha256, PhoneSoundRelay.sha256(File(it, task.offer.fileName))) }
    }
    @Test fun changedPayloadCannotWriteAnyUsbFiles() {
        val root = temp.newFolder("usb")
        val input = wav("input.wav")
        val task = task(input)
        input.appendText("corruption")
        val result = ZeekrSoundInstaller.install(task, target(root), input, allowReplace = false) { }
        assertFalse(result.ok); assertTrue(root.listFiles()!!.isEmpty())
    }
}
