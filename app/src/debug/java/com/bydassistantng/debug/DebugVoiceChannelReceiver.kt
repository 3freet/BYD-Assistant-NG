package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Plays a short tone on the car's voice stream (17, the one the car's own assistant speaks on) inside the cabin, to
 * see whether an app can use it, how loud it is and whether the music is lowered under it. Sound comes out of the
 * cabin speakers, so say it only when someone is there to listen.
 *
 *     am broadcast -a com.bydassistantng.debug.VOICE_CHANNEL -n <pkg>/com.bydassistantng.debug.DebugVoiceChannelReceiver \
 *         --es mode play --es duck none
 *
 * Options, all optional: `--es mode play|read|noise|levels` (read only logs the streams and the players, noise takes three cabin-noise readings of `dur` ms each, levels plays a steady tone at several volume indexes and reads each at the microphone), `--ei stream 17`,
 * `--es ctor legacy|attrs`, `--es duck none|call|focus|both`, `--ei dur 3000`, `--ef amp 0.3`, `--ei index 15`
 * (a volume index to set on the stream for the try, put back afterwards).
 *
 * Every step is logged as "VoiceChannel: ..." in the app log. Only exists in debug builds.
 */
class DebugVoiceChannelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val attempt = VoiceTry(
            mode = intent.getStringExtra("mode") ?: "play",
            streamType = intent.getIntExtra("stream", 17),
            constructor = intent.getStringExtra("ctor") ?: "legacy",
            duck = intent.getStringExtra("duck") ?: "none",
            durationMs = intent.getIntExtra("dur", 3_000).coerceIn(500, 15_000),
            amplitude = intent.getFloatExtra("amp", 0.3f).toDouble().coerceIn(0.0, 0.8),
            index = if (intent.hasExtra("index")) intent.getIntExtra("index", 0) else null,
        )
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                VoiceChannelTest.run(app, attempt)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
