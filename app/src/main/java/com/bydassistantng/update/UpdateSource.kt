package com.bydassistantng.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where updates come from: the GitHub releases of one repository, named by the build (see `assistant.updateRepo`
 * in app/build.gradle.kts). An update is only ever downloaded from that repository's own release assets.
 */
class UpdateSource private constructor(
    val repo: String,
    /** The list-releases API URL. */
    val releasesUrl: String,
    private val anyHost: Boolean,
) {
    /** Whether [url] is one of this repository's release downloads (always over https). */
    fun allowsDownload(url: HttpUrl): Boolean =
        anyHost || (url.isHttps && url.host == "github.com" && url.encodedPath.startsWith("/$repo/releases/download/"))

    /** Whether a download that was redirected ends up on a host that serves GitHub release files. */
    fun allowsRedirectTarget(url: HttpUrl): Boolean =
        anyHost || (url.isHttps && (url.host == "github.com" || url.host.endsWith(".githubusercontent.com")))

    companion object {
        private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

        /** The source for `owner/name`, or null when [repo] isn't one (an empty value means "this build has no updates"). */
        fun forRepo(repo: String): UpdateSource? =
            if (REPO.matches(repo)) UpdateSource(repo, "https://api.github.com/repos/$repo/releases?per_page=30", anyHost = false) else null

        /** A source at any URL, for exercising the update flow against a local server from a debug build. */
        fun forTesting(releasesUrl: String): UpdateSource? =
            releasesUrl.toHttpUrlOrNull()?.let { UpdateSource("test/test", releasesUrl, anyHost = true) }
    }
}
