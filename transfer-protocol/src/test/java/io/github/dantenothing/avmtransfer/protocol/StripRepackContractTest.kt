package io.github.dantenothing.avmtransfer.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StripRepackContractTest {
    private val layout = StripRepackContract(inputWidth=1280,inputHeight=5140,stripHeight=1728,encodedWidth=3840,encodedHeight=1728)

    @Test fun knownStripSeamsAndLastSourceRowSurviveTheMapping() {
        val examples = listOf(
            RasterPixel(0,0) to RasterPixel(0,0),
            RasterPixel(1279,1727) to RasterPixel(1279,1727),
            RasterPixel(0,1728) to RasterPixel(1280,0),
            RasterPixel(1279,3455) to RasterPixel(2559,1727),
            RasterPixel(0,3456) to RasterPixel(2560,0),
            RasterPixel(1279,5139) to RasterPixel(3839,1683),
        )
        examples.forEach { (source,encoded) ->
            assertEquals(encoded,layout.toEncoded(source.x,source.y))
            assertEquals(source,layout.toSource(encoded.x,encoded.y))
        }
        assertNull(layout.toSource(2560,1684));assertNull(layout.toSource(3839,1727))
        assertThrows(IllegalArgumentException::class.java) {layout.toSource(3840,0)}
    }

    @Test fun middleCameraViewsNeedTwoPiecesInsteadOfOneWrongRectangularCrop() {
        val second=layout.cropPieces(PixelRectangle(0,1288,1280,1280))
        assertEquals(listOf(PixelRectangle(0,1288,1280,440),PixelRectangle(1280,0,1280,840)),second.map {it.encoded})
        assertEquals(listOf(PixelRectangle(0,0,1280,440),PixelRectangle(0,440,1280,840)),second.map {it.destination})
        val third=layout.cropPieces(PixelRectangle(0,2572,1280,1280))
        assertEquals(listOf(PixelRectangle(1280,844,1280,884),PixelRectangle(2560,0,1280,396)),third.map {it.encoded})
        assertEquals(listOf(PixelRectangle(0,0,1280,884),PixelRectangle(0,884,1280,396)),third.map {it.destination})
    }

    @Test fun wholeRasterPreservesAll5140RowsAndDoesNotTreat44PaddingRowsAsCameraContent() {
        val pieces=layout.cropPieces(PixelRectangle(0,0,1280,5140))
        assertEquals(listOf(1728,1728,1684),pieces.map {it.encoded.height})
        assertEquals(1280L*5140,pieces.sumOf {it.encoded.width.toLong()*it.encoded.height})
        assertEquals(listOf(0,1728,3456),pieces.map {it.destination.top})
        for(i in pieces.indices) for(j in 0 until i) assertFalse(pieces[i].encoded.overlaps(pieces[j].encoded))
    }

    @Test fun zoomedCropAcrossBothSeamsRetainsHorizontalOffsetAndDestinationContinuity() {
        val pieces=layout.cropPieces(PixelRectangle(200,1600,300,2000))
        assertEquals(listOf(PixelRectangle(200,1600,300,128),PixelRectangle(1480,0,300,1728),PixelRectangle(2760,0,300,144)),pieces.map {it.encoded})
        assertEquals(listOf(0,128,1856),pieces.map {it.destination.top})
        assertEquals(2000,pieces.sumOf {it.destination.height})
    }

    @Test fun metadataRoundTripAndActualTrackDimensionsAreRequired() {
        val value=Json.encodeToString(StripRepackContract.serializer(),layout)
        val restored=Json.decodeFromString(StripRepackContract.serializer(),value)
        assertEquals(layout,restored);assertTrue(restored.matchesTrack(3840,1728))
        assertFalse(restored.matchesTrack(1280,5140));assertFalse(restored.matchesTrack(2560,2560))
    }

    @Test fun dimensionsWithoutAnExplicitLayoutIdentityCannotBecomeRepackedVideo() {
        val dimensionsOnly="""{"inputWidth":1280,"inputHeight":5140,"stripHeight":1728,"encodedWidth":3840,"encodedHeight":1728}"""
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            Json.decodeFromString(StripRepackContract.serializer(),dimensionsOnly)
        }
    }

    @Test fun unknownOrMalformedDescriptorsAndOutOfBoundsCropsFailClosed() {
        val bad=listOf(layout.copy(version=2),layout.copy(layout="OPENAVM_GRID_LAYOUT_V1"),
            layout.copy(coordinateOrigin="BOTTOM_LEFT"),layout.copy(inputWidth=Int.MAX_VALUE),
            layout.copy(inputHeight=-1),layout.copy(stripHeight=0),layout.copy(stripHeight=1),
            layout.copy(encodedWidth=3841),layout.copy(encodedHeight=1727))
        bad.forEach {assertTrue(it.validate().isNotEmpty());assertFalse(it.matchesTrack(3840,1728))}
        for(crop in listOf(PixelRectangle(-1,0,10,10),PixelRectangle(0,0,1281,10),PixelRectangle(0,5139,1,2),PixelRectangle(0,0,0,1)))
            assertThrows(IllegalArgumentException::class.java) {layout.cropPieces(crop)}
    }
}
