package com.bydassistantng.util

import com.bydassistantng.update.installCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUserTest {
    @Test
    fun theMainUsersAppsAreTheOnesInTheLowestUidRange() {
        assertTrue(AppUser.isMain(10234))
        assertTrue(AppUser.isMain(1000))
        assertEquals(0, AppUser.idOf(10234))
    }

    @Test
    fun theCarsSecondUserIsNotTheMainUser() {
        // Uid of an app in user 999: 999 * 100000 + the app's own number.
        assertEquals(AppUser.SECOND_USER, AppUser.idOf(99_910_234))
        assertFalse(AppUser.isMain(99_910_234))
    }

    @Test
    fun anyOtherUserIsNotTheMainUserEither() {
        assertEquals(10, AppUser.idOf(1_010_234))
        assertFalse(AppUser.isMain(1_010_234))
    }

    @Test
    fun theUpdateInstallsForTheMainUserOnlyAndTakesOutTheSecondUsersCopy() {
        val command = installCommand("com.bydassistantng", "/sdcard/Download/update.apk", 12_345)
        assertEquals(
            "pm uninstall --user 999 com.bydassistantng > /dev/null 2>&1; cat /sdcard/Download/update.apk | pm install -r --user 0 -S 12345",
            command,
        )
    }
}
