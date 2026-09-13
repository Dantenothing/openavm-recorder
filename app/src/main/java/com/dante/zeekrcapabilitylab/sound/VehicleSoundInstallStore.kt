package com.dante.zeekrcapabilitylab.sound

import android.content.Context
import android.util.AtomicFile
import com.dante.zeekrbridge.sound.SoundPurpose
import com.dante.zeekrbridge.sound.WavPcmValidator
import com.dante.zeekrcapabilitylab.transfer.*
import com.dante.zeekrcapabilitylab.usbexport.*
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Foreground user-initiated local installs, independent of phone pairing and networking. */
internal class VehicleSoundInstallStore(private val context: Context) {
    private val root = File(context.filesDir, "sound-maker-install")
    private val record = AtomicFile(File(root, "last.json"))
    private val payload = File(root, "pending.wav")

    fun pending(): SoundRelayTask? = synchronized(LOCK) {
        read()?.takeIf { it.state !in SoundOfferStates.terminal }
    }

    fun install(source: File, rawName: String, purpose: SoundPurpose, storageUuid: String): SoundRelayTask = synchronized(LOCK) {
        check(pending() == null) { "PENDING_INSTALL" }
        val target = target(storageUuid)
        UsbMutationCoordinator.withTarget(target.storageUuid) {
            val usbRoot = File(requireNotNull(target.directoryPath)).canonicalFile
            val existing = SoundTransferProtocol.TARGET_DIRECTORIES.flatMap { name ->
                val directory = File(usbRoot, name).canonicalFile
                require(directory.parentFile == usbRoot) { "PATH_ESCAPE" }
                directory.listFiles().orEmpty().map { it.name }
            }
            val fileName = VehicleSoundNames.available(rawName, purpose, existing)
            val validation = WavPcmValidator.validateWavFile(source)
            require(validation.valid && source.length() < SoundTransferProtocol.MAX_WAV_BYTES) { "INVALID_WAV" }
            root.mkdirs()
            val hash = PhoneSoundRelay.sha256(source)
            PhoneSoundRelay.copyVerified(source, payload, hash)
            val task = SoundRelayTask(
                offer = SoundOfferMetadata(offerId = UUID.randomUUID().toString(), targetCarDeviceId = "local-editor",
                    fileName = fileName, sizeBytes = source.length(), sha256 = hash,
                    wav = PhoneSoundRelay.parseWav(source), purpose = purpose.name, createdAt = System.currentTimeMillis()),
                state = SoundOfferStates.INSTALLING, boundStorageUuid = target.storageUuid,
                targetDescription = target.description, cachedFile = payload.absolutePath,
            )
            require(SoundTransferValidation.validateOffer(task.offer) == null)
            persist(task) // Keep the source and operation ID before either USB folder changes.
            execute(task, target)
        }
    }

    fun retry(): SoundRelayTask = synchronized(LOCK) {
        val task = requireNotNull(pending()) { "NO_PENDING_INSTALL" }
        execute(task, target(requireNotNull(task.boundStorageUuid)))
    }

    /** The UI explicitly says this keeps any already-written USB files. */
    fun discardPending() = synchronized(LOCK) {
        pending()?.let { persist(it.copy(state = SoundOfferStates.CANCELLED, updatedAt = System.currentTimeMillis())) }
        payload.delete()
    }

    private fun target(uuid: String): UsbExportTarget = UsbExportVolumeResolver.mountedTargets(context)
        .singleOrNull { it.storageUuid.equals(uuid, true) && it.directoryPath != null }
        ?: error("ORIGINAL_USB_NOT_MOUNTED")

    private fun execute(task: SoundRelayTask, target: UsbExportTarget): SoundRelayTask {
        var current = task.copy(state = SoundOfferStates.INSTALLING, errorCode = null)
        persist(current)
        val result = try {
            ZeekrSoundInstaller.install(current, target, payload, allowReplace = false) { evidence ->
                current = current.copy(directories = evidence, updatedAt = System.currentTimeMillis())
                persist(current)
            }
        } catch (error: Exception) {
            val failed = current.copy(state = SoundOfferStates.FAILED_RECOVERABLE,
                errorCode = "LOCAL_INSTALL_FAILED", message = error.message, updatedAt = System.currentTimeMillis())
            persist(failed)
            return failed
        }
        current = current.copy(state = if (result.ok) SoundOfferStates.COMPLETED else SoundOfferStates.FAILED_RECOVERABLE,
            directories = result.directories, errorCode = result.errorCode, message = result.message, updatedAt = System.currentTimeMillis())
        persist(current)
        if (result.ok) payload.delete()
        return current
    }

    private fun read(): SoundRelayTask? = runCatching {
        record.openRead().use {
            require(it.channel.size() in 1..128 * 1024)
            JSON.decodeFromString<SoundRelayTask>(it.bufferedReader().readText())
        }
    }.getOrNull()

    private fun persist(task: SoundRelayTask) {
        root.mkdirs()
        val output = record.startWrite()
        try { output.write(JSON.encodeToString(task).toByteArray()); record.finishWrite(output) }
        catch (t: Throwable) { record.failWrite(output); throw t }
    }

    companion object {
        private val LOCK = Any()
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
