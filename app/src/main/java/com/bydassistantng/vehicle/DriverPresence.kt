package com.bydassistantng.vehicle

/**
 * Whether the driver is in the seat and the driver's door is shut, read-only. Both are plain states of the car, found
 * by recording a sit and a stand (see `docs/byd-internals/driver-presence.md`).
 *
 * The seat is on the safety-belt device and reads **2 occupied, 1 empty** (the framework's own names for the seat states
 * say the opposite, so the numbers here are the ones seen on the car). The door is on the bodywork device, **1 open,
 * 0 shut**; on this left-hand-drive car the left front door is the driver's.
 */
object DriverPresence {
    internal const val SEAT_DEVICE_TYPE = 1042
    internal const val SEAT_STATE_ID = 0x31200040
    internal const val SEAT_CLASS = "android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice"

    internal const val DOOR_DEVICE_TYPE = 1001
    internal const val DOOR_STATE_ID = 0x29400008

    const val SEAT_EMPTY = 1
    const val SEAT_OCCUPIED = 2
    const val DOOR_SHUT = 0
    const val DOOR_OPEN = 1

    /** What the car reported; a part it gave no reading for is null. */
    data class State(val seat: Int?, val door: Int?) {
        val seated: Boolean get() = seat == SEAT_OCCUPIED

        /** Only a reading of "shut" counts: no reading is not taken for a shut door. */
        val doorShut: Boolean get() = door == DOOR_SHUT

        /** The driver is in the seat and the door is shut. */
        val ready: Boolean get() = seated && doorShut

        /** Somebody is getting in or out: the door is open or the seat is taken. */
        val someoneAbout: Boolean get() = seated || door == DOOR_OPEN

        /** Nobody about, read for certain: the seat is empty and the door is shut. */
        val nobodyAbout: Boolean get() = seat == SEAT_EMPTY && doorShut
    }

    fun encode(state: State): String = "seat=${state.seat ?: UNREAD} door=${state.door ?: UNREAD}"

    fun parse(note: String?): State? {
        if (note == null) return null
        val values = note.trim().split(' ').mapNotNull {
            val (key, value) = it.split('=', limit = 2).takeIf { parts -> parts.size == 2 } ?: return@mapNotNull null
            key to value.toIntOrNull()
        }.toMap()
        if ("seat" !in values || "door" !in values) return null
        return State(seat = values["seat"]?.takeIf { it != UNREAD }, door = values["door"]?.takeIf { it != UNREAD })
    }

    private const val UNREAD = -1
}
