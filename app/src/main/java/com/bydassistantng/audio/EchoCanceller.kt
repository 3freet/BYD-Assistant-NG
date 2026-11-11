package com.bydassistantng.audio

import androidx.annotation.Keep
import com.bydassistantng.util.AppLogger

private const val TAG = "EchoCanceller"

/** The calls into the native library (see `app/src/main/cpp`). Public members of an internal object, so that the compiler does not rename them. */
@Keep
internal object EchoNative {
    @JvmStatic external fun nativeCreate(sampleRate: Int, echoMode: Int, comfortNoise: Boolean): Long

    @JvmStatic external fun nativeFarend(handle: Long, samples: ShortArray, count: Int): Int

    @JvmStatic external fun nativeProcess(handle: Long, near: ShortArray, out: ShortArray, count: Int, delayMs: Int): Int

    @JvmStatic external fun nativeFree(handle: Long)
}

/**
 * Takes the echo out of the cabin microphone's sound during a live broadcast ([LiveBroadcast]): the exterior speaker is loud enough for the
 * cabin microphone to hear it, so without this what is said comes out again a moment later, and again.
 *
 * It is WebRTC's mobile echo canceller. It is given what is being played ([addFarend], the reference) and then the microphone's chunk
 * ([clean]), and it takes out of that chunk what the reference would have added to it, having found out by itself how late and how loud the
 * speaker's sound arrives. Both are chunks of 10 ms (160 samples at 16 kHz), one of each per cycle; [delayMs] says roughly how long after
 * a chunk is played its sound reaches the microphone, which the canceller then refines.
 *
 * One thread at a time uses it (the broadcast's capture thread); [close] may come from another, and a call after it does nothing.
 */
internal class EchoCanceller private constructor(private var handle: Long) {
    private val lock = Any()

    /** Hands the canceller the chunk that is the reference for the next [clean]. */
    fun addFarend(samples: ShortArray) {
        synchronized(lock) {
            if (handle != 0L) EchoNative.nativeFarend(handle, samples, samples.size)
        }
    }

    /** [near] without the echo; [near] itself when the canceller cannot do it (closed, or the library refused the chunk). */
    fun clean(near: ShortArray, delayMs: Int): ShortArray = synchronized(lock) {
        if (handle == 0L) return near
        val out = ShortArray(near.size)
        if (EchoNative.nativeProcess(handle, near, out, near.size, delayMs) == 0) out else near
    }

    fun close() {
        synchronized(lock) {
            if (handle != 0L) EchoNative.nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        // Loading the library can fail (a device it was not built for): the broadcast then goes on without an echo canceller.
        private val available: Boolean by lazy {
            try {
                System.loadLibrary("echocancel")
                true
            } catch (e: Throwable) {
                AppLogger.logError(TAG, "The echo canceller's library would not load", e)
                false
            }
        }

        /** A canceller for [sampleRate] (8000 or 16000), or null when there is none on this device. [echoMode] 0..4: how hard it suppresses (WebRTC's routing modes, 1 = earpiece). */
        fun create(sampleRate: Int = BROADCAST_SAMPLE_RATE, echoMode: Int = 1, comfortNoise: Boolean = true): EchoCanceller? {
            if (!available) return null
            val handle = try {
                EchoNative.nativeCreate(sampleRate, echoMode, comfortNoise)
            } catch (e: Throwable) {
                AppLogger.logError(TAG, "The echo canceller could not be created", e)
                0L
            }
            return if (handle == 0L) null else EchoCanceller(handle)
        }
    }
}

/**
 * What was played, for the last few chunks: the echo canceller's reference is what was sent out [back] chunks ago (the sound of the last
 * chunk or two is still on its way to the speaker when the microphone's chunk arrives, so they are not what that chunk heard).
 */
internal class EchoReference(private val chunkSamples: Int) {
    private val played = ArrayDeque<ShortArray>()

    /** How many chunks are kept (the lag of [back] cannot go past it). */
    val size: Int get() = played.size

    fun push(chunk: ShortArray) {
        played.addLast(chunk)
        while (played.size > KEPT) played.removeFirst()
    }

    /** The chunk played [lag] chunks before now (1 = the one played last); silence while that many have not been played yet. */
    fun back(lag: Int): ShortArray = if (lag in 1..played.size) played[played.size - lag] else ShortArray(chunkSamples)

    private companion object {
        const val KEPT = 8
    }
}
