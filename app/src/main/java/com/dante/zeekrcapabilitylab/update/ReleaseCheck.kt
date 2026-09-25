package com.dante.zeekrcapabilitylab.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val stage: Int, val revision: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other,
        { it.major }, { it.minor }, { it.patch }, { it.stage }, { it.revision })
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("(?i)^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-(alpha|beta|rc)\\.?(\\d+))?$").matchEntire(value.trim()) ?: return null
            val g = match.groupValues
            return runCatching { ReleaseVersion(g[1].toInt(), g[2].ifEmpty { "0" }.toInt(), g[3].ifEmpty { "0" }.toInt(),
                when(g[4].lowercase()) { "alpha" -> 0; "beta" -> 1; "rc" -> 2; else -> 3 }, g[5].ifEmpty { "0" }.toInt()) }.getOrNull()
        }
    }
}

@Serializable data class ReleaseAsset(val name: String = "", val browser_download_url: String = "", val size: Long = 0)
@Serializable data class OfficialRelease(val tag_name: String = "", val name: String = "", val body: String = "",
    val html_url: String = "", val prerelease: Boolean = false, val draft: Boolean = false, val assets: List<ReleaseAsset> = emptyList())
data class ReleaseOffer(val release: OfficialRelease, val asset: ReleaseAsset)

object ReleasePolicy {
    private const val REPO = "/Dantenothing/openavm-recorder/releases/"
    fun officialUrl(url: String, download: Boolean): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("github.com", true) && uri.port == -1 && uri.userInfo == null &&
            uri.rawPath.startsWith(REPO + if(download) "download/" else "tag/") && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
    fun newest(releases: List<OfficialRelease>, installed: String, includeBeta: Boolean): ReleaseOffer? {
        val current = ReleaseVersion.parse(installed) ?: return null
        return releases.mapNotNull { release ->
            val version = ReleaseVersion.parse(release.tag_name) ?: return@mapNotNull null
            if (release.draft || version.stage == 0 || (!includeBeta && (release.prerelease || version.stage < 3)) ||
                version <= current || !officialUrl(release.html_url, false)) return@mapNotNull null
            val assets = release.assets.filter { asset ->
                asset.name.startsWith("OpenAVM-Recorder-", true) && asset.name.endsWith("-arm64-v8a.apk", true) &&
                    asset.size in 1..50L*1024*1024 && officialUrl(asset.browser_download_url, true)
            }
            if (assets.size != 1) return@mapNotNull null
            version to ReleaseOffer(release, assets.single())
        }.maxByOrNull { it.first }?.second
    }
}

object ReleaseCheck {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()
    fun check(installed: String, includeBeta: Boolean): ReleaseOffer? {
        val request = Request.Builder().url("https://api.github.com/repos/Dantenothing/openavm-recorder/releases?per_page=50")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "OpenAVM-Recorder/$installed")
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val source = response.body?.source() ?: error("EMPTY_RESPONSE")
            require(!source.request(2_000_001)) { "RESPONSE_TOO_LARGE" }
            val releases = Json { ignoreUnknownKeys = true }.decodeFromString<List<OfficialRelease>>(source.readUtf8())
            ReleasePolicy.newest(releases, installed, includeBeta)
        }
    }
}
