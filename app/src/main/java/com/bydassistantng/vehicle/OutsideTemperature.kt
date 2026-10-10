package com.bydassistantng.vehicle

import android.content.Context
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the helper read for the outside temperature; [celsius] is null when the car gave no usable value. */
@Serializable
data class RawOutsideTemperature(val celsius: Int? = null, val unitCode: Int? = null)

/**
 * "How hot is it outside?" — the outside-air temperature the instrument cluster shows, as a read-only tool.
 * The cluster reports it in its display unit; only Celsius (unit code 1) has been observed, so any other unit is
 * reported as unavailable rather than guessed.
 */
object OutsideTemperatureTool {
    const val FUNCTION_NAME = "get_outside_temperature"
    private const val CELSIUS_UNIT_CODE = 1

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Read the outside air temperature from the car, in degrees Celsius. Use for any " +
            "question about how hot or cold it is outside.",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): RawOutsideTemperature? = try {
        json.decodeFromString(RawOutsideTemperature.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun encode(reading: RawOutsideTemperature): String = json.encodeToString(RawOutsideTemperature.serializer(), reading)

    fun report(reading: RawOutsideTemperature): JsonObject = buildJsonObject {
        val celsius = reading.celsius
        if (celsius == null || reading.unitCode != CELSIUS_UNIT_CODE) {
            put("status", "error")
            put("error", "NOT_AVAILABLE")
            put("detail", "The car did not report an outside temperature.")
        } else {
            put("status", "ok")
            put("celsius", celsius)
        }
    }

    /** Reads it with the cluster's own getters; runs only inside the helper process. */
    internal fun read(context: Context): Result<RawOutsideTemperature> = try {
        val device = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            .getMethod("getInstance", Context::class.java).invoke(null, context)
            ?: error("the instrument device is not available")
        val type = device.javaClass
        val value = type.getMethod("getOutCarTemperature").invoke(device) as? Int
        val unit = type.getMethod("getUnit", Int::class.javaPrimitiveType).invoke(device, 1) as? Int
        // 195 is the cluster's "no reading"; the rest of its range is real temperatures.
        Result.success(RawOutsideTemperature(celsius = value?.takeIf { it in -60..150 }, unitCode = unit))
    } catch (t: Throwable) {
        Result.failure(t.cause ?: t)
    }
}
