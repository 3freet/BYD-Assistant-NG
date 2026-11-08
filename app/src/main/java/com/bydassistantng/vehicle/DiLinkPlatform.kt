package com.bydassistantng.vehicle

import android.os.Build

/**
 * Which generation of BYD's head-unit software this car runs, as far as the numbers it gives vehicle features go.
 * Each platform has its own profile in the command registry (see `platforms` in `vehicle_commands.json`): the numbers
 * that differ from [DILINK_5], and the commands the platform has no confirmed number for.
 *
 * Two things say which platform it is, and they are used together:
 *  - the car's own **numbers** for a few features (see [PlatformSignature]): DiLink 4 numbers its features like DiLink 5,
 *    and DiLink 3 numbers many of them differently;
 *  - the **Android level**, which splits DiLink 4 (Android 10) from DiLink 5 (newer) once the numbers say "4 or 5".
 *
 * The Android level alone cannot do it: DiLink 3 and DiLink 4 are both Android 10. Nor can the head unit's version
 * properties (see [HeadUnitProperties]): they name the *user interface* generation, which is not the platform.
 */
enum class DiLinkPlatform(val key: String, val label: String) {
    DILINK_3("dilink3", "DiLink 3"),
    DILINK_4("dilink4", "DiLink 4"),
    DILINK_5("dilink5", "DiLink 5"),

    /** The car's numbers matched none of the known schemes. Treated like [DILINK_5], the platform every number was first verified on. */
    UNKNOWN("unknown", "unrecognised"),
    ;

    companion object {
        fun byKey(key: String?): DiLinkPlatform? = entries.find { it.key == key }
    }
}

/** How the car numbers its features. */
enum class FeatureNumbering { DILINK_3, DILINK_4_AND_5 }

/**
 * Three features whose number is expected to tell the two numbering schemes apart. The car's own framework
 * (`BYDAutoFeatureIds`) assigns the numbers, so reading them says which scheme this firmware uses whatever its build
 * strings say. All three must agree, or the platform is not recognised and nothing changes from how the app has always
 * behaved. The DiLink 3 numbers are not confirmed on a car.
 */
internal object PlatformSignature {
    data class Canary(val group: String, val name: String, val dilink3: Int, val dilink4And5: Int)

    val canaries: List<Canary> = listOf(
        Canary("Ac", "AC_CTRL_MODE", dilink3 = 330301458, dilink4And5 = 1077936146),
        Canary("Ac", "AC_CYCLE_MODE", dilink3 = 330301460, dilink4And5 = 1077936148),
        Canary("Ac", "AC_DEFROST_FRONT_STATE", dilink3 = 330301462, dilink4And5 = 1077936150),
    )

    /** The scheme the car's numbers for [canaries] (in that order; null where the car has none) belong to, or null. */
    fun numbering(readings: List<Int?>): FeatureNumbering? {
        if (readings.size != canaries.size) return null
        return when {
            canaries.indices.all { readings[it] == canaries[it].dilink3 } -> FeatureNumbering.DILINK_3
            canaries.indices.all { readings[it] == canaries[it].dilink4And5 } -> FeatureNumbering.DILINK_4_AND_5
            else -> null
        }
    }

    /** What this process can read of the car's numbers for [canaries]: all null off a BYD head unit. */
    fun read(): List<Int?> = canaries.map { CarFeatureIds.idOf(it.group, it.name) }
}

/**
 * What the head unit's own system properties say about itself, read for the log. `ro.vehicle.type` names the user
 * interface generation ("Di5.0" or "Di5.1", also spelled "DiLink100" and "DiLink150"), **not the platform**: a DiLink 3.0
 * unit (Android 10) can run the 5.0 interface and then holds `DiLink50_5.0UI`. So these are never what decides the
 * platform. They say nothing about the owner: the properties that hold a VIN, the car's name or its region are not read.
 */
internal object HeadUnitProperties {
    val KEYS = listOf("ro.vehicle.type", "ro.byd.dilink.version", "ro.dilink.version", "ro.product.model")

    /** A system property, or "" where it is not set or this process may not read it. */
    fun read(key: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun readAll(): Map<String, String> = KEYS.associateWith { read(it) }
}

internal object PlatformDetector {
    /** The last Android level that DiLink 3 and 4 run (Android 10); DiLink 5.0 is Android 12 and 5.1 Android 13. */
    const val LAST_ANDROID_10_SDK = 29

    /**
     * The platform that [readings] (the car's numbers for [PlatformSignature.canaries]) and the Android level [sdkInt]
     * describe. Numbers that no known scheme uses, or that this process cannot read, leave it not recognised.
     */
    fun classify(sdkInt: Int, readings: List<Int?>): DiLinkPlatform = when (PlatformSignature.numbering(readings)) {
        FeatureNumbering.DILINK_3 -> DiLinkPlatform.DILINK_3
        FeatureNumbering.DILINK_4_AND_5 -> if (sdkInt <= LAST_ANDROID_10_SDK) DiLinkPlatform.DILINK_4 else DiLinkPlatform.DILINK_5
        null -> DiLinkPlatform.UNKNOWN
    }

    /** `Di3.0` in `Di3.0_3.5UI`: the platform part of `ro.vehicle.type` when it is written as a version. */
    private val OWN_NAME = Regex("^Di(\\d)\\.(\\d)", RegexOption.IGNORE_CASE)

    /**
     * What the log calls the platform: [platform], as the numbers and the Android level read it, and where the car's own
     * `ro.vehicle.type` ([vehicleType]) names its platform as a version, that name. A DiLink 3.0 head unit (`Di3.0_3.5UI`, Android
     * 10) was seen with the numbers of the newer scheme, which the numbers and the Android level alone call DiLink 4, so the log
     * says `DiLink 3.0 (numbers read as DiLink 4)`. Only the words change: what the app does follows [platform], and a property
     * that is not written as a version (a unit that holds `DiLink50_5.0UI`) is left out of it, as ever.
     */
    fun label(platform: DiLinkPlatform, vehicleType: String?): String {
        val own = OWN_NAME.find(vehicleType.orEmpty().trim()) ?: return platform.label
        val name = "DiLink ${own.groupValues[1]}.${own.groupValues[2]}"
        return when {
            platform == DiLinkPlatform.UNKNOWN -> "$name (numbers not recognised)"
            platform.label == "DiLink ${own.groupValues[1]}" -> name
            else -> "$name (numbers read as ${platform.label})"
        }
    }

    /** The `ro.vehicle.type` the helper wrote into its line (`props=vehicle.type:Di3.0_3.5UI,...`), or null. */
    fun vehicleTypeIn(details: String): String? =
        Regex("(?:^|[ ,=])vehicle\\.type:([^, ]+)").find(details)?.groupValues?.get(1)?.takeIf { it != "-" }

    /** The platform as this process can tell it from the car's numbers and the Android level. */
    fun detect(): DiLinkPlatform = classify(
        Build.VERSION.SDK_INT,
        runCatching { PlatformSignature.read() }.getOrElse { PlatformSignature.canaries.map { null } },
    )

    /**
     * The platform the app uses before the vehicle helper (which can always read the car's numbers) has said what it
     * found: what this process could tell itself, else what the helper last reported for this firmware.
     */
    fun choose(detected: DiLinkPlatform, saved: DiLinkPlatform?): DiLinkPlatform =
        if (detected != DiLinkPlatform.UNKNOWN) detected else saved ?: DiLinkPlatform.UNKNOWN
}
