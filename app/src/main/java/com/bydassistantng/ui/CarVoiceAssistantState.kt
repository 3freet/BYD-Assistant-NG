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
import com.bydassistantng.util.CarVoiceAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the UI needs to show and switch the car's own voice assistant. */
@Stable
class CarVoiceAssistantSetup internal constructor(private val context: Context, private val scope: CoroutineScope) {
    var state by mutableStateOf(CarVoiceAssistant.state(context))
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun refresh() {
        state = CarVoiceAssistant.state(context)
    }

    fun set(on: Boolean) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            val ok = CarVoiceAssistant.set(context, on)
            busy = false
            failed = !ok
            refresh()
        }
    }
}

/** Re-reads the state on every resume, since it can also change outside the app. */
@Composable
fun rememberCarVoiceAssistantSetup(): CarVoiceAssistantSetup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setup = remember { CarVoiceAssistantSetup(context, scope) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) setup.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return setup
}
