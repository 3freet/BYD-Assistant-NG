package com.bydassistantng.audio

/**
 * Decides, while the assistant is talking, whether the *user* has started speaking over it — as
 * opposed to the mic simply hearing the assistant's own voice from the car's speakers.
 *
 * Three things set the bar a mic level has to clear to count as the user:
 *  - an **absolute minimum** ([minBargeInRms]). Measured on a DiLink head unit through the platform's voice
 *    processing: speech peaks at 5,000–9,000 (its AGC lifts it a lot) while echo is a few hundred at
 *    most, so there's no need to be touchy — and a barge-in that wrongly cuts a reply is far worse
 *    than one that needs a firm voice;
 *  - the **ambient** level of the cabin (a slowly-moving minimum), times [noiseMultiplier];
 *  - the **echo**: how much of what the speakers play leaks into the mic. That's learnt per reply from
 *    its first stretch (the user is very unlikely to talk over the first half-second), as the mic's
 *    level above ambient divided by the playback level, and applied to the playback level at each
 *    moment — so a louder passage of the reply raises the bar with it.
 *
 * Enough of the recent chunks have to clear the bar ([needChunks] of the last [windowChunks]) rather
 * than strictly consecutive ones: real speech dips between words.
 *
 * The failure mode is deliberately the safe one: if the user starts talking *during* calibration, the
 * echo estimate comes out too high and they have to speak up — a barge-in is missed, but the reply is
 * never cut off by the assistant's own voice.
 *
 * Feed it one chunk at a time via [onChunk] (mic RMS and the playback RMS expected to be reaching the
 * mic right now). Call [startReply] when a reply begins playing. Safe to call from several threads.
 */
class BargeInDetector(
    private val chunkMs: Int = 50,
    // Real speech over a reply measured 1,450–5,100 at the mic; every weak trigger below ~800 produced a
    // junk transcript ("nächste", the assistant's own words) and cut a reply off for nothing.
    private val minBargeInRms: Int = 1200,
    private val noiseMultiplier: Double = 6.0,
    /** How many times the learnt echo the mic must be above. */
    private val echoMargin: Double = 2.5,
    private val windowChunks: Int = 8,
    private val needChunks: Int = 5,
    private val calibrationMs: Int = 600,
    /** Playback quieter than this isn't a usable reference (a pause between words). */
    private val minReference: Int = 250,
    /** Mic-above-ambient per unit of playback that can still be echo. Measured on the car: echo up to
     * ~0.17, genuine speech from 0.35 — the ceiling sits between, nearer the echo. */
    private val echoCeiling: Double = 0.25,
) {
    private var noiseFloor = Double.MAX_VALUE
    private var echoCoupling = 0.0
    private var replyMs = 0
    private val calibrationSamples = ArrayList<Double>()
    private var calibrated = false
    private val recent = BooleanArray(windowChunks)
    private var recentIndex = 0

    // For the log line when something triggers.
    private var lastMic = 0
    private var lastReference = 0
    private var lastBar = 0
    private var triggerRatio = 0.0 // mic-above-ambient per unit of playback at the moment it last triggered

    /** The cabin's ambient level as learnt so far (0 until anything has been heard). */
    val ambient: Double @Synchronized get() = floorOrZero()

    /** Keeps the ambient estimate current while the user (rather than the assistant) is the one
     * making the sound — otherwise it would only ever be updated in the gaps of a reply. */
    @Synchronized
    fun observeAmbient(micRms: Int) {
        noiseFloor = minOf(if (noiseFloor == Double.MAX_VALUE) micRms.toDouble() else noiseFloor * 1.003, micRms.toDouble()).coerceAtLeast(1.0)
    }

    /** A new reply started playing: forget the last reply's echo estimate, keep the ambient one. */
    @Synchronized
    fun startReply() {
        replyMs = 0
        recent.fill(false)
        calibrationSamples.clear()
        calibrated = false
        echoCoupling = 0.0
    }

    /** @return true when the user has been speaking over the reply for long enough. */
    @Synchronized
    fun onChunk(micRms: Int, referenceRms: Int): Boolean {
        val playing = referenceRms >= minReference
        replyMs += chunkMs

        // Ambient: the quietest the cabin has been, drifting up slowly so a change (AC on, driving) is followed.
        // Only trusted while nothing is playing; during playback the mic level includes the echo.
        if (!playing) noiseFloor = minOf(noiseFloor * 1.003, micRms.toDouble()).coerceAtLeast(1.0)
        else if (noiseFloor == Double.MAX_VALUE) noiseFloor = micRms.toDouble()

        if (playing) learnEcho(micRms, referenceRms)

        val echoAllowance = if (playing) echoMargin * echoCoupling * referenceRms else 0.0
        val bar = maxOf(minBargeInRms.toDouble(), floorOrZero() * noiseMultiplier, floorOrZero() + echoAllowance)
        lastMic = micRms; lastReference = referenceRms; lastBar = bar.toInt()

        recent[recentIndex] = micRms >= bar
        recentIndex = (recentIndex + 1) % windowChunks
        val triggered = recent.count { it } >= needChunks
        if (triggered && playing) triggerRatio = (micRms - floorOrZero()).coerceAtLeast(0.0) / referenceRms
        return triggered
    }

    /**
     * The trigger just reported turned out not to be the user (the sound died away when the reply was
     * turned down). Whatever it was leaked into the mic far more than the echo learnt at the start of
     * this reply, so meet it halfway: the same burst won't trigger again, while a user talking over the
     * rest of the reply still clears a bar that is only moderately higher. Lasts until the next reply.
     */
    @Synchronized
    fun noteFalseAlarm() {
        echoCoupling = maxOf(echoCoupling, (echoCoupling + triggerRatio) / 2)
        recent.fill(false)
    }

    private fun learnEcho(micRms: Int, referenceRms: Int) {
        val ratio = ((micRms - floorOrZero()).coerceAtLeast(0.0)) / referenceRms
        if (!calibrated) {
            calibrationSamples += ratio
            if (replyMs >= calibrationMs && calibrationSamples.size >= 4) {
                // The median, so one chunk of the user's own voice or a click can't skew it.
                echoCoupling = calibrationSamples.sorted()[calibrationSamples.size / 2]
                calibrated = true
            } else {
                // Until then, assume the worst seen so far: no barge-in during the first moments.
                echoCoupling = calibrationSamples.max()
            }
        } else if (ratio <= echoCeiling) {
            // Keep following the echo. It can rise through a reply (the mic's gain recovers after the
            // user's loud speech) but that takes seconds, whereas the user's voice arrives abruptly — so
            // it is followed up slowly (a quarter-second of speech barely moves it) and down more slowly
            // still. Anything above the ceiling is the user's voice and never drags the estimate up.
            echoCoupling += (if (ratio > echoCoupling) 0.04 else 0.02) * (ratio - echoCoupling)
        }
    }

    private fun floorOrZero(): Double = if (noiseFloor == Double.MAX_VALUE) 0.0 else noiseFloor

    @Synchronized
    fun summary(): String =
        "bargein: mic=$lastMic ref=$lastReference bar=$lastBar floor=${floorOrZero().toInt()} echoCoupling=${"%.3f".format(echoCoupling)} calibrated=$calibrated"
}
