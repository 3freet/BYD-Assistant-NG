package com.bydassistantng.audio

import android.media.AudioAttributes
import com.bydassistantng.util.AppLogger

/**
 * The audio attributes that make the car play a track on its navi stream, which with the exterior speaker
 * switch on is heard outside only. The public builder does not take a navi content type or the extra flags,
 * so those are written into the attributes directly, as the exterior sound apps do.
 */
internal object NaviAudio {
    /** `AudioManager.STREAM_NAVI` on this car. */
    const val STREAM_NAVI = 15

    /** `AudioAttributes.CONTENT_TYPE_NAVI` on this car. */
    const val CONTENT_TYPE_NAVI = 6

    /** MUTE_HAPTIC | CONTENT_SPATIALIZED: what the exterior sound apps set. */
    const val FLAGS = 0x4800

    fun attributes(
        streamType: Int = STREAM_NAVI,
        usage: Int = AudioAttributes.USAGE_MEDIA,
        contentType: Int = CONTENT_TYPE_NAVI,
        flags: Int = FLAGS,
    ): AudioAttributes {
        val attributes = AudioAttributes.Builder()
            .setLegacyStreamType(streamType)
            .setUsage(usage)
            .build()
        setField(attributes, "mUsage", usage)
        setField(attributes, "mContentType", contentType)
        setField(attributes, "mFlags", flags)
        return attributes
    }

    private fun setField(target: Any, name: String, value: Int) {
        runCatching {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setInt(target, value)
        }.onFailure { AppLogger.log("NaviAudio", "Could not set $name: $it") }
    }
}
