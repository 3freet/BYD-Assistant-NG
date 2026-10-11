package com.bydassistantng.vehicle


/**
 * The single, deliberate exception to the motor/engine/gearbox/ADAS denylist for drive modes: the one car
 * feature the car's own drive-mode screen writes to select a mode, with the few values that screen is known
 * to send.
 *
 * The denylist itself is untouched. Nothing here goes through the command registry, so the model can never
 * be offered it as a vehicle command; the command that writes it ([DriveModeCommand], run inside the vehicle
 * helper) has this one feature id and these values compiled in, refuses anything else, and refuses at or
 * above [MAX_SPEED_KMH]. It is only reachable in a build with the experimental car features, on a car whose
 * framework gives the feature the verified id ([isOnThisCar]), and after the user switched it on in
 * Advanced settings.
 *
 * What it is, from the car's own screen (`com.byd.dlc.drivingmode`) on this car, 2026-10-11: it calls
 * `AbsBYDAutoDevice.set(int[]{SETTING_PRESELECTED_DRIVING_MODE_SET}, BYDAutoEventValue(mode))` on the
 * Setting device, logging `setValue ----> device = BYDAutoSettingDevice featureId : 4c123030 value : 5`,
 * and the car answers by moving `SETTING_TARGET_DRIVING_MODE` to the same number within ~0.25 s.
 */
internal object DriveModeControl {
    /** The car's Setting device, the same one the other settings use. */
    const val DEVICE_TYPE = 1023

    /** The names the car's framework gives the write feature and the state read back. */
    const val PRESELECT_NAME = "SETTING_PRESELECTED_DRIVING_MODE_SET"
    const val TARGET_NAME = "SETTING_TARGET_DRIVING_MODE"

    /** What [PRESELECT_NAME] is on the car it was seen on; the helper refuses to write if the car says another id. */
    const val PRESELECT_ID = 0x4C123030

    /** The group of the car's feature ids the drive mode features are in. */
    const val FEATURE_GROUP = "Setting"

    /** Whether this car's framework names the drive mode feature the way it was verified: only then is it used. */
    fun isOnThisCar(): Boolean = CarFeatureIds.idOf(FEATURE_GROUP, PRESELECT_NAME) == PRESELECT_ID

    /** The reading the car gives for "no answer" on some states. */
    const val NO_READING = 65535

    /** Nothing is sent at or above this speed (the car's own conditions still decide below it). */
    const val MAX_SPEED_KMH = 80.0

    /** The road modes: the car's own screen never writes them (they come from the car's own switch); an experiment. */
    val ROAD_MODES = setOf(1, 2, 3)

    /** The number the car's drive-mode screen sends for [mode]. */
    fun requestFor(mode: DriveMode): Int = when (mode) {
        DriveMode.NORMAL -> 1
        DriveMode.ECO -> 2
        DriveMode.SPORT -> 3
        DriveMode.SNOW -> 4
        DriveMode.SAND -> 5
        DriveMode.MUD -> 6
        DriveMode.MOUNTAIN -> 7
        DriveMode.ROCK -> 8
        DriveMode.AUTO -> 21
        DriveMode.EXIT -> 255
    }

    private val permitted: Set<Int> = DriveMode.entries.map(::requestFor).toSet()

    /** True only for the one write this feature is allowed to make. */
    fun isPermitted(deviceType: Int, featureId: Int, value: Int): Boolean =
        deviceType == DEVICE_TYPE && featureId == PRESELECT_ID && value in permitted

    /** Whether the car is below the speed limit for a change: an unknown speed is not. */
    fun mayChangeAt(speedKmh: Double?): Boolean = speedKmh != null && !speedKmh.isNaN() && speedKmh >= 0 && speedKmh < MAX_SPEED_KMH

    /** The car's own name for a mode number. */
    fun nameOf(value: Int): String = when (value) {
        1 -> "Normal"
        2 -> "Eco"
        3 -> "Sport"
        4 -> "Snow"
        5 -> "Sand"
        6 -> "Mud"
        7 -> "Mountain"
        8 -> "Rock"
        9 -> "Wade"
        10 -> "Super wading"
        12 -> "VOT"
        19 -> "Custom"
        20 -> "Warp"
        21 -> "Auto"
        23 -> "Traction"
        else -> "mode $value"
    }

    /** What the helper reports after a write: the mode before, the mode the car reports afterwards, and how long it took. */
    data class Reading(val before: Int, val now: Int, val requested: Int, val ms: Long, val speedKmh: Int)

    fun note(reading: Reading): String =
        "before=${reading.before} now=${reading.now} requested=${reading.requested} ms=${reading.ms} speed=${reading.speedKmh}"

    fun parseNote(note: String): Reading? {
        val fields = note.trim().split(' ').mapNotNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        return Reading(
            before = fields["before"]?.toIntOrNull() ?: return null,
            now = fields["now"]?.toIntOrNull() ?: return null,
            requested = fields["requested"]?.toIntOrNull() ?: return null,
            ms = fields["ms"]?.toLongOrNull() ?: return null,
            speedKmh = fields["speed"]?.toIntOrNull() ?: 0,
        )
    }

    /** What to tell the assistant about [result], the helper's answer (null when it could not be reached). */
    fun outcomeOf(mode: DriveMode, result: VehicleDispatchResult?): DriveModeOutcome = when (result) {
        null -> DriveModeOutcome.Refused("the car's ADB connection could not be reached")
        is VehicleDispatchResult.Blocked -> DriveModeOutcome.Refused(result.reason)
        is VehicleDispatchResult.Failure ->
            DriveModeOutcome.Refused(result.detail ?: result.error.name.lowercase().replace('_', ' '))
        is VehicleDispatchResult.Success -> {
            val reading = result.note?.let(::parseNote)
            if (reading == null) {
                DriveModeOutcome.Refused("the car's answer could not be read")
            } else {
                val reached = when (mode) {
                    DriveMode.EXIT -> reading.now != reading.before || reading.before in ROAD_MODES
                    else -> reading.now == requestFor(mode)
                }
                if (reached) {
                    DriveModeOutcome.Changed(before = nameOf(reading.before), now = nameOf(reading.now))
                } else {
                    DriveModeOutcome.Unchanged(
                        current = nameOf(reading.now),
                        detail = if (reading.now == reading.before) "it stayed as it was" else "it went to ${nameOf(reading.now)} instead",
                    )
                }
            }
        }
    }
}
