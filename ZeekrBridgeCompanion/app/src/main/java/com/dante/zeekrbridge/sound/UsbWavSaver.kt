package com.dante.zeekrbridge.sound

import android.content.Context
import android.net.Uri
import com.dante.zeekrbridge.ui.PhoneLanguage
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class UsbSaveResult(
    val ok: Boolean,
    val finalName: String? = null,
    val backupName: String? = null,
    val message: String,
    val errorCode: String? = null,
)

data class UsbEntryInfo(
    val documentId: String,
    val name: String,
    val sizeBytes: Long?,
    val flags: Int,
    val mimeType: String? = null,
    val lastModifiedMs: Long? = null,
)

/** Minimal SAF tree listing/querying used by the sound USB writer. */
object UsbSaf {
    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun rootDocumentUri(treeUri: Uri): Uri? =
        try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        } catch (t: Throwable) {
            null
        }

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun listChildren(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String? = null,
    ): List<UsbEntryInfo>? =
        try {
            val root = rootDocumentUri(treeUri) ?: return null
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                parentDocumentId ?: DocumentsContract.getDocumentId(root),
            )
            val entries = mutableListOf<UsbEntryInfo>()
            val cursor = context.contentResolver.query(children, PROJECTION, null, null, null)
                ?: return null
            cursor.use {
                val idIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val flagsIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                val modifiedIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (it.moveToNext()) {
                    val documentId = if (idIdx >= 0 && !it.isNull(idIdx)) it.getString(idIdx) else null
                    if (documentId == null) continue
                    val name = if (nameIdx >= 0 && !it.isNull(nameIdx)) it.getString(nameIdx) else documentId
                    val size = if (sizeIdx >= 0 && !it.isNull(sizeIdx)) it.getLong(sizeIdx) else null
                    val flags = if (flagsIdx >= 0 && !it.isNull(flagsIdx)) it.getInt(flagsIdx) else 0
                    val mime = if (mimeIdx >= 0 && !it.isNull(mimeIdx)) it.getString(mimeIdx) else null
                    val modified = if (modifiedIdx >= 0 && !it.isNull(modifiedIdx)) it.getLong(modifiedIdx) else null
                    entries += UsbEntryInfo(documentId, name, size, flags, mime, modified)
                }
            }
            entries
        } catch (t: Throwable) {
            null
        }

    fun ensureDirectory(context: Context, treeUri: Uri, name: String): Uri? {
        val cleanName = name.trim().trim('/').takeIf { it.isNotEmpty() } ?: return rootDocumentUri(treeUri)
        val root = rootDocumentUri(treeUri) ?: return null
        val existing = listChildren(context, treeUri)?.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        if (existing != null) {
            return if (existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                documentUri(treeUri, existing.documentId)
            } else {
                null
            }
        }
        return createDocument(
            context,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            cleanName,
        )
    }

    fun documentId(uri: Uri): String? = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    fun availableBytes(context: Context, documentUri: Uri): Long? =
        try {
            val rootId = DocumentsContract.getRootId(documentUri)
            val authority = documentUri.authority ?: return null
            val rootsUri = DocumentsContract.buildRootsUri(authority)
            val cursor = context.contentResolver.query(
                rootsUri,
                arrayOf(
                    DocumentsContract.Root.COLUMN_ROOT_ID,
                    DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                ),
                null,
                null,
                null,
            )
            cursor?.use {
                while (it.moveToNext()) {
                    if (it.getString(0) == rootId) {
                        if (it.isNull(1)) return null
                        return it.getLong(1)
                    }
                }
                null
            }
        } catch (t: Throwable) {
            null
        }

    fun createDocument(context: Context, parentUri: Uri, mimeType: String, name: String): Uri? =
        try {
            DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, name)
        } catch (t: Throwable) {
            null
        }

    fun renameDocument(context: Context, uri: Uri, newName: String): Uri? =
        try {
            DocumentsContract.renameDocument(context.contentResolver, uri, newName)
        } catch (t: Throwable) {
            null
        }

    fun deleteDocument(context: Context, uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (t: Throwable) {
            false
        }

    fun writeStream(context: Context, uri: Uri, source: File): Boolean =
        try {
            val out = context.contentResolver.openOutputStream(uri, "w") ?: return false
            out.use { stream ->
                source.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read > 0) stream.write(buf, 0, read)
                    }
                }
                stream.flush()
            }
            true
        } catch (t: Throwable) {
            false
        }

    fun copyDocument(context: Context, from: Uri, to: Uri): Boolean =
        try {
            val input = context.contentResolver.openInputStream(from) ?: return false
            val output = context.contentResolver.openOutputStream(to, "w") ?: return false
            output.use { out ->
                input.use { inStream ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = inStream.read(buf)
                        if (read < 0) break
                        if (read > 0) out.write(buf, 0, read)
                    }
                }
                out.flush()
            }
            true
        } catch (t: Throwable) {
            false
        }

    fun readDocumentToFile(context: Context, uri: Uri, dest: File): Boolean =
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            dest.outputStream().use { out ->
                input.use { inStream ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = inStream.read(buf)
                        if (read < 0) break
                        if (read > 0) out.write(buf, 0, read)
                    }
                }
            }
            true
        } catch (t: Throwable) {
            false
        }

    fun verifyStream(context: Context, uri: Uri, expectedBytes: Long, expectedSha: String): Boolean =
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buf = ByteArray(64 * 1024)
            input.use { stream ->
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        digest.update(buf, 0, read)
                        size += read
                    }
                }
            }
            val sha = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            size == expectedBytes && sha == expectedSha
        } catch (t: Throwable) {
            false
        }

    fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                if (read > 0) digest.update(buf, 0, read)
            }
            digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
}

