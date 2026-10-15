package com.bydassistantng.data

/**
 * Car features that stay off until the user switches them on, under Advanced settings. Both reach into the
 * car beyond what the vehicle commands do (sound outside the car, the drive mode), so a build that has them
 * still offers nothing of them to the assistant, starts nothing for them, and shows no related setting
 * values until the switch is on.
 */
object OptInFeatures {
    const val EXTERIOR_SPEAKER_DEFAULT = false
    const val DRIVE_MODES_DEFAULT = false

    /** Putting an app on the instrument cluster (development builds only). */
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
}
