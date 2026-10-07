package com.bydassistantng.vehicle

/**
 * The dispatch-time enforcement floor for blocked vehicle domains (adas/motor/engine/gearbox/
 * radar/security/power). Every [VehicleController] implementation MUST call
 * [assertDispatchAllowed] as its first statement. This is defense in depth on top of the fact
 * that a blocked-domain command can never be declared to Gemini as a function in the first place
 * (see [VehicleCommandRegistry]) — two independent layers, not one.
 */
object VehicleSafety {
    fun assertDispatchAllowed(command: VehicleCommand) {
        check(!command.domain.isBlocked) {
            "Refusing to dispatch '${command.id}': domain ${command.domain} is hard-blocked"
        }
    }
}
