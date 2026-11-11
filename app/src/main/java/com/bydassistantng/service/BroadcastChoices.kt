package com.bydassistantng.service

import android.content.Context
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bydassistantng.R
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.audio.MusicMode
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger

private const val TAG = "BroadcastChoices"

/**
 * The small choices that hover on the screen under the level meter while a live broadcast is on ([com.bydassistantng.audio.LiveBroadcast]): where the
 * music goes (paused, outside, or inside, which uses the slow route) and how loud the exterior speaker is (low, medium, high). A tap on one makes it
 * so at once, in the broadcast that is going on, and it is kept for the next ones. The music group is left out where the car has only the one route.
 *
 * Like the status banner and the meter it is a window of type `TYPE_ACCESSIBILITY_OVERLAY` (an accessibility service may add one with no "display
 * over other apps" permission) built from plain Views, but it is touchable, and takes no focus so that the screen underneath keeps working. All
 * methods may be called from any thread.
 */
class BroadcastChoices(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var card: LinearLayout? = null
    private val musicChips = LinkedHashMap<MusicMode, TextView>()
    private val loudnessChips = LinkedHashMap<ExteriorVolume, TextView>()
    private var attached = false
    private var withMusic = true
    private var music = MusicMode.DEFAULT
    private var loudness = ExteriorVolume.DEFAULT
    private var onMusic: ((MusicMode) -> Unit)? = null
    private var onLoudness: ((ExteriorVolume) -> Unit)? = null

    /** Shows the choices with [music] and [loudness] picked; [musicChoice] is whether the music group is offered. The callbacks are called on a tap. */
    fun show(musicChoice: Boolean, music: MusicMode, loudness: ExteriorVolume, onMusic: (MusicMode) -> Unit, onLoudness: (ExteriorVolume) -> Unit) {
        handler.post {
            this.onMusic = onMusic
            this.onLoudness = onLoudness
            this.music = music
            this.loudness = loudness
            // A change in whether the music group is there needs a new card.
            if (attached && withMusic != musicChoice) detach()
            withMusic = musicChoice
            if (ensureAttached()) apply()
        }
    }

    /** Picks what is picked now, when it was changed some other way (or has not been taken yet). */
    fun update(music: MusicMode, loudness: ExteriorVolume) {
        handler.post {
            this.music = music
            this.loudness = loudness
            if (attached) apply()
        }
    }

    fun hide() {
        handler.post { detach() }
    }

    private fun apply() {
        // The card is the same way round in every language: loudness on the left, music on the right (the words in the chips follow their own script).
        card?.layoutDirection = View.LAYOUT_DIRECTION_LTR
        for ((mode, chip) in musicChips) style(chip, mode == music, musicAccent(mode))
        for ((level, chip) in loudnessChips) style(chip, level == loudness, loudnessAccent(level))
    }

    private fun style(chip: TextView, selected: Boolean, accent: Int) {
        (chip.background as GradientDrawable).apply {
            setColor(if (selected) accent else Color.TRANSPARENT)
            setStroke(dp(1), if (selected) accent else Color.parseColor("#33FFFFFF"))
        }
        chip.setTextColor(if (selected) Color.parseColor("#0A121A") else Color.parseColor("#D3DEE8"))
        chip.isSelected = selected
    }

    private fun ensureAttached(): Boolean {
        if (attached) return true
        val view = buildView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // At the top centre, under the status banner (which is a little over 100 dp tall with its second line) and the level meter: where the
            // driver looks during a broadcast, and clear of the corners where apps keep their own buttons.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(METER_TOP_DP + METER_HEIGHT_DP + 10)
        }
        return try {
            windowManager.addView(view, params)
            card = view
            attached = true
            true
        } catch (e: Throwable) {
            AppLogger.logError(TAG, "Could not add the choices window", e)
            musicChips.clear()
            loudnessChips.clear()
            false
        }
    }

    private fun detach() {
        val view = card ?: return
        runCatching { windowManager.removeView(view) }
        attached = false
        card = null
        musicChips.clear()
        loudnessChips.clear()
    }

    private fun buildView(): LinearLayout {
        musicChips.clear()
        loudnessChips.clear()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.parseColor("#FF121E2A"))
                setStroke(dp(1) + 1, Color.parseColor("#4D35D1BC"))
            }
            elevation = dp(6).toFloat()
        }
        root.addView(groupIcon(R.drawable.ic_choice_voice, Color.parseColor("#9FB4C7")))
        for (level in ExteriorVolume.entries) {
            val chip = chip(label(level)) { onLoudness?.invoke(level) }
            loudnessChips[level] = chip
            root.addView(chip)
        }
        if (withMusic) {
            root.addView(divider())
            root.addView(groupIcon(R.drawable.ic_choice_music, Color.parseColor("#9FB4C7")))
            for (mode in MusicMode.entries) {
                val chip = chip(label(mode)) { onMusic?.invoke(mode) }
                musicChips[mode] = chip
                root.addView(chip)
            }
        }
        return root
    }

    private fun label(mode: MusicMode): String = AppLanguage.string(
        context,
        when (mode) {
            MusicMode.PAUSED -> R.string.live_choice_music_paused
            MusicMode.OUTSIDE -> R.string.live_choice_music_outside
            MusicMode.INSIDE -> R.string.live_choice_music_inside
        },
    )

    private fun label(level: ExteriorVolume): String = AppLanguage.string(
        context,
        when (level) {
            ExteriorVolume.LOW -> R.string.exterior_volume_low
            ExteriorVolume.MEDIUM -> R.string.exterior_volume_medium
            ExteriorVolume.HIGH -> R.string.exterior_volume_high
        },
    )

    private fun groupIcon(drawable: Int, tint: Int): ImageView = ImageView(context).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
            marginEnd = dp(6)
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(Color.parseColor("#33FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        }
    }

    private fun chip(text: String, onTap: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        contentDescription = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(13), dp(7), dp(13), dp(7))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
        isClickable = true
        // No system click: on the fast route the output the click is played on is the one that goes out of the exterior speaker.
        isSoundEffectsEnabled = false
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) }
    }

    private fun musicAccent(mode: MusicMode): Int = when (mode) {
        MusicMode.PAUSED -> Color.parseColor("#9FB4C7")
        MusicMode.OUTSIDE -> Color.parseColor("#FF9F43")
        MusicMode.INSIDE -> Color.parseColor("#35D1BC")
    }

    private fun loudnessAccent(level: ExteriorVolume): Int = when (level) {
        ExteriorVolume.LOW -> Color.parseColor("#9FD8CF")
        ExteriorVolume.MEDIUM -> Color.parseColor("#35D1BC")
        ExteriorVolume.HIGH -> Color.parseColor("#FF9F43")
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
