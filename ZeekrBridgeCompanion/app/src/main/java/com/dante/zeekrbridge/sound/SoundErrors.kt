package com.dante.zeekrbridge.sound

import com.dante.zeekrbridge.ui.PhoneLanguage

/** Cancellation shared between decode/convert/write stages. */
class SoundCancellation {
    @Volatile
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun check() {
        if (cancelled) throw SoundCancelledException()
    }
}

class SoundCancelledException : Exception("转换已取消")

/** User-visible input problem (empty selection, missing decoder, bad file, ...). */
class SoundInputException(
    message: String,
    val code: String = "INPUT_ERROR",
    cause: Throwable? = null,
) : Exception(message, cause)

/** I/O or storage problem (permission, space, USB unplugged/read-only, ...). */
class SoundIoException(
    message: String,
    val code: String = "IO_ERROR",
    cause: Throwable? = null,
) : Exception(message, cause)

/** Maps stable error codes to user-facing Chinese status text. */
object SoundErrors {
    fun userMessage(code: String?, fallback: String): String = when (code) {
        "SOURCE_UNREADABLE" -> text("The source file cannot be read. Choose it again.", "源文件无法读取或读取权限失效，请重新选择文件")
        "NO_AUDIO_TRACK" -> text("No recognizable audio track was found.", "源文件没有可识别的音频轨道")
        "NO_DECODER" -> text("This phone has no decoder for that audio format.", "系统缺少该格式的解码器，无法解码此音频")
        "DECODE_FAILED" -> text("Decoding failed. The file may be damaged or unsupported.", "解码失败：文件可能损坏或格式不受支持")
        "EMPTY_SELECTION" -> text("The selection is empty or out of range.", "选区为空或超出范围，请调整开始/结束位置")
        "SPACE_PHONE" -> text("There is not enough phone storage to finish.", "手机存储空间不足，无法完成转换")
        "USB_UNAVAILABLE" -> text("The USB folder is unavailable or disconnected.", "无法访问 USB 目录（可能已被拔出）")
        "USB_READ_ONLY" -> text("The USB folder is read-only.", "USB 目录不可写或已转为只读")
        "USB_SPACE" -> text("There is not enough space on the USB drive.", "USB 剩余空间不足")
        "USB_BACKUP_FAILED" -> text("The existing file could not be backed up, so it was not replaced.", "同名文件备份失败，未覆盖原文件")
        "USB_WRITE_FAILED" -> text("Writing to USB failed. It may be disconnected or read-only.", "写入 USB 失败（可能已拔出或只读）")
        "USB_VERIFY_FAILED" -> text("USB verification failed. The write was rolled back.", "写入 USB 后校验失败，已回滚，原文件保留")
        "VERIFY_FAILED" -> text("Output verification failed. No file was saved.", "输出校验失败，未保存文件")
        "BAD_NAME" -> text("The file name was cleaned to a safe name.", "文件名无效，已自动清理为安全名称")
        "CANCELLED" -> text("Cancelled", "已取消")
        else -> fallback
    }

    private fun text(en: String, zh: String) = PhoneLanguage.text(en, zh)
}
