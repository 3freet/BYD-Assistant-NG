package com.bydassistantng.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "CarLocation"

/** Where the car is, how old that reading is, and what gave it (a location provider, or the Car Link). */
data class CarPosition(val latitude: Double, val longitude: Double, val ageMs: Long, val source: String) {
    /** The position as a favorite place's destination. */
    val destination: String get() = FavoritePlaces.coordinates(latitude, longitude)
}

/** What asking where the car is came to. */
sealed interface CarLocationResult {
    data class Found(val position: CarPosition) : CarLocationResult

    /** The app may not read the precise location, and the Car Link had nothing recent either. */
    data object NotAllowed : CarLocationResult

    /** Nothing recent enough to be where the car is now. */
    data object Unavailable : CarLocationResult
}

/**
 * Where the car is now, for saving a favorite place. The app's own location permission (asked for only when the user
 * presses "Use where the car is now") gives a fresh fix; the Car Link, which runs as the shell, can only read the newest
 * location Android has recorded, so it is the second way, used when that reading is recent.
 */
object CarLocation {
    /** A reading older than this is not where the car is now. */
    const val MAX_AGE_MS = 2 * 60_000L
    // Long enough for a GPS that is already on, short enough for the assistant to answer while the driver waits.
    private const val FIX_TIMEOUT_MS = 15_000L

    /** What the app asks for, the precise location and the approximate one that Android 12 and later want with it. */
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Whether the app may read the precise location. Only that will do for a place: from Android 12 the user may allow
     * just an approximate one, which can be kilometres off, and then the Car Link is asked instead.
     */
    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Where the car is now: from Android when the app may ask, otherwise (or when that finds nothing) from [carLink]. */
    suspend fun now(context: Context, carLink: VehicleController?): CarLocationResult {
        val allowed = allowed(context)
        if (allowed) fromAndroid(context)?.let { return CarLocationResult.Found(it) }
        if (carLink != null) fromCarLink(carLink)?.let { return CarLocationResult.Found(it) }
        return if (allowed) CarLocationResult.Unavailable else CarLocationResult.NotAllowed
    }

    @SuppressLint("MissingPermission")
    private suspend fun fromAndroid(context: Context): CarPosition? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
        // A fresh fix first: the car may have moved since the last reading.
        val provider = listOf(LocationManager.GPS_PROVIDER, FUSED, LocationManager.NETWORK_PROVIDER).firstOrNull { it in providers }
        if (provider != null) {
            val fix = try {
                withTimeoutOrNull(FIX_TIMEOUT_MS) { currentLocation(context, manager, provider) }
            } catch (e: SecurityException) {
                // Android may refuse the app while it is not in front (a voice request): the Car Link is tried next.
                AppLogger.log(TAG, "Android refused a location fix: ${e.message}")
                null
            }
            if (fix != null) return position(fix, ageOf(fix))
            AppLogger.log(TAG, "No location fix from $provider")
        }
        // Then a reading another app (the maps, the car's own services) had a moment ago.
        val recent = providers
            .mapNotNull { name -> runCatching { manager.getLastKnownLocation(name) }.getOrNull() }
            .map { it to ageOf(it) }
            .filter { (_, age) -> age <= MAX_AGE_MS }
            .minByOrNull { (_, age) -> age }
        return recent?.let { (location, age) -> position(location, age) }
    }

    private fun position(location: Location, ageMs: Long) = CarPosition(location.latitude, location.longitude, ageMs, location.provider ?: "android")

    private fun ageOf(location: Location): Long = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } else {
                // Android 10 has no getCurrentLocation; every method is overridden, as its listener has no defaults.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    private suspend fun fromCarLink(carLink: VehicleController): CarPosition? {
        // The Car Link talks over a socket, which Android forbids on the main thread (where Settings asks from).
        val result = withContext(Dispatchers.IO) { carLink.query(VehicleQuery.LOCATION) }
        if (result !is VehicleDispatchResult.Success) {
            AppLogger.log(TAG, "The Car Link had no location: ${(result as? VehicleDispatchResult.Failure)?.detail ?: result}")
            return null
        }
        val position = CarLinkLocation.decode(result.note) ?: return null
        if (position.ageMs > MAX_AGE_MS) {
            AppLogger.log(TAG, "The Car Link's newest location is ${position.ageMs / 1000} s old: too old for where the car is now")
            return null
        }
        return position
    }

    /** "fused" exists from Android 12; asked for by name, it is simply not among the providers before that. */
    private const val FUSED = "fused"
}

/**
 * The car's position as the Car Link reads it: the newest location in Android's own location report (`dumpsys location`),
 * which the shell may read, with its age. The shell cannot ask for a new fix itself: Android keeps location from
 * programs that are not on screen, so this is only as fresh as the last app that asked.
 */
object CarLinkLocation {
    // "Location[gps 23.588000,58.382898 hAcc=20 et=+1m59s561ms alt=0.0 ...]", the form a Location prints itself in.
    private val LOCATION = Regex("""Location\[(\w+) (-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)[^\]]*?\bet=\+?((?:\d+(?:d|h|ms|m|s))+)""")
    private val DURATION_PART = Regex("""(\d+)(ms|d|h|m|s)""")

    /** The newest location in [report], aged at [nowElapsedMs] (the time since boot the report's `et=` counts from). */
    fun newest(report: String, nowElapsedMs: Long): CarPosition? = LOCATION.findAll(report).mapNotNull { match ->
        val at = durationMs(match.groupValues[4]) ?: return@mapNotNull null
        val latitude = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        val longitude = match.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
        CarPosition(latitude, longitude, (nowElapsedMs - at).coerceAtLeast(0), "car link ${match.groupValues[1]}")
    }.minByOrNull { it.ageMs }

    /** Android's way of writing a duration ("1d2h3m4s5ms", "59s561ms") in milliseconds. */
    internal fun durationMs(text: String): Long? {
        val parts = DURATION_PART.findAll(text).toList()
        if (parts.isEmpty()) return null
        return parts.sumOf { part ->
            val n = part.groupValues[1].toLong()
            when (part.groupValues[2]) {
                "d" -> n * 86_400_000
                "h" -> n * 3_600_000
                "m" -> n * 60_000
                "s" -> n * 1_000
                else -> n
            }
        }
    }

    /** How the helper sends a position back: "latitude,longitude,age in ms,source". */
    fun encode(position: CarPosition): String = "${position.destination},${position.ageMs},${position.source}"

    fun decode(note: String?): CarPosition? {
        val parts = note?.split(',', limit = 4) ?: return null
        if (parts.size < 3) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        val age = parts[2].toLongOrNull() ?: return null
        return CarPosition(latitude, longitude, age, parts.getOrNull(3) ?: "car link")
    }

    /** Run in the helper, as the shell: the newest location Android recorded, or why there is none. */
    fun read(): VehicleDispatchResult = try {
        val process = ProcessBuilder("dumpsys", "location").redirectErrorStream(true).start()
        // dumpsys has written everything once its output ends; it exits right after.
        val report = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        newest(report, SystemClock.elapsedRealtime())
            ?.let { VehicleDispatchResult.Success(encode(it)) }
            ?: VehicleDispatchResult.Failure(VehicleDispatchError.NOT_AVAILABLE_ON_THIS_CAR, "Android has recorded no location yet")
    } catch (e: Exception) {
        VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "Could not read Android's location report: ${e.message}")
    }
}
