package com.bydassistantng.vehicle

import android.content.Context
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the helper read from the car's PM2.5 sensor pair: the concentration (µg/m³) and the car's own verdict for the
 * air inside the cabin and the air outside, with anything the car did not report as null.
 */
@Serializable
data class RawAirQuality(
    val insideUgm3: Int? = null,
    val outsideUgm3: Int? = null,
    /** 1 excellent, 2 good, 3 light pollution, 4 moderate, 5 heavy, 6 serious; 0 or null = no verdict. */
    val insideLevel: Int? = null,
    val outsideLevel: Int? = null,
    /** 1 the sensors are on, 2 they are off. */
    val online: Int? = null,
    /** 1 the cabin air is worse than the limit, 2 the outside air is; 0 normal. */
    val warning: Int? = null,
    /** 1 the cabin's negative-ion generator is running, 2 it is not. */
    val ionizer: Int? = null,
)

/**
 * "How is the air?" — the car's PM2.5 sensors, as a read-only tool.
 *
 * `BYDAutoPM2p5Device` (type 1008) answers two values per question, **inside first, outside second**
 * (`getPM2p5Value`, `getPM2p5Level`). The level numbers are the device's own constants. The sensor reads
 * 0..3000 µg/m³; anything else is "no reading".
 */
object AirQualityTool {
    const val FUNCTION_NAME = "get_air_quality"

    private const val VALUE_MAX = 3000

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Read the PM2.5 air quality sensors of the car: the air inside the cabin and the air outside, " +
            "in micrograms per cubic metre, with the car's own rating. Use for any question about air quality, " +
            "pollution, dust or whether the cabin air is clean.",
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): RawAirQuality? = try {
        json.decodeFromString(RawAirQuality.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun encode(reading: RawAirQuality): String = json.encodeToString(RawAirQuality.serializer(), reading)

    /** The name of one of the car's level numbers, or null for "no verdict". */
    fun levelName(level: Int?): String? = when (level) {
        1 -> "excellent"
        2 -> "good"
        3 -> "light_pollution"
        4 -> "moderate_pollution"
        5 -> "heavy_pollution"
        6 -> "serious_pollution"
        else -> null
    }

    fun report(reading: RawAirQuality): JsonObject = buildJsonObject {
        val inside = reading.insideUgm3?.takeIf { it in 0..VALUE_MAX }
        val outside = reading.outsideUgm3?.takeIf { it in 0..VALUE_MAX }
        if (reading.online == 2 || (inside == null && outside == null)) {
            put("status", "error")
            put("error", "NOT_AVAILABLE")
            put(
                "detail",
                if (reading.online == 2) "The car's air quality sensors are switched off." else "The car did not report an air quality value.",
            )
            return@buildJsonObject
        }
        put("status", "ok")
        put("unit", "µg/m³ PM2.5")
        inside?.let { put("inside", it) }
        levelName(reading.insideLevel)?.let { put("inside_rating", it) }
        outside?.let { put("outside", it) }
        levelName(reading.outsideLevel)?.let { put("outside_rating", it) }
        when (reading.warning) {
            1 -> put("warning", "The air inside the cabin is above the limit.")
            2 -> put("warning", "The air outside is above the limit.")
        }
        when (reading.ionizer) {
            1 -> put("air_ionizer", "on")
            2 -> put("air_ionizer", "off")
        }
    }

    /** Reads it with the PM2.5 device's own getters; runs only inside the helper process. */
    internal fun read(context: Context): Result<RawAirQuality> = try {
        val device = Class.forName("android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device")
            .getMethod("getInstance", Context::class.java).invoke(null, context)
            ?: error("the PM2.5 device is not available")
        val type = device.javaClass
        fun pair(name: String): IntArray? = (type.getMethod(name).invoke(device) as? IntArray)?.takeIf { it.size >= 2 }
        fun single(name: String): Int? = type.getMethod(name).invoke(device) as? Int
        val values = pair("getPM2p5Value")
        val levels = pair("getPM2p5Level")
        Result.success(
            RawAirQuality(
                insideUgm3 = values?.get(0)?.takeIf { it in 0..VALUE_MAX },
                outsideUgm3 = values?.get(1)?.takeIf { it in 0..VALUE_MAX },
                insideLevel = levels?.get(0)?.takeIf { it in 0..6 },
                outsideLevel = levels?.get(1)?.takeIf { it in 0..6 },
                online = single("getPM2p5OnlineState")?.takeIf { it in 0..2 },
                warning = single("getPM2p5WarningInfo")?.takeIf { it in 0..2 },
                ionizer = single("getPM2p5AnionState")?.takeIf { it in 1..2 },
            ),
        )
    } catch (t: Throwable) {
        Result.failure(t.cause ?: t)
    }
}
