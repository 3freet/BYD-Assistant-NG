package com.bydassistantng.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.bydassistantng.util.AppLogger
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "CabinNoise"
private const val SAMPLE_RATE = 16_000
private const val WINDOW_MS = 50

// An unprocessed source first: the conversation's own microphone (voice communication) cancels echo, suppresses
// noise and evens out the gain, so what it hears says little about how loud the cabin is.
private val SOURCES = listOf(
    MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
    MediaRecorder.AudioSource.MIC to "MIC",
)

/**
 * How loud the cabin is for a voice: the level of the microphone's speech band over a short time, in dBFS (0 is full
 * scale, silence is far below).
 */
object CabinNoise {
    /**
     * Listens for [durationMs] and returns the typical speech-band level of the windows after the first [skipMs]
     * (their median, so a click or a word does not decide it), or null when the microphone could not be opened. Blocks the caller
     * for the whole time; the conversation's own capture can be running meanwhile.
     */
    fun measure(durationMs: Int, skipMs: Int = 0): Double? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        for ((source, name) in SOURCES) {
            val record = try {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
            } catch (e: SecurityException) {
                // The microphone permission was taken away: nothing to measure with.
                AppLogger.log(TAG, "No microphone permission for the cabin noise: $e")
                return null
            } catch (e: Exception) {
                AppLogger.log(TAG, "Could not open the $name microphone: $e")
                continue
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                continue
            }
            try {
                record.startRecording()
                val levels = listen(record, durationMs, skipMs)
                if (levels.isEmpty()) continue
                val speech = levels.map { it.speech }
                val level = median(speech)
                AppLogger.log(
                    TAG,
                    "Cabin noise $name: ${"%.1f".format(level)} dBFS in the speech band, ${"%.1f".format(median(levels.map { it.whole }))} over the whole band " +
                        "(${levels.size} windows, range ${"%.1f".format(speech.min())} to ${"%.1f".format(speech.max())})",
                )
                return level
            } catch (e: Exception) {
                AppLogger.log(TAG, "Measuring with $name failed: $e")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
        return null
    }

    private fun listen(record: AudioRecord, durationMs: Int, skipMs: Int): List<WindowLevels> {
        val window = ShortArray(SAMPLE_RATE * WINDOW_MS / 1000)
        val band = SpeechBand(SAMPLE_RATE)
        val levels = ArrayList<WindowLevels>()
        var heardMs = 0
        while (heardMs < durationMs) {
            var filled = 0
            while (filled < window.size) {
                val read = record.read(window, filled, window.size - filled)
                if (read <= 0) return levels
                filled += read
            }
            heardMs += WINDOW_MS
            // The filters see every window, so they are settled by the time the first one is counted.
            val windowLevels = levelsOf(window, band)
            if (heardMs > skipMs) levels += windowLevels
        }
        return levels
    }

    internal fun dbfs(samples: ShortArray): Double {
        if (samples.isEmpty()) return SILENCE_DBFS
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return toDbfs(sqrt(sum / samples.size) / Short.MAX_VALUE)
    }

    internal fun toDbfs(rms: Double): Double = if (rms <= 0.0) SILENCE_DBFS else maxOf(SILENCE_DBFS, 20 * log10(rms))

    /** Both levels of one window of the microphone; [band] carries its filter state from one window to the next. */
    internal fun levelsOf(samples: ShortArray, band: SpeechBand): WindowLevels =
        WindowLevels(whole = dbfs(samples), speech = maxOf(SILENCE_DBFS, band.dbfs(samples) + SPEECH_BAND_OFFSET_DB))

    /**
     * What the speech band's level is raised by, to read about where the whole band's did for a cabin that is mostly
     * the hiss of the fan and music, which the volume curve was fitted on (a silent cabin reads 7 dB lower in the speech
     * band; a cabin of music and fan, which has less bass, should read about 6 lower). Only the low rumble of a moving
     * car then reads lower than before. A first estimate: both levels are logged, to be checked against a drive.
     */
    const val SPEECH_BAND_OFFSET_DB = 6.0

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** The [fraction] (0 is the lowest, 1 the highest) of [values], taking the one at or just below that point. */
    internal fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        return sorted[(fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)).toInt()]
    }

    /** What digital silence reads as, instead of minus infinity. */
    internal const val SILENCE_DBFS = -96.0
}