/**
 * Safe USB WAV install through a user-granted SAF tree:
 * list -> plan -> backup same-name file (rename, or copy+verify+delete) ->
 * write temp doc -> verify read-back -> rename to final -> verify final.
 * Every failure path rolls back without leaving the original overwritten.
 */
object UsbWavSaver {
    suspend fun save(
        context: Context,
        treeUri: Uri,
        source: File,
        requestedName: String,
        targetDirectoryName: String? = null,
        maxWavFiles: Int? = null,
    ): UsbSaveResult = withContext(Dispatchers.IO) {
        saveBlocking(context, treeUri, source, requestedName, targetDirectoryName, maxWavFiles)
    }

    private fun saveBlocking(
        context: Context,
        treeUri: Uri,
        source: File,
        requestedName: String,
        targetDirectoryName: String?,
        maxWavFiles: Int?,
    ): UsbSaveResult {
        if (!source.isFile || source.length() == 0L) {
            return UsbSaveResult(false, message = "待写入文件不存在或为空", errorCode = "SOURCE_MISSING")
        }
        val rootUri = if (targetDirectoryName.isNullOrBlank()) {
            UsbSaf.rootDocumentUri(treeUri)
        } else {
            UsbSaf.ensureDirectory(context, treeUri, targetDirectoryName)
        } ?: return UsbSaveResult(false, message = "无法访问或创建目标 USB 目录", errorCode = "USB_UNAVAILABLE")

        val parentId = UsbSaf.documentId(rootUri)
            ?: return UsbSaveResult(false, message = "无法识别目标 USB 目录", errorCode = "USB_UNAVAILABLE")
        val entries = UsbSaf.listChildren(context, treeUri, parentId)
            ?: return UsbSaveResult(false, message = "无法访问 USB 目录（可能已被拔出）", errorCode = "USB_UNAVAILABLE")
        val names = entries.filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }.map { it.name }

        val available = UsbSaf.availableBytes(context, treeUri)
        if (available != null && available >= 0L && available < source.length()) {
            return UsbSaveResult(false, message = "USB 剩余空间不足", errorCode = "USB_SPACE")
        }

        val epoch = System.currentTimeMillis()
        val plan = SoundBackupPlanner.plan(requestedName, names, epoch)
        val existing = entries.firstOrNull { it.name.equals(plan.finalName, ignoreCase = true) }
        val wavCount = entries.count {
            it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR &&
                it.name.endsWith(".wav", ignoreCase = true) &&
                !it.name.contains(".installing-", ignoreCase = true)
        }
        if (maxWavFiles != null && existing == null && wavCount >= maxWavFiles) {
            return UsbSaveResult(
                false,
                message = "目标目录已有 $wavCount 个 WAV；此预设最多允许 $maxWavFiles 个音效",
                errorCode = "USB_MAX_FILES",
            )
        }

        var backupUri: Uri? = null
        var tempUri: Uri? = null
        var finalUri: Uri? = null
        var backupTempFile: File? = null

