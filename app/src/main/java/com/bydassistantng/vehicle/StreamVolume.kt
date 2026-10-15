package com.bydassistantng.vehicle

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Sets an Android audio stream's volume index and confirms it by reading it back. The car's own volume is the media
 * stream (stream 3, 0..39 on the car this was checked on), and any app may set it (`MODIFY_AUDIO_SETTINGS` is a
 * normal permission), so this needs no vehicle helper and no ADB link; see [InAppVehicleController].
 */
internal object StreamVolume {
    private const val TAG = "StreamVolume"
    private const val VERIFY_ATTEMPTS = 15
    private const val VERIFY_INTERVAL_MS = 150L

    suspend fun set(context: Context, stream: Int, value: Int): VehicleDispatchResult {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "No audio service")
            val max = audio.getStreamMaxVolume(stream)
            val target = value.coerceIn(0, max)
            audio.setStreamVolume(stream, target, 0)
            var now = -1
            for (attempt in 1..VERIFY_ATTEMPTS) {
                delay(VERIFY_INTERVAL_MS)
                now = audio.getStreamVolume(stream)
                if (now == target) return VehicleDispatchResult.Success("confirmed: the volume is now $now of $max")
            }
            VehicleDispatchResult.Failure(
                VehicleDispatchError.INVOCATION_FAILED,
                "The car did not apply it: asked for $target but the volume is $now of $max",
            )
        } catch (e: SecurityException) {
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error setting the stream volume", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }
}

/**
 * Runs the commands that are plain Android calls (see [VehicleCommand.needsCarLink]) inside the app: no helper
 * process, no ADB connection, and no dependence on the vehicle-control switch, which governs only the commands
 * that reach the car's own systems.
 */
internal class InAppVehicleController(private val context: Context) : VehicleController {
    override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult {
        VehicleSafety.assertDispatchAllowed(command)
        return when (val invocation = command.invocation) {
            is VehicleInvocation.AudioStreamVolume -> StreamVolume.set(context, invocation.stream, value)
            else -> VehicleDispatchResult.Failure(
                VehicleDispatchError.INVALID_ARGUMENT,
                "${command.id} needs the car link and cannot run inside the app",
            )
        }
    }
}
