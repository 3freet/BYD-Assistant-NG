package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.apps.AppLauncherTool
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.media.MediaControlTool
import com.bydassistantng.media.PlayMediaTool
import com.bydassistantng.media.PlaybackFollowUp
import com.bydassistantng.passenger.PassengerScreenTool
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.AirQualityTool
import com.bydassistantng.vehicle.ShellHelperVehicleController
import com.bydassistantng.vehicle.VehicleCommandRegistry
import com.bydassistantng.vehicle.OutsideTemperatureTool
import com.bydassistantng.vehicle.TyreStatusTool
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface VehicleReadEntryPoint {
    fun vehicleController(): ShellHelperVehicleController
}

/**
 * `am broadcast -a com.bydassistantng.debug.TOOL -n <pkg>/com.bydassistantng.debug.DebugToolReceiver --es tool open_app --es args '{"name":"camera"}'`
 * runs one of the assistant's app tools exactly as the model's function call would, and logs what it answers
 * ("DebugTool: …" in the app log). Lets the tools be checked without a voice session.
 */
class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tool = intent.getStringExtra("tool") ?: return
        val args = runCatching { Json.parseToJsonElement(intent.getStringExtra("args") ?: "{}").jsonObject }.getOrNull() ?: return
        val call = GeminiFunctionCall(name = tool, args = args)
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val result = when (tool) {
                    AppLauncherTool.FUNCTION_NAME -> runBlocking { AppLauncherTool.handle(app, call) { } }
                    MediaControlTool.FUNCTION_NAME -> MediaControlTool.handle(app, call)
                    OutsideTemperatureTool.FUNCTION_NAME -> {
                        val controller = EntryPointAccessors.fromApplication(app, VehicleReadEntryPoint::class.java).vehicleController()
                        when (val answer = runBlocking { controller.query(VehicleQuery.OUTSIDE_TEMPERATURE) }) {
                            is VehicleDispatchResult.Success -> answer.note?.let { OutsideTemperatureTool.parse(it) }?.let { OutsideTemperatureTool.report(it) } ?: answer
                            else -> answer
                        }
                    }
                    PlayMediaTool.FUNCTION_NAME -> runBlocking {
                        val answer = PlayMediaTool.handle(app, call) { }
                        // No conversation here, so the second half runs straight away and its outcome is logged too.
                        val outcome = PlaybackFollowUp.runPendingNow(app)
                        AppLogger.log("DebugTool", "play_media follow-up -> $outcome")
                        answer
                    }
                    TyreStatusTool.FUNCTION_NAME -> {
                        val controller = EntryPointAccessors.fromApplication(app, VehicleReadEntryPoint::class.java).vehicleController()
                        when (val answer = runBlocking { controller.query(VehicleQuery.TYRES) }) {
                            is VehicleDispatchResult.Success -> answer.note?.let { TyreStatusTool.parse(it) }?.let { TyreStatusTool.report(it) } ?: answer
                            else -> answer
                        }
                    }
                    PassengerScreenTool.FUNCTION_NAME -> {
                        val controller = EntryPointAccessors.fromApplication(app, VehicleReadEntryPoint::class.java).vehicleController()
                        runBlocking { PassengerScreenTool.handle(app, call, controller, true) { } }
                    }
                    AirQualityTool.FUNCTION_NAME -> {
                        val controller = EntryPointAccessors.fromApplication(app, VehicleReadEntryPoint::class.java).vehicleController()
                        when (val answer = runBlocking { controller.query(VehicleQuery.AIR_QUALITY) }) {
                            is VehicleDispatchResult.Success -> answer.note?.let { AirQualityTool.parse(it) }?.let { AirQualityTool.report(it) } ?: answer
                            else -> answer
                        }
                    }
                    else -> VehicleCommandRegistry.byFunctionName(tool)?.let { command ->
                        // A registry command, sent exactly as a function call would be: value resolved by name or number.
                        val raw = (args["value"] as? JsonPrimitive)?.content
                        val value = VehicleCommandRegistry.resolveValue(command, raw)
                        if (value == null) {
                            "value '$raw' is not valid for ${command.id}"
                        } else {
                            val controller = EntryPointAccessors.fromApplication(app, VehicleReadEntryPoint::class.java).vehicleController()
                            runBlocking { controller.dispatch(command, value) }
                        }
                    }
                }
                AppLogger.log("DebugTool", "$tool($args) -> $result")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
