package com.dante.zeekrcapabilitylab.enhancement

import com.dante.zeekrcapabilitylab.update.*
import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    private fun release(tag: String, pre: Boolean = false) = OfficialRelease(tag_name = tag, html_url = "https://github.com/Dantenothing/openavm-recorder/releases/tag/$tag", prerelease = pre,
        assets = listOf(ReleaseAsset("OpenAVM-Recorder-$tag-arm64-v8a.apk", "https://github.com/Dantenothing/openavm-recorder/releases/download/$tag/car.apk", 2300000)))
    @Test fun versionsAndChannelsPreventDowngrades() {
        assertEquals(ReleaseVersion.parse("v4"), ReleaseVersion.parse("4.0.0"))
        assertTrue(ReleaseVersion.parse("4.1.0-beta10")!! > ReleaseVersion.parse("4.1.0-beta9")!!)
        assertTrue(ReleaseVersion.parse("4.1.0")!! > ReleaseVersion.parse("4.1.0-rc2")!!)
        assertNull(ReleasePolicy.newest(listOf(release("v4")), "4.1.0-beta5", false))
        assertNull(ReleasePolicy.newest(listOf(release("4.1.0-beta6", true)), "4.1.0-beta5", false))
        assertNotNull(ReleasePolicy.newest(listOf(release("4.1.0-beta6", true)), "4.1.0-beta5", true))
        assertNull(ReleasePolicy.newest(listOf(release("4.2.0-alpha1", true)), "4.1.0-beta5", true))
    }
    @Test fun rejectsAmbiguousWrongAppAndExternalDownloads() {
        val good = release("4.1.0")
        assertNull(ReleasePolicy.newest(listOf(good.copy(assets = good.assets + good.assets)), "v4", true))
        assertNull(ReleasePolicy.newest(listOf(good.copy(assets = listOf(good.assets[0].copy(name = "OpenAVM-Companion-arm64-v8a.apk")))), "v4", true))
        listOf("http://github.com/Dantenothing/openavm-recorder/releases/download/v4/c.apk", "https://github.com.evil.test/Dantenothing/openavm-recorder/releases/download/v4/c.apk", "https://github.com/another/repo/releases/download/v4/c.apk").forEach {
            assertNull(ReleasePolicy.newest(listOf(good.copy(assets = listOf(good.assets[0].copy(browser_download_url = it)))), "v4", true))
        }
    }
}
