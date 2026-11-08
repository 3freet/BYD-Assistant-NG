package com.bydassistantng.vehicle

/**
 * A look at a few states of the car, for the helper's log line, that say whether it has a feature this app cannot offer yet and
 * what the car itself holds for it: the exterior speaker's switch, configuration and sources, and the drive mode. Nothing is written
 * and nothing is assumed about what a number means; the numbers are only written down, so that an owner's log, taken before and
 * after the owner moves the car's own setting for it, shows which state moves.
 *
 * Only the states that the car's own table names are read, by the car's number, straight from its native service (which the ADB shell may
 * use), so that a car that has none of them gets no field.
 */
internal object StatePeek {
    private const val AUDIO = 1002
    private const val ENERGY = 1006
    private const val SETTING = 1023

    class Probe(val name: String, val deviceType: Int)

    val probes: List<Probe> = listOf(
        Probe("Audio.AUDIO_EXTERIOR_SPEAKER_CONFIG", AUDIO),
        Probe("Audio.AUDIO_EXTERIOR_SPEAKER_SWITCH_STATUS", AUDIO),
        Probe("Audio.AUDIO_AVAS_AUDIO_SOURCE_TO_EXTERNAL_SPEAKER_STATUS", AUDIO),
        Probe("Audio.AUDIO_EXTERIOR_PROMPT_TONE_SOURCE_STATUS", AUDIO),
        Probe("Audio.AUDIO_AVAS_SOUND_SOURCE_STATE", AUDIO),
        Probe("Energy.ENERGY_OPERATION_MODE", ENERGY),
        Probe("Energy.ENERGY_ROAD_SURFACE_MODE", ENERGY),
        Probe("Setting.SETTING_TARGET_DRIVING_MODE", SETTING),
        Probe("Setting.SETTING_ACTUAL_DRIVING_MODE", SETTING),
    )

    /**
     * `name:value,...` for the probes the car's [table] has, in order: [read] is asked for each (a device type and the car's number)
     * and a state the car did not answer is written `?`. Null where the table has none of them.
     */
    fun describe(table: Map<String, Int>, read: (deviceType: Int, id: Int) -> Int?): String? {
        val parts = probes.mapNotNull { probe ->
            table[probe.name]?.let { id -> probe.name + ":" + (read(probe.deviceType, id)?.toString() ?: "?") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}
