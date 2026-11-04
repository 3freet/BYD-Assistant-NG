package com.bydassistantng.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Process

/**
 * A refusal by the car's own permission check, worded for the person in the car, and a line for the log saying who holds the
 * permissions that guard the car's devices.
 *
 * On a DiLink 5.0 car (the log of a BYD Song Plus) the climate, bodywork, setting and instrument device classes refuse the
 * process that asks for an instance of them unless it holds a runtime permission of the car, `BYDAUTO_<DEVICE>_COMMON`
 * (the SDK for these head units says as much: "when getInstance(Context) is used you need to grant the runtime permission").
 * The ADB shell the vehicle helper runs as does not hold them, so `getInstance` throws "Neither user 2000 nor current
 * process has android.permission.BYDAUTO_BODYWORK_COMMON." and, since the call is made by reflection, the exception arrives
 * wrapped in an `InvocationTargetException` that has no message of its own. The tyre device has no such permission, and a
 * read of it from the same helper worked on that car. See docs/byd-internals/hal-and-permissions.md.
 */
internal object PermissionRefusal {
    private val CAR_PERMISSION = Regex("""android\.permission\.(BYDAUTO_[A-Z0-9_]+)""")

    /**
     * The car's permission a [SecurityException] says is missing (`BYDAUTO_BODYWORK_COMMON`), looking through the exceptions that
     * wrap it; null for any other failure.
     */
    fun missingPermission(failure: Throwable?): String? {
        var current = failure
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is SecurityException) CAR_PERMISSION.find(current.message.orEmpty())?.let { return it.groupValues[1] }
            current = (current as? java.lang.reflect.InvocationTargetException)?.targetException ?: current.cause
        }
        return null
    }

    /** The part of the car a permission guards, in plain words. */
    private fun partOf(permission: String): String = when (permission.removePrefix("BYDAUTO_").substringBeforeLast('_')) {
        "BODYWORK" -> "the windows, doors and sunroof"
        "SETTING" -> "the seats, lights and settings"
        "AC" -> "the climate controls"
        "INSTRUMENT" -> "the instrument readings"
        else -> "that part of the car ($permission)"
    }

    /** What the person hears. A command that was refused sent nothing; a question that was refused read nothing. */
    fun message(permission: String, wasCommand: Boolean): String =
        "This car's software does not let this app use ${partOf(permission)}." + if (wasCommand) " Nothing was sent." else ""

    // ── The line for the log ───────────────────────────────────────────────────────────────────

    private val DEVICES = listOf("BODYWORK", "SETTING", "AC", "INSTRUMENT", "AUDIO", "LIGHT", "TYRE")
    private val KINDS = listOf("COMMON", "GET", "SET")

    /** The permissions the line is about: three kinds for each device the app reaches. */
    internal fun permissionNames(): List<String> = DEVICES.flatMap { device -> KINDS.map { kind -> "android.permission.BYDAUTO_${device}_$kind" } }

    /**
     * Who holds the permissions that guard the car's devices, for the log: `NAME:<level>/<helper>/<app>` for each, where
     * `<level>` is how the system defines it (`n` normal, `d` dangerous, `s` signature, `S` signature or system, then `p` if
     * privileged and `v` if a development permission, which `pm grant` can give; `-` if the system does not define it),
     * `<helper>` is `y` or `n` for the process this runs in (the vehicle helper, the ADB shell) and `<app>` for the app's
     * package. A `?` is something that could not be asked.
     */
    fun report(context: Context, appPackage: String): String = permissionNames().joinToString(",") { name ->
        "${name.removePrefix("android.permission.BYDAUTO_")}:${level(context, name)}/${helperHolds(context, name)}/${appHolds(context, name, appPackage)}"
    }

    @Suppress("DEPRECATION")
    private fun level(context: Context, name: String): String = try {
        levelCode(context.packageManager.getPermissionInfo(name, 0).protectionLevel)
    } catch (_: PackageManager.NameNotFoundException) {
        "-"
    } catch (_: Throwable) {
        "?"
    }

    /** The letters for a protection level: the base kind and the flags that matter for granting it. */
    internal fun levelCode(protectionLevel: Int): String {
        val base = when (protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
            PermissionInfo.PROTECTION_NORMAL -> "n"
            PermissionInfo.PROTECTION_DANGEROUS -> "d"
            PermissionInfo.PROTECTION_SIGNATURE -> "s"
            else -> "S"
        }
        val privileged = if (protectionLevel and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) "p" else ""
        val development = if (protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0) "v" else ""
        return base + privileged + development
    }

    private fun helperHolds(context: Context, name: String): String = try {
        yesNo(context.checkPermission(name, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED)
    } catch (_: Throwable) {
        "?"
    }

    private fun appHolds(context: Context, name: String, appPackage: String): String = try {
        yesNo(context.packageManager.checkPermission(name, appPackage) == PackageManager.PERMISSION_GRANTED)
    } catch (_: Throwable) {
        "?"
    }

    private fun yesNo(held: Boolean) = if (held) "y" else "n"
}
