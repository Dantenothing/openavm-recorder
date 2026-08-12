package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompositePreviewSizePolicyTest {
    @Test
    fun matchesWorkingTesterByPreferring640x480Surface() {
        val selected = CompositePreviewSizePolicy.choose(
            listOf(
                ProfileSize(1280, 720),
                ProfileSize(5120, 1280),
                ProfileSize(640, 480),
                ProfileSize(1280, 5140),
            ),
        )

        assertEquals(ProfileSize(640, 480), selected)
    }

    @Test
    fun choosesNearestSmallSurfaceWhenExact640x480IsAbsent() {
        val selected = CompositePreviewSizePolicy.choose(
            listOf(ProfileSize(1280, 720), ProfileSize(800, 600), ProfileSize(5120, 1280)),
        )

        assertEquals(ProfileSize(800, 600), selected)
    }

    @Test
    fun fallsBackToFirstDeclaredSurfaceLikeOriginalTester() {
        val selected = CompositePreviewSizePolicy.choose(
            listOf(ProfileSize(1280, 5140), ProfileSize(5120, 1280)),
        )

        assertEquals(ProfileSize(1280, 5140), selected)
    }

    @Test
    fun emptyDeclarationHasNoPreviewSurface() {
        assertNull(CompositePreviewSizePolicy.choose(emptyList()))
    }

    @Test
    fun highResolutionIsChosenOnlyWhenSurfaceTextureDeclaresExactComposite() {
        assertEquals(
            ProfileSize(1280, 5140),
            CompositePreviewSizePolicy.chooseDeclaredHighResolution(
                listOf(ProfileSize(640, 480), ProfileSize(1280, 5140)),
            ),
        )
        assertNull(
            CompositePreviewSizePolicy.chooseDeclaredHighResolution(
                listOf(ProfileSize(640, 480), ProfileSize(1280, 5120), ProfileSize(3840, 2160)),
            ),
        )
    }
}
