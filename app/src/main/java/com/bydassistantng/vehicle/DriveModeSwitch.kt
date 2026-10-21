package com.bydassistantng.vehicle

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Provider
import javax.inject.Singleton

/**
 * A drive mode the assistant can ask the car for, by the word the model uses (see [SetDriveModeTool]).
 *
 * Only the names live here. The numbers the car understands and the one feature they are written to are in
 * [DriveModeControl], which the model never sees.
 */
enum class DriveMode(val word: String, val label: String) {
    SNOW("snow", "Snow"),
    SAND("sand", "Sand"),
    MUD("mud", "Mud"),
    MOUNTAIN("mountain", "Mountain"),
    ROCK("rock", "Rock"),
    AUTO("auto", "Auto"),
    NORMAL("normal", "Normal"),
    ECO("eco", "Eco"),
    SPORT("sport", "Sport"),

    /** Leaves an off-road mode, as the car's own screen's exit button does. */
    EXIT("exit", "Exit"),
    ;

    companion object {
        /** The mode named by [word], or null when it names none. */
        fun from(word: String?): DriveMode? = entries.find { it.word.equals(word?.trim(), ignoreCase = true) }
    }
}

/** What the car did with a request for a drive mode. The names are the car's own for its modes. */
sealed interface DriveModeOutcome {
    /** The car reports [now] after the request ([before] is what it was in). */
    data class Changed(val before: String?, val now: String) : DriveModeOutcome

    /** The car was asked and did not switch: it is in [current]. */
    data class Unchanged(val current: String?, val detail: String) : DriveModeOutcome

    /** Nothing was sent, or nothing could be confirmed: [reason] says why (moving, no link to the car...). */
    data class Refused(val reason: String) : DriveModeOutcome
}

/**
 * Where the assistant's drive mode requests go. It exists only on a car whose framework names the drive mode feature
 * the way it was verified (see [DriveModeControl]); anywhere else the model is never offered the tool. Even then
 * it stays off until the user switches it on in Advanced settings.
 */
interface DriveModeSwitch {
    /** Asks the car for [mode] and reports what the car says afterwards. Never throws. */
    suspend fun switchTo(mode: DriveMode): DriveModeOutcome
}

/** Provides the drive mode switch where the car has one, and nothing where it does not. */
@Module
@InstallIn(SingletonComponent::class)
internal object DriveModeSwitchModule {
    @Provides
    @Singleton
    fun driveModeSwitch(switch: Provider<CarDriveModeSwitch>): Optional<DriveModeSwitch> =
        if (DriveModeControl.isOnThisCar()) {
            Optional.of(switch.get())
        } else {
            Optional.empty()
        }
}
