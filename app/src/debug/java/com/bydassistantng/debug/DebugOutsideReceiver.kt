package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.util.AppLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface OutsidePreferencesEntryPoint {
    fun preferences(): PreferencesRepository
}

/**
 * Tries the car's exterior speaker from a debug build. Sound comes out of the car, so everything that
 * switches it on needs `--ez present true`: say it only with someone standing by the car who knows. Like
 * the assistant's own use of it, it is refused unless the exterior speaker is switched on in Advanced
 * settings (switching it off, or reading its state, is always allowed).
 *
 *     am broadcast -a com.bydassistantng.debug.OUTSIDE -n <pkg>/com.bydassistantng.debug.DebugOutsideReceiver \
 *         --es mode chime --ez present true
 *
 * `--es recipe v20|v22` presets the settings of the two versions of the exterior sound app (stream volume
 * to max, or to zero with audio focus; both open the microphone first); anything below still overrides it.
 *
 * Modes (`--es mode`): `chime` (default) switches on, plays a short tone, switches off; `switch` holds the
 * switch on for `dur` ms without any sound of ours; `state` only reads the cabin mute state; `off` only
 * switches off. With `--es sound <name>` the chime is replaced by that sound effect (doorbell, chime, bell,
 * beep, alarm, horn, whistle, bird, cat). Options, all optional: `--ei dur 1500`, `--ef amp 0.3`, `--es volume keep|zero|max`,
 * `--ei gain <millibels>`, `--ez steady true` (a tone that does not fade), `--ei index 10` (navi volume index), `--ef tvol 0.3` (the track's own volume), `--ef pgain 0.3` (scales the samples themselves), `--ez focus true`, `--ez mic true`, `--ei lead 300`, `--ei tail 400`,
 * `--ei stream 15`, `--ei content 6`, `--ei flags 18432`.
 *
 * Every step is logged as "OutsideSpeaker: …" in the app log. Only exists in debug builds.
 */
class DebugOutsideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Presets for the two versions of the exterior sound app; any extra below still overrides them.
        val defaults = when (intent.getStringExtra("recipe")) {
            "v20" -> OutsideTry(volume = StreamVolume.MAX, mic = true)
            "v22" -> OutsideTry(volume = StreamVolume.ZERO, focus = true, mic = true)
            else -> OutsideTry()
        }
        val attempt = OutsideTry(
            mode = intent.getStringExtra("mode") ?: defaults.mode,
            streamType = intent.getIntExtra("stream", defaults.streamType),
            contentType = intent.getIntExtra("content", defaults.contentType),
            flags = intent.getIntExtra("flags", defaults.flags),
            volume = when (intent.getStringExtra("volume")?.lowercase()) {
                "zero" -> StreamVolume.ZERO
                "max" -> StreamVolume.MAX
                "keep" -> StreamVolume.KEEP
                else -> defaults.volume
            },
            gainMb = intent.getIntExtra("gain", defaults.gainMb),
            focus = intent.getBooleanExtra("focus", defaults.focus),
            mic = intent.getBooleanExtra("mic", defaults.mic),
            leadMs = intent.getIntExtra("lead", defaults.leadMs.toInt()).toLong().coerceIn(0, 2_000),
            tailMs = intent.getIntExtra("tail", defaults.tailMs.toInt()).toLong().coerceIn(0, 2_000),
            durationMs = intent.getIntExtra("dur", defaults.durationMs).coerceIn(200, 15_000),
            steady = intent.getBooleanExtra("steady", defaults.steady),
            effect = intent.getStringExtra("sound") ?: defaults.effect,
            naviIndex = if (intent.hasExtra("index")) intent.getIntExtra("index", 0).coerceIn(0, 15) else defaults.naviIndex,
            trackVolume = intent.getFloatExtra("tvol", defaults.trackVolume).coerceIn(0f, 1f),
            pcmGain = intent.getFloatExtra("pgain", defaults.pcmGain).coerceIn(0f, 1f),
            amplitude = intent.getFloatExtra("amp", defaults.amplitude.toFloat()).toDouble().coerceIn(0.0, 1.0),
        )
        val switchesOn = attempt.mode != "state" && attempt.mode != "off"
        if (switchesOn && !intent.getBooleanExtra("present", false)) {
            AppLogger.log("OutsideSpeaker", "Refused: mode ${attempt.mode} makes sound outside the car; pass --ez present true once someone is by the car")
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                if (switchesOn) {
                    val preferences = EntryPointAccessors.fromApplication(app, OutsidePreferencesEntryPoint::class.java).preferences()
                    if (!runBlocking { preferences.exteriorSpeakerEnabled.first() }) {
                        AppLogger.log("OutsideSpeaker", "Refused: the exterior speaker is switched off in Advanced settings")
                        return@Thread
                    }
                }
                runBlocking { OutsideSpeakerTest.run(app, attempt) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
