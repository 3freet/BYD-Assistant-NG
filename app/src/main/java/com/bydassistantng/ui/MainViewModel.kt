package com.bydassistantng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.data.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Screen { ONBOARDING, HOME, SETTINGS, ADVANCED, UPDATES, ABOUT, CRASH_LOG, APP_LOG }

@HiltViewModel
class MainViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
) : ViewModel() {
    /** Null while the initial onboarding-completed check is still in flight. */
    private val _screen = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = _screen.asStateFlow()

    init {
        viewModelScope.launch {
            _screen.value = if (preferencesRepository.isOnboardingCompleted()) Screen.HOME else Screen.ONBOARDING
        }
    }

    fun navigateTo(screen: Screen) {
        _screen.value = screen
    }

    fun onOnboardingFinished() {
        _screen.value = Screen.HOME
    }
}
