package com.bydassistantng.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bydassistantng.R
import com.bydassistantng.util.WheelKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the UI needs to show and switch on the steering-wheel button service. */
@Stable
class WheelServiceSetup internal constructor(private val context: Context, private val scope: CoroutineScope) {
    var enabled by mutableStateOf(WheelKeys.isServiceEnabled(context))
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    val failureHint: String get() = context.getString(R.string.wheel_failure_hint, context.packageName)

    fun refresh() {
        enabled = WheelKeys.isServiceEnabled(context)
    }

    fun enable() {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            val ok = WheelKeys.enableService(context)
            busy = false
            failed = !ok
            refresh()
        }
    }
}

/** Re-reads the enabled state on every resume, since it can also change outside the app. */
@Composable
fun rememberWheelServiceSetup(): WheelServiceSetup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { WheelServiceSetup(context, scope) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) setup.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return setup
}
