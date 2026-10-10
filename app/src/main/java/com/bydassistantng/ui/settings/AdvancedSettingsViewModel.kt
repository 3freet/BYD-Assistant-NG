package com.bydassistantng.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.update.UpdateChannel
import com.bydassistantng.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(private val manager: UpdateManager) : ViewModel() {
    val channel: StateFlow<UpdateChannel> = manager.channel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.installed.channel)
    val autoCheck: StateFlow<Boolean> = manager.autoCheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether this build can update itself; a local developer build can't, so it has no update options. */
    val updatesSupported: Boolean get() = manager.isSupported

    fun setChannel(channel: UpdateChannel) = manager.setChannel(channel)
    fun setAutoCheck(enabled: Boolean) = manager.setAutoCheck(enabled)
}
