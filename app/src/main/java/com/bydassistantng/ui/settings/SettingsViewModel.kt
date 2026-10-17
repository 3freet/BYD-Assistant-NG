package com.bydassistantng.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.R
import com.bydassistantng.audio.EndOfSpeechWait
import com.bydassistantng.audio.ExteriorSpeaker
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.audio.HeadrestRoute
import com.bydassistantng.audio.MusicDuck
import com.bydassistantng.audio.VoiceChannel
import com.bydassistantng.audio.VoiceChannelOptions
import com.bydassistantng.audio.VoiceSpeaker
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.cluster.ClusterScreenAvailability
import com.bydassistantng.data.CoreFeatures
import com.bydassistantng.data.OptInFeatures
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.greeting.GreetingCoordinator
import com.bydassistantng.memory.AddOutcome
import com.bydassistantng.memory.MemoryFact
import com.bydassistantng.memory.MemoryRepository
import com.bydassistantng.service.WheelKeyService
import com.bydassistantng.update.UpdateChannel
import com.bydassistantng.update.UpdateManager
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLanguageChoice
import com.bydassistantng.util.WheelKeys
import com.bydassistantng.vehicle.DriveModeSwitch
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

private const val KEY_CAPTURE_TIMEOUT_MS = 60_000L

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureCredentials: SecureCredentials,
    private val preferencesRepository: PreferencesRepository,
    private val memory: MemoryRepository,
    private val updateManager: UpdateManager,
    private val headrestRoute: HeadrestRoute,
    private val greeting: GreetingCoordinator,
    exteriorSpeaker: Optional<ExteriorSpeaker>,
    driveModes: Optional<DriveModeSwitch>,
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoreFeatures.VEHICLE_CONTROL_DEFAULT)
    val bargeInEnabled: StateFlow<Boolean> = preferencesRepository.bargeInEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val endOfSpeechWaitMs: StateFlow<Int> = preferencesRepository.endOfSpeechWaitMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EndOfSpeechWait.DEFAULT_MS)

    fun setEndOfSpeechWaitMs(ms: Int) {
        viewModelScope.launch { preferencesRepository.setEndOfSpeechWaitMs(ms) }
    }

    val greetingEnabled: StateFlow<Boolean> = preferencesRepository.greetingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setGreetingEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setGreetingEnabled(enabled) }
    }

    val greetingSpeaker: StateFlow<VoiceSpeaker> = preferencesRepository.greetingSpeaker
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceSpeaker.HEADREST)

    fun setGreetingSpeaker(speaker: VoiceSpeaker) {
        viewModelScope.launch { preferencesRepository.setGreetingSpeaker(speaker) }
    }

    /** Plays the greeting now, whatever the car is doing (the test button); [onOutcome] gets what happened, to tell the user. */
    fun playGreeting(onOutcome: (GreetingCoordinator.SimulationOutcome) -> Unit) {
        viewModelScope.launch { onOutcome(greeting.simulate()) }
    }

    val shortTermMemoryEnabled: StateFlow<Boolean> = preferencesRepository.shortTermMemoryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val longTermMemoryEnabled: StateFlow<Boolean> = preferencesRepository.longTermMemoryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val memories: StateFlow<List<MemoryFact>> = memory.facts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Turning short-term memory off also drops what it was holding, so nothing is left behind unused. */
    fun setShortTermMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setShortTermMemoryEnabled(enabled)
            if (!enabled) memory.clearRecent()
        }
    }

    fun setLongTermMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setLongTermMemoryEnabled(enabled) }
    }

    /** Adds a note by hand; returns what happened, for the dialog to say. */
    fun addMemory(text: String, onResult: (AddOutcome) -> Unit) {
        viewModelScope.launch { onResult(memory.addFact(text)) }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch { memory.deleteFact(id) }
    }

    fun clearMemories() {
        viewModelScope.launch {
            memory.clearFacts()
            memory.clearRecent()
        }
    }

    /** Which releases the update check follows, and whether it runs by itself. */
    val updateChannel: StateFlow<UpdateChannel> = updateManager.channel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), updateManager.installed.channel)
    val autoCheckUpdates: StateFlow<Boolean> = updateManager.autoCheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setUpdateChannel(channel: UpdateChannel) = updateManager.setChannel(channel)
    fun setAutoCheckUpdates(enabled: Boolean) = updateManager.setAutoCheck(enabled)

    /** Whether this build can use the car's exterior speaker, and so has a switch and a volume for it. */
    val exteriorSpeakerSupported: Boolean = exteriorSpeaker.isPresent

    /** Whether this build can change the car's drive mode, and so has a switch for it. */
    val driveModesSupported: Boolean = driveModes.isPresent

    /** Whether this build and this car offer the instrument cluster feature (experimental; it turns BYD Maps on and off). */
    val clusterSupported: Boolean = ClusterScreenAvailability.isOnThisCar(context)

    /** The experimental car features are off until the user switches them on (see [OptInFeatures]). */
    val exteriorSpeakerEnabled: StateFlow<Boolean> = preferencesRepository.exteriorSpeakerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.EXTERIOR_SPEAKER_DEFAULT)
    val driveModesEnabled: StateFlow<Boolean> = preferencesRepository.driveModesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.DRIVE_MODES_DEFAULT)
    val clusterScreenEnabled: StateFlow<Boolean> = preferencesRepository.clusterScreenEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.CLUSTER_SCREEN_DEFAULT)
    val clusterKeepMapsDisabled: StateFlow<Boolean> = preferencesRepository.clusterKeepMapsDisabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.CLUSTER_KEEP_MAPS_DISABLED_DEFAULT)
    val exteriorVolume: StateFlow<ExteriorVolume> = preferencesRepository.exteriorVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExteriorVolume.DEFAULT)

    fun setExteriorSpeakerEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setExteriorSpeakerEnabled(enabled) }
    }
    fun setDriveModesEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setDriveModesEnabled(enabled) }
    }
    fun setClusterScreenEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setClusterScreenEnabled(enabled) }
    }
    fun setClusterKeepMapsDisabled(disabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setClusterKeepMapsDisabled(disabled) }
    }
    fun setExteriorVolume(volume: ExteriorVolume) {
        viewModelScope.launch { preferencesRepository.setExteriorVolume(volume) }
    }

    /** Whether this car has the voice channel, and so has its section in Settings. */
    val voiceChannelSupported: Boolean = VoiceChannel.isAvailable(context)

    val voiceChannelEnabled: StateFlow<Boolean> = preferencesRepository.voiceChannelEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoreFeatures.VOICE_CHANNEL_DEFAULT)

    val voiceChannelMusic: StateFlow<MusicDuck> = preferencesRepository.voiceChannelMusic
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicDuck.DEFAULT)

    val voiceChannelAdaptive: StateFlow<Boolean> = preferencesRepository.voiceChannelAdaptive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoreFeatures.VOICE_CHANNEL_ADAPTIVE_DEFAULT)

    val voiceChannelGain: StateFlow<Int> = preferencesRepository.voiceChannelGain
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val voiceChannelDip: StateFlow<Int> = preferencesRepository.voiceChannelDip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceChannelOptions.DEFAULT_DIP_PERCENT)

    val voiceChannelSpeaker: StateFlow<VoiceSpeaker> = preferencesRepository.voiceChannelSpeaker
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceSpeaker.DEFAULT)

    /** Whether the car has a headrest speaker to choose (known once the car has been asked; see [findHeadrestSpeaker]). */
    val headrestSpeakerPresent: StateFlow<Boolean> = preferencesRepository.headrestSpeakerPresent
        .map { it == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setVoiceChannelSpeaker(speaker: VoiceSpeaker) {
        viewModelScope.launch { preferencesRepository.setVoiceChannelSpeaker(speaker) }
    }

    /**
     * Asks the car, once, whether it has a headrest speaker (it needs the same ADB connection the car controls use, so
     * nothing is asked until the sound settings are opened). An answer is kept; a car that could not be reached is
     * asked again next time.
     */
    fun findHeadrestSpeaker() {
        viewModelScope.launch {
            if (preferencesRepository.headrestSpeakerPresent.first() != null) return@launch
            val state = headrestRoute.read() ?: return@launch
            preferencesRepository.setHeadrestSpeakerPresent(state.hasSpeaker)
        }
    }

    fun setVoiceChannelEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setVoiceChannelEnabled(enabled) }
    }
    fun setVoiceChannelMusic(music: MusicDuck) {
        viewModelScope.launch { preferencesRepository.setVoiceChannelMusic(music) }
    }
    fun setVoiceChannelAdaptive(adaptive: Boolean) {
        viewModelScope.launch { preferencesRepository.setVoiceChannelAdaptive(adaptive) }
    }
    fun setVoiceChannelDip(percent: Int) {
        viewModelScope.launch { preferencesRepository.setVoiceChannelDip(percent) }
    }
    fun setVoiceChannelGain(steps: Int) {
        viewModelScope.launch { preferencesRepository.setVoiceChannelGain(steps) }
    }

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

    /** Forgets what the last save or clear said, so the key dialog opens without yesterday's answer in it. */
    fun clearApiKeyMessage() {
        _apiKeyMessage.value = null
    }

    fun clearApiKey() {
        viewModelScope.launch {
            secureCredentials.clearApiKey()
            _hasApiKey.value = false
            _apiKeyMessage.value = text(R.string.api_cleared)
        }
    }
}
