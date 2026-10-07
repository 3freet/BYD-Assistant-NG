package com.bydassistantng.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "AudioRecorder"
private const val MAX_DURATION_MS = 15_000

// Simple energy-based voice-activity detection via MediaRecorder.getMaxAmplitude(), calibrated
// against a short warm-up sample of ambient noise rather than one fixed threshold — a quiet room
// and a car cabin at speed have very different noise floors.
private const val SILENCE_POLL_INTERVAL_MS = 150L
private const val WARMUP_MS = 500L
private const val MIN_SPEECH_THRESHOLD = 1200
private const val SILENCE_DURATION_TO_STOP_MS = 1500L

/** AAC/M4A capture via [MediaRecorder] — compressed and small enough to send inline to Gemini
 * directly, unlike the old app's raw PCM buffers which existed only to feed a local STT model.
 * Not a Hilt singleton: each recording session owns its own [MediaRecorder] instance and file. */
class AudioRecorder @Inject constructor(@ApplicationContext private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingStartMs: Long = 0L
    private var autoStopped = false
    private var silenceWatcherJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    val isRecording: Boolean get() = recorder != null

    /**
     * Starts recording to a fresh cache file. Ends itself automatically either when
     * [MAX_DURATION_MS] elapses ([onMaxDurationReached]) or after [SILENCE_DURATION_TO_STOP_MS] of
     * silence following detected speech ([onSilenceDetected]) — this is what lets a turn end on its
     * own instead of requiring a second manual tap. Both callbacks are always delivered on the main
     * thread, regardless of which internal mechanism triggered them. Returns null if a recording is
     * already in progress or the platform refused to start one (e.g. mic held by another app).
     */
    fun start(onMaxDurationReached: () -> Unit, onSilenceDetected: () -> Unit): File? {
        if (recorder != null) return null

        val file = File(context.cacheDir, "turn_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

        return try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        AppLogger.log(TAG, "Max recording duration (${MAX_DURATION_MS}ms) reached")
                        autoStopped = true
                        mainHandler.post(onMaxDurationReached)
                    }
                }
                prepare()
                start()
            }
            recorder = mediaRecorder
            outputFile = file
            recordingStartMs = SystemClock.elapsedRealtime()
            autoStopped = false
            AppLogger.log(TAG, "Recording started -> ${file.name}")
            silenceWatcherJob = scope.launch { watchForSilence(mediaRecorder, onSilenceDetected) }
            file
        } catch (e: Exception) {
            AppLogger.logError(TAG, "Failed to start recording", e)
            mediaRecorder.release()
            file.delete()
            null
        }
    }

    private suspend fun watchForSilence(mediaRecorder: MediaRecorder, onSilenceDetected: () -> Unit) {
        val noiseSamples = mutableListOf<Int>()
        val warmupDeadline = SystemClock.elapsedRealtime() + WARMUP_MS
        while (SystemClock.elapsedRealtime() < warmupDeadline) {
            delay(SILENCE_POLL_INTERVAL_MS)
            noiseSamples += runCatching { mediaRecorder.maxAmplitude }.getOrNull() ?: return
        }
        val noiseFloor = if (noiseSamples.isEmpty()) 0 else noiseSamples.average().toInt()
        val speechThreshold = maxOf(noiseFloor * 2, MIN_SPEECH_THRESHOLD)
        AppLogger.log(TAG, "Silence watcher calibrated: noiseFloor=$noiseFloor, speechThreshold=$speechThreshold")

        var hasDetectedSpeech = false
        var silenceStartMs: Long? = null

        while (true) {
            delay(SILENCE_POLL_INTERVAL_MS)
            val amplitude = runCatching { mediaRecorder.maxAmplitude }.getOrNull() ?: return

            if (amplitude >= speechThreshold) {
                hasDetectedSpeech = true
                silenceStartMs = null
            } else if (hasDetectedSpeech) {
                val now = SystemClock.elapsedRealtime()
                val since = silenceStartMs ?: now.also { silenceStartMs = now }
                if (now - since >= SILENCE_DURATION_TO_STOP_MS) {
                    AppLogger.log(TAG, "Silence for ${SILENCE_DURATION_TO_STOP_MS}ms after speech — auto-stopping")
                    autoStopped = true
                    mainHandler.post(onSilenceDetected)
                    return
                }
            }
        }
    }

    /** Stops recording and returns the finished file, or null if nothing usable was captured
     * (e.g. stopped almost immediately after starting, before any audio was encoded). */
    fun stop(): File? {
        val mediaRecorder = recorder ?: return null
        val file = outputFile
        val durationMs = SystemClock.elapsedRealtime() - recordingStartMs
        silenceWatcherJob?.cancel()
        silenceWatcherJob = null

        return try {
            // MediaRecorder already stopped itself when auto-stop fired (max duration or silence)
            // and finished writing a perfectly good file — calling stop() again on it throws, and
            // that used to be caught below as "no usable audio" and delete a valid recording.
            if (!autoStopped) mediaRecorder.stop()
            AppLogger.log(TAG, "Recording stopped after ${durationMs}ms -> ${file?.name} (${file?.length()} bytes)")
            file
        } catch (e: RuntimeException) {
            AppLogger.logError(TAG, "Recording produced no usable audio after ${durationMs}ms", e)
            file?.delete()
            null
        } finally {
            mediaRecorder.release()
            recorder = null
            outputFile = null
        }
    }

    /** Stops (if needed) and discards the in-progress recording without returning it. */
    fun cancel() {
        val mediaRecorder = recorder ?: return
        silenceWatcherJob?.cancel()
        silenceWatcherJob = null
        try {
            if (!autoStopped) mediaRecorder.stop()
        } catch (_: RuntimeException) {
            // Discarding regardless; a too-early stop() throwing is expected here.
        } finally {
            mediaRecorder.release()
            outputFile?.delete()
            recorder = null
            outputFile = null
        }
    }
}
