package com.bydassistantng.vehicle

import com.bydassistantng.audio.ExteriorSpeakerSwitch
import com.bydassistantng.cluster.ClusterScreenParsing

/**
 * The feature numbers the app uses outside the command registry (its reads, and the three opt-in features), each with the
 * name the car's own table of features gives it (`Group.NAME`, as in `BYDAutoFeatureIds`). The numbers are those of the car
 * they were found on; the names are what stays the same from car to car. [FeatureNameCheck] compares the two on every car.
 */
internal object OtherFeatures {
    /** One feature: what it is used for, its name in the car's table, and the number it has on the car it was found on. */
    class Feature(val label: String, val name: String, val number: Int)

    val all: List<Feature> = listOf(
        Feature("headrest.mode", HeadrestSound.MODE_STATE_NAME, HeadrestSound.MODE_STATE_ID),
        Feature("headrest.speakers", HeadrestSound.SPEAKER_CONFIG_STATE_NAME, HeadrestSound.SPEAKER_CONFIG_STATE_ID),
        Feature("driver.seat", DriverPresence.SEAT_STATE_NAME, DriverPresence.SEAT_STATE_ID),
        Feature("driver.door", DriverPresence.DOOR_STATE_NAME, DriverPresence.DOOR_STATE_ID),
        Feature("cabin.temperature", InsideTemperatureTool.STATE_NAME, InsideTemperatureTool.STATE_ID),
        Feature("driveMode.preselect", "${DriveModeControl.FEATURE_GROUP}.${DriveModeControl.PRESELECT_NAME}", DriveModeControl.PRESELECT_ID),
        Feature("exteriorSpeaker.switch", "${ExteriorSpeakerSwitch.FEATURE_GROUP}.${ExteriorSpeakerSwitch.FEATURE_NAME}", ExteriorSpeakerSwitch.FEATURE_ID),
        Feature("exteriorSpeaker.muteState", ExteriorSpeakerSwitch.MUTE_STATE_NAME, ExteriorSpeakerSwitch.MUTE_STATE_ID),
        Feature("cluster.mapType.state", "${ClusterScreenParsing.FEATURE_GROUP}.${ClusterScreenParsing.MAP_TYPE_STATE_NAME}", ClusterScreenParsing.MAP_TYPE_STATE_ID),
        Feature("cluster.mapType.control", "${ClusterScreenParsing.FEATURE_GROUP}.${ClusterScreenParsing.MAP_TYPE_CONTROL_NAME}", ClusterScreenParsing.MAP_TYPE_CONTROL_ID),
    )
}
