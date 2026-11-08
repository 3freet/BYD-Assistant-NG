package com.bydassistantng.update

import android.content.pm.PackageManager

/**
 * How the updater asks the system for the signing certificates of an APK, the downloaded file and the installed app, so that
 * the two can be compared before the install is started.
 *
 * `getPackageArchiveInfo`, the call that reads a file that is not installed, fills in the certificates only for the flags
 * it is given. On Android 9 and 10 it collects them only when [PackageManager.GET_SIGNATURES] is among the flags: asked
 * for [PackageManager.GET_SIGNING_CERTIFICATES] alone it returns neither `signingInfo` nor `signatures`. Android 11 and later
 * read them for either flag. A head unit on Android 10 (DiLink 3 and 4) therefore showed the downloaded file with no
 * certificate at all, which the check took for a different signature, and every update was refused with `WRONG_SIGNATURE`
 * before any install was tried (seen in a DiLink 3.0 car's log, 2026-11-07, and reproduced on a stock Android 10 emulator, where
 * the two flags together gave the certificate and either alone did not).
 */
internal object ApkSigners {
    /** The first Android level with `PackageInfo.signingInfo` (Android 9). */
    private const val FIRST_WITH_SIGNING_INFO = 28

    /** The first Android level at which `getPackageArchiveInfo` reads the certificates for the current flag alone (Android 11). */
    private const val FIRST_THAT_READS_FILES_BY_CURRENT_FLAG = 30

    @Suppress("DEPRECATION")
    private const val LEGACY = PackageManager.GET_SIGNATURES
    private const val CURRENT = PackageManager.GET_SIGNING_CERTIFICATES

    /** The flags that make the system read the certificates of an APK **file**. */
    fun archiveFlags(sdkInt: Int): Int = when {
        sdkInt >= FIRST_THAT_READS_FILES_BY_CURRENT_FLAG -> CURRENT
        sdkInt >= FIRST_WITH_SIGNING_INFO -> CURRENT or LEGACY
        else -> LEGACY
    }

    /** The flags that make the system read the certificates of an **installed** app, which every level with `signingInfo` fills in. */
    fun installedFlags(sdkInt: Int): Int = if (sdkInt >= FIRST_WITH_SIGNING_INFO) CURRENT else LEGACY

    /** The certificates: those of the `SigningInfo` when it has any, else the legacy list; empty where the system gave none. */
    fun <T> signersOf(fromSigningInfo: Collection<T>?, legacy: Collection<T>?): Set<T> =
        fromSigningInfo?.takeIf { it.isNotEmpty() }?.toSet() ?: legacy?.toSet().orEmpty()
}
