package com.bydassistantng.vehicle

import android.os.Build

/**
 * One line about the car's software for the app log, written by the vehicle helper (which, running as the ADB shell,
 * can read what the app itself may not). When a car's commands do not work, this is what says why without anyone
 * needing a cable: the head unit's identification, which device classes and generic calls the car has, which system
 * services answer, how many features its own table holds, and **how the numbers this app uses compare with that table by
 * name** (see [FeatureNameCheck]): which agree, which the car numbers differently (with the car's own number) and which
 * names it does not have at all. When a window name is among the differences, the car's window features are listed too.
 * Where the car has no A/C service (see [AcRoute]) the line says how the A/C is reached instead, and names the car's other
 * services that look like they have to do with its climate or its vehicle systems, to find where it lives. It also says who
 * holds the permissions that guard the car's devices (`perms=`), since a car may refuse the helper for want of one, and whether
 * the car's native service can be spoken to directly and agrees with the device classes (`native=`).
 *
 * Writing the line only looks; nothing is written to the car. (What the app makes of the comparison is [NameResolution]'s: the
 * car's own numbers are used, and a command the car lacks a name for is withheld; the line says which.) Each part is read on
 * its own, so one that fails shows as "?" and the others still come through.
 */
internal object CarDiagnostics {
    private const val BASE_CLASS = "android.hardware.bydauto.AbsBYDAutoDevice"

    /**
     * [readings] are the car's numbers for [PlatformSignature.canaries], [properties] what [HeadUnitProperties] read and [table]
     * the car's own table of features ([CarFeatureTable.read]; null where it cannot be read, and then the registry's own numbers
     * are in use). [acRoute] is how the car's climate system is reached (null where the helper did not look) and [permissions]
     * who holds the permissions that guard the car's devices ([PermissionRefusal.report]; null where it was not asked).
     */
    fun describe(
        readings: List<Int?>,
        properties: Map<String, String>,
        table: Map<String, Int>?,
        acRoute: AcRoute? = null,
        permissions: String? = null,
        native: String? = null,
    ): String {
        val results = table?.let { FeatureNameCheck.check(FeatureNameCheck.references(), it) }
        return buildString {
            append("sdk=").append(Build.VERSION.SDK_INT)
            append(" release=").append(Build.VERSION.RELEASE)
            append(" props=").append(properties(properties))
            append(" numbers=").append(numbers(readings))
            append(" constants=").append(table?.size ?: "?")
            append(" classes=").append(classes())
            append(" calls=").append(calls())
            append(" services=").append(services())
            if (acRoute != null) {
                append(" acroute=").append(acRoute.key)
                // A car that is reached through the device reads the A/C's states by name too: the ones its table lacks.
                if (acRoute == AcRoute.DEVICE && table != null) {
                    val lacking = VehicleCommandRegistry.deviceRouteNames().filter { it !in table }
                    if (lacking.isNotEmpty()) append(" acabsent=").append(lacking.joinToString(","))
                }
                if (acRoute != AcRoute.SERVICE) relatedServices(SystemServices.names())?.let { append(" others=").append(it) }
                // The climate service under its other name, with what its binder says it speaks: the commands use it only if that is theirs.
                SystemServices.descriptorOf(AcService.ALIAS)?.let { append(" acalias=").append(it.replace(Regex("[^A-Za-z0-9._$-]"), "_").take(60)) }
                if (AcService.name != AcService.PRIMARY) append(" acservice=").append(AcService.name)
            }
            permissions?.let { append(" perms=").append(it) }
            native?.let { append(" native=").append(it) }
            append(' ').append(results?.let { FeatureNameCheck.summary(it, VehicleCommandRegistry.unavailable.keys) } ?: "names=?")
            if (results != null && FeatureNameCheck.windowsDiffer(results)) append(" windows=").append(attempt { CarFeatureTable.windowFeatures() })
        }
    }

    /** The head unit's own identification, one `name:value` each, with nothing in a value that would break the line. */
    internal fun properties(properties: Map<String, String>): String = HeadUnitProperties.KEYS.joinToString(",") { key ->
        val value = properties[key].orEmpty().trim().replace(Regex("\\s+"), "_").take(60)
        "${key.removePrefix("ro.")}:${value.ifEmpty { "-" }}"
    }

    internal fun numbers(readings: List<Int?>): String =
        PlatformSignature.canaries.zip(readings).joinToString(",") { (canary, reading) -> "${canary.name}:${reading ?: "?"}" }

    private fun <T> attempt(read: () -> T): String = try {
        read().toString()
    } catch (_: Throwable) {
        "?"
    }

    private fun load(name: String): Class<*>? = try {
        Class.forName(name, false, javaClass.classLoader)
    } catch (_: Throwable) {
        null
    }

    private fun classes(): String {
        val named = ReflectionVehicleController.GENERIC_ROUTE_CLASS_BY_DOMAIN.map { (domain, name) -> domain.name.lowercase() to name } +
            ("ac" to ReflectionVehicleController.AC_DEVICE_CLASS)
        return named.joinToString(",") { (short, name) -> (if (load(name) != null) "" else "-") + short }
    }

    /** Where the generic `get` and `set` the commands go through are found, walking up from a device class. */
    private fun calls(): String {
        val device = load(ReflectionVehicleController.GENERIC_ROUTE_CLASS_BY_DOMAIN.getValue(VehicleDomain.BODYWORK))
            ?: return "no-device-class"
        val int = Int::class.javaPrimitiveType!!
        fun declaredIn(name: String, vararg parameters: Class<*>): String {
            var current: Class<*>? = device
            while (current != null) {
                val found = try {
                    current.getDeclaredMethod(name, *parameters)
                } catch (_: NoSuchMethodException) {
                    null
                }
                if (found != null) return current.simpleName
                current = current.superclass
            }
            return "missing"
        }
        val base = if (load(BASE_CLASS) != null) "base" else "-base"
        return "$base,set:${declaredIn("set", int, IntArray::class.java, IntArray::class.java)},get:${declaredIn("get", int, int)}"
    }

    private fun services(): String {
        fun present(name: String): String = when (SystemServices.answers(name)) {
            true -> name
            false -> "-$name"
            null -> "?$name"
        }
        return listOf("autoservice", ReflectionVehicleController.AC_SERVICE_NAME).joinToString(",") { present(it) }
    }

    /**
     * The services of the car, other than the two looked at above, whose names suggest the climate or the vehicle's own
     * systems, as a short list: `?` when the service manager cannot be asked and null when none look so. Names only.
     */
    internal fun relatedServices(names: List<String>?, limit: Int = 40): String? {
        if (names == null) return "?"
        val related = names.asSequence()
            .filter { RELATED.containsMatchIn(it) && it != "autoservice" && it != ReflectionVehicleController.AC_SERVICE_NAME }
            .map { it.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40) }
            .distinct().sorted().toList()
        if (related.isEmpty()) return null
        return related.take(limit).joinToString(",") + if (related.size > limit) ",+${related.size - limit} more" else ""
    }

    private val RELATED = Regex("(?i)byd|climate|hvac|aircon|(^|[_.])ac([_.]|$)|vehicle|automotive")
}
