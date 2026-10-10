package com.bydassistantng.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.R
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.service.WheelKeyService
import com.bydassistantng.update.UpdateManager
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLanguageChoice
import com.bydassistantng.util.WheelKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val KEY_CAPTURE_TIMEOUT_MS = 60_000L

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureCredentials: SecureCredentials,
    private val preferencesRepository: PreferencesRepository,
    private val updateManager: UpdateManager,
) : ViewModel() {
    private val _appLanguage = MutableStateFlow(AppLanguage.choice(context))
    /** The language of the app's own screens (not the one the assistant speaks). */
    val appLanguage: StateFlow<AppLanguageChoice> = _appLanguage.asStateFlow()

    fun setAppLanguage(choice: AppLanguageChoice) {
        AppLanguage.setChoice(context, choice)
        _appLanguage.value = choice
        // Messages already on screen are in the old language; drop them rather than show a mix.
        _keyMessage.value = null
        _apiKeyMessage.value = null
    }

    private fun text(id: Int, vararg args: Any): String = AppLanguage.string(context, id, *args)

    val language: StateFlow<AssistantLanguage> = preferencesRepository.assistantLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantLanguage.AUTO)
    val vehicleControlEnabled: StateFlow<Boolean> = preferencesRepository.vehicleControlEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val bargeInEnabled: StateFlow<Boolean> = preferencesRepository.bargeInEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val arabicDialect: StateFlow<ArabicDialect> = preferencesRepository.arabicDialect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArabicDialect.MATCH)
    val assistantVoice: StateFlow<String> = preferencesRepository.assistantVoice
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val webSearchEnabled: StateFlow<Boolean> = preferencesRepository.webSearchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setArabicDialect(dialect: ArabicDialect) {
        viewModelScope.launch { preferencesRepository.setArabicDialect(dialect) }
    }

    fun setAssistantVoice(voiceName: String) {
        viewModelScope.launch { preferencesRepository.setAssistantVoice(voiceName) }
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setWebSearchEnabled(enabled) }
    }

    fun setBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setBargeInEnabled(enabled) }
    }

    val triggerKeyCode: StateFlow<Int> = preferencesRepository.triggerKeyCode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WheelKeys.MIC_SHORT_PRESS)

    private val _capturingKey = MutableStateFlow(false)
    /** True while Settings is waiting for the user to press the button they want to use. */
    val capturingKey: StateFlow<Boolean> = _capturingKey.asStateFlow()

    private val _keyMessage = MutableStateFlow<String?>(null)
    val keyMessage: StateFlow<String?> = _keyMessage.asStateFlow()

    private var captureTimeout: Job? = null

    fun startKeyCapture() {
        _keyMessage.value = null
        val listening = WheelKeyService.beginKeyCapture { keyCode ->
            captureTimeout?.cancel()
            _capturingKey.value = false
            if (WheelKeys.isReserved(keyCode)) {
                _keyMessage.value = text(R.string.key_cannot_use, WheelKeys.describe(context, keyCode))
            } else {
                _keyMessage.value = text(R.string.key_now_using, WheelKeys.describe(context, keyCode))
                viewModelScope.launch { preferencesRepository.setTriggerKeyCode(keyCode) }
            }
        }
        if (listening) {
            _capturingKey.value = true
            // The service can be restarted by the system while waiting, which silently drops the
            // pending capture — without this the screen would wait for a press forever.
            captureTimeout = viewModelScope.launch {
                delay(KEY_CAPTURE_TIMEOUT_MS)
                if (_capturingKey.value) {
                    cancelKeyCapture()
                    _keyMessage.value = text(R.string.key_no_press)
                }
            }
        } else {
            _keyMessage.value = text(R.string.key_service_not_running)
        }
    }

    fun cancelKeyCapture() {
        captureTimeout?.cancel()
        WheelKeyService.cancelKeyCapture()
        _capturingKey.value = false
    }

    fun resetKey() {
        _keyMessage.value = null
        viewModelScope.launch { preferencesRepository.setTriggerKeyCode(WheelKeys.MIC_SHORT_PRESS) }
    }

    override fun onCleared() {
        WheelKeyService.cancelKeyCapture()
        super.onCleared()
    }

    val autoStartVisitedAt: StateFlow<Long> = preferencesRepository.autoStartVisitedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun markAutoStartVisited() {
        viewModelScope.launch { preferencesRepository.markAutoStartVisited() }
    }

    private val _hasApiKey = MutableStateFlow(false)
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _apiKeyMessage = MutableStateFlow<String?>(null)
    val apiKeyMessage: StateFlow<String?> = _apiKeyMessage.asStateFlow()

    /** What the Updates row shows; the Updates screen has the detail. */
    val updateState: StateFlow<UpdateState> = updateManager.state
    val installedVersionName: String get() = updateManager.installed.versionName

    /** Whether this build can update itself at all (a local developer build can't). */
    val updatesSupported: Boolean get() = updateManager.isSupported

    init {
        viewModelScope.launch { _hasApiKey.value = secureCredentials.hasApiKey() }
    }

    fun setLanguage(language: AssistantLanguage) {
        viewModelScope.launch { preferencesRepository.setAssistantLanguage(language) }
    }

    fun setVehicleControlEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setVehicleControlEnabled(enabled) }
    }

    fun updateApiKey(key: String) {
        if (key.isBlank()) {
            _apiKeyMessage.value = text(R.string.api_enter_key)
            return
        }
        viewModelScope.launch {
            val saved = secureCredentials.setApiKey(key.trim())
            _apiKeyMessage.value = text(if (saved) R.string.api_saved else R.string.api_save_failed)
            _hasApiKey.value = secureCredentials.hasApiKey()
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            secureCredentials.clearApiKey()
            _hasApiKey.value = false
            _apiKeyMessage.value = text(R.string.api_cleared)
        }
    }
}
