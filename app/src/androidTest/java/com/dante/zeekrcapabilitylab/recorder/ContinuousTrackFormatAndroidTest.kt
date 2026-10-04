package com.dante.zeekrcapabilitylab.recorder

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Build
import com.dante.zeekrcapabilitylab.service.recorder.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@android.annotation.SuppressLint("NewApi") // Test is skipped below API 29 before using MediaFormat.keys.
class ContinuousTrackFormatAndroidTest {
    @Test fun platformAdapterPreservesTheSpecificMissingCsdAndNumericTypeEvidence() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 1)
        val format = MediaFormat.createVideoFormat("video/avc", 640, 480).apply { setByteBuffer("csd-0", ByteBuffer.wrap(sps)) }
        fun failure(): ContinuousTrackFormatException = try {
            ContinuousTrackFormatAndroid.capture(format)
            throw AssertionError("Expected typed format failure")
        } catch (error: ContinuousTrackFormatException) { error }
        assertEquals("CSD_1", failure().fields.single().field)
        assertEquals("MISSING", failure().fields.single().kind)
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps)); format.setLong("profile", 1L)
        assertEquals("PROFILE", failure().fields.single().field)
        assertEquals("TYPE_MISMATCH", failure().fields.single().kind)
    }

    @Test fun eachMuxerReceivesIndependentCsdCursorsAndTheComparisonSnapshotStaysStable() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1f)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0x12)
        val sourceSps = ByteBuffer.wrap(byteArrayOf(99) + sps + byteArrayOf(98)).apply {
            position(1); limit(1 + sps.size)
        }
        val source = MediaFormat.createVideoFormat("video/avc", 640, 480).apply {
            setByteBuffer("csd-0", sourceSps); setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }
        val captured = ContinuousTrackFormatAndroid.capture(source)
        assertEquals(1, sourceSps.position())
        val firstMuxer = ContinuousTrackFormatAndroid.copyForMuxer(captured.format)
        for (key in listOf("csd-0", "csd-1")) {
            val consumed = requireNotNull(firstMuxer.getByteBuffer(key)); consumed.position(consumed.limit())
        }
        sourceSps.put(1, 88) // The codec-owned buffer is also independent of the retained format.
        val secondMuxer = ContinuousTrackFormatAndroid.copyForMuxer(captured.format)
        fun remaining(format: MediaFormat, key: String): ByteArray {
            val buffer = requireNotNull(format.getByteBuffer(key)).duplicate()
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        assertArrayEquals(sps, remaining(secondMuxer, "csd-0"))
        assertArrayEquals(pps, remaining(secondMuxer, "csd-1"))
        assertTrue(captured.spec.compatibleWith(ContinuousTrackFormatAndroid.capture(secondMuxer).spec))
    }
}
