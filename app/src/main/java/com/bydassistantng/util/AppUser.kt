package com.bydassistantng.util

import android.os.Process

/**
 * Which Android user this process runs as.
 *
 * The car has a second user (999, the passenger screen's) and its package manager puts a copy of an app into it
 * whenever the app is installed over ADB. That copy has storage of its own, and so an ADB key of its own that the car
 * has never been asked about: at every boot the head unit sends it the boot broadcast, it connects, and the car shows an
 * "Allow USB debugging?" prompt that nobody can usefully answer. Nothing the assistant does is meant for that user, so
 * everything that starts by itself (the boot receiver, the app's start-up work) and everything that reaches ADB does
 * nothing there.
 */
object AppUser {
    /** The size of the range of uids each user gets (a uid is the user's number times this, plus the app's). */
    private const val PER_USER_RANGE = 100_000

    /** The car's second Android user, the one the passenger screen runs. */
    const val SECOND_USER = 999

    /** The number of the user the uid [uid] belongs to; 0 is the main user. */
    fun idOf(uid: Int): Int = uid / PER_USER_RANGE

    /** Whether the process is the main user's (the only one the assistant works for). */
    fun isMain(uid: Int = Process.myUid()): Boolean = idOf(uid) == 0
}
