package com.dante.zeekrcapabilitylab.product

/**
 * Pure decisions for exporting finalized recordings to a removable USB volume.
 *
 * Exports are idempotent by design: a file already present on the stick with
 * the same size is skipped, so plugging the same drive in again only copies
 * what is new, and a torn copy from an unplug (size mismatch, or a leftover
 * .part file) is redone.
 */
object UsbExportPolicy {

    /** Folder created inside the app-specific directory on the USB volume. */
    const val EXPORT_SUBDIR = "AVMRecorder"

    /** Free space that must remain on the stick after every copy. */
    const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L

    /** In-flight copies write to this suffix and rename on completion. */
    const val PART_SUFFIX = ".part"

    fun shouldCopy(targetExists: Boolean, sourceBytes: Long, targetBytes: Long): Boolean =
        !targetExists || targetBytes != sourceBytes

    fun hasSpace(
        freeBytes: Long,
        nextCopyBytes: Long,
        marginBytes: Long = FREE_SPACE_MARGIN_BYTES,
    ): Boolean = freeBytes >= nextCopyBytes + marginBytes
}
