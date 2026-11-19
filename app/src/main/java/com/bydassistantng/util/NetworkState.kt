package com.bydassistantng.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkState {
    /** Whether Android believes there is a working internet connection right now. Only a hint, used to
     * explain a connection that timed out: on this head unit (tethered to a phone) a dead hotspot leaves
     * the Wi-Fi "connected" but not validated. */
    fun isOnline(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: SecurityException) {
        true // can't tell, so don't claim to be offline
    }

    /** Whether the device is connected to any network that carries internet traffic, validated or not. False is
     * certain (nothing can connect: the head unit wakes before the phone's hotspot is joined and has no other
     * network); true only says something is connected, not that it works. Where it cannot be asked, true. */
    fun hasNetwork(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        manager == null || manager.activeNetwork != null
    } catch (_: SecurityException) {
        true
    }
}
