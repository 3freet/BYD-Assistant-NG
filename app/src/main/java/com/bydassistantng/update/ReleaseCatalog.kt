package com.bydassistantng.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One published build that could be installed. */
data class UpdateRelease(
    val version: AppVersion,
    val tag: String,
    val prerelease: Boolean,
    /** Release notes as published (markdown), already cut to a displayable length. */
    val notes: String,
    /** ISO-8601 time the release was published, e.g. `2026-10-10T12:00:00Z`; empty if unknown. */
    val publishedAt: String,
    /** The release's web page. */
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 of the APK as GitHub computed it when the file was uploaded; null if not offered. */
    val sha256: String?,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    val size: Long = 0L,
    val digest: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

/** Reads GitHub's list-releases response and picks the newest build a channel offers. */
object ReleaseCatalog {
    private const val MAX_NOTES_CHARS = 6_000
    private val json = Json { ignoreUnknownKeys = true }

    /** Every installable release in [body]. Drafts, tags that aren't versions of this app and releases without an APK are skipped. */
    fun parse(body: String): List<UpdateRelease> =
        json.decodeFromString<List<GitHubRelease>>(body).mapNotNull { release ->
            if (release.draft) return@mapNotNull null
            val version = AppVersion.parse(release.tagName) ?: return@mapNotNull null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return@mapNotNull null
            UpdateRelease(
                version = version,
                tag = release.tagName,
                prerelease = release.prerelease,
                notes = (release.body ?: "").trim().take(MAX_NOTES_CHARS),
                publishedAt = release.publishedAt ?: "",
                pageUrl = release.htmlUrl,
                apkUrl = apk.browserDownloadUrl,
                apkSize = apk.size,
                sha256 = parseDigest(apk.digest),
            )
        }

    /** The newest release [channel] offers, whatever its age; null when it has none. */
    fun latest(releases: List<UpdateRelease>, channel: UpdateChannel): UpdateRelease? =
        releases.filter { channel.accepts(it) }.maxByOrNull { it.version }

    /** GitHub writes an asset's digest as `sha256:<hex>`. */
    fun parseDigest(digest: String?): String? {
        val hex = digest?.trim()?.removePrefix("sha256:")?.lowercase() ?: return null
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}

/** Turns the markdown of a release page into plain lines fit for a small screen. */
object ReleaseNotes {
    private val byAuthor = Regex("""\s+by @\S+ in \S+""")
    private val bullet = Regex("""^[*\-]\s+""")
    private val bold = Regex("""\*\*|__|`""")

    fun plain(markdown: String): String =
        markdown.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("**Full Changelog**") || it.startsWith("Full Changelog") }
            .map { line ->
                var text = line.trimStart('#').trim().replace(byAuthor, "").replace(bold, "")
                if (bullet.containsMatchIn(line)) text = "• " + text.replaceFirst(bullet, "").removePrefix("beta:").trim()
                text
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
