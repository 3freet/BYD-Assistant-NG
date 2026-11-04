package com.bydassistantng.vehicle

import android.os.IBinder

/**
 * How the car's climate system is reached, which is not the same on every car.
 *
 * The A/C commands were verified on a DiLink 5.1 car, where the climate system is a system service of its own
 * (`byd_airconditioning`, see docs/byd-internals/ac-service.md). The log of a DiLink 5.0 car (a BYD Song Plus) shows no such
 * service, and every A/C command failed there. What that car does have is the climate device class (`BYDAutoAcDevice`) that
 * BYD's API documentation describes and that the app already reads the cabin temperature from. So where the service is missing
 * the A/C commands go through that class, or straight to the car's native service where the car refuses the helper that class,
 * with the same values, confirmed the same way by reading the state back (see [CommandEntryDto.withoutAcService]).
 *
 * The vehicle helper finds the route once, when it starts, and the registry follows it ([VehicleCommandRegistry.useAcRoute]).
 */
internal enum class AcRoute(val key: String) {
    /** The climate service answers: what the commands were verified on, and what they use. */
    SERVICE("service"),

    /** No such service, but the car has the climate device class (or its native service) to do the same by. */
    DEVICE("device"),

    /** Neither: the A/C commands are not offered. */
    NONE("none"),
    ;

    companion object {
        /** A route set by hand to try the others on a car that has the service: `setprop debug.bydang.acroute service|device|none`. */
        const val PROPERTY = "debug.bydang.acroute"

        /**
         * [serviceAnswers]: whether the climate service is registered, null where the service manager could not be asked (then the
         * route the commands were verified on stays). [deviceUsable]: whether the climate device class, or the native service
         * behind it, can be used.
         */
        fun choose(serviceAnswers: Boolean?, deviceUsable: Boolean): AcRoute = when {
            serviceAnswers != false -> SERVICE
            deviceUsable -> DEVICE
            else -> NONE
        }

        /** The route a property value names; null for anything else. */
        fun forced(value: String?): AcRoute? = entries.firstOrNull { it.key == value?.trim()?.lowercase() }

        /** What this car has. Only the vehicle helper asks: the app does not read the services of the car. */
        fun detect(loader: ClassLoader? = AcRoute::class.java.classLoader): AcRoute =
            forced(HeadUnitProperties.read(PROPERTY)) ?: choose(AcService.look(), deviceUsable(loader))

        /** The climate device class exists and has the `start` the power command calls, or the car's native service is there to be called. */
        fun deviceUsable(loader: ClassLoader? = AcRoute::class.java.classLoader): Boolean =
            kitUsable(loader) || SystemServices.answers(NativeAutoservice.SERVICE_NAME) == true

        private fun kitUsable(loader: ClassLoader?): Boolean = try {
            Class.forName(ReflectionVehicleController.AC_DEVICE_CLASS, false, loader)
                .getMethod("start", Int::class.javaPrimitiveType)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * The climate service of the car, which is not called the same on every one. DiLink 5.1 calls it `byd_airconditioning`; the
 * `others=` list in the log of a DiLink 5.0 car (a BYD Song Plus) had `airconditioning` and no `byd_airconditioning`. The other name
 * is only used when its binder says it speaks the protocol the commands are written for, so a service that merely has a
 * similar name is never sent anything.
 */
internal object AcService {
    const val PRIMARY = "byd_airconditioning"
    const val ALIAS = "airconditioning"

    /** The interface of the master service the sub-services are asked of (see [ReflectionVehicleController]). */
    const val MASTER_DESCRIPTOR = "com.byd.ac.IBydAcService"

    /** The name the car's climate service answers to, as [look] last found it. */
    @Volatile
    var name: String = PRIMARY
        private set

    /** Whether the climate service is there: true (under [name]), false (under neither name), null (the service manager cannot be asked). */
    fun look(): Boolean? {
        when (SystemServices.answers(PRIMARY)) {
            true -> return true.also { name = PRIMARY }
            null -> return null
            false -> Unit
        }
        if (SystemServices.descriptorOf(ALIAS) == MASTER_DESCRIPTOR) {
            name = ALIAS
            return true
        }
        return false
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

    /** The binder registered under [name], or null. */
    fun binder(name: String): IBinder? = try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    } catch (_: Throwable) {
        null
    }

    /**
     * The interface descriptor of the service [name], or null where there is none or it cannot be asked. Asking is the plain
     * interface transaction every binder answers: nothing is read from or written to the car.
     */
    fun descriptorOf(name: String): String? = try {
        binder(name)?.interfaceDescriptor?.takeIf { it.isNotEmpty() }
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
