package com.bydassistantng.vehicle

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * The car's native service (`autoservice`) called directly, without the Java device classes in front of it.
 *
 * The device classes (`BYDAutoBodyworkDevice` and the others) are a convenience layer over this service: a read or a write of
 * one feature ends in a call like the ones here. On a DiLink 5.0 car the climate, bodywork, setting and instrument classes also
 * check, when the helper asks for one, that the asking process holds a `BYDAUTO_<DEVICE>_COMMON` permission, which the ADB
 * shell the helper runs as cannot hold (see `docs/byd-internals/hal-and-permissions.md`). The service itself does not ask that
 * of such a caller: its own check lets every caller with a uid below 10,000 (root, system, the shell) through and asks apps
 * for the device's signature-level permission. So where the class refuses the helper, the helper can still make the call
 * itself. That is all this is: the same request, one layer lower. The caller is still the shell, the service is still the
 * authority on who may use it, and what the helper may ask for is still only what the command registry lists.
 *
 * The layout of a call is the one the car's own client library uses (read from the binder stub in `libbydauto.so`):
 *  - `5` reads an int: the interface token, then the device type and the feature id (ints); the reply is the service's status
 *    (0 when it answered) and then the value.
 *  - `6` writes an int: the interface token, then the device type, the feature id and the value, and no callback; the reply is
 *    the status (1 or more when the request took, 0 when it was accepted and ignored, negative when it was refused).
 * The interface token is the descriptor the service's own binder gives, so it is asked for, not written down here.
 */
internal class NativeAutoservice private constructor(private val binder: IBinder, val descriptor: String) {
    /** One answer: [status] is the service's own return code and [value] what a read returned (0 after a write). */
    data class Reply(val status: Int, val value: Int)

    fun getInt(deviceType: Int, featureId: Int): Reply? = call(TX_GET_INT, deviceType, featureId, null)

    fun setInt(deviceType: Int, featureId: Int, value: Int): Reply? = call(TX_SET_INT, deviceType, featureId, value)

    private fun call(code: Int, deviceType: Int, featureId: Int, value: Int?): Reply? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeInt(deviceType)
            data.writeInt(featureId)
            if (value != null) data.writeInt(value)
            if (!binder.transact(code, data, reply, 0)) return null
            val words = ArrayList<Int>(2)
            while (words.size < 2 && reply.dataAvail() >= Int.SIZE_BYTES) words += reply.readInt()
            decode(words)
        } catch (e: Exception) {
            Log.w(TAG, "autoservice call $code ($deviceType, $featureId) failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        const val SERVICE_NAME = "autoservice"
        const val TX_GET_INT = 5
        const val TX_SET_INT = 6
        private const val TAG = "NativeAutoservice"

        /** The service, or null where the car has none or its binder gives no descriptor to speak to it with. */
        fun connect(): NativeAutoservice? = try {
            val binder = SystemServices.binder(SERVICE_NAME)
            val descriptor = binder?.interfaceDescriptor
            if (binder != null && !descriptor.isNullOrEmpty()) NativeAutoservice(binder, descriptor) else null
        } catch (_: Throwable) {
            null
        }

        /** The words of a reply as an answer: status then value; a reply with only a status (a write) has the value 0. */
        internal fun decode(words: List<Int>): Reply? = when {
            words.size >= 2 -> Reply(words[0], words[1])
            words.size == 1 -> Reply(words[0], 0)
            else -> null
        }
    }
}

/** What the answers of the native service mean, as the rest of the helper deals in them. */
internal object NativeAnswer {
    /** What the log shows after a note that came from the native service, and the model is never told. */
    const val NATIVE_TAG = " [native]"

    /** The status the Java classes turn into their "permission deny" exception. */
    const val PERMISSION_DENIED = -2147482644

    /** The status of a control that is not writable on this car, or a read that has no value. */
    const val NOT_AVAILABLE = -10011

    private const val NO_READING = 65535

    /** The value of a read that worked; null when the service said no, or gave the "no reading" values the classes give. */
    fun readValue(reply: NativeAutoservice.Reply?): Int? =
        reply?.takeIf { it.status == 0 && it.value >= 0 && it.value != NO_READING }?.value

    /** The value of a read the service answered, whatever it is (a temperature may be below zero); null when it did not answer. */
    fun rawValue(reply: NativeAutoservice.Reply?): Int? = reply?.takeIf { it.status == 0 }?.value

    /** A write the service took (or accepted and ignored: the read-back says which), or why not. */
    fun writeResult(reply: NativeAutoservice.Reply?): VehicleDispatchResult = when {
        reply == null -> VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "The car's service did not answer. Nothing was sent.")
        reply.status == PERMISSION_DENIED ->
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, "The car's service does not let this app use that control. Nothing was sent.")
        reply.status == NOT_AVAILABLE ->
            VehicleDispatchResult.Failure(VehicleDispatchError.NOT_AVAILABLE_ON_THIS_CAR, "This car's service does not take that control. Nothing was sent.")
        reply.status < 0 -> VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "The car's service refused it (answer ${reply.status}).")
        else -> VehicleDispatchResult.Success("HAL returned ${reply.status}$NATIVE_TAG")
    }
}

/** How the helper chooses between a device class and the native service. */
internal enum class NativeMode {
    /** The device class, and the native service where the car's permission check refuses the helper that class. */
    AUTO,

    /** The native service for everything that has a route to it: to try it on a car whose device classes accept the helper. */
    ALWAYS,

    /** The device classes only. */
    NEVER,
    ;

    companion object {
        const val PROPERTY = "debug.bydang.nativeroute"

        fun parse(text: String?): NativeMode = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: AUTO

        /** The mode set on this head unit (by `setprop`, for a test); [AUTO] where none is. */
        fun current(): NativeMode = parse(HeadUnitProperties.read(PROPERTY))
    }
}

internal object NativeRoute {
    /** Whether a device class is left alone and the native service used from the first call. */
    fun preferNative(mode: NativeMode, kitRefusedBefore: Boolean): Boolean = when (mode) {
        NativeMode.ALWAYS -> true
        NativeMode.NEVER -> false
        NativeMode.AUTO -> kitRefusedBefore
    }

    /** Whether the native service is tried when the car's permission check has just refused the helper a device class. */
    fun fallBack(mode: NativeMode): Boolean = mode != NativeMode.NEVER
}
