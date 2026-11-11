package com.bydassistantng.debug

import com.bydassistantng.audio.BROADCAST_SAMPLE_RATE
import com.bydassistantng.audio.EchoCanceller
import com.bydassistantng.util.AppLogger
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

private const val TAG = "EchoSelfTest"

/**
 * DEBUG BUILDS ONLY. Checks the echo canceller's native library with computed signals, no sound anywhere: a speech-like far end, a microphone
 * that hears it 85 ms late through a short echo path, and the canceller between them, as the live broadcast uses it. Logs how much echo is
 * left per second, which is about 30 dB on a working library.
 */
internal object EchoSelfTest {
    fun run(delayMs: Int = 75, mode: Int = 1) {
        val canceller = EchoCanceller.create(BROADCAST_SAMPLE_RATE, mode) ?: return AppLogger.log(TAG, "No echo canceller on this device (the library did not load or create)")
        val seconds = 12
        val rate = BROADCAST_SAMPLE_RATE
        val n = seconds * rate
        val random = Random(1)
        val far = ShortArray(n)
        var low = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val envelope = max(0.0, sin(2 * PI * 3.3 * t)) * (0.6 + 0.4 * sin(2 * PI * 0.35 * t))
            low = 0.75 * low + 0.25 * random.nextGaussian()
            far[i] = (low * envelope * 5000).toInt().coerceIn(-32768, 32767).toShort()
        }
        val echoDelay = 85 * rate / 1000
        val taps = listOf(0 to 0.25, 7 to 0.11, 19 to 0.06, 41 to 0.03)
        val near = ShortArray(n) { i ->
            var s = random.nextGaussian() * 6
            for ((offset, gain) in taps) {
                val j = i - echoDelay - offset
                if (j >= 0) s += gain * far[j]
            }
            s.toInt().coerceIn(-32768, 32767).toShort()
        }
        val chunk = rate / 100
        var sumNear = 0.0
        var sumOut = 0.0
        val lines = ArrayList<String>()
        var tail = 0.0
        var tailCount = 0
        var k = 0
        while ((k + 1) * chunk <= n) {
            val farChunk = far.copyOfRange(k * chunk, (k + 1) * chunk)
            val nearChunk = near.copyOfRange(k * chunk, (k + 1) * chunk)
            canceller.addFarend(farChunk)
            val out = canceller.clean(nearChunk, delayMs)
            var a = 0.0
            var b = 0.0
            for (i in 0 until chunk) {
                a += nearChunk[i].toDouble() * nearChunk[i]
                b += out[i].toDouble() * out[i]
            }
            sumNear += a
            sumOut += b
            if ((k + 1) % 100 == 0) {
                val reduction = 10 * log10(max(sumNear, 1.0) / max(sumOut, 1.0))
                lines += "%.0f".format(reduction)
                if (k >= 400) {
                    tail += reduction
                    tailCount++
                }
                sumNear = 0.0
                sumOut = 0.0
            }
            k++
        }
        canceller.close()
        AppLogger.log(TAG, "Echo reduction per second (dB): ${lines.joinToString(", ")}; mean of seconds 5..12: ${"%.1f".format(tail / max(1, tailCount))} dB (delay $delayMs ms, mode $mode)")
    }
}
