package com.bydassistantng.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydassistantng.R
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.data.SecureCredentials
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLanguageChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureCredentials: SecureCredentials,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {
    private val _appLanguage = MutableStateFlow(AppLanguage.choice(context))
    val appLanguage: StateFlow<AppLanguageChoice> = _appLanguage.asStateFlow()

    fun setAppLanguage(choice: AppLanguageChoice) {
        AppLanguage.setChoice(context, choice)
        _appLanguage.value = choice
        _errorMessage.value = null
    }

    private val _language = MutableStateFlow(AssistantLanguage.AUTO)
    val language: StateFlow<AssistantLanguage> = _language.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setLanguage(language: AssistantLanguage) {
        _language.value = language
        viewModelScope.launch { preferencesRepository.setAssistantLanguage(language) }
    }

    fun saveApiKeyAndFinish(apiKey: String, onFinished: () -> Unit) {
        if (apiKey.isBlank()) {
            _errorMessage.value = AppLanguage.string(context, R.string.onb_enter_key)
            return
        }
        viewModelScope.launch {
            val saved = secureCredentials.setApiKey(apiKey.trim())
            if (!saved) {
                _errorMessage.value = AppLanguage.string(context, R.string.api_save_failed)
                return@launch
            }
            preferencesRepository.setOnboardingCompleted(true)
            onFinished()
        }
    }
}
