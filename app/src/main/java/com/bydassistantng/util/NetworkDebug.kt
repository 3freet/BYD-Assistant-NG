package com.bydassistantng.util

/**
 * A test hook, switched on only by the debug build's `DebugOfflineReceiver`: while set, the Live connection is
 * aimed at a host that can't resolve, so the real "no internet" path (the same exception a dead hotspot
 * throws, the tone, the banner) can be exercised on the car without cutting the ADB link it is tested over.
 * Nothing in a release build ever sets it.
 */
object NetworkDebug {
    @Volatile var simulateOffline = false
}
