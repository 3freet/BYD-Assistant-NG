package com.bydassistantng.ui

import android.view.SoundEffectConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import com.bydassistantng.BuildConfig
import com.bydassistantng.util.AppLogger

/**
 * The system's tap sound for taps in Compose. A classic View plays it by itself when pressed; Compose's
 * buttons, rows and switches do not, so a tap here was silent where the rest of the head unit ticks.
 *
 * Put once on the root of each window (the activity's, and a dialog's, which is a window of its own). It
 * looks at how a press ends *after* the elements below have had their say: a press that something took (a
 * button, a row, a switch consumed it) and that ended as a click, released soon and without moving, is a tap;
 * a scroll, a long press and a press on nothing are not.
 */
@Composable
fun Modifier.tapSound(): Modifier {
    val view = LocalView.current
    val configuration = LocalViewConfiguration.current
    return pointerInput(view) {
        awaitEachGesture {
            // Final pass: children (Main) have already looked at the event, so "consumed" says someone took it.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (!down.isConsumed) return@awaitEachGesture
            val up = withTimeoutOrNull(configuration.longPressTimeoutMillis) {
                var released: PointerInputChange? = null
                while (released == null) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull null
                    if (change.changedToUpIgnoreConsumed()) {
                        released = change
                    } else if ((change.position - down.position).getDistance() > configuration.touchSlop) {
                        return@withTimeoutOrNull null
                    }
                }
                released
            }
            // The click was recognised (and so consumed) by the element that took the press.
            if (up != null && up.isConsumed) {
                if (BuildConfig.DEBUG) AppLogger.log("TapSound", "tap")
                view.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
    }
}

/** An [AlertDialog] whose taps tick too: a dialog is a window of its own, outside the activity's root. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.tapSound(),
        dismissButton = dismissButton,
        title = title,
        text = text,
    )
}
