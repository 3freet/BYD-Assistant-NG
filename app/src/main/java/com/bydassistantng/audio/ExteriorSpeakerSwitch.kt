package com.bydassistantng.audio

/**
 * The single, deliberate exception to the ADAS-domain denylist: the one car feature that sends the audio
 * being played to the exterior speaker.
 *
 * The denylist itself is untouched. Nothing here goes through the command registry, so the model can
 * never be offered it; the shell helper that writes it ([OutsideSpeakerHelper]) has this one feature
 * id and the values 0 and 1 compiled in and refuses everything else. It is only reachable in a build with
 * the experimental car features, on a car whose framework gives the feature the verified id
 * ([isOnThisCar]), and after the user switched it on in Advanced settings.
 */
internal object ExteriorSpeakerSwitch {
    /** The car's audio device, the same one the volume control uses. */
    const val DEVICE_TYPE = 1002

    /** `ADAS_UE_BROADCAST_POSITION_SET` (0xAA000282): 1 = play to the exterior speaker, 0 = back to normal. */
    const val FEATURE_ID = -1442839934

    /** `AUDIO_MEDIA_SOUND_MUTE_STATE` (0x4FD0002D): read only, goes from 1 to 2 while the switch is on. */
    const val MUTE_STATE_ID = 0x4FD0002D

    const val OFF = 0
    const val ON = 1

    /** The name and group the car's framework gives [FEATURE_ID]. */
    private const val FEATURE_GROUP = "Adas"
    private const val FEATURE_NAME = "ADAS_UE_BROADCAST_POSITION_SET"

    /** Whether this car's framework names the feature the way it was verified: only then is it used. */
    fun isOnThisCar(): Boolean = com.bydassistantng.vehicle.CarFeatureIds.idOf(FEATURE_GROUP, FEATURE_NAME) == FEATURE_ID

    /** The reading the car gives for "no answer" on some states. */
    const val NO_READING = 65535

    /** True only for the one write this feature is allowed to make. */
    fun isPermitted(deviceType: Int, featureId: Int, value: Int): Boolean =
        deviceType == DEVICE_TYPE && featureId == FEATURE_ID && (value == OFF || value == ON)
}

/**
 * When the helper must switch the exterior speaker off by itself. Kept apart from the process code so
 * the rule can be tested: the app holds the switch on with a heartbeat while it plays, so a crashed or
 * disconnected app can never leave the car sending sound outside.
 */
internal object ExteriorSpeakerGuard {
    /** No heartbeat for this long while the switch is on: switch it off. */
    const val HEARTBEAT_TIMEOUT_MS = 4_000L

    /** The longest the switch may stay on at a stretch, however well it is held. */
    const val MAX_ON_MS = 60_000L

    enum class Reason { NO_HEARTBEAT, TOO_LONG }

    fun mustSwitchOff(on: Boolean, nowMs: Long, onSinceMs: Long, lastHeartbeatMs: Long): Reason? = when {
        !on -> null
        nowMs - onSinceMs > MAX_ON_MS -> Reason.TOO_LONG
        nowMs - lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS -> Reason.NO_HEARTBEAT
        else -> null
    }
}
