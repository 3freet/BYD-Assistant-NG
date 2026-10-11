package com.bydassistantng.vehicle

/**
 * What the car's own framework calls a feature. The ids were verified on one car; another model or firmware may
 * number the same name differently, so a feature that writes to the car is only offered, and only written, when
 * the car's framework gives its name the id it was verified with.
 */
internal object CarFeatureIds {
    private const val IDS_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds"

    /** The id the car gives [name] in its group [group] ("Setting", "Adas"...), or null when it has none. */
    fun idOf(group: String, name: String): Int? = try {
        Class.forName(IDS_CLASS).declaredClasses
            .firstOrNull { it.simpleName == group }
            ?.getDeclaredField(name)
            ?.getInt(null)
    } catch (_: Throwable) {
        null
    }
}
