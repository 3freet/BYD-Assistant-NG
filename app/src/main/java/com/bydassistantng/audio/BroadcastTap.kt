package com.bydassistantng.audio

import android.content.Context
import com.bydassistantng.util.AppLogger
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

private const val TAG = "BroadcastTap"
private const val KEEP_SESSIONS = 12

/**
 * DEBUG BUILDS ONLY (nothing creates one in a release build). Writes what a live broadcast took in, what the echo canceller made of it and what
 * it put out, chunk for chunk, as raw 16 kHz mono 16-bit PCM in the app's files (`tap/mic_<time>.pcm`, `tap/aec_<time>.pcm` and `tap/out_<time>.pcm`),
 * so that the echo, the noise and what the gate does can be studied offline. The n-th chunk of each file belongs to the n-th chunk of the others.
 */
internal class BroadcastTap(context: Context) {
    private val mic: BufferedOutputStream
    private val aec: BufferedOutputStream
    private val out: BufferedOutputStream

    init {
        val dir = File(context.filesDir, "tap").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_SESSIONS * 3)?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()
        mic = BufferedOutputStream(FileOutputStream(File(dir, "mic_$stamp.pcm")), 64 * 1024)
        aec = BufferedOutputStream(FileOutputStream(File(dir, "aec_$stamp.pcm")), 64 * 1024)
        out = BufferedOutputStream(FileOutputStream(File(dir, "out_$stamp.pcm")), 64 * 1024)
        AppLogger.log(TAG, "Recording the broadcast to files/tap (mic_$stamp.pcm, aec_$stamp.pcm, out_$stamp.pcm)")
    }

    fun add(heard: ShortArray, cleaned: ShortArray, played: ShortArray) {
        write(mic, heard)
        write(aec, cleaned)
        write(out, played)
    }

    private fun write(stream: BufferedOutputStream, samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[2 * i] = (samples[i].toInt() and 0xFF).toByte()
            bytes[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        runCatching { stream.write(bytes) }
    }

    fun close() {
        runCatching { mic.close() }
        runCatching { aec.close() }
        runCatching { out.close() }
    }
}
