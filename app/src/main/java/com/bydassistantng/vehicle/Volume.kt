package com.bydassistantng.vehicle

import android.content.Context
import android.media.AudioManager
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "What's the volume?" / "turn it up a bit": the car's media volume, so the assistant can answer and make relative
 * changes from the real value.
 *
 * The car's volume *is* Android's media stream (stream 3, 0..39 on the car this was checked on), which any app
 * may read, so this runs in the app itself: no helper, no ADB, and it needs no vehicle-control switch. Setting it
 * is a registry command (`audio.volume`) and does go through the helper.
 */
object VolumeTool {
    const val FUNCTION_NAME = "get_volume"

    private const val MEDIA_STREAM = AudioManager.STREAM_MUSIC

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Read the car's current media volume (music, radio, video) and its maximum. Use it to answer " +
            "\"what is the volume\", and before a relative change such as \"turn it up a bit\": read first, " +
            "then set the new value.",
    )

    fun report(media: Int?, max: Int?): JsonObject = buildJsonObject {
        if (media == null || max == null || max <= 0 || media < 0) {
            put("status", "error")
            put("error", "NOT_AVAILABLE")
            put("detail", "The car did not report its volume.")
            return@buildJsonObject
        }
        put("status", "ok")
        put("media_volume", media)
        put("media_volume_max", max)
        put("media_volume_percent", Math.round(media * 100f / max))
    }

    /** The answer for the model, read from the system's audio service. */
    fun read(context: Context): JsonObject = try {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        report(audio.getStreamVolume(MEDIA_STREAM), audio.getStreamMaxVolume(MEDIA_STREAM))
    } catch (_: Exception) {
        report(null, null)
    }
}
