package com.bydassistantng.debug

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.bydassistantng.audio.MusicAudio
import com.bydassistantng.util.AppLogger

/**
 * How fast the system's two answers to "is music playing" follow a player that pauses, with a player of our own that plays silence (zeros) on the music
 * stream: the player list ([AudioManager.getActivePlaybackConfigurations], the apps' own reports) and [AudioManager.isMusicActive] (the audio policy's).
 * No route is opened and nothing audible is played. Debug builds only.
 */
internal object MusicStateProbe {
    private const val TAG = "MusicStateProbe"

    // A tool for the car (Android 13), run by hand: the calls that need a newer Android than the app's lowest are fine there.
    @SuppressLint("NewApi")
    fun run(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun players(): Int = audio.activePlaybackConfigurations.count {
            val usage = it.audioAttributes.usage
            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME || usage == AudioAttributes.USAGE_UNKNOWN
        }
        AppLogger.log(TAG, "before: isMusicActive ${audio.isMusicActive}, players on the music stream ${players()}")
        val track = AudioTrack.Builder()
            .setAudioAttributes(MusicAudio.attributes())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(6_400)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val silence = ShortArray(1_600)
        repeat(6) {
            track.write(silence, 0, silence.size)
            Thread.sleep(100)
        }
        AppLogger.log(TAG, "while a silent player plays: isMusicActive ${audio.isMusicActive}, players ${players()}")
        val paused = SystemClock.elapsedRealtime()
        track.pause()
        var playersGoneAfter = -1L
        var inactiveAfter = -1L
        while (SystemClock.elapsedRealtime() - paused < 4_000 && (playersGoneAfter < 0 || inactiveAfter < 0)) {
            val now = SystemClock.elapsedRealtime() - paused
            if (playersGoneAfter < 0 && players() == 0) playersGoneAfter = now
            if (inactiveAfter < 0 && !audio.isMusicActive) inactiveAfter = now
            Thread.sleep(10)
        }
        track.release()
        AppLogger.log(TAG, "after pause(): the player list is empty after $playersGoneAfter ms, isMusicActive is false after $inactiveAfter ms (-1: not within 4 s; something else was playing)")
    }
}