        fun cleanup(restore: Boolean) {
            tempUri?.let { UsbSaf.deleteDocument(context, it) }
            tempUri = null
            finalUri?.let { UsbSaf.deleteDocument(context, it) }
            finalUri = null
            backupTempFile?.delete()
            backupTempFile = null
            val backup = backupUri
            if (restore && backup != null) {
                val restored = UsbSaf.renameDocument(context, backup, plan.finalName) != null
                if (!restored) {
                    // Copy-based fallback: recreate final from backup bytes.
                    val finalDoc = UsbSaf.createDocument(context, rootUri, "audio/wav", plan.finalName)
                    if (finalDoc == null || !UsbSaf.copyDocument(context, backup, finalDoc)) {
                        // Last resort: keep the backup document so user data is not lost.
                    }
                }
                backupUri = null
            } else {
                backupUri?.let { UsbSaf.deleteDocument(context, it) }
                backupUri = null
            }
        }

        try {
            if (existing != null && plan.conflict) {
                val existingUri = UsbSaf.documentUri(treeUri, existing.documentId)
                val renamed = UsbSaf.renameDocument(context, existingUri, plan.backupName!!)
                if (renamed != null) {
                    backupUri = renamed
                } else {
                    // Copy-based backup: copy existing -> backup doc, verify, then remove original.
                    val backupDoc = UsbSaf.createDocument(context, rootUri, "application/octet-stream", plan.backupName)
                        ?: return UsbSaveResult(false, message = "同名文件备份失败，未覆盖原文件", errorCode = "USB_BACKUP_FAILED")
                    backupUri = backupDoc
                    val tmpFile = File.createTempFile("usb-backup-src-", ".bin", context.cacheDir)
                    backupTempFile = tmpFile
                    val readOk = UsbSaf.readDocumentToFile(context, existingUri, tmpFile)
                    val wroteOk = readOk && UsbSaf.writeStream(context, backupDoc, tmpFile)
                    if (!readOk || !wroteOk) {
                        cleanup(restore = false)
                        return UsbSaveResult(false, message = "同名文件备份失败，未覆盖原文件", errorCode = "USB_BACKUP_FAILED")
                    }
                    if (!UsbSaf.deleteDocument(context, existingUri)) {
                        // Original still present; do not replace blindly.
                        cleanup(restore = false)
                        return UsbSaveResult(false, message = "无法替换同名文件，未覆盖原文件", errorCode = "USB_WRITE_FAILED")
                    }
                }
            }

            val tempDoc = UsbSaf.createDocument(context, rootUri, "audio/wav", plan.tempName)
                ?: run {
                    cleanup(restore = true)
                    return UsbSaveResult(false, message = "USB 目录不可写或已转为只读", errorCode = "USB_READ_ONLY")
                }
            tempUri = tempDoc
            val wrote = UsbSaf.writeStream(context, tempDoc, source)
            if (!wrote) {
                cleanup(restore = true)
                return UsbSaveResult(false, message = "写入 USB 失败（可能已拔出或只读）", errorCode = "USB_WRITE_FAILED")
            }
            val sourceSha = UsbSaf.sha256(source)
            if (!UsbSaf.verifyStream(context, tempDoc, source.length(), sourceSha)) {
                cleanup(restore = true)
                return UsbSaveResult(false, message = "写入 USB 后校验失败，已回滚，原文件保留", errorCode = "USB_VERIFY_FAILED")
            }
            val renamed = UsbSaf.renameDocument(context, tempDoc, plan.finalName)
            if (renamed == null) {
                cleanup(restore = true)
                return UsbSaveResult(false, message = "USB 文件重命名失败，已回滚", errorCode = "USB_WRITE_FAILED")
            }
            tempUri = null
            finalUri = renamed
            val finalVerified = UsbSaf.verifyStream(context, renamed, source.length(), sourceSha)
            if (!finalVerified) {
                cleanup(restore = true)
                return UsbSaveResult(false, message = "写入 USB 后校验失败，已回滚，原文件保留", errorCode = "USB_VERIFY_FAILED")
            }
            backupTempFile?.delete()
            backupTempFile = null
            return UsbSaveResult(
                ok = true,
                finalName = plan.finalName,
                backupName = plan.backupName,
                message = PhoneLanguage.text(
                    "Safely written to USB: ${plan.finalName}${if (plan.conflict) " (original backed up)" else ""}",
                    "已安全写入 USB：${plan.finalName}${if (plan.conflict) "（原文件已备份）" else ""}",
                ),
            )
        } catch (t: Throwable) {
            cleanup(restore = true)
            return UsbSaveResult(
                false,
                message = "写入 USB 失败（可能已拔出或只读）：${t.message ?: t.javaClass.simpleName}",
                errorCode = "USB_WRITE_FAILED",
            )
        }
    }
}
