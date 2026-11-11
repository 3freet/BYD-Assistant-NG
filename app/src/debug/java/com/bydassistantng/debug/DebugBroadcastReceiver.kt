package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.audio.LiveBroadcast
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
internal interface LiveBroadcastEntryPoint {
    fun liveBroadcast(): LiveBroadcast

    fun earcons(): com.bydassistantng.audio.Earcons

    fun exteriorSpeaker(): java.util.Optional<com.bydassistantng.audio.ExteriorSpeaker>

    fun preferences(): PreferencesRepository
}

/**
 * Starts and stops the live microphone broadcast from a debug build, without the steering-wheel button. What the cabin microphone hears
 * comes out of the car, so starting needs `--ez present true`: say it only with someone by the car who knows. Like the wheel's hold, it
 * is refused unless the exterior speaker is switched on in Settings (the broadcast's own switch is not asked: this is the test of the
 * audio path, not of the setting).
 *
 *     am broadcast -a com.bydassistantng.debug.BROADCAST -n <pkg>/com.bydassistantng.debug.DebugBroadcastReceiver \
 *         --es action start --ez present true [--es volume low]
 *     ... --es action stop
 *     ... --es action lab --ez present true [--es volume low] [--es set effects]   (the latency lab, see [BroadcastLab])
 *     ... --es action status
 *
 * Every step is logged as "LiveBroadcast: ..." in the app log. Only exists in debug builds.
 */
class DebugBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, LiveBroadcastEntryPoint::class.java)
        val broadcast = entry.liveBroadcast()
        when (val action = intent.getStringExtra("action") ?: "status") {
            "start" -> {
                if (!intent.getBooleanExtra("present", false)) {
                    AppLogger.log("LiveBroadcast", "Refused: it sends the cabin microphone out of the car; pass --ez present true once someone is by the car")
                    return
                }
                if (!runBlocking { entry.preferences().exteriorSpeakerEnabled.first() }) {
                    AppLogger.log("LiveBroadcast", "Refused: the exterior speaker is switched off in Settings")
                    return
                }
                AppLogger.log("LiveBroadcast", "Debug start: ${broadcast.start() ?: "under way"}")
            }
            "lab" -> {
                if (!intent.getBooleanExtra("present", false)) {
                    AppLogger.log("LiveBroadcast", "Refused: the lab makes short sounds outside the car; pass --ez present true once someone is by the car")
                    return
                }
                val speaker = entry.exteriorSpeaker().orElse(null) ?: return AppLogger.log("LiveBroadcast", "No exterior speaker on this car")
                if (!runBlocking { entry.preferences().exteriorSpeakerEnabled.first() }) {
                    AppLogger.log("LiveBroadcast", "Refused: the exterior speaker is switched off in Settings")
                    return
                }
                val volume = ExteriorVolume.from(intent.getStringExtra("volume")) ?: ExteriorVolume.LOW
                val lab = BroadcastLab(context.applicationContext, speaker)
                if (intent.getStringExtra("set") == "speech") {
                    Thread { runBlocking { lab.speech(volume) } }.start()
                    return
                }
                val configs = when (intent.getStringExtra("set")) {
                    "effects" -> lab.effectConfigs
                    "media" -> lab.routeConfigs.filter { it.name == "media-route-double-buffer" }
                    else -> lab.routeConfigs
                }
                Thread { runBlocking { lab.run(volume, configs) } }.start()
            }
            // Sets how the next broadcast is made, with no sound of its own: --ei mode 0..4 --ei delay <ms> --ei ref <chunks> --ez echo true|false
            // --ez comm true|false (the audio mode "in communication") --ei start <ms the track waits before it starts> --ez media true|false
            // --ei gain <percent of the voice level, 100 = as it is> --ez agcoff true|false --ez stereo true|false --ez music true|false (music goes out too)
            "tune" -> {
                if (intent.hasExtra("echo")) broadcast.echoCancel = intent.getBooleanExtra("echo", true)
                if (intent.hasExtra("mode")) broadcast.echoMode = intent.getIntExtra("mode", 1)
                if (intent.hasExtra("delay")) broadcast.echoDelayMs = intent.getIntExtra("delay", 150)
                if (intent.hasExtra("ref")) broadcast.echoReferenceChunks = intent.getIntExtra("ref", 2)
                if (intent.hasExtra("comm")) broadcast.communicationMode = intent.getBooleanExtra("comm", true)
                if (intent.hasExtra("start")) broadcast.trackStartMs = intent.getIntExtra("start", 60)
                if (intent.hasExtra("settle")) broadcast.settleMs = intent.getIntExtra("settle", -1).toLong()
                if (intent.hasExtra("media")) broadcast.useMediaRoute = intent.getBooleanExtra("media", true)
                if (intent.hasExtra("gain")) broadcast.voiceGain = intent.getIntExtra("gain", 100) / 100f
                if (intent.hasExtra("agcoff")) broadcast.microphoneGainControlOff = intent.getBooleanExtra("agcoff", false)
                if (intent.hasExtra("stereo")) broadcast.stereoCapture = intent.getBooleanExtra("stereo", true)
                if (intent.hasExtra("navdelay")) broadcast.navEchoDelayMs = intent.getIntExtra("navdelay", 400)
                intent.getStringExtra("music")?.let { word -> com.bydassistantng.audio.MusicMode.from(word)?.let { broadcast.setMusicMode(it) } }
                intent.getStringExtra("loud")?.let { word -> com.bydassistantng.audio.ExteriorVolume.from(word)?.let { broadcast.setLoudness(it) } }
                AppLogger.log(
                    "LiveBroadcast",
                    "Tuning: echo canceller ${broadcast.echoCancel}, mode ${broadcast.echoMode}, delay ${broadcast.echoDelayMs} ms, reference ${broadcast.echoReferenceChunks} chunks back, " +
                        "communication mode ${broadcast.communicationMode}, track start ${broadcast.trackStartMs} ms, media route ${broadcast.useMediaRoute}, " +
                        "voice gain ${broadcast.voiceGain}, unit gain control off ${broadcast.microphoneGainControlOff}, stereo ${broadcast.stereoCapture}, music ${broadcast.musicMode.value.word}, loudness ${broadcast.loudness.value.word}, nav echo hint ${broadcast.navEchoDelayMs} ms",
                )
            }
            // The echo canceller's library on computed signals, no sound at all: --ei delay <ms> --ei mode 0..4
            "echotest" -> {
                val delay = intent.getIntExtra("delay", 75)
                val mode = intent.getIntExtra("mode", 1)
                Thread { EchoSelfTest.run(delay, mode) }.start()
            }
            // Records the cabin microphone as the broadcast would hear it, with no sound out of the car: --ei seconds N (default 5) --ez stereo true|false
            // --ez agcoff true|false. Logs what the unit gave (channels, levels) and writes files/listen_<time>.pcm (16 kHz mono).
            "listen" -> {
                val seconds = intent.getIntExtra("seconds", 5).coerceIn(1, 120)
                val stereo = intent.getBooleanExtra("stereo", true)
                val agcOff = intent.getBooleanExtra("agcoff", false)
                Thread { MicListener.run(context.applicationContext, seconds, stereo, agcOff) }.start()
            }
            // Shows or hides the music and loudness choices with no broadcast and no sound, to look at them and try a tap: --ez show true|false --ez music true|false
            "choices", "musicbutton" -> com.bydassistantng.service.WheelKeyService.instance?.showChoicesForDebug(intent.getBooleanExtra("show", true), intent.getBooleanExtra("music", true))
                ?: AppLogger.log("LiveBroadcast", "The wheel key service is not connected")
            // How long the broadcast's track keeps sound waiting, measured with silence and no route (nothing is heard anywhere): --ei start <ms, 0 = the
            // platform's own> --ei seconds N (default 4). The result is logged as TrackLatencyTest.
            "tracktest" -> {
                val speaker = entry.exteriorSpeaker().orElse(null) ?: return AppLogger.log("LiveBroadcast", "No exterior speaker on this car")
                val start = intent.getIntExtra("start", 0)
                val seconds = intent.getIntExtra("seconds", 4).coerceIn(1, 20)
                val navigation = intent.getStringExtra("route") == "nav"
                val boost = intent.getIntExtra("boost", 0)
                Thread { TrackLatencyTest.run(speaker, start, seconds, navigation, boost) }.start()
            }
            // Stops the music as a broadcast would and lets it go on after 1.5 s, with NO route and no sound outside (the cabin music pauses): logged as
            // LiveBroadcast "Music stopped after ...".
            "musicwait" -> Thread { kotlinx.coroutines.runBlocking { broadcast.debugMusicWait() } }.start()
            // Plays the tone that says a live broadcast has started in the cabin (no route is opened, so it is heard there only): --ez nav true plays it on the
            // navigation stream (what the fast route's broadcast does), false on the plain path (what the slow route's does)
            "livetone" -> entry.earcons().playLive(intent.getBooleanExtra("nav", true))
            // Plays silence on a track with the attributes of a kind of sound (assistant, sonification, notification, alarm, music, navi) for --ei seconds N
            // (default 6), so that `dumpsys media.audio_flinger` can be read for the stream and output it lands on: --es kind assistant
            "streamprobe" -> {
                val kind = intent.getStringExtra("kind") ?: "assistant"
                val seconds = intent.getIntExtra("seconds", 6).coerceIn(1, 20)
                Thread { StreamProbe.run(kind, seconds) }.start()
            }
            // How fast the system's "is music playing" answers follow a player that pauses, with a silent player of our own and no route: logged as MusicStateProbe.
            "musicstate" -> Thread { MusicStateProbe.run(context.applicationContext) }.start()
            // Shows the level meter for a few seconds with made-up levels, no broadcast and no sound, to look at it: --ei seconds N (default 12)
            "meter" -> com.bydassistantng.service.WheelKeyService.instance?.showMeterDemoForDebug(intent.getIntExtra("seconds", 12).coerceIn(2, 60))
                ?: AppLogger.log("LiveBroadcast", "The wheel key service is not connected")
            "stop" -> broadcast.stop()
            "status" -> AppLogger.log("LiveBroadcast", "State ${broadcast.state.value}, available ${broadcast.isAvailable}")
            else -> AppLogger.log("LiveBroadcast", "Unknown action '$action' (start, stop, status)")
        }
    }
}
