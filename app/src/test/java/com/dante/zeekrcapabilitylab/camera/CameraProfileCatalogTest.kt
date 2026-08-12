package com.dante.zeekrcapabilitylab.camera

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraProfileCatalogTest {

    private val declaredTall = setOf(ProfileSize(1280, 5140), ProfileSize(1920, 1080))
    private val declaredWide = setOf(ProfileSize(5120, 1280), ProfileSize(1920, 1080))

    @Test
    fun wideFourCameraProfileAt28MbpsIsPreferredWhenDeclared() {
        val profiles = CameraProfileCatalog.availableProfiles(declaredWide)

        assertEquals(
            listOf(
                CameraFormatProfile(ProfileSize(5120, 1280), 28_000_000),
                CameraFormatProfile(ProfileSize(5120, 1280), 14_000_000),
            ),
            profiles,
        )
    }

    @Test
    fun tallProfileIsNotFilteredByAspectRatio() {
        val profiles = CameraProfileCatalog.availableProfiles(declaredTall)

        assertEquals(2, profiles.size)
        assertTrue(profiles.all { it.size == ProfileSize(1280, 5140) })
        assertEquals(
            listOf(28_000_000, 14_000_000),
            profiles.map { it.bitrateBps },
        )
    }

    @Test
    fun exact1280x5120VerticalCompositeUses28MbpsInProduct() {
        val profile = CameraProfileCatalog.productPreferredProfile(
            setOf(ProfileSize(1280, 5120), ProfileSize(1920, 1080)),
        )

        assertEquals(ProfileSize(1280, 5120), profile?.size)
        assertEquals(28_000_000, profile?.bitrateBps)
    }

    @Test
    fun productPrefersVerticalFourLaneFeedOverHorizontalAlternative() {
        val profile = CameraProfileCatalog.productPreferredProfile(
            setOf(ProfileSize(5120, 1280), ProfileSize(1280, 5140)),
        )

        assertEquals(ProfileSize(1280, 5140), profile?.size)
        assertEquals(28_000_000, profile?.bitrateBps)
    }

    @Test
    fun emptyRecordCandidatesProduceNoProfiles() {
        val profiles = CameraProfileCatalog.labProfilesForRecordCandidates(emptyList())

        assertTrue(profiles.isEmpty())
        assertTrue(profiles.none { it.size == ProfileSize(640, 480) })
    }

    @Test
    fun onlyDeclaredSizesAreListed() {
        val declared = setOf(ProfileSize(3840, 2160), ProfileSize(1920, 1080))

        val profiles = CameraProfileCatalog.availableProfiles(declared)

        assertEquals(3, profiles.size)
        assertTrue(profiles.all { it.size == ProfileSize(3840, 2160) })
        assertEquals(
            listOf(14_000_000, 28_000_000, 40_000_000),
            profiles.map { it.bitrateBps },
        )
    }

    @Test
    fun noSilentFallbackToAnotherSizeOrBitrate() {
        val declared = setOf(ProfileSize(3840, 2160), ProfileSize(1920, 1080))
        val requestedTall = CameraProfileCatalog.EXPLICIT_PROFILES.first {
            it.size == ProfileSize(1280, 5140) && it.bitrateBps == 14_000_000
        }

        assertNull(
            CameraProfileCatalog.resolveExactOrNull(
                declaredSizes = declared,
                requested = requestedTall,
            ),
        )
        val available = CameraProfileCatalog.availableProfiles(declared)
        assertTrue(available.none { it.size == ProfileSize(1280, 5140) })
        assertTrue(available.none { it.bitrateBps == 40_000_000 && it.size == ProfileSize(1280, 5140) })
    }

    @Test
    fun exactMatchStillResolvesWhenDeclared() {
        val requested = CameraProfileCatalog.EXPLICIT_PROFILES.first {
            it.size == ProfileSize(1280, 5140) && it.bitrateBps == 14_000_000
        }

        val resolved = CameraProfileCatalog.resolveExactOrNull(
            declaredSizes = declaredTall,
            requested = requested,
        )

        assertEquals(requested, resolved)
    }

    @Test
    fun sortProfilesOrdersByPixelAreaThenBitrate() {
        val profiles = listOf(
            CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000),
            CameraFormatProfile(ProfileSize(3840, 2160), 14_000_000),
            CameraFormatProfile(ProfileSize(3840, 2160), 40_000_000),
            CameraFormatProfile(ProfileSize(1920, 1080), 8_000_000),
        )

        val sorted = CameraProfileCatalog.sortProfiles(profiles)

        assertEquals(
            listOf(
                ProfileSize(3840, 2160),
                ProfileSize(3840, 2160),
                ProfileSize(1280, 5140),
                ProfileSize(1920, 1080),
            ),
            sorted.map { it.size },
        )
        assertEquals(
            listOf(40_000_000, 14_000_000, 14_000_000, 8_000_000),
            sorted.map { it.bitrateBps },
        )
    }

    @Test
    fun labProfilesKeepExplicitMatrixFirstWithoutDuplicateSizes() {
        val declared = setOf(
            ProfileSize(5120, 1280),
            ProfileSize(1280, 5120),
            ProfileSize(1280, 5140),
            ProfileSize(3840, 2160),
            ProfileSize(2560, 1440),
        )

        val profiles = CameraProfileCatalog.labProfiles(declared)

        val explicit = profiles.take(CameraProfileCatalog.EXPLICIT_PROFILES.size)
        assertEquals(CameraProfileCatalog.EXPLICIT_PROFILES, explicit)
        val autoProfiles = profiles.filter { it.source == CameraFormatProfile.SOURCE_DECLARED_AUTO }
        val explicitSizes = CameraProfileCatalog.EXPLICIT_PROFILES.map { it.size }.toSet()
        // Multiple bitrates for the same size are expected within the explicit matrix,
        // but the auto tier must never duplicate a size the explicit matrix already covers.
        assertTrue(autoProfiles.isNotEmpty())
        assertTrue(autoProfiles.all { it.size !in explicitSizes })
        assertTrue(profiles.any { it.size == ProfileSize(2560, 1440) })
    }

    @Test
    fun bitrateForSizeUsesPixelAreaTiers() {
        assertEquals(14_000_000, CameraProfileCatalog.bitrateForSize(ProfileSize(3840, 2160)))
        assertEquals(8_000_000, CameraProfileCatalog.bitrateForSize(ProfileSize(1280, 5140)))
        assertEquals(8_000_000, CameraProfileCatalog.bitrateForSize(ProfileSize(1920, 1080)))
        assertEquals(6_000_000, CameraProfileCatalog.bitrateForSize(ProfileSize(1280, 720)))
        assertEquals(4_000_000, CameraProfileCatalog.bitrateForSize(ProfileSize(640, 480)))
    }
}
