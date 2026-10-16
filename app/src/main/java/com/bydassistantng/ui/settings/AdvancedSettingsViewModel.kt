package com.bydassistantng.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.audio.ExteriorSpeaker
import com.bydassistantng.audio.ExteriorVolume
import com.bydassistantng.cluster.ClusterScreenAvailability
import com.bydassistantng.data.OptInFeatures
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.update.UpdateChannel
import com.bydassistantng.update.UpdateManager
import com.bydassistantng.vehicle.DriveModeSwitch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val manager: UpdateManager,
    private val preferences: PreferencesRepository,
    exteriorSpeaker: Optional<ExteriorSpeaker>,
    driveModes: Optional<DriveModeSwitch>,
    @ApplicationContext context: Context,
) : ViewModel() {
    val channel: StateFlow<UpdateChannel> = manager.channel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.installed.channel)
    val autoCheck: StateFlow<Boolean> = manager.autoCheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether this build can update itself; a local developer build can't, so it has no update options. */
    val updatesSupported: Boolean get() = manager.isSupported

    /** Whether this build can use the car's exterior speaker, and so has a switch and a volume for it. */
    val exteriorSpeakerSupported: Boolean = exteriorSpeaker.isPresent

    /** Whether this build can change the car's drive mode, and so has a switch for it. */
    val driveModesSupported: Boolean = driveModes.isPresent

    /** Off until the user switches it on (see [OptInFeatures]). */
    val exteriorSpeakerEnabled: StateFlow<Boolean> = preferences.exteriorSpeakerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.EXTERIOR_SPEAKER_DEFAULT)

    val driveModesEnabled: StateFlow<Boolean> = preferences.driveModesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.DRIVE_MODES_DEFAULT)

    /** Whether this build and this car offer the instrument cluster feature (experimental; it turns BYD Maps on and off). */
    val clusterSupported: Boolean = ClusterScreenAvailability.isOnThisCar(context)

    val clusterScreenEnabled: StateFlow<Boolean> = preferences.clusterScreenEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.CLUSTER_SCREEN_DEFAULT)

    val clusterKeepMapsDisabled: StateFlow<Boolean> = preferences.clusterKeepMapsDisabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptInFeatures.CLUSTER_KEEP_MAPS_DISABLED_DEFAULT)

    val exteriorVolume: StateFlow<ExteriorVolume> = preferences.exteriorVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExteriorVolume.DEFAULT)

    fun setChannel(channel: UpdateChannel) = manager.setChannel(channel)
    fun setAutoCheck(enabled: Boolean) = manager.setAutoCheck(enabled)
    fun setExteriorVolume(volume: ExteriorVolume) {
        viewModelScope.launch { preferences.setExteriorVolume(volume) }
    }
    fun setExteriorSpeakerEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setExteriorSpeakerEnabled(enabled) }
    }
    fun setDriveModesEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setDriveModesEnabled(enabled) }
    }
    fun setClusterScreenEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setClusterScreenEnabled(enabled) }
    }
    fun setClusterKeepMapsDisabled(disabled: Boolean) {
        viewModelScope.launch { preferences.setClusterKeepMapsDisabled(disabled) }
    }
}