/**
 * The recent levels of the cabin, window by window, and what they say the cabin noise is: the low quarter of the last
 * three seconds. The user's own speech and the odd bump are loud windows among quiet ones, so a low percentile reads
 * through them to what is there all the time (road, fan, music).
 */
internal class NoiseWindows(
    private val keep: Int = 60,
    private val maxAgeMs: Long = 20_000,
    private val minWindows: Int = 10,
) {
    private val levels = ArrayDeque<Pair<Long, Double>>()

    @Synchronized
    fun add(atMs: Long, dbfs: Double) {
        levels.addLast(atMs to dbfs)
        while (levels.size > keep) levels.removeFirst()
    }

    /** The cabin noise in dBFS, or null when there is too little to go on or the last window is old. */
    @Synchronized
    fun estimate(nowMs: Long): Double? {
        if (levels.size < minWindows || nowMs - levels.last().first > maxAgeMs) return null
        return CabinNoise.percentile(levels.map { it.second }, 0.25)
    }

    @Synchronized
    fun clear() = levels.clear()
}

/**
 * Listens to the cabin for as long as a conversation is open, on a microphone of its own (unprocessed, beside the
 * conversation's), and keeps the recent levels so the noise is known the moment a reply starts. It only keeps what it
 * hears while [sampling] says so: not while the assistant is speaking, whose own voice would be taken for noise.
 */
class CabinNoiseMonitor(private val sampling: () -> Boolean) {
    private val windows = NoiseWindows()
    private val wholeBandWindows = NoiseWindows()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    /** The cabin noise now (dBFS), or null when it is not known yet. */
    fun estimate(): Double? = windows.estimate(SystemClock.elapsedRealtime())

    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        for ((source, name) in SOURCES) {
            val opened = try {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
            } catch (e: SecurityException) {
                AppLogger.log(TAG, "No microphone permission for the cabin noise: $e")
                return false
            } catch (e: Exception) {
                AppLogger.log(TAG, "Could not open the $name microphone: $e")
                continue
            }
            if (opened.state != AudioRecord.STATE_INITIALIZED) {
                opened.release()
                continue
            }
            try {
                opened.startRecording()
            } catch (e: Exception) {
                AppLogger.log(TAG, "Could not start the $name microphone: $e")
                opened.release()
                continue
            }
            windows.clear()
            wholeBandWindows.clear()
            record = opened
            running = true
            thread = Thread({ listen(opened) }, "CabinNoise").apply {
                isDaemon = true
                start()
            }
            AppLogger.log(TAG, "Listening to the cabin noise ($name)")
            return true
        }
        return false
    }

    fun stop() {
        val opened = record ?: return
        record = null
        running = false
        runCatching { opened.stop() }
        runCatching { thread?.join(500) }
        thread = null
        opened.release()
        windows.clear()
        wholeBandWindows.clear()
        AppLogger.log(TAG, "Stopped listening to the cabin noise")
    }

    private fun listen(opened: AudioRecord) {
        val window = ShortArray(SAMPLE_RATE * WINDOW_MS / 1000)
        val band = SpeechBand(SAMPLE_RATE)
        var lastLogAt = 0L
        while (running) {
            var filled = 0
            while (filled < window.size && running) {
                val read = opened.read(window, filled, window.size - filled)
                if (read <= 0) return
                filled += read
            }
            if (filled < window.size) continue
            // The filters follow the microphone all the time, so they are settled when sampling starts again.
            val levels = CabinNoise.levelsOf(window, band)
            if (!sampling()) continue
            val now = SystemClock.elapsedRealtime()
            windows.add(now, levels.speech)
            wholeBandWindows.add(now, levels.whole)
            if (now - lastLogAt >= LOG_EVERY_MS) {
                lastLogAt = now
                windows.estimate(now)?.let { speech ->
                    val whole = wholeBandWindows.estimate(now)?.let { ", ${"%.1f".format(it)} over the whole band" }.orEmpty()
                    AppLogger.log(TAG, "Cabin noise now ${"%.1f".format(speech)} dBFS in the speech band$whole")
                }
            }
        }
    }

    private companion object {
        const val LOG_EVERY_MS = 5_000L
    }
}
