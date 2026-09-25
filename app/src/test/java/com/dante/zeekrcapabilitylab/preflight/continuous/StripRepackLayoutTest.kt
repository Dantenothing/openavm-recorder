package com.dante.zeekrcapabilitylab.preflight.continuous

import java.util.BitSet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StripRepackLayoutTest {
    private val layout = StripRepackLayout(RepackRaster(1280, 5140), 1728)

    @Test fun fullSizeCoordinatesKeepTheExtraTwentyRowsAndThePartialLastStrip() {
        assertEquals(RepackRaster(3840, 1728), layout.encoded)
        assertEquals(6_579_200L, layout.input.pixels)
        assertEquals(56_320L, layout.paddingPixels)
        assertEquals(RepackPoint(0, 0), layout.toEncoded(0, 0))
        assertEquals(RepackPoint(1279, 1727), layout.toEncoded(1279, 1727))
        assertEquals(RepackPoint(1280, 0), layout.toEncoded(0, 1728))
        assertEquals(RepackPoint(2559, 1727), layout.toEncoded(1279, 3455))
        assertEquals(RepackPoint(2560, 0), layout.toEncoded(0, 3456))
        assertEquals(RepackPoint(2560, 1664), layout.toEncoded(0, 5120))
        assertEquals(RepackPoint(3839, 1683), layout.toEncoded(1279, 5139))
        assertNull(layout.toInput(2560, 1684))
        assertNull(layout.toInput(3839, 1727))
    }

    @Test fun everySourcePixelRoundTripsExactlyOnceAndOnlyPaddingIsUnmapped() {
        val visited = BitSet(layout.encoded.pixels.toInt())
        for (y in 0 until layout.input.height) for (x in 0 until layout.input.width) {
            val output = layout.toEncoded(x, y)
            val index = output.y * layout.encoded.width + output.x
            if (visited[index]) fail("Overlapping output at $output")
            visited.set(index)
            val restored = layout.toInput(output.x, output.y)
            if (restored?.x != x || restored.y != y) fail("Lost source pixel $x,$y")
        }
        assertEquals(layout.input.pixels, visited.cardinality().toLong())
        var padding = 0L
        for (y in 0 until layout.encoded.height) for (x in 0 until layout.encoded.width) {
            val restored = layout.toInput(x, y)
            if (restored == null) {
                padding++
                if (visited[y * layout.encoded.width + x]) fail("Padding overlaps an input pixel")
            } else if (!visited[y * layout.encoded.width + x]) fail("Unreachable non-padding pixel")
        }
        assertEquals(56_320L, padding)
    }

    @Test fun rendererRegionsHaveEqualSourceAndDestinationSizes() {
        assertEquals(listOf(
            RepackRegion(RepackRect(0, 0, 1280, 1728), RepackRect(0, 0, 1280, 1728)),
            RepackRegion(RepackRect(0, 1728, 1280, 1728), RepackRect(1280, 0, 1280, 1728)),
            RepackRegion(RepackRect(0, 3456, 1280, 1684), RepackRect(2560, 0, 1280, 1684)),
        ), layout.regions)
    }

    @Test fun versionedDescriptorRoundTripsAndRejectsAmbiguousOrCorruptLayouts() {
        val descriptor = layout.descriptor()
        val decoded = Json.decodeFromString<RepackLayoutDescriptor>(Json.encodeToString(descriptor))
        assertEquals(descriptor, StripRepackLayout.fromDescriptor(decoded).descriptor())
        for (invalid in listOf(
            descriptor.copy(version = 2), descriptor.copy(layout = "FOUR_VIEW_GRID"),
            descriptor.copy(coordinateOrigin = "BOTTOM_LEFT"), descriptor.copy(encodedWidth = 1280),
            descriptor.copy(inputHeight = 6000),
        )) assertThrows(IllegalArgumentException::class.java) { StripRepackLayout.fromDescriptor(invalid) }
    }

    @Test fun smallCandidateUsesSameMappingAndExactDivisionHasNoPadding() {
        val small = StripRepackLayout(RepackRaster(128, 514), 176)
        assertEquals(RepackRaster(384, 176), small.encoded)
        assertEquals(14L * 128, small.paddingPixels)
        val exact = StripRepackLayout(RepackRaster(128, 512), 128)
        assertEquals(0L, exact.paddingPixels)
        assertEquals(RepackPoint(511, 127), exact.toEncoded(127, 511))
    }

    @Test fun invalidSizesAndCoordinatesCannotOverflowOrWrapIntoValidPixels() {
        assertThrows(IllegalArgumentException::class.java) { RepackRaster(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { StripRepackLayout(RepackRaster(1280, 5140), 0) }
        assertThrows(IllegalArgumentException::class.java) { StripRepackLayout(RepackRaster(Int.MAX_VALUE, 4), 2) }
        assertThrows(IllegalArgumentException::class.java) { StripRepackLayout(RepackRaster(128, 5140), 1) }
        assertThrows(IllegalArgumentException::class.java) { layout.toEncoded(1280, 0) }
        assertThrows(IllegalArgumentException::class.java) { layout.toEncoded(0, 5140) }
        assertThrows(IllegalArgumentException::class.java) { layout.toInput(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { layout.toInput(0, 1728) }
    }
}
