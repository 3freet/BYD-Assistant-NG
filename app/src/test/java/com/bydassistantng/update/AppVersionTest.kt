package com.bydassistantng.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun readsStableAndBetaVersionsWithOrWithoutTheV() {
        assertEquals(AppVersion(1, 2, 3), v("1.2.3"))
        assertEquals(AppVersion(1, 2, 3), v("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3, beta = 4), v("v1.2.3-beta.4"))
        assertEquals("1.2.3-beta.4", v("1.2.3-beta.4").toString())
    }

    @Test
    fun refusesWhatIsNotAVersionOfThisApp() {
        for (text in listOf("", "1.2", "1.2.3.4", "1.2.3-dev", "1.2.3-rc.1", "1.2.3-beta", "1.2.3-beta.0", "1.2.3-beta.999", "100.0.0", "stable-12", "1.2.x")) {
            assertNull("'$text' must not parse", AppVersion.parse(text))
        }
    }

    @Test
    fun aBetaSortsBelowTheStableVersionItLeadsUpTo() {
        val order = listOf("1.0.0", "1.1.0-beta.1", "1.1.0-beta.2", "1.1.0-beta.10", "1.1.0", "1.1.1-beta.1", "1.1.1", "1.2.0-beta.1", "2.0.0").map(::v)
        assertEquals(order, order.shuffled().sorted())
    }

    @Test
    fun versionCodeAlwaysRisesWithTheVersion() {
        val order = listOf("1.0.0", "1.1.0-beta.1", "1.1.0-beta.2", "1.1.0-beta.10", "1.1.0", "1.1.1-beta.1", "1.1.1", "1.2.0-beta.1", "2.0.0", "99.99.99").map(::v)
        for ((older, newer) in order.zipWithNext()) {
            assertTrue("${older.code} should be below ${newer.code} ($older -> $newer)", older.code < newer.code)
        }
    }

    @Test
    fun versionCodeMatchesTheFormulaInTheBuildFile() {
        // app/build.gradle.kts: major * 10_000_000 + minor * 100_000 + patch * 1_000 + (beta ?: 999)
        assertEquals(10_100_999, v("1.1.0").code)
        assertEquals(10_100_003, v("1.1.0-beta.3").code)
        assertEquals(20_000_999, v("2.0.0").code)
        assertEquals(10_001_999, v("1.0.1").code)
    }

    @Test
    fun theChannelsOfferWhatTheyShould() {
        fun release(version: String, prerelease: Boolean = false) =
            UpdateRelease(v(version), "v$version", prerelease, "", "", "", "https://example.invalid/a.apk", 1, null)
        val beta = release("1.1.0-beta.2", prerelease = true)
        val stable = release("1.1.0")
        val markedPrerelease = release("1.2.0", prerelease = true)
        assertTrue(UpdateChannel.BETA.accepts(beta) && UpdateChannel.BETA.accepts(stable))
        assertTrue(UpdateChannel.STABLE.accepts(stable))
        assertTrue(!UpdateChannel.STABLE.accepts(beta))
        assertTrue("a release flagged as pre-release is never stable", !UpdateChannel.STABLE.accepts(markedPrerelease))
    }

    @Test
    fun theInstalledBuildsChannelIsReadFromItsName() {
        assertEquals(UpdateChannel.BETA, UpdateChannel.ofVersionName("1.1.0-beta.2"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.ofVersionName("1.1.0"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.ofVersionName("1.1.0-dev"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromId("beta"))
        assertNull(UpdateChannel.fromId("nightly"))
    }
}
