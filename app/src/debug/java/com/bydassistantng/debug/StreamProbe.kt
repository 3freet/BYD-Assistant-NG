package com.bydassistantng.debug

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.bydassistantng.audio.MusicAudio
import com.bydassistantng.audio.NaviAudio
import com.bydassistantng.util.AppLogger

/**
 * Plays silence (zeros) on a track with the audio attributes of [kind], so that `dumpsys media.audio_flinger` shows which stream type and output it
 * lands on while it plays: no route is opened and nothing audible is played. Debug builds only.
 */
internal object StreamProbe {
    fun run(kind: String, seconds: Int) {
        val attributes = when (kind) {
            "assistant" -> AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            "sonification" -> AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            "notification" -> AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            "alarm" -> AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            "music" -> MusicAudio.attributes()
            "navi" -> NaviAudio.attributes()
            else -> return AppLogger.log("StreamProbe", "Unknown kind $kind")
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(44_100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(44_100 * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        AppLogger.log("StreamProbe", "$kind: playing silence for $seconds s")
        val silence = ShortArray(4_410)
        repeat(seconds * 10) { track.write(silence, 0, silence.size) }
        track.release()
        AppLogger.log("StreamProbe", "$kind: done")
    }
}
