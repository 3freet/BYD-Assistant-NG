package com.bydassistantng.vehicle

import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One read-only tool for the things the car's own sensors report (tyres, the temperature outside and inside, air quality) instead
 * of one declaration each. They all need the car link, so the tool is only offered with vehicle control on; the
 * media volume is a plain Android read and stays its own tool ([VolumeTool]).
 */
object ReadCarTool {
    const val FUNCTION_NAME = "read_car"

    enum class What(val word: String, internal val query: VehicleQuery) {
        TYRES("tyres", VehicleQuery.TYRES),
        OUTSIDE_TEMPERATURE("outside_temperature", VehicleQuery.OUTSIDE_TEMPERATURE),
        INSIDE_TEMPERATURE("inside_temperature", VehicleQuery.INSIDE_TEMPERATURE),
        AIR_QUALITY("air_quality", VehicleQuery.AIR_QUALITY),
        ;

        companion object {
            fun from(word: String?): What? = entries.find { it.word.equals(word?.trim(), ignoreCase = true) }
        }
    }

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Read a value from the car's sensors (read-only). what=tyres: the pressure of all four wheels " +
            "with each tyre's warning status and temperature; use for any question about tyre pressure, a low or " +
            "flat tyre, or tyre temperature, and report pressures in the unit the user used. " +
            "what=outside_temperature: the outside air temperature in degrees Celsius; use for how hot or cold it " +
            "is outside. what=inside_temperature: the air temperature inside the cabin in degrees Celsius, from the " +
            "climate system's inside sensor; use for how hot or cold it is inside the car. what=air_quality: the PM2.5 air quality inside the cabin and outside, with the car's own " +
            "rating; use for questions about air quality, pollution or dust. Mention any warning plainly.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "what" to GeminiSchema(
                    type = "STRING",
                    description = "Which reading.",
                    enum = What.entries.map { it.word },
                ),
            ),
            required = listOf("what"),
        ),
    )

    fun what(call: GeminiFunctionCall): What? = What.from((call.args["what"] as? JsonPrimitive)?.content)

    /** The answer for the model from what the helper printed, or null when it cannot be read. */
    fun report(what: What, note: String?): JsonObject? {
        if (note == null) return null
        return when (what) {
            What.TYRES -> TyreStatusTool.parse(note)?.let { TyreStatusTool.report(it) }
            What.OUTSIDE_TEMPERATURE -> OutsideTemperatureTool.parse(note)?.let { OutsideTemperatureTool.report(it) }
            What.INSIDE_TEMPERATURE -> InsideTemperatureTool.parse(note)?.let { InsideTemperatureTool.report(it) }
            What.AIR_QUALITY -> AirQualityTool.parse(note)?.let { AirQualityTool.report(it) }
        }
    }
}
