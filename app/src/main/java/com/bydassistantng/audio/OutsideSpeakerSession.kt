package com.bydassistantng.audio

import android.content.Context
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.HelperProtocol
import com.bydassistantng.vehicle.HelperSession
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult

private const val TAG = "OutsideSpeaker"
private const val CONNECT_TIMEOUT_MS = 8_000L
private const val START_TIMEOUT_MS = 10_000L
private const val COMMAND_TIMEOUT_MS = 6_000L

/**
 * The app's end of the exterior speaker helper ([OutsideSpeakerHelper]): one shell-identity process,
 * reached over one loopback ADB stream, that lives only as long as the session. Closing the session
 * (or losing the link) makes the helper switch the exterior speaker off.
 */
internal class OutsideSpeakerSession private constructor(private val helper: HelperSession) {
    /** One command and the helper's answer; a lost link is reported as unavailable, never thrown. */
    suspend fun command(line: String): VehicleDispatchResult {
        // The heartbeat goes twice a second for as long as something plays: not worth a line each.
        if (line != "hold") AppLogger.log(TAG, "Helper: $line")
        val reply = try {
            helper.write(line)
            helper.readLineStartingWith(HelperProtocol.RESULT_PREFIX.trim(), COMMAND_TIMEOUT_MS)
        } catch (e: java.io.IOException) {
            null
        } ?: return VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "No answer from the helper to '$line'")
        return HelperProtocol.parse(reply)
            ?: VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "Unreadable helper reply: ${reply.take(200)}")
    }

    fun close() = helper.close()

    companion object {
        /** Starts the helper; null when the ADB connection or the helper could not be brought up. */
        suspend fun open(context: Context): OutsideSpeakerSession? {
            val dadb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, socketTimeoutMs = 0) ?: return null
            val apk = context.applicationInfo.sourceDir
            val helper = try {
                HelperSession(
                    dadb,
                    dadb.openShell("CLASSPATH='$apk' exec app_process /system/bin ${OutsideSpeakerHelper::class.java.name} --serve 2>&1"),
                )
            } catch (e: Exception) {
                runCatching { dadb.close() }
                AppLogger.logError(TAG, "Could not start the helper", e)
                return null
            }
            if (helper.readLineStartingWith(HelperProtocol.READY_LINE, START_TIMEOUT_MS) == null) {
                AppLogger.logError(TAG, "The helper never reported ready")
                helper.close()
                return null
            }
            return OutsideSpeakerSession(helper)
        }
    }
}
