package com.bydassistantng.service

import android.animation.ValueAnimator
import android.content.Context
import android.text.TextUtils
import androidx.annotation.StringRes
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.R
import com.bydassistantng.util.AppLogger

private const val TAG = "StatusBanner"

// A banner nobody is updating any more (a lost "done") must not stay on a driver's screen for good.
private const val STALE_AFTER_MS = 45_000L
private const val ERROR_SHOWN_MS = 4_000L

/** What the assistant is doing, as the banner shows it. */
enum class BannerMode(@StringRes val labelRes: Int, val accent: Int) {
    CONNECTING(R.string.banner_connecting, Color.parseColor("#9FB4C7")),
    LISTENING(R.string.banner_listening, Color.parseColor("#4FD1FF")),
    THINKING(R.string.banner_thinking, Color.parseColor("#FFC857")),
    SPEAKING(R.string.banner_speaking, Color.parseColor("#7CF0C4")),
    ERROR(R.string.banner_error, Color.parseColor("#FF6B4A")),
}

/**
 * A small status pill at the top of the screen that stays up for the whole conversation and says what
 * the assistant is doing right now — so it is obvious at a glance whether the mic is live.
 *
 * It is a window of type `TYPE_ACCESSIBILITY_OVERLAY`, which an accessibility service may add with no
 * "display over other apps" permission, and it is never touchable or focusable, so it can't get in the
 * way of the screen underneath. (Toasts were used before: Android caps them at ~2 seconds and drops
 * them past a quota, so they can't show "the mic is on".)
 *
 * Built from plain Views rather than Compose, which would need its own lifecycle plumbing inside a
 * service. All methods may be called from any thread.
 */
class StatusBanner(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var pill: LinearLayout? = null
    private var icon: ImageView? = null
    private var label: TextView? = null
    private var detail: TextView? = null
    private var pulse: ValueAnimator? = null
    private var attached = false

    private val hideRunnable = Runnable { hideNow() }

    /** Shows (or updates) the banner. [detail] is an optional second line, e.g. what's being said. */
    fun show(mode: BannerMode, detail: String? = null, labelOverride: String? = null, shownMs: Long? = null) {
        handler.post { showNow(mode, detail, labelOverride, shownMs) }
    }

    fun hide() {
        handler.post { hideNow() }
    }

    /** Removes the window immediately, for when the owning service is going away. */
    fun release() {
        handler.post {
            handler.removeCallbacks(hideRunnable)
            setAnimation(Pulse.NONE)
            pill?.let { runCatching { windowManager.removeView(it) } }
            attached = false
            pill = null
        }
    }

    private fun showNow(mode: BannerMode, detailText: String?, labelOverride: String?, shownMs: Long?) {
        if (!ensureAttached()) return
        val pill = pill ?: return
        // Same reason as in MainActivity: this view tree was built when the service started.
        pill.layoutDirection = TextUtils.getLayoutDirectionFromLocale(AppLanguage.locale(context))

        label?.text = labelOverride ?: AppLanguage.string(context, mode.labelRes)
        detail?.apply {
            text = detailText.orEmpty()
            visibility = if (detailText.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        (pill.background as? GradientDrawable)?.setStroke(dp(2), withAlpha(mode.accent, 0xAA))

        icon?.apply {
            setImageResource(
                when (mode) {
                    BannerMode.SPEAKING -> R.drawable.ic_banner_speaker
                    BannerMode.ERROR -> R.drawable.ic_banner_warning
                    BannerMode.THINKING, BannerMode.CONNECTING -> R.drawable.ic_banner_dots
                    BannerMode.LISTENING -> R.drawable.ic_banner_mic
                },
            )
            imageTintList = ColorStateList.valueOf(mode.accent)
        }
        // Motion carries the meaning: the live mic swells ("I can hear you"), the dots fade in and out
        // ("working"); the others are still.
        setAnimation(
            when (mode) {
                BannerMode.LISTENING -> Pulse.SWELL
                BannerMode.THINKING, BannerMode.CONNECTING -> Pulse.FADE
                else -> Pulse.NONE
            },
        )

        pill.animate().cancel()
        pill.alpha = 1f
        pill.visibility = View.VISIBLE

        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, shownMs ?: if (mode == BannerMode.ERROR) ERROR_SHOWN_MS else STALE_AFTER_MS)
    }

    private fun hideNow() {
        handler.removeCallbacks(hideRunnable)
        setAnimation(Pulse.NONE)
        val pill = pill ?: return
        pill.animate().cancel()
        pill.animate().alpha(0f).setDuration(180).withEndAction { detach() }.start()
    }

    private enum class Pulse { NONE, SWELL, FADE }

    private fun setAnimation(kind: Pulse) {
        pulse?.cancel()
        pulse = null
        icon?.apply { scaleX = 1f; scaleY = 1f; alpha = 1f }
        if (kind == Pulse.NONE) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (kind == Pulse.SWELL) 650 else 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                icon?.apply {
                    if (kind == Pulse.SWELL) {
                        scaleX = 1f + 0.22f * t
                        scaleY = 1f + 0.22f * t
                    } else {
                        alpha = 1f - 0.65f * t
                    }
                }
            }
            start()
        }
    }

    private fun ensureAttached(): Boolean {
        if (attached) return true
        val view = buildView()
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
            y = dp(66) // just under the head unit's status bar
        }
        return try {
            windowManager.addView(view, params)
            attached = true
            true
        } catch (e: Throwable) {
            AppLogger.logError(TAG, "Could not add the status banner window", e)
            pill = null
            false
        }
    }

    private fun detach() {
        val view = pill ?: return
        if (view.alpha > 0f) return // shown again while it was fading out
        runCatching { windowManager.removeView(view) }
        attached = false
        pill = null
        icon = null
        label = null
        detail = null
    }

    private fun buildView(): View {
        val iconView = ImageView(context).apply { layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER) }
        val iconSlot = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) }
            addView(iconView)
        }

        val labelView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
        }
        val detailView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Color.parseColor("#C9D6E2"))
            maxLines = 4 // an error can carry the same message in English and Arabic
            maxWidth = dp(560)
            visibility = View.GONE
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelView)
            addView(detailView)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(28), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(40).toFloat()
                setColor(Color.parseColor("#F00A1A2F"))
            }
            elevation = dp(6).toFloat()
            addView(iconSlot)
            addView(texts)
        }
        pill = root
        icon = iconView
        label = labelView
        detail = detailView
        return root
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
