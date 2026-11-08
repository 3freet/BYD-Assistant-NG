package com.bydassistantng.vehicle

/**
 * Where a car takes a drive mode request. A car is on the first route that its own table of features names, and on none when
 * it names neither (the feature is then not offered anywhere).
 */
internal enum class DriveModeRoute {
    /** The car names neither route: nothing is offered and nothing is sent. */
    NONE,

    /** The Leopard 5's way ([DriveModeControl]): the Setting device's `SETTING_PRESELECTED_DRIVING_MODE_SET`, every mode of the car's drive-mode screen. */
    SETTING,

    /** The classic way ([OperationModeRoute]): the Energy device's operation mode, the six modes that device's class takes. */
    OPERATION_MODE,
}

/**
 * The classic drive mode: what the car's `BYDAutoEnergyDevice` calls its **operation mode**, the switch older cars have for Eco, Normal
 * and Sport (and, in the class's own numbers, Snow, Muddy and Sand) and that the Leopard 5's own switch also moves (its
 * `ENERGY_OPERATION_MODE` follows the mode there). It is a second route to the same thing, for a car whose table does not name the
 * Leopard's feature (a DiLink 3.0 car's did not: its owner says it has drive modes, and which ones is not known; Eco, Normal and
 * Sport were only examples). Which of the six the car has is the car's to say: a request for one it does not have is not
 * followed, and the read-back says so.
 *
 * The encoding is the car's own, from its framework class (`android.hardware.bydauto.energy.BYDAutoEnergyDevice`, seen in the Leopard
 * 5's): `setOperationMode(value)` accepts 1 to 6 and takes the API's numbers (`ENERGY_OPERATION_ECONOMY` 1, `ENERGY_OPERATION_SPORT` 2,
 * `ENERGY_OPERATION_NORMAL` 3, `ENERGY_OPERATION_SNOW` 4, `ENERGY_OPERATION_MUDDY` 5, `ENERGY_OPERATION_SAND` 6), turns the first
 * three into the numbers of the car's drive-mode screen (Normal 1, Eco 2, Sport 3: 3 becomes 1, 1 becomes 2, 2 becomes 3, and 4 to 6
 * stay as they are) and writes that to `ENERGY_OPERATION_MODE_SET` of device [DEVICE_TYPE]; `getOperationMode()` returns
 * `ENERGY_OPERATION_MODE` as it is, in the API's numbers. So a request is the drive-mode screen's number (what this build calls
 * `requestFor`: Sand is 5 and Mud 6 there, and the class's sand is 6 and its muddy 5, which [operationOf] and [rawOf] keep apart),
 * and the state read back is turned into that numbering ([driveModeOf]) to be compared with it. Seen on the Leopard: the state
 * reads 3 in Normal, 1 in Eco and 2 in Sport; nobody has seen 4 to 6 on a car.
 *
 * Like the Leopard's route it is not an entry of the command registry (whose denylist it does not touch): the one feature, the three
 * values and the speed limit are compiled in ([isPermitted], [DriveModeCommand]). Unlike it, its feature is found **by name** in the car's own
 * table and the car's number is used, since the one car it is for has no other car to have been verified against.
 */
internal object OperationModeRoute {
    /** The car's Energy device. */
    const val DEVICE_TYPE = 1006

    const val GROUP = "Energy"
    const val CONTROL_NAME = "ENERGY_OPERATION_MODE_SET"
    const val STATE_NAME = "ENERGY_OPERATION_MODE"

    const val DEVICE_CLASS = "android.hardware.bydauto.energy.BYDAutoEnergyDevice"

    /** The drive-mode screen's numbers for the modes this route may ask for: Normal, Eco, Sport, Snow, Sand and Mud. */
    val REQUESTS: Set<Int> = setOf(1, 2, 3, 4, 5, 6)

    /** The modes this route offers (in the order of [DriveMode]); never the ones the class has no number for (Mountain, Rock, Auto, exit). */
    val MODES: List<DriveMode> = DriveMode.entries.filter { DriveModeControl.requestFor(it) in REQUESTS }

    /**
     * What `setOperationMode` takes for the drive-mode screen's number [driveMode]: Normal 1 is 3, Eco 2 is 1, Sport 3 is 2, Snow 4 is 4,
     * and the class's sand is 6 and its muddy 5 where the screen's Sand is 5 and its Mud 6.
     */
    fun operationOf(driveMode: Int): Int? = when (driveMode) {
        1 -> 3
        2 -> 1
        3 -> 2
        4 -> 4
        5 -> 6
        6 -> 5
        else -> null
    }

    /** What the class writes to `ENERGY_OPERATION_MODE_SET` for the drive-mode screen's number [driveMode]: Normal 1, Eco 2, Sport 3, Snow 4, Sand 6, Mud 5. */
    fun rawOf(driveMode: Int): Int? = when (driveMode) {
        1, 2, 3, 4 -> driveMode
        5 -> 6
        6 -> 5
        else -> null
    }

    /**
     * The drive-mode screen's number for what `ENERGY_OPERATION_MODE` reads (3 Normal, 1 Eco, 2 Sport; 4 Snow, 5 muddy and 6 sand
     * in the class's constants, which the screen numbers 4, 6 and 5); null for any other reading.
     */
    fun driveModeOf(operation: Int): Int? = when (operation) {
        3 -> 1
        1 -> 2
        2 -> 3
        4 -> 4
        5 -> 6
        6 -> 5
        else -> null
    }

    /** Whether the car names both features of this route: only then is it used. */
    fun isOnThisCar(resolve: (group: String, name: String) -> Int? = CarFeatureIds::idOf): Boolean =
        resolve(GROUP, CONTROL_NAME) != null && resolve(GROUP, STATE_NAME) != null

    /** True only for the one write this route is allowed to make: [value] to the operation mode of the Energy device. */
    fun isPermitted(deviceType: Int, value: Int): Boolean = deviceType == DEVICE_TYPE && value in REQUESTS
}
