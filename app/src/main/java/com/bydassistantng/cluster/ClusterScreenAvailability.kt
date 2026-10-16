package com.bydassistantng.cluster

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import com.bydassistantng.BuildConfig
import com.bydassistantng.vehicle.CarFeatureIds

/**
 * Whether the instrument cluster feature is offered at all: only in a build with the experimental car features
 * (debug and beta builds), and only on a car that looks like the one it was found on: the framework names the map
 * type the way it was verified, the cluster's own display is there, and so is BYD's navigation app (which carries the
 * cluster, and may well be disabled). Anywhere else the feature has no switch and no tool.
 */
object ClusterScreenAvailability {
    fun isOnThisCar(context: Context): Boolean =
        BuildConfig.EXPERIMENTAL_CAR_FEATURES && namesMatch() && hasClusterDisplay(context) && hasNavigationApp(context)

    private fun namesMatch(): Boolean =
        CarFeatureIds.idOf("Instrument", "INSTRUMENT_NAVI_TYPE") == ClusterScreenParsing.MAP_TYPE_STATE_ID &&
            CarFeatureIds.idOf("Instrument", "INSTRUMENT_NAVI_TYPE_SET") == ClusterScreenParsing.MAP_TYPE_CONTROL_ID

    private fun hasClusterDisplay(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        ClusterScreenParsing.clusterDisplay(manager.displays.map { NamedDisplay(it.displayId, it.name.orEmpty()) }) != null
    }.getOrDefault(false)

    private fun hasNavigationApp(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(ClusterScreenParsing.MAPS_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
