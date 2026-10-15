package com.bydassistantng.vehicle

interface VehicleController {
    /** Implementations MUST call [VehicleSafety.assertDispatchAllowed] as their first statement. */
    suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult

    /**
     * Asks the car a read-only question; on success the note carries the answer. Nothing is written. Controllers
     * that cannot reach the car say so.
     */
    suspend fun query(query: VehicleQuery): VehicleDispatchResult =
        VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "This controller cannot read the car.")

    /**
     * Runs one passenger-screen command (`screen off`, `open 0 <package>`, ... see
     * [com.bydassistantng.passenger.PassengerScreenCommand]) in the vehicle helper and returns its answer.
     * Controllers that have no helper say so.
     */
    suspend fun passengerScreen(command: String): VehicleDispatchResult =
        VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "This controller cannot reach the passenger screen.")

    /**
     * Runs one instrument-cluster command (`open <package>`, `close keep|disable`, `status`, see
     * [com.bydassistantng.cluster.ClusterScreenCommand]) in the vehicle helper and returns its answer. Controllers
     * that have no helper say so.
     */
    suspend fun clusterScreen(command: String): VehicleDispatchResult =
        VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "This controller cannot reach the instrument cluster.")
}

sealed interface VehicleDispatchResult {
    /** The HAL call was accepted without throwing — this does NOT mean the car actually moved.
     * No read-back/getter is documented anywhere in the source research to confirm real actuation. */
    data class Success(val note: String? = null) : VehicleDispatchResult

    data class Blocked(val reason: String) : VehicleDispatchResult

    data class Failure(val error: VehicleDispatchError, val detail: String? = null) : VehicleDispatchResult
}

enum class VehicleDispatchError {
    CLASS_NOT_FOUND,
    METHOD_NOT_FOUND,
    SECURITY_DENIED,
    INVOCATION_FAILED,
    INVALID_ARGUMENT,
    /** The `"byd_airconditioning"` master service, or a named sub-service resolved from it via
     * `getSub()`, could not be found — see [VehicleInvocation.AcBinderProperty]. */
    SUB_SERVICE_NOT_FOUND,
    /** The ADB connection the helper process is launched through couldn't be reached. */
    HELPER_UNAVAILABLE,
    /** The car is in a state the command can't be applied in (see [StateRequirement]); nothing was sent. */
    PRECONDITION_NOT_MET,
    UNKNOWN,
}
