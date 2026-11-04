package com.bydassistantng.vehicle

/**
 * How the car's climate system is reached, which is not the same on every car.
 *
 * The A/C commands were verified on a DiLink 5.1 car, where the climate system is a system service of its own
 * (`byd_airconditioning`, see docs/byd-internals/ac-service.md). The log of a DiLink 5.0 car (a BYD Song Plus) shows no such
 * service, and every A/C command failed there. What that car does have is the climate device class (`BYDAutoAcDevice`) that
 * the SDK for these head units documents and that the app already reads the cabin temperature from. So where the service is
 * missing the A/C commands go through that class: the same controls, the same values, confirmed the same way by reading the
 * state back (see [CommandEntryDto.withoutAcService]).
 *
 * The vehicle helper finds the route once, when it starts, and the registry follows it ([VehicleCommandRegistry.useAcRoute]).
 */
internal enum class AcRoute(val key: String) {
    /** The `byd_airconditioning` service answers: what the commands were verified on, and what they use. */
    SERVICE("service"),

    /** No such service, but the car has the climate device class with the methods the A/C commands call. */
    DEVICE("device"),

    /** Neither: the A/C commands are not offered. */
    NONE("none"),
    ;

    companion object {
        /**
         * [serviceAnswers]: whether the A/C service is registered, null where the service manager could not be asked (then the
         * route the commands were verified on stays). [deviceUsable]: whether the climate device class and its methods exist.
         */
        fun choose(serviceAnswers: Boolean?, deviceUsable: Boolean): AcRoute = when {
            serviceAnswers != false -> SERVICE
            deviceUsable -> DEVICE
            else -> NONE
        }

        /** What this car has. Only the vehicle helper asks: the app does not read the services of the car. */
        fun detect(loader: ClassLoader? = AcRoute::class.java.classLoader): AcRoute =
            choose(SystemServices.answers(ReflectionVehicleController.AC_SERVICE_NAME), deviceUsable(loader))

        /** The climate device class exists and has the `start` the power command calls (every version of the SDK has it). */
        fun deviceUsable(loader: ClassLoader? = AcRoute::class.java.classLoader): Boolean = try {
            Class.forName(ReflectionVehicleController.AC_DEVICE_CLASS, false, loader)
                .getMethod("start", Int::class.javaPrimitiveType)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/** What the car's service manager says exists. Only looks. */
internal object SystemServices {
    /** Whether [name] is registered: null where the service manager cannot be asked. */
    fun answers(name: String): Boolean? = try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) != null
    } catch (_: Throwable) {
        null
    }

    /** The names of all registered services, or null where the service manager cannot be asked. */
    fun names(): List<String>? = try {
        (Class.forName("android.os.ServiceManager").getMethod("listServices").invoke(null) as? Array<*>)?.filterIsInstance<String>()
    } catch (_: Throwable) {
        null
    }
}
