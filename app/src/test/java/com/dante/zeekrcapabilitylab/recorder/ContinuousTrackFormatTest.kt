package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ContinuousTrackFormatTest {
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0x12)
    private fun fields(extra: Map<String, Any?> = emptyMap()): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "mime" to "video/avc", "width" to 640, "height" to 480,
        "csd-0" to ByteBuffer.wrap(sps.copyOf()), "csd-1" to ByteBuffer.wrap(pps.copyOf()),
    ).apply { putAll(extra) }
    private fun spec(values: Map<String, Any?> = fields()) = ContinuousTrackFormatSpec.capture(object : ContinuousTrackFormatFields {
        override val keys = values.keys
        override fun string(key: String) = values[key] as String?
        override fun integer(key: String) = values[key] as Int
        override fun buffer(key: String) = values[key] as ByteBuffer?
    })
    private fun rejects(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: IllegalStateException) { assertEquals(code, error.message) }
    }

    @Test fun repeatedFormatAtFirstCutKeepsExistingMuxerAndDoesNotReconfigure() {
        val state = ContinuousTrackFormatState()
        var configured = 0
        repeat(5) {
            if (state.accept(spec()) == ContinuousFormatDecision.INSTALL) configured++
        }
        assertEquals(1, configured)
        assertEquals(5L, state.snapshot().notificationCount)
        assertEquals(4L, state.snapshot().compatibleRepeats)
        assertEquals("KEEP", state.snapshot().decision)
    }

    @Test fun repeatWithRateOrEncoderRuntimeMetadataChangesStillKeepsTrack() {
        val state = ContinuousTrackFormatState()
        assertEquals(ContinuousFormatDecision.INSTALL, state.accept(spec(fields(mapOf("bitrate" to 1_000_000, "frame-rate" to 15)))))
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(fields(mapOf("bitrate" to 1_200_000, "frame-rate" to 30,
            "latency" to 3, "vendor.example.counter" to 9)))))
    }

    @Test fun changedCsdSizeOrStableMetadataCannotBeSilentlyAccepted() {
        val changes = listOf(mapOf("width" to 1280), mapOf("height" to 720),
            mapOf("csd-0" to ByteBuffer.wrap(sps.copyOf().apply { this[lastIndex] = 0x20 })),
            mapOf("rotation-degrees" to 90), mapOf("crop-left" to 1), mapOf("sar-width" to 4, "sar-height" to 3))
        changes.forEach { change ->
            val state = ContinuousTrackFormatState(); state.accept(spec())
            rejects(ContinuousTrackFormatSpec.INCOMPATIBLE) { state.accept(spec(fields(change))) }
        }
    }

    @Test fun profileAndLevelConflictsAreRejectedButRepeatedCsdCanOmitRedundantHints() {
        for (key in listOf("profile", "level", "color-standard", "color-range", "color-transfer")) {
            val state = ContinuousTrackFormatState(); state.accept(spec(fields(mapOf(key to 1))))
            assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec()))
            rejects(ContinuousTrackFormatSpec.INCOMPATIBLE) { state.accept(spec(fields(mapOf(key to 2)))) }
        }
    }

    @Test fun explicitDefaultCropRotationAndAspectRatioMatchOmittedDefaults() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(fields(mapOf(
            "crop-left" to 0, "crop-top" to 0, "crop-right" to 639, "crop-bottom" to 479,
            "rotation-degrees" to 0, "sar-width" to 2, "sar-height" to 2, "color-standard" to 0)))))
    }

    @Test fun csdSnapshotOwnsBytesAndPreservesSourcePositionAndLimit() {
        val backing = byteArrayOf(99) + sps + byteArrayOf(98)
        val buffer = ByteBuffer.wrap(backing).apply { position(1); limit(backing.size - 1) }
        val first = spec(fields(mapOf("csd-0" to buffer)))
        assertEquals(1, buffer.position()); assertEquals(backing.size - 1, buffer.limit())
        backing[backing.size - 2] = 77
        buffer.position(buffer.limit()) // Model must never re-read a cursor consumed by MediaMuxer.
        assertTrue(first.compatibleWith(spec()))
        assertFalse(first.toString().contains("AAAA"))
    }

    @Test fun combinedDecoderInputDoesNotProveACompleteMuxerTrack() {
        val values = fields(); values.remove("csd-1"); values["csd-0"] = ByteBuffer.wrap(sps + pps)
        rejects(ContinuousTrackFormatSpec.INCOMPLETE) { spec(values) }
    }

    @Test fun missingEmptyMalformedAndOversizeConfigurationCannotInstallATrack() {
        val invalid = listOf(fields().apply { remove("mime") }, fields().apply { remove("width") },
            fields(mapOf("width" to 0)), fields(mapOf("width" to "640")), fields(mapOf("height" to 50_000)),
            fields(mapOf("mime" to "video/hevc")), fields().apply { remove("csd-0") },
            fields().apply { remove("csd-1") }, fields(mapOf("csd-0" to ByteBuffer.allocate(0))),
            fields(mapOf("csd-0" to ByteBuffer.wrap(byteArrayOf(7, 8)))),
            fields(mapOf("csd-0" to ByteBuffer.wrap(pps), "csd-1" to ByteBuffer.wrap(sps))),
            fields(mapOf("csd-0" to ByteBuffer.allocate(ContinuousTrackFormatSpec.MAX_BUFFER_BYTES + 1))),
            fields(mapOf("crop-right" to 640)), fields(mapOf("sar-width" to 4)), fields(mapOf("profile" to -1)))
        invalid.forEach { values -> rejects(ContinuousTrackFormatSpec.INCOMPLETE) { spec(values) } }
    }

    @Test fun invalidRepeatDoesNotOverwriteAcceptedContract() {
        val state = ContinuousTrackFormatState(); state.accept(spec())
        rejects(ContinuousTrackFormatSpec.INCOMPLETE) { spec(fields(mapOf("csd-1" to null))) }
        state.incomplete()
        assertEquals("INCOMPLETE", state.snapshot().decision)
        assertEquals(2L, state.snapshot().notificationCount)
        assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec()))
    }

    @Test fun initiallyUnspecifiedHintsCannotHideALaterExplicitConflict() {
        for (key in listOf("profile", "level", "color-standard", "color-range", "color-transfer")) {
            val state = ContinuousTrackFormatState(); state.accept(spec(fields(mapOf(key to 0))))
            assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec(fields(mapOf(key to 1)))))
            assertEquals(ContinuousFormatDecision.KEEP, state.accept(spec()))
            rejects(ContinuousTrackFormatSpec.INCOMPATIBLE) { state.accept(spec(fields(mapOf(key to 2)))) }
            assertEquals("INCOMPATIBLE", state.snapshot().decision)
            assertEquals(4L, state.snapshot().notificationCount)
            assertEquals(2L, state.snapshot().compatibleRepeats)
        }
    }
}
