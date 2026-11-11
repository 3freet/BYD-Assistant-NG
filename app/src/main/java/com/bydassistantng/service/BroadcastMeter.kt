package com.bydassistantng.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.bydassistantng.audio.LiveBroadcast
import com.bydassistantng.util.AppLogger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val TAG = "BroadcastMeter"

/** Where the meter sits (just under the status banner, which is a little over 100 dp tall with its second line), and how big it is. */
internal const val METER_TOP_DP = 66 + 112
internal const val METER_WIDTH_DP = 360
internal const val METER_HEIGHT_DP = 84

/**
 * The live broadcast's level meter: a small glass card under the status pill that shows the last four seconds of the broadcast as it scrolls by,
 * so that the driver can see at a glance what the people outside are getting.
 *
 *  - The **ribbon** in the middle is the voice that is being sent out, its thickness the loudness: thin and mint-white when quiet, thick and
 *    teal when loud. It scrolls smoothly to the left; the glowing dot at the right edge is now.
 *  - The faint **outline** behind it is what the cabin microphone heard before the voice was processed, so the gap between the two shows what
 *    the echo canceller and the fade are holding back.
 *  - The warm **band** along the bottom is the feedback: how much of the exterior speaker the microphone picked up (what the echo canceller took
 *    out). A thin line there is a healthy car; a band that grows amber and then red is a loop about to ring. A squeal held back turns the whole
 *    card's edge and the dot red for a moment.
 *
 * Like the status banner it is a window of type `TYPE_ACCESSIBILITY_OVERLAY` that is never touchable or focusable, so it cannot get in the way of
 * the screen underneath. Drawn by hand (no animation framework), at the display's rate while it is on. All methods may be called from any thread.
 */
class BroadcastMeter(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var view: MeterView? = null
    private var attached = false

    /** Shows the meter, empty. */
    fun show() {
        handler.post {
            if (ensureAttached()) view?.start()
        }
    }

    /** Adds what the last 100 ms were like. */
    fun push(frame: LiveBroadcast.LevelFrame) {
        handler.post { view?.push(frame) }
    }

    fun hide() {
        handler.post { detach() }
    }

    private fun ensureAttached(): Boolean {
        if (attached) return true
        val meter = MeterView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (METER_TOP_DP * context.resources.displayMetrics.density).toInt()
        }
        return try {
            windowManager.addView(meter, params)
            view = meter
            attached = true
            true
        } catch (e: Throwable) {
            AppLogger.logError(TAG, "Could not add the level meter window", e)
            false
        }
    }

    private fun detach() {
        val meter = view ?: return
        meter.stop()
        runCatching { windowManager.removeView(meter) }
        attached = false
        view = null
    }
}

/** How a level in dB below full scale (mean power) is drawn: as 0..1 on the card. */
internal object MeterScale {
    /** The voice's: -60 dB is nothing, -10 dB is the full thickness; a little curve so that a quiet voice shows. */
    fun voice(db: Float): Float = ((db + 60f) / 50f).coerceIn(0f, 1f).pow(0.85f)

    /** The feedback's: -80 dB is nothing, -40 dB is the full band. */
    fun echo(db: Float): Float = ((db + 80f) / 40f).coerceIn(0f, 1f).pow(1.1f)
}

/** The card itself: keeps the last four seconds of levels and draws them. */
private class MeterView(context: Context) : View(context) {
    private class Sample(val atMs: Long, val sent: Float, val mic: Float, val echo: Float)

    private val density = context.resources.displayMetrics.density
    private val samples = ArrayList<Sample>()
    private var squealUntilMs = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    // What is drawn, made once. The shaders depend on the size, so they are made when it is known.
    private val card = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var borderShader: Shader? = null
    private var haloTeal: Shader? = null
    private var haloRed: Shader? = null

    private val ribbon = Path()
    private val upper = Path()
    private val lower = Path()
    private val outlineUpper = Path()
    private val outlineLower = Path()
    private val band = Path()
    private var xs = FloatArray(64)
    private var ysSent = FloatArray(64)
    private var ysMic = FloatArray(64)
    private var ysEcho = FloatArray(64)

