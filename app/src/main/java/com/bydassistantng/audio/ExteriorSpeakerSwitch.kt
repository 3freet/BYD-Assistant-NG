package com.bydassistantng.audio

/**
 * The deliberate exception to the denylist for the exterior speaker: the car's two ways of sending the audio being played to it, the navigation
 * stream's ([FEATURE_ID], filed by the car in the driver-assistance group) and the music output's ([MEDIA_FEATURE_ID], in the audio group, used by
 * the live microphone broadcast).
 *
 * The denylist itself is untouched. Nothing here goes through the command registry, so the model can
 * never be offered them; the shell helper that writes them ([OutsideSpeakerHelper]) has these two feature
 * ids and the values 0 and 1 compiled in and refuses everything else. Each is only reachable on a car whose
 * framework gives the feature the verified id ([isOnThisCar], [isMediaRouteOnThisCar]), and after the user switched the feature on in Advanced settings.
 */
internal object ExteriorSpeakerSwitch {
    /** The car's audio device, the same one the volume control uses. */
    const val DEVICE_TYPE = 1002

    /** `ADAS_UE_BROADCAST_POSITION_SET` (0xAA000282): 1 = play to the exterior speaker, 0 = back to normal. */
    const val FEATURE_ID = -1442839934

    /**
     * `AUDIO_MEDIA_SOUND_MUTE_STATE` (0x4FD0002D): read only, goes from 1 to 2 when the media route is switched on (the navigation route leaves it at
     * 1). It also reads 2 for a while whenever the media bus has been busy (music playing, or a broadcast that was cut short), so it cannot always show
     * that a route took: see [MUTE_STATE_ROUTE_ON].
     */
    const val MUTE_STATE_ID = 0x4FD0002D
    const val MUTE_STATE_NAME = "Audio.AUDIO_MEDIA_SOUND_MUTE_STATE"

    /** What the mute state reads while the media route is on (and for a while after music has played). */
    const val MUTE_STATE_ROUTE_ON = 2

    const val OFF = 0
    const val ON = 1

    /** The name and group the car's framework gives [FEATURE_ID]. */
    internal const val FEATURE_GROUP = "Adas"
    internal const val FEATURE_NAME = "ADAS_UE_BROADCAST_POSITION_SET"

    /**
     * The second way to the exterior speaker, the one the exterior sound apps use for the **music** stream: `AUDIO_LOCK_CAR_SOUND_EFFECT_PLAYBACK_STATUS_SET`
     * (0xAA000321, in the Audio group), 1 = send the music output outside, 0 = back. The first route above sends the *navigation* stream, whose output
     * on this car is buffered for about a quarter of a second (a broadcast through it lags by half a second); the music stream's is about 0.15 s,
     * which is what a live broadcast needs. The car sends outside what its mixer has on the music output, which is the music stream, the system's
     * click sounds and the assistant's plain tones: so a live broadcast stops the music first (unless the user chooses otherwise). Like the first it is
     * held by the helper, with the same heartbeat and limit, and only the values 0 and 1 are written.
     */
    const val MEDIA_FEATURE_ID = -1442839775
    internal const val MEDIA_FEATURE_GROUP = "Audio"
    internal const val MEDIA_FEATURE_NAME = "AUDIO_LOCK_CAR_SOUND_EFFECT_PLAYBACK_STATUS_SET"

    /** The device types the media feature is written with, in the order tried: the Audio device's own, then the one the exterior sound apps pass. */
    val MEDIA_DEVICE_TYPES = intArrayOf(DEVICE_TYPE, 1000)

    /** Whether the media route may be used at all: the one place to switch it off. */
    const val MEDIA_ROUTE_ENABLED = true

    /** Whether the media route is offered here: enabled, and the car's framework names the feature the way it was confirmed. */
    fun isMediaRouteOnThisCar(): Boolean =
        MEDIA_ROUTE_ENABLED && com.bydassistantng.vehicle.CarFeatureIds.idOf(MEDIA_FEATURE_GROUP, MEDIA_FEATURE_NAME) == MEDIA_FEATURE_ID

    /** Whether this car's framework names the feature the way it was verified: only then is it used. */
    fun isOnThisCar(): Boolean = com.bydassistantng.vehicle.CarFeatureIds.idOf(FEATURE_GROUP, FEATURE_NAME) == FEATURE_ID

    /** The reading the car gives for "no answer" on some states. */
    const val NO_READING = 65535

    /**
     * True only for the writes this exception allows: the one feature on the audio device, and where [mediaRoute] is on the media route's, with
     * the values 0 and 1 only.
     */
    fun isPermitted(deviceType: Int, featureId: Int, value: Int, mediaRoute: Boolean = MEDIA_ROUTE_ENABLED): Boolean {
        if (value != OFF && value != ON) return false
        if (deviceType == DEVICE_TYPE && featureId == FEATURE_ID) return true
        return mediaRoute && featureId == MEDIA_FEATURE_ID && deviceType in MEDIA_DEVICE_TYPES
    }
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
