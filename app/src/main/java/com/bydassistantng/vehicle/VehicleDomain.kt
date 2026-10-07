package com.bydassistantng.vehicle

/**
 * Vehicle subsystem groupings of the BYD HAL. `isBlocked`
 * domains control how the car drives or brakes — a misheard voice command there is a crash, not
 * an inconvenience — and must never be reachable. In this app the denylist is even stronger than
 * before: a blocked-domain command is never even declared to Gemini as a callable function (see
 * [VehicleCommandRegistry]), so the model has no way to attempt one in the first place. [UNKNOWN]
 * exists so anything that fails to resolve to a real domain fails closed, not open.
 */
enum class VehicleDomain(val isBlocked: Boolean) {
    CLIMATE(isBlocked = false),
    BODYWORK(isBlocked = false),
    AUDIO(isBlocked = false),
    LIGHT(isBlocked = false),
    SETTING(isBlocked = false),

    ADAS(isBlocked = true),
    MOTOR(isBlocked = true),
    ENGINE(isBlocked = true),
    GEARBOX(isBlocked = true),
    RADAR(isBlocked = true),
    SECURITY(isBlocked = true),
    POWER(isBlocked = true),

    UNKNOWN(isBlocked = true),
}
