package com.bydassistantng.ota

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
)

/** App-domain model built manually from [GitHubRelease]/[GitHubAsset] — not deserialized directly. */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val apkName: String,
    val size: Long = 0L,
)
