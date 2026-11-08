package com.bydassistantng.update

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class ApkSignersTest {
    private val current = PackageManager.GET_SIGNING_CERTIFICATES
    private val legacy = PackageManager.GET_SIGNATURES

    @Test
    fun aFileOnAndroid10IsReadWithBothFlagsBecauseTheCurrentFlagAloneGivesNoCertificate() {
        // Android 9 and 10: getPackageArchiveInfo collects certificates only for GET_SIGNATURES. Seen on a DiLink 3.0 head unit
        // (the update was refused as WRONG_SIGNATURE before any install) and on a stock Android 10 emulator.
        for (sdk in listOf(28, 29)) {
            val flags = ApkSigners.archiveFlags(sdk)
            assertTrue("sdk $sdk: the legacy flag is needed", flags and legacy != 0)
            assertTrue("sdk $sdk: the current flag stays", flags and current != 0)
        }
        assertNotEquals("the current flag alone is what failed on Android 10", current, ApkSigners.archiveFlags(29))
    }

    @Test
    fun aFileOnAndroid11OrLaterKeepsTheCurrentFlagAlone() {
        for (sdk in listOf(30, 31, 33, 34, 35)) assertEquals("sdk $sdk", current, ApkSigners.archiveFlags(sdk))
    }

    @Test
    fun beforeAndroid9OnlyTheLegacySignaturesExist() {
        for (sdk in listOf(21, 26, 27)) {
            assertEquals("sdk $sdk (file)", legacy, ApkSigners.archiveFlags(sdk))
            assertEquals("sdk $sdk (installed)", legacy, ApkSigners.installedFlags(sdk))
        }
    }

    @Test
    fun anInstalledAppIsReadWithTheCurrentFlagFromAndroid9() {
        for (sdk in listOf(28, 29, 30, 33)) assertEquals("sdk $sdk", current, ApkSigners.installedFlags(sdk))
    }

    @Test
    fun theSigningInfoCertificatesAreUsedWhenThereAreAny() {
        assertEquals(setOf("a", "b"), ApkSigners.signersOf(listOf("a", "b"), listOf("legacy")))
    }

    @Test
    fun theLegacyListIsUsedWhenTheSigningInfoIsMissingOrEmpty() {
        assertEquals(setOf("legacy"), ApkSigners.signersOf(null, listOf("legacy")))
        assertEquals(setOf("legacy"), ApkSigners.signersOf(emptyList(), listOf("legacy")))
    }

    @Test
    fun noCertificateAtAllStaysEmptySoTheUpdateIsStillRefused() {
        // What Android 10 gave for the current flag alone: nothing. The check must not take "nothing" for "the same".
        assertTrue(ApkSigners.signersOf<String>(null, null).isEmpty())
        assertTrue(ApkSigners.signersOf<String>(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun twoAppsSignedWithTheSameKeyHaveEqualSignerSetsWhicheverListTheyCameFrom() {
        // The installed app answered from its SigningInfo, the downloaded file from the legacy list (or the other way round).
        assertEquals(ApkSigners.signersOf(listOf("key"), null), ApkSigners.signersOf(null, listOf("key")))
    }
}
