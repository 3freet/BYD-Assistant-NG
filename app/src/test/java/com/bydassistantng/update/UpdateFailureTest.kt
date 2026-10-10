package com.bydassistantng.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFailureTest {
    @Test
    fun aRateLimitedRequestIsTold_apartFromAForbiddenOne() {
        assertEquals(UpdateFailure.Kind.RATE_LIMITED, UpdateFailure.forHttp(403, "0").kind)
        assertEquals(UpdateFailure.Kind.RATE_LIMITED, UpdateFailure.forHttp(429, null).kind)
        assertEquals(UpdateFailure.Kind.SERVER_ERROR, UpdateFailure.forHttp(403, "42").kind)
        assertEquals("HTTP 500", UpdateFailure.forHttp(500, null).detail)
    }

    @Test
    fun readsWhatPmInstallPrinted() {
        assertNull(UpdateFailure.fromInstallOutput("Success\nStarting: Intent { cmp=com.x/.Main }"))
        assertEquals(UpdateFailure.Kind.NOT_NEWER, UpdateFailure.fromInstallOutput("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")?.kind)
        assertEquals(UpdateFailure.Kind.WRONG_SIGNATURE, UpdateFailure.fromInstallOutput("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match]")?.kind)
        assertEquals(UpdateFailure.Kind.WRONG_SIGNATURE, UpdateFailure.fromInstallOutput("Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]")?.kind)
        assertEquals(UpdateFailure.Kind.NOT_ENOUGH_SPACE, UpdateFailure.fromInstallOutput("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")?.kind)
        val other = UpdateFailure.fromInstallOutput("Failure [INSTALL_FAILED_INVALID_APK: bad]")
        assertEquals(UpdateFailure.Kind.INSTALL_REJECTED, other?.kind)
        assertEquals("INSTALL_FAILED_INVALID_APK", other?.detail)
        assertNotNull(UpdateFailure.fromInstallOutput(""))
        assertEquals("no output", UpdateFailure.fromInstallOutput("")?.detail)
    }

    @Test
    fun anUpdateIsOnlyEverDownloadedFromTheRepositorysOwnReleases() {
        val source = UpdateSource.forRepo("owner/app")!!
        assertTrue(source.allowsDownload("https://github.com/owner/app/releases/download/v1.0.0/app.apk".toHttpUrl()))
        assertFalse(source.allowsDownload("http://github.com/owner/app/releases/download/v1.0.0/app.apk".toHttpUrl()))
        assertFalse(source.allowsDownload("https://github.com/other/app/releases/download/v1.0.0/app.apk".toHttpUrl()))
        assertFalse(source.allowsDownload("https://github.com/owner/app/archive/v1.0.0.zip".toHttpUrl()))
        assertFalse(source.allowsDownload("https://evil.example/owner/app/releases/download/v1.0.0/app.apk".toHttpUrl()))
        assertTrue(source.allowsRedirectTarget("https://release-assets.githubusercontent.com/x".toHttpUrl()))
        assertFalse(source.allowsRedirectTarget("https://evil.example/x".toHttpUrl()))
        assertFalse(source.allowsRedirectTarget("http://objects.githubusercontent.com/x".toHttpUrl()))
    }

    @Test
    fun onlyRealRepositoryNamesAreAccepted() {
        assertNotNull(UpdateSource.forRepo("3freet/BYD-Assistant-NG"))
        assertNull(UpdateSource.forRepo(""))
        assertNull(UpdateSource.forRepo("noslash"))
        assertNull(UpdateSource.forRepo("a/b/c"))
        assertNull(UpdateSource.forRepo("a/b c"))
    }
}
