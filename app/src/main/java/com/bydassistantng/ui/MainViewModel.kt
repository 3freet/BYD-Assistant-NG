package com.bydassistantng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.ui.settings.SettingsCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Screen {
    ONBOARDING, HOME, SETTINGS, UPDATES, ABOUT, CRASH_LOG, APP_LOG,
    ;

    /**
     * Where the system back button (and the arrow on each screen) goes from here: one step back, never out of the app,
     * except from Home (and the onboarding), which have no step before them. The Updates screen can be opened from
     * Home, from Settings or from a notification, so it goes back to [updatesFrom].
     */
    fun backTarget(updatesFrom: Screen): Screen? = when (this) {
        ONBOARDING, HOME -> null
        SETTINGS -> HOME
        ABOUT, CRASH_LOG, APP_LOG -> SETTINGS
        UPDATES -> updatesFrom
    }
}

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

    /** Which group of the settings is open (null: none yet, so the list on a narrow screen and the first group on a wide one). */
    private val _settingsCategory = MutableStateFlow<SettingsCategory?>(null)
    val settingsCategory: StateFlow<SettingsCategory?> = _settingsCategory.asStateFlow()

    fun selectSettingsCategory(category: SettingsCategory?) {
        _settingsCategory.value = category
    }

    /** Opens the settings at [category], for the screens that point at one of them. */
    fun openSettings(category: SettingsCategory) {
        _settingsCategory.value = category
        _screen.value = Screen.SETTINGS
    }

    fun navigateTo(screen: Screen) {
        // Leaving the settings for Home forgets which group was open, so the next visit starts from the top.
        if (screen == Screen.HOME) _settingsCategory.value = null
        _screen.value = screen
    }

    fun onOnboardingFinished() {
        _screen.value = Screen.HOME
    }
}
