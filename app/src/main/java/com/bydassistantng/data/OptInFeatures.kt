package com.bydassistantng.data

/**
 * Car features that stay off until the user switches them on, under Advanced settings. Both reach into the
 * car beyond what the vehicle commands do (sound outside the car, the drive mode), so a build that has them
 * still offers nothing of them to the assistant, starts nothing for them, and shows no related setting
 * values until the switch is on.
 */
object OptInFeatures {
    const val EXTERIOR_SPEAKER_DEFAULT = false

    /**
     * The live microphone broadcast: holding the steering-wheel microphone button sends what the cabin microphone hears out
     * of the exterior speaker until the button is pressed again. It is a way of using the exterior speaker, so it has no effect
     * while that is off.
     */
    const val LIVE_BROADCAST_DEFAULT = false

    const val DRIVE_MODES_DEFAULT = false

    /** Putting an app on the instrument cluster (experimental). */
    const val CLUSTER_SCREEN_DEFAULT = false

    /** BYD Maps is left enabled once the app has left the cluster, unless the user chooses to keep it disabled. */
    const val CLUSTER_KEEP_MAPS_DISABLED_DEFAULT = false

}

/**
 * Features that are on until the user switches them off: what the assistant is for. Vehicle control was off by
 * default while it was being proven on a real car; it is the core of the assistant now.
 */
object CoreFeatures {
    const val VEHICLE_CONTROL_DEFAULT = true

    /**
     * Replies on the car's voice channel, with the music turned down while the assistant is active. It is on wherever
     * the car has the channel (see [com.bydassistantng.audio.VoiceChannel.isAvailable]); anywhere else it is absent.
     */
    const val VOICE_CHANNEL_DEFAULT = true

    /** The voice channel's volume follows the cabin noise. */
    const val VOICE_CHANNEL_ADAPTIVE_DEFAULT = true
}
