package com.bydassistantng.ui.home

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.audio.AudioRecorder
import com.bydassistantng.audio.TextToSpeechEngine
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.gemini.ConversationController
import com.bydassistantng.gemini.LiveConversationController
import com.bydassistantng.gemini.TurnStatus
import com.bydassistantng.update.UpdateManager
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "HomeViewModel"
private const val RECORDED_AUDIO_MIME_TYPE = "audio/m4a"

sealed interface HomeUiState {
    data object Idle : HomeUiState
    data object Connecting : HomeUiState
    data object Listening : HomeUiState
    data object Thinking : HomeUiState
    data class Dispatching(val displayName: String) : HomeUiState
    data class Speaking(val text: String) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

/**
 * A tap decides which pipeline handles the turn purely from resource state — whichever of
 * [LiveConversationController.inConversation] / [AudioRecorder.isRecording] is true, if either — rather
 * than a separately tracked mode flag that could drift out of sync with what's actually running
 * (e.g. a Live turn ending on its own via server-side voice-activity detection, with no second tap
 * ever happening to update a flag).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val conversationController: ConversationController,
    private val liveConversationController: LiveConversationController,
    private val ttsEngine: TextToSpeechEngine,
    preferencesRepository: PreferencesRepository,
    private val updateManager: UpdateManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val vehicleControlEnabled: StateFlow<Boolean> =
        preferencesRepository.vehicleControlEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val updateState: StateFlow<UpdateState> = updateManager.state

    /** The version just installed, until the note about it is dismissed. */
    val justUpdatedTo: StateFlow<String?> = updateManager.justUpdatedTo
    fun dismissJustUpdated() = updateManager.dismissJustUpdated()

    fun onMicTapped() {
        when {
            // A tap ends a conversation in progress, whatever stage it's at; otherwise it starts one.
            liveConversationController.inConversation -> liveConversationController.endConversation()
            audioRecorder.isRecording -> stopClassicRecordingAndRunTurn()
            else -> startNewTurn()
        }
    }

    private fun startNewTurn() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Connecting
            val liveStarted = liveConversationController.start { status -> handleLiveStatus(status) }
            if (liveStarted) {
                _uiState.value = HomeUiState.Listening
                return@launch
            }

            AppLogger.log(TAG, "Live unavailable this turn, falling back to the classic pipeline")
            val file = audioRecorder.start(onMaxDurationReached = { onMicTapped() }, onSilenceDetected = { onMicTapped() })
            _uiState.value = if (file != null) HomeUiState.Listening else HomeUiState.Error(AppLanguage.string(context, R.string.err_cant_record))
        }
    }

    private fun stopClassicRecordingAndRunTurn() {
        val file = audioRecorder.stop()
        if (file == null) {
            _uiState.value = HomeUiState.Error(AppLanguage.string(context, R.string.err_no_audio))
        } else {
            runClassicTurn(file)
        }
    }

    private fun runClassicTurn(file: File) {
        _uiState.value = HomeUiState.Thinking
        viewModelScope.launch {
            // Speaking happens AFTER runTurn() returns, never from inside its callback: the
            // callback runs while ConversationController's turn mutex is still held, and a slow or
            // hung TTS engine (seen for real: 20+ minutes before an init callback ever fired on one
            // device) must never be able to block every subsequent voice turn behind it.
            var toSpeak: TurnStatus.Speaking? = null
            conversationController.runTurn(file, RECORDED_AUDIO_MIME_TYPE) { status ->
                _uiState.value = statusToUiState(status)
                if (status is TurnStatus.Speaking) toSpeak = status
            }
            file.delete()
            // The classic path only ever gets text back — Live produces real audio output itself,
            // so only this fallback path still needs a local TTS call.
            toSpeak?.let { ttsEngine.speak(it.text, it.language) }
        }
    }

    private suspend fun handleLiveStatus(status: TurnStatus) {
        // Done means the conversation is over: back to idle (but keep a shown error readable).
        if (status == TurnStatus.Done) {
            if (_uiState.value !is HomeUiState.Error) _uiState.value = HomeUiState.Idle
            return
        }
        _uiState.value = statusToUiState(status)
    }

    private fun statusToUiState(status: TurnStatus): HomeUiState = when (status) {
        TurnStatus.Listening -> HomeUiState.Listening
        TurnStatus.Thinking -> HomeUiState.Thinking
        is TurnStatus.DispatchingCommand -> HomeUiState.Dispatching(status.displayName)
        is TurnStatus.Speaking -> HomeUiState.Speaking(status.text)
        is TurnStatus.Failed -> HomeUiState.Error(status.message)
        TurnStatus.Done -> HomeUiState.Idle
    }

    override fun onCleared() {
        audioRecorder.cancel()
        // Deliberately NOT closing the Live conversation: it is shared with the steering-wheel button,
        // which can have started it while this screen wasn't even showing. It ends on its own (silence,
        // "stop", a press).
        super.onCleared()
    }
}