    private var plotLeft = 0f
    private var plotRight = 0f
    private var centerY = 0f
    private var maxHalf = 0f
    private var bandBase = 0f
    private var bandHeight = 0f

    fun start() {
        if (running) return
        running = true
        postOnAnimation(tick)
    }

    fun stop() {
        running = false
        removeCallbacks(tick)
    }

    fun push(frame: LiveBroadcast.LevelFrame) {
        val now = SystemClock.uptimeMillis()
        samples += Sample(now, MeterScale.voice(frame.sentDb), MeterScale.voice(frame.micDb), MeterScale.echo(frame.echoDb))
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)
        if (frame.squeal) squealUntilMs = now + SQUEAL_SHOWN_MS
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(METER_WIDTH_DP), dp(METER_HEIGHT_DP))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = w.toFloat()
        val height = h.toFloat()
        card.set(dpf(1f), dpf(1f), width - dpf(1f), height - dpf(1f))
        plotLeft = dpf(18f)
        plotRight = width - dpf(22f)
        centerY = height * 0.45f
        maxHalf = height * 0.32f
        bandBase = height - dpf(11f)
        bandHeight = dpf(17f)

        fillPaint.shader = LinearGradient(0f, 0f, 0f, height, intArrayOf(INK_TOP, INK_BOTTOM), null, Shader.TileMode.CLAMP)
        borderPaint.strokeWidth = dpf(1.5f)
        borderShader = LinearGradient(card.left, 0f, card.right, 0f, intArrayOf(withAlpha(TEAL, 0x70), withAlpha(ORANGE, 0x70)), null, Shader.TileMode.CLAMP)
        // The glow of the dot is a circle of the largest radius, scaled down to what the voice needs.
        haloTeal = RadialGradient(0f, 0f, HALO_MAX_DP * density, intArrayOf(withAlpha(TEAL, 0xB0), withAlpha(TEAL, 0x00)), null, Shader.TileMode.CLAMP)
        haloRed = RadialGradient(0f, 0f, HALO_MAX_DP * density, intArrayOf(withAlpha(RED, 0xC0), withAlpha(RED, 0x00)), null, Shader.TileMode.CLAMP)

        // The ribbon: bright at the middle, deeper teal towards its edges, and fading in from the left so that the oldest of it melts away.
        val vertical = LinearGradient(
            0f, centerY - maxHalf, 0f, centerY + maxHalf,
            intArrayOf(withAlpha(TEAL, 0x66), withAlpha(TEAL, 0xCC), withAlpha(MINT, 0xFF), withAlpha(TEAL, 0xCC), withAlpha(TEAL, 0x66)),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        val fadeIn = LinearGradient(
            plotLeft, 0f, plotRight, 0f,
            intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        ribbonPaint.shader = ComposeShader(vertical, fadeIn, PorterDuff.Mode.DST_IN)

        crestPaint.strokeWidth = dpf(2f)
        crestPaint.shader = LinearGradient(
            plotLeft, 0f, plotRight, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(MINT, 0xB0), withAlpha(Color.WHITE, 0xF0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        glowPaint.strokeWidth = dpf(8f)
        glowPaint.shader = LinearGradient(
            plotLeft, 0f, plotRight, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(TEAL, 0x22), withAlpha(TEAL, 0x4A)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        tickPaint.strokeWidth = dpf(1f)
        tickPaint.color = withAlpha(Color.WHITE, 0x1C)
        outlinePaint.strokeWidth = dpf(1.2f)
        outlinePaint.shader = LinearGradient(
            plotLeft, 0f, plotRight, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(COOL, 0x4A), withAlpha(COOL, 0x70)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )

        // The feedback band: amber at its foot, red at its top, and fading in from the left like the ribbon.
        val bandVertical = LinearGradient(
            0f, bandBase, 0f, bandBase - bandHeight,
            intArrayOf(withAlpha(ORANGE, 0x55), withAlpha(ORANGE, 0xE0), withAlpha(RED, 0xFF)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        bandPaint.shader = ComposeShader(bandVertical, fadeIn, PorterDuff.Mode.DST_IN)
        baselinePaint.strokeWidth = dpf(1f)
        baselinePaint.shader = LinearGradient(
            plotLeft, 0f, plotRight, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(Color.WHITE, 0x2E), withAlpha(Color.WHITE, 0x40)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val squealing = now < squealUntilMs
        val corner = dpf(26f)

        canvas.drawRoundRect(card, corner, corner, fillPaint)
        borderPaint.shader = if (squealing) null else borderShader
        borderPaint.color = withAlpha(RED, 0xE0)
        canvas.drawRoundRect(card, corner, corner, borderPaint)

        // A ruler of seconds that scrolls with the ribbon (four of them are what the card shows), so that its span can be read.
        val secondWidth = (plotRight - plotLeft) * 1000f / WINDOW_MS
        val firstTick = plotRight - (now % 1000L) * secondWidth / 1000f
        for (k in 0 until 4) {
            val x = firstTick - k * secondWidth
            if (x > plotLeft + dpf(6f)) canvas.drawLine(x, dpf(9f), x, dpf(14f), tickPaint)
        }

        // The line the feedback band stands on.
        canvas.drawLine(plotLeft, bandBase, plotRight, bandBase, baselinePaint)

        // The points: one per frame, at the middle of the 100 ms it describes, and the last value held out to now so that the head is never behind.
        var n = 0
        val windowMs = WINDOW_MS.toFloat()
        ensureCapacity(samples.size + 2)
        for (sample in samples) {
            val age = now - (sample.atMs - FRAME_MS / 2)
            if (age > WINDOW_MS + FRAME_MS * 2) continue
            xs[n] = plotRight - age * (plotRight - plotLeft) / windowMs
            ysSent[n] = sample.sent
            ysMic[n] = sample.mic
            ysEcho[n] = sample.echo
            n++
        }
        val last = samples.lastOrNull()
        if (last != null && n > 0) {
            xs[n] = plotRight
            ysSent[n] = last.sent
            ysMic[n] = last.mic
            ysEcho[n] = last.echo
            n++
        }

        val headLevel = if (n > 0) ysSent[n - 1] else 0f
        if (n >= 2) {
            // The microphone's outline, behind everything.
            smoothEdges(outlineUpper, outlineLower, n, ysMic)
            canvas.drawPath(outlineUpper, outlinePaint)
            canvas.drawPath(outlineLower, outlinePaint)

            // The ribbon: the upper edge forwards, the lower edge back, one closed shape.
            ribbon.reset()
            ribbon.moveTo(xs[0], centerY - halfOf(ysSent[0]))
            addSmooth(ribbon, n, ysSent) { v -> centerY - halfOf(v) }
            ribbon.lineTo(xs[n - 1], centerY + halfOf(ysSent[n - 1]))
            addSmoothBackwards(ribbon, n, ysSent) { v -> centerY + halfOf(v) }
            ribbon.close()
            canvas.drawPath(ribbon, ribbonPaint)
            smoothEdges(upper, lower, n, ysSent)
            canvas.drawPath(upper, glowPaint)
            canvas.drawPath(lower, glowPaint)
            canvas.drawPath(upper, crestPaint)
            canvas.drawPath(lower, crestPaint)

            // The feedback band.
            band.reset()
            band.moveTo(xs[0], bandBase)
            band.lineTo(xs[0], bandBase - bandOf(ysEcho[0]))
            addSmooth(band, n, ysEcho) { v -> bandBase - bandOf(v) }
            band.lineTo(xs[n - 1], bandBase)
            band.close()
            canvas.drawPath(band, bandPaint)
        } else {
            // Nothing yet: a calm line where the ribbon will be.
            canvas.drawLine(plotLeft, centerY, plotRight, centerY, crestPaint)
        }

        // Now: a dot that swells with the voice.
        val hot = squealing
        val scale = (7f + headLevel * 12f) / HALO_MAX_DP
        haloPaint.shader = if (hot) haloRed else haloTeal
        canvas.save()
        canvas.translate(plotRight, centerY)
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, HALO_MAX_DP * density, haloPaint)
        canvas.restore()
        dotPaint.color = if (hot) RED else MINT
        canvas.drawCircle(plotRight, centerY, dpf(3.6f), dotPaint)
    }

    // ── Shapes ────────────────────────────────────────────────────────────────────────────

    private fun halfOf(level: Float): Float = dpf(1.1f) + (maxHalf - dpf(1.1f)) * level

    private fun bandOf(level: Float): Float = dpf(1f) + (bandHeight - dpf(1f)) * level

    /** The two edges of a ribbon whose thickness at each point is [levels]: [upperEdge] above the centre line and [lowerEdge] below it, both left to right. */
    private fun smoothEdges(upperEdge: Path, lowerEdge: Path, n: Int, levels: FloatArray) {
        upperEdge.reset()
        lowerEdge.reset()
        upperEdge.moveTo(xs[0], centerY - halfOf(levels[0]))
        lowerEdge.moveTo(xs[0], centerY + halfOf(levels[0]))
        addSmooth(upperEdge, n, levels) { v -> centerY - halfOf(v) }
        addSmooth(lowerEdge, n, levels) { v -> centerY + halfOf(v) }
    }

    /** Continues [path] through the points (xs, [y] of each level) with a smooth curve (a Catmull-Rom spline as cubic segments). The first point is already there. */
    private fun addSmooth(path: Path, n: Int, levels: FloatArray, y: (Float) -> Float) {
        for (i in 0 until n - 1) {
            val i0 = max(0, i - 1)
            val i3 = min(n - 1, i + 2)
            val x0 = xs[i0]
            val y0 = y(levels[i0])
            val x1 = xs[i]
            val y1 = y(levels[i])
            val x2 = xs[i + 1]
            val y2 = y(levels[i + 1])
            val x3 = xs[i3]
            val y3 = y(levels[i3])
            path.cubicTo(x1 + (x2 - x0) / 6f, y1 + (y2 - y0) / 6f, x2 - (x3 - x1) / 6f, y2 - (y3 - y1) / 6f, x2, y2)
        }
    }

    /** Continues [path] from the last point back to the first, through the same points with the same kind of curve (the lower edge of the ribbon, walked right to left). */
    private fun addSmoothBackwards(path: Path, n: Int, levels: FloatArray, y: (Float) -> Float) {
        for (i in n - 1 downTo 1) {
            val i0 = min(n - 1, i + 1)
            val i3 = max(0, i - 2)
            val x0 = xs[i0]
            val y0 = y(levels[i0])
            val x1 = xs[i]
            val y1 = y(levels[i])
            val x2 = xs[i - 1]
            val y2 = y(levels[i - 1])
            val x3 = xs[i3]
            val y3 = y(levels[i3])
            path.cubicTo(x1 + (x2 - x0) / 6f, y1 + (y2 - y0) / 6f, x2 - (x3 - x1) / 6f, y2 - (y3 - y1) / 6f, x2, y2)
        }
    }

    private fun ensureCapacity(n: Int) {
        if (xs.size >= n) return
        xs = FloatArray(n)
        ysSent = FloatArray(n)
        ysMic = FloatArray(n)
        ysEcho = FloatArray(n)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun dpf(value: Float): Float = value * density

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    companion object {
        /** How much of the past the card shows. */
        private const val WINDOW_MS = 4_000L
        private const val FRAME_MS = 100L
        private const val MAX_SAMPLES = (WINDOW_MS / FRAME_MS).toInt() + 8
        private const val SQUEAL_SHOWN_MS = 1_500L
        private const val HALO_MAX_DP = 19f

        private val INK_TOP = Color.parseColor("#FF121E2A")
        private val INK_BOTTOM = Color.parseColor("#FF0A121A")
        private val TEAL = Color.parseColor("#35D1BC")
        private val MINT = Color.parseColor("#BDFFF3")
        private val COOL = Color.parseColor("#9FB4C7")
        private val ORANGE = Color.parseColor("#FF9F43")
        private val RED = Color.parseColor("#FF5A4D")
    }
}
