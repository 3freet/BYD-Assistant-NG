package com.bydassistantng.vehicle

import android.util.Log

/**
 * Default [VehicleController]: logs the resolved command and returns success, without touching
 * the car. This is what dispatch uses until the user explicitly arms [ReflectionVehicleController]
 * via the vehicle-control setting.
 */
object LoggingVehicleController : VehicleController {
    private const val TAG = "VehicleController"

    override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult {
        VehicleSafety.assertDispatchAllowed(command)

        val target = when (val invocation = command.invocation) {
            VehicleInvocation.GenericFeatureSet ->
                "deviceType=${command.deviceType}, featureId=0x${command.featureId?.toString(16)}"
            is VehicleInvocation.NamedMethod ->
                "${invocation.deviceClass}.${invocation.methodName}(${invocation.argsTemplate.joinToString { it?.toString() ?: "value" }})"
            is VehicleInvocation.AcBinderProperty ->
                "${invocation.interfaceDescriptor} (sub=${invocation.subServiceKey}, area=${invocation.area}) id=0x${command.featureId?.toString(16)}"
        }
        Log.i(TAG, "STUB dispatch: ${command.id} ($target) <- $value")
        return VehicleDispatchResult.Success(note = "stub: no HAL call made")
    }
}
