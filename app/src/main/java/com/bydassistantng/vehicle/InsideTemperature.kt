package com.bydassistantng.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the helper read for the cabin temperature; [celsius] is null when the car gave no usable value. */
@Serializable
data class RawInsideTemperature(val celsius: Int? = null, val unitCode: Int? = null)

/**
 * "How warm is it inside the car?" — the air temperature of the cabin, from the climate system. A state on the climate
 * device (type 1000), whole degrees, next to the one for the outside air; the climate device's own unit choice (1 is
 * Celsius) says what it is in. Read-only.
 *
 * There are two such states, `AC_TEMP_INSIDE` and `AC_TEMP_INSIDE_FILTERING`, and the cabin's is the **filtered** one: on
 * 2026-10-18 it read 26 to 27 while the phone app showed 26. The other read 22, the temperature the climate was set to, and
 * followed that rather than the cabin (see `docs/byd-internals/cabin-and-dashcam.md`).
 */
object InsideTemperatureTool {
    internal const val DEVICE_TYPE = 1000
    internal const val STATE_ID = 0x4EB06010 // AC_TEMP_INSIDE_FILTERING
    private const val CELSIUS_UNIT_CODE = 1

    /** Zero is the climate system's own "no temperature" (`AC_TEMP_INVALID`); a real cabin is not at 0 or past 90. */
    internal fun usable(celsius: Int?): Int? = celsius?.takeIf { it in 1..90 }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): RawInsideTemperature? = try {
        json.decodeFromString(RawInsideTemperature.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun encode(reading: RawInsideTemperature): String = json.encodeToString(RawInsideTemperature.serializer(), reading)

    fun report(reading: RawInsideTemperature): JsonObject = buildJsonObject {
        val celsius = usable(reading.celsius)
        if (celsius == null || reading.unitCode != CELSIUS_UNIT_CODE) {
            put("status", "error")
            put("error", "NOT_AVAILABLE")
            put("detail", "The car did not report a temperature for the inside.")
        } else {
            put("status", "ok")
            put("celsius", celsius)
        }
    }
}
