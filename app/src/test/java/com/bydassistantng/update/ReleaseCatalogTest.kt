package com.bydassistantng.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCatalogTest {
    private val digest = "a".repeat(64)

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, asset: String? = "app.apk", published: String = "2026-10-10T12:00:00Z", body: String = "notes") = """
        {
          "tag_name": "$tag", "draft": $draft, "prerelease": $prerelease, "body": "$body",
          "published_at": "$published", "html_url": "https://github.com/o/r/releases/tag/$tag",
          "unknown_field": {"ignored": true},
          "assets": [ ${if (asset == null) "" else """{"name": "$asset", "size": 1234, "digest": "sha256:$digest", "browser_download_url": "https://github.com/o/r/releases/download/$tag/$asset"}"""} ]
        }
    """.trimIndent()

    private fun catalog(vararg releases: String) = ReleaseCatalog.parse("[" + releases.joinToString(",") + "]")

    @Test
    fun readsTheFieldsOfARelease() {
        val parsed = catalog(release("v1.1.0-beta.2", prerelease = true)).single()
        assertEquals(AppVersion(1, 1, 0, 2), parsed.version)
        assertEquals("v1.1.0-beta.2", parsed.tag)
        assertTrue(parsed.prerelease)
        assertEquals("https://github.com/o/r/releases/download/v1.1.0-beta.2/app.apk", parsed.apkUrl)
        assertEquals(1234L, parsed.apkSize)
        assertEquals(digest, parsed.sha256)
        assertEquals("2026-10-10T12:00:00Z", parsed.publishedAt)
    }

    @Test
    fun skipsDraftsReleasesWithoutAnApkAndTagsThatAreNotVersions() {
        val parsed = catalog(
            release("v1.0.0", draft = true),
            release("v1.0.1", asset = null),
            release("v1.0.2", asset = "notes.txt"),
            release("nightly"),
            release("v1.0.3"),
        )
        assertEquals(listOf("v1.0.3"), parsed.map { it.tag })
    }

    @Test
    fun theStableChannelNeverSeesABeta() {
        val all = catalog(release("v1.1.0-beta.3", prerelease = true), release("v1.0.0"), release("v1.1.0-beta.2", prerelease = true))
        assertEquals("v1.0.0", ReleaseCatalog.latest(all, UpdateChannel.STABLE)?.tag)
    }

    @Test
    fun theBetaChannelOffersWhicheverIsNewestBetaOrStable() {
        val betaAhead = catalog(release("v1.0.0"), release("v1.1.0-beta.1", prerelease = true), release("v1.1.0-beta.2", prerelease = true))
        assertEquals("v1.1.0-beta.2", ReleaseCatalog.latest(betaAhead, UpdateChannel.BETA)?.tag)
        val stableCaughtUp = catalog(release("v1.1.0"), release("v1.1.0-beta.2", prerelease = true), release("v1.1.0-beta.1", prerelease = true))
        assertEquals("v1.1.0", ReleaseCatalog.latest(stableCaughtUp, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun aChannelWithNothingPublishedOffersNothing() {
        assertNull(ReleaseCatalog.latest(catalog(release("v1.1.0-beta.1", prerelease = true)), UpdateChannel.STABLE))
        assertNull(ReleaseCatalog.latest(emptyList(), UpdateChannel.BETA))
    }

    @Test
    fun theDigestMustBeAProperSha256() {
        assertEquals(digest, ReleaseCatalog.parseDigest("sha256:$digest"))
        assertEquals(digest, ReleaseCatalog.parseDigest("sha256:" + digest.uppercase()))
        assertNull(ReleaseCatalog.parseDigest(null))
        assertNull(ReleaseCatalog.parseDigest("sha1:abcd"))
        assertNull(ReleaseCatalog.parseDigest("sha256:abc"))
        assertNull(ReleaseCatalog.parseDigest("sha256:" + "z".repeat(64)))
    }

    @Test
    fun aReleaseWithoutADigestIsStillOffered() {
        val body = """[{"tag_name":"v1.0.0","assets":[{"name":"a.apk","size":5,"browser_download_url":"https://github.com/o/r/releases/download/v1.0.0/a.apk"}]}]"""
        assertNull(ReleaseCatalog.parse(body).single().sha256)
    }

    @Test
    fun longNotesAreCut() {
        val parsed = catalog(release("v1.0.0", body = "x".repeat(20_000))).single()
        assertEquals(6_000, parsed.notes.length)
    }

    @Test
    fun garbageIsReportedNotSwallowed() {
        var failed = false
        try {
            ReleaseCatalog.parse("<html>rate limited</html>")
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertNotNull(catalog())
    }
}

class ReleaseNotesTest {
    @Test
    fun turnsCommitListsAndGeneratedNotesIntoPlainLines() {
        val markdown = """
            ## What's Changed
            * beta: fix Vosk crash by keeping JNA fields from R8 obfuscation by @someone in https://github.com/o/r/pull/3
            - add offline speech recognition
            **Full Changelog**: https://github.com/o/r/compare/v1.0.0...v1.1.0
        """.trimIndent()
        assertEquals(
            "What's Changed\n• fix Vosk crash by keeping JNA fields from R8 obfuscation\n• add offline speech recognition",
            ReleaseNotes.plain(markdown),
        )
    }

    @Test
    fun stripsMarkdownEmphasisAndExtraBlankLines() {
        assertEquals("A bold idea\n\nnext", ReleaseNotes.plain("A **bold** idea\n\n\n\n`next`"))
    }

    @Test
    fun emptyNotesStayEmpty() {
        assertEquals("", ReleaseNotes.plain("   "))
    }
}
