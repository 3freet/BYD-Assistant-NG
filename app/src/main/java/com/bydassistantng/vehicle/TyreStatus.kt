package com.bydassistantng.vehicle

import com.bydassistantng.gemini.GeminiFunctionDeclaration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** Read-only questions the car can answer. The helper process answers them; none of them writes anything. */
enum class VehicleQuery(val id: String) {
    TYRES("tyres"),
    ;

    companion object {
        fun byId(id: String): VehicleQuery? = entries.find { it.id == id }
    }
}

/** The four wheels, with the area number each of the car's two readouts uses for it. */
enum class Wheel(val key: String, val spoken: String, val tyreArea: Int, val clusterArea: Int) {
    FRONT_LEFT("front_left", "front left", tyreArea = 1, clusterArea = 3),
    FRONT_RIGHT("front_right", "front right", tyreArea = 2, clusterArea = 1),
    REAR_LEFT("rear_left", "rear left", tyreArea = 3, clusterArea = 4),
    REAR_RIGHT("rear_right", "rear right", tyreArea = 4, clusterArea = 2),
}

/** What the helper reads from the car for one wheel, with anything the car didn't report as null. */
@Serializable
data class RawWheel(
    /** [Wheel.key]. */
    val wheel: String,
    /** Pressure in kPa, as the tyre device reports it. */
    val kpa: Int? = null,
    /** 0 normal, 1 over-pressure, 2 under-pressure (the car's own judgement). */
    val state: Int? = null,
    /** 0 none, 1 fast leak, 2 slow leak. */
    val leak: Int? = null,
    /** 0 the sensor is heard, 1 it is not. */
    val signal: Int? = null,
    /** Degrees Celsius from the instrument cluster; null unless the cluster is set to Celsius. */
    val celsius: Int? = null,
)

@Serializable
data class RawTyreSnapshot(
    /** Whether this car has a direct tyre pressure monitor at all. */
    val monitored: Boolean,
    /** 0 normal, 1 self-checking, 2 signal abnormal, 3 breakdown, 4 masked. */
    val system: Int? = null,
    /** The pressure unit the cluster shows in (2 is psi, the only code confirmed). */
    val clusterPressureUnit: Int? = null,
    val wheels: List<RawWheel> = emptyList(),
)

/**
 * Tyre pressure as a tool the assistant can call. The reading comes from the car's tyre device (kPa per wheel, with
 * the car's own low/high and leak verdicts) and, for temperature, from the instrument cluster; see
 * docs/byd-internals/tyres.md for how the numbers were pinned down.
 */
object TyreStatusTool {
    const val FUNCTION_NAME = "get_tyre_pressure"

    /** The cluster's code for "show pressure in psi". */
    const val PSI_UNIT_CODE = 2

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Read the current tyre pressure of all four wheels from the car's tyre pressure monitor, " +
            "with each tyre's warning status and temperature when available. Use for any question about tyre " +
            "pressure, whether a tyre is low or flat, or tyre temperature. Report the pressures in the unit the " +
            "user used (or the car's own unit when given), and mention any warning plainly.",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses what the helper printed; null when it isn't a snapshot. */
    fun parse(text: String): RawTyreSnapshot? = try {
        json.decodeFromString(RawTyreSnapshot.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun encode(snapshot: RawTyreSnapshot): String = json.encodeToString(RawTyreSnapshot.serializer(), snapshot)

    fun bar(kpa: Int): Double = kpa / 100.0

    fun psi(kpa: Int): Double = (kpa * 0.145038 * 10).roundToInt() / 10.0

    /** The answer given back to the model. */
    fun report(snapshot: RawTyreSnapshot): JsonObject = buildJsonObject {
        if (!snapshot.monitored) {
            put("status", "error")
            put("error", "NOT_AVAILABLE")
            put("detail", "This car has no tyre pressure monitoring.")
            return@buildJsonObject
        }
        put("status", "ok")
        if (snapshot.clusterPressureUnit == PSI_UNIT_CODE) put("car_display_unit", "psi")
        snapshot.system?.let { code -> systemState(code)?.let { put("system", it) } }

        val reported = Wheel.entries.mapNotNull { wheel -> snapshot.wheels.find { it.wheel == wheel.key }?.let { wheel to it } }
        put("wheels", buildJsonArray { reported.forEach { (wheel, raw) -> add(wheelReport(wheel, raw)) } })
        put("warnings", buildJsonArray { warnings(reported).forEach { add(JsonPrimitive(it)) } })
    }

    private fun wheelReport(wheel: Wheel, raw: RawWheel): JsonObject = buildJsonObject {
        put("position", wheel.spoken)
        val kpa = raw.kpa
        if (raw.signal == 1 || kpa == null) {
            put("pressure", "not available")
        } else {
            put("kpa", kpa)
            put("bar", bar(kpa))
            put("psi", psi(kpa))
        }
        put(
            "pressure_state",
            when (raw.state) {
                0 -> "normal"
                1 -> "high"
                2 -> "low"
                else -> "unknown"
            },
        )
        put(
            "leak",
            when (raw.leak) {
                1 -> "fast"
                2 -> "slow"
                else -> "none"
            },
        )
        raw.celsius?.let { put("temperature_celsius", it) }
    }

    private fun warnings(wheels: List<Pair<Wheel, RawWheel>>): List<String> = buildList {
        for ((wheel, raw) in wheels) {
            when (raw.state) {
                2 -> add("${wheel.spoken} tyre pressure is low")
                1 -> add("${wheel.spoken} tyre pressure is high")
            }
            when (raw.leak) {
                1 -> add("${wheel.spoken} tyre is losing air quickly")
                2 -> add("${wheel.spoken} tyre is losing air slowly")
            }
            if (raw.signal == 1) add("no signal from the ${wheel.spoken} tyre sensor")
        }
    }

    private fun systemState(code: Int): String? = when (code) {
        1 -> "the tyre system is checking itself"
        2 -> "the tyre system has no signal from the sensors"
        3 -> "the tyre system reports a fault"
        4 -> "the tyre system is switched off"
        else -> null
    }
}
