package com.bydassistantng.vehicle

import android.content.Context

/**
 * Reads the tyre pressure monitor with the car's own typed getters. Runs only inside the helper process (the
 * device classes refuse an ordinary app), and only ever calls getters.
 *
 * Pressure, the car's low/high verdict, leak and sensor flags come from `BYDAutoTyreDevice`; the temperature comes
 * from `BYDAutoInstrumentDevice` (the tyre device exposes only a coarse temperature state), which numbers the
 * wheels differently — see [Wheel]. Anything the car answers with an error code or "not fitted" becomes null.
 */
internal class TyreReader(private val context: Context) {

    fun read(): Result<RawTyreSnapshot> = try {
        val tyre = deviceInstance(TYRE_CLASS)
        if (!hasFeature(tyre, FEATURE_TYRE_PRESSURE_MONITOR)) {
            Result.success(RawTyreSnapshot(monitored = false))
        } else {
            val cluster = runCatching { deviceInstance(INSTRUMENT_CLASS) }.getOrNull()
            val celsius = cluster?.let { callInt(it, "getUnit", TEMPERATURE_UNIT_CATEGORY) == CELSIUS_UNIT_CODE } ?: false
            val wheels = Wheel.entries.map { wheel ->
                val kpa = callInt(tyre, "getTyrePressureValue", wheel.tyreArea)
                RawWheel(
                    wheel = wheel.key,
                    kpa = kpa?.takeIf { it in PRESSURE_MIN..PRESSURE_MAX },
                    state = callInt(tyre, "getTyrePressureState", wheel.tyreArea).asState(),
                    leak = callInt(tyre, "getTyreAirLeakState", wheel.tyreArea).asState(),
                    signal = callInt(tyre, "getTyreSignalState", wheel.tyreArea).asState(),
                    celsius = if (celsius) cluster?.let { callInt(it, "getWheelTemperature", wheel.clusterArea) }?.takeIf { it in TEMP_MIN..TEMP_MAX } else null,
                    cluster = cluster?.let { callInt(it, "getWheelPressure", wheel.clusterArea) }?.takeIf { it in CLUSTER_PRESSURE_MIN..CLUSTER_PRESSURE_MAX },
                )
            }
            Result.success(
                RawTyreSnapshot(
                    monitored = true,
                    system = callInt(tyre, "getTyreSystemState", null).asState(),
                    clusterPressureUnit = cluster?.let { callInt(it, "getUnit", PRESSURE_UNIT_CATEGORY) }?.asState(),
                    wheels = wheels,
                ),
            )
        }
    } catch (t: Throwable) {
        Result.failure(t.cause ?: t)
    }

    private fun deviceInstance(className: String): Any =
        Class.forName(className).getMethod("getInstance", Context::class.java).invoke(null, context)
            ?: error("$className.getInstance returned null")

    /** Calls a public getter taking one int (or none, for a null [arg]); null when the car answers with an error. */
    private fun callInt(device: Any, name: String, arg: Int?): Int? = try {
        if (arg == null) {
            device.javaClass.getMethod(name).invoke(device)
        } else {
            device.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(device, arg)
        } as? Int
    } catch (_: Exception) {
        null
    }

    private fun hasFeature(device: Any, feature: String): Boolean = try {
        device.javaClass.getMethod("hasFeature", String::class.java).invoke(device, feature) == 1
    } catch (_: Exception) {
        false
    }

    /** The car's states are small non-negative codes; its error and "not fitted" answers are not. */
    private fun Int?.asState(): Int? = this?.takeIf { it in 0..9 }

    private companion object {
        const val TYRE_CLASS = "android.hardware.bydauto.tyre.BYDAutoTyreDevice"
        const val INSTRUMENT_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        const val FEATURE_TYRE_PRESSURE_MONITOR = "TyrePressureMonitor"

        const val PRESSURE_MIN = 0
        const val PRESSURE_MAX = 4094
        // BYDAutoInstrumentDevice.PRESSURE_MIN / PRESSURE_MAX.
        const val CLUSTER_PRESSURE_MIN = 0
        const val CLUSTER_PRESSURE_MAX = 1000
        const val TEMP_MIN = -40
        const val TEMP_MAX = 250

        // BYDAutoInstrumentDevice.getUnit(category): the temperature unit is category 1, the pressure unit 2.
        const val TEMPERATURE_UNIT_CATEGORY = 1
        const val PRESSURE_UNIT_CATEGORY = 2
        const val CELSIUS_UNIT_CODE = 1
    }
}
