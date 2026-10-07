package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.bydassistantng.audio.pcm16Rms
import com.bydassistantng.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private const val TAG = "EchoTest"
private const val IN_RATE = 16_000
private const val OUT_RATE = 24_000
private const val CHUNK_BYTES = 1_600 // 50ms

/**
 * DEBUG BUILDS ONLY. Makes a few seconds of speech-like noise through the car's speakers (on the same
 * audio path as the assistant's voice) while recording the mic with each candidate configuration, and
 * logs how much of that noise each configuration lets back in. The lower the "echo" level, the better
 * that configuration would let the assistant be interrupted by voice.
 *
 * Trigger: `adb shell am broadcast -a com.bydassistantng.debug.ECHO_TEST -n <pkg>/com.bydassistantng.debug.DebugEchoTestReceiver`
 * Results: lines tagged [EchoTest] in the app log.
 */
class DebugEchoTestReceiver : BroadcastReceiver() {
    private data class Config(val name: String, val source: Int, val aec: Boolean = false, val ns: Boolean = false, val agc: Boolean = false)

    private val configs = listOf(
        Config("A  VOICE_RECOGNITION (what the app uses now)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        Config("B  VOICE_COMMUNICATION (platform default processing)", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
        Config("C  VOICE_COMMUNICATION + AEC + NS + AGC forced on", MediaRecorder.AudioSource.VOICE_COMMUNICATION, aec = true, ns = true, agc = true),
        Config("D  VOICE_RECOGNITION + AEC forced on", MediaRecorder.AudioSource.VOICE_RECOGNITION, aec = true),
        Config("E  MIC", MediaRecorder.AudioSource.MIC),
        Config("F  UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED),
    )

    override fun onReceive(context: Context, intent: Intent) {
        // The app process stays alive as the host of the accessibility service, so no goAsync() needed.
        CoroutineScope(Dispatchers.Default).launch {
            AppLogger.log(TAG, "=== echo test start: ${configs.size} configurations, ~3.5s each ===")
            for (config in configs) {
                runCatching { run(config) }.onFailure { AppLogger.logError(TAG, "${config.name} failed", it) }
                delay(600)
            }
            AppLogger.log(TAG, "=== echo test done ===")
        }
    }

    private suspend fun run(config: Config) {
        val minBuffer = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(config.source, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.log(TAG, "${config.name}: AudioRecord would not initialise")
            record.release()
            return
        }
        val effects = buildList {
            if (config.aec && AcousticEchoCanceler.isAvailable()) add(AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true })
            if (config.ns && NoiseSuppressor.isAvailable()) add(NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true })
            if (config.agc && AutomaticGainControl.isAvailable()) add(AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = true })
        }

        val signal = testSignal(seconds = 2.5)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(signal.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(signal, 0, signal.size)

        val levels = ArrayList<Int>()
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            record.startRecording()
            // 1.0s of quiet, then 2.5s of playback, then 0.5s of tail: 80 chunks of 50ms.
            for (i in 0 until 80) {
                if (i == 20) track.play()
                val read = record.read(buffer, 0, buffer.size)
                levels += if (read > 0) pcm16Rms(buffer.copyOf(read)) else -1
            }
        } finally {
            runCatching { track.stop() }
            track.release()
            record.stop()
            record.release()
            effects.forEach { it?.release() }
        }

        fun mean(from: Int, to: Int) = levels.subList(from, to).filter { it >= 0 }.average().toInt()
        val quiet = mean(6, 20)
        val playing = mean(26, 68)
        val effectsOn = effects.count { it != null }

        // Echo, separated from the ambient noise: mic power in each 50ms chunk = noise power + c x (power
        // of what the speakers were playing a moment earlier). A least-squares fit over all chunks gives the
        // noise level (intercept) and c (slope); the lag with the best fit is the speaker-to-mic delay.
        val chunkOut = OUT_RATE / 20
        val refPower = DoubleArray(80) { i ->
            val k = i - 20
            if (k !in 0 until 50) 0.0 else {
                var sum = 0.0
                for (n in k * chunkOut until (k + 1) * chunkOut) sum += signal[n].toDouble() * signal[n]
                sum / chunkOut
            }
        }
        val micPower = DoubleArray(80) { i -> if (levels[i] < 0) 0.0 else levels[i].toDouble() * levels[i] }
        var best = Triple(-1.0, 0, doubleArrayOf(0.0, 0.0)) // r^2, lag, [intercept, slope]
        for (lag in 0..8) {
            val xs = (6 until 80).map { refPower.getOrElse(it - lag) { 0.0 } }
            val ys = (6 until 80).map { micPower[it] }
            val mx = xs.average(); val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
            val syy = ys.sumOf { (it - my) * (it - my) }
            if (sxx <= 0.0 || syy <= 0.0) continue
            val slope = sxy / sxx
            val r2 = (sxy * sxy) / (sxx * syy)
            if (r2 > best.first) best = Triple(r2, lag, doubleArrayOf(my - slope * mx, slope))
        }
        val (r2, lag, fit) = best
        val noiseRms = Math.sqrt(fit[0].coerceAtLeast(0.0)).toInt()
        val refMeanPower = refPower.slice(20 until 70).average()
        val echoRms = Math.sqrt((fit[1] * refMeanPower).coerceAtLeast(0.0)).toInt()
        AppLogger.log(
            TAG,
            "${config.name}: ambient=$noiseRms  ECHO=$echoRms (while the assistant is loudest-ish)  " +
                "echo/ambient=${if (noiseRms > 0) "%.2f".format(echoRms.toDouble() / noiseRms) else "?"}  " +
                "fit r2=${"%.2f".format(r2)} lag=${lag * 50}ms  | raw quiet=$quiet playing=$playing  effectsAttached=$effectsOn",
        )
    }

    /** Band-limited noise with a ~3.5Hz syllable-like envelope: similar in level and rhythm to speech. */
    private fun testSignal(seconds: Double): ShortArray {
        val random = java.util.Random(7)
        var lowPassed = 0.0
        var rumble = 0.0
        return ShortArray((OUT_RATE * seconds).toInt()) { i ->
            lowPassed += 0.35 * (random.nextGaussian() - lowPassed)
            rumble += 0.02 * (lowPassed - rumble)
            val band = lowPassed - rumble
            val envelope = 0.15 + 0.85 * abs(sin(2 * PI * 3.5 * i / OUT_RATE))
            (band * envelope * 9000).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
