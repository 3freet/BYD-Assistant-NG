package com.bydassistantng.vehicle

import java.lang.reflect.Modifier

/**
 * The car's own table of features: every number of its `BYDAutoFeatureIds` class by name (`Group.NAME`, or just `NAME` for a
 * constant of the class itself). The class chooses its numbers when it loads, from the car's bus protocol (see
 * `docs/byd-internals/platforms.md`), so a name is what stays the same from car to car and the number is what this car
 * says it is. The vehicle helper reads it once, as the ADB shell, and the registry looks every command up in it
 * ([NameResolution]).
 */
internal object CarFeatureTable {
    private const val IDS_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds"

    /** The table, or null where the car has no such class or it cannot be read. */
    fun read(loader: ClassLoader? = CarFeatureTable::class.java.classLoader): Map<String, Int>? = try {
        val ids = Class.forName(IDS_CLASS, false, loader)
        val table = HashMap<String, Int>()
        fun collect(type: Class<*>, prefix: String) {
            for (field in constantsOf(type)) {
                field.isAccessible = true
                val value = (field.get(null) as? Number)?.toLong() ?: continue
                if (value in Int.MIN_VALUE..Int.MAX_VALUE) table.putIfAbsent(prefix + field.name, value.toInt())
            }
        }
        collect(ids, "")
        for (group in ids.declaredClasses) collect(group, group.simpleName + ".")
        table
    } catch (_: Throwable) {
        null
    }

    /** The static integer constants a class declares. */
    fun constantsOf(type: Class<*>): List<java.lang.reflect.Field> = type.declaredFields.filter {
        Modifier.isStatic(it.modifiers) && (it.type == Int::class.javaPrimitiveType || it.type == Long::class.javaPrimitiveType)
    }

    /** The window features of the car's own table with their numbers, so that what a car calls its windows can be read from a log. */
    fun windowFeatures(loader: ClassLoader? = CarFeatureTable::class.java.classLoader): String {
        val ids = Class.forName(IDS_CLASS, false, loader)
        val bodywork = ids.declaredClasses.firstOrNull { it.simpleName == "Bodywork" } ?: return "no-bodywork-group"
        return constantsOf(bodywork)
            .filter { "WINDOW" in it.name }
            .sortedBy { it.name }
            .joinToString(",") { field ->
                field.isAccessible = true
                "${field.name}=${field.get(null)}"
            }
    }
}
