package com.bydassistantng.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.update.UpdateChannel
import com.bydassistantng.update.UpdateManager
import com.bydassistantng.update.UpdateRelease
import com.bydassistantng.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(private val manager: UpdateManager) : ViewModel() {
    val state: StateFlow<UpdateState> = manager.state
    val installed = manager.installed
    val supported: Boolean get() = manager.isSupported

    /** The channel being followed. */
    val channel: StateFlow<UpdateChannel> = manager.channel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.installed.channel)

    fun onOpened() = manager.checkIfNeverChecked()
    fun check() = manager.checkNow()
    fun install(release: UpdateRelease) = manager.install(release)
    fun cancelDownload() = manager.cancelDownload()
}
