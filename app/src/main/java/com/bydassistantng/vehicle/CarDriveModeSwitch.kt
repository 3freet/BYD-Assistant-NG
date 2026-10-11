package com.bydassistantng.vehicle

import android.os.SystemClock
import com.bydassistantng.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DriveMode"

/**
 * The car's [DriveModeSwitch]: asks the vehicle helper that is already running (the one the vehicle
 * commands use, started when the conversation began) for a drive mode, written the way the car's own screen
 * does it ([DriveModeCommand]), and reports the mode the car says it is in afterwards.
 */
@Singleton
internal class CarDriveModeSwitch @Inject constructor(
    private val helper: ShellHelperVehicleController,
) : DriveModeSwitch {
    override suspend fun switchTo(mode: DriveMode): DriveModeOutcome {
        val value = DriveModeControl.requestFor(mode)
        // The helper checks the same things again; this is the independent first gate.
        if (!DriveModeControl.isPermitted(DriveModeControl.DEVICE_TYPE, DriveModeControl.PRESELECT_ID, value)) {
            return DriveModeOutcome.Refused("that mode is not one this build may ask for")
        }
        val started = SystemClock.elapsedRealtime()
        val result = helper.requestDriveMode(value)
        val outcome = DriveModeControl.outcomeOf(mode, result)
        AppLogger.log(
            TAG,
            "Drive mode ${mode.word} ($value) -> ${result ?: "no answer"} => $outcome " +
                "[${SystemClock.elapsedRealtime() - started} ms]",
        )
        return outcome
    }
}
