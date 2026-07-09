package app.captions.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import app.captions.providers.KeyValidationResult
import app.captions.providers.KeyValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class KeyFieldStatus { IDLE, SAVED, VERIFYING, VALID, INVALID, NETWORK_ERROR, EMPTY }

data class KeyFieldState(
    val text: String = "",
    val status: KeyFieldStatus = KeyFieldStatus.IDLE,
)

data class SettingsUiState(
    val loaded: Boolean = false,
    val openRouter: KeyFieldState = KeyFieldState(),
    val deepgram: KeyFieldState = KeyFieldState(),
    val elevenLabs: KeyFieldState = KeyFieldState(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ApiKeyRepository,
    private val validator: KeyValidator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val saveJobs = mutableMapOf<ApiProvider, Job>()

    init {
        viewModelScope.launch {
            val openRouter = repository.key(ApiProvider.OPENROUTER).first().orEmpty()
            val deepgram = repository.key(ApiProvider.DEEPGRAM).first().orEmpty()
            val elevenLabs = repository.key(ApiProvider.ELEVENLABS).first().orEmpty()
            _uiState.update {
                it.copy(
                    loaded = true,
                    openRouter = KeyFieldState(openRouter),
                    deepgram = KeyFieldState(deepgram),
                    elevenLabs = KeyFieldState(elevenLabs),
                )
            }
        }
    }

    fun onKeyChanged(provider: ApiProvider, text: String) {
        updateField(provider) { it.copy(text = text, status = KeyFieldStatus.IDLE) }
        saveJobs[provider]?.cancel()
        saveJobs[provider] = viewModelScope.launch {
            repository.setKey(provider, text)
            updateField(provider) { current ->
                if (current.status == KeyFieldStatus.IDLE) {
                    current.copy(status = KeyFieldStatus.SAVED)
                } else {
                    current
                }
            }
        }
    }

    fun onVerify(provider: ApiProvider) {
        val key = fieldFor(provider).text.trim()
        if (key.isEmpty()) {
            updateField(provider) { it.copy(status = KeyFieldStatus.EMPTY) }
            return
        }
        updateField(provider) { it.copy(status = KeyFieldStatus.VERIFYING) }
        viewModelScope.launch {
            saveJobs[provider]?.join()
            val status = when (validator.validate(provider, key)) {
                KeyValidationResult.VALID -> KeyFieldStatus.VALID
                KeyValidationResult.INVALID -> KeyFieldStatus.INVALID
                KeyValidationResult.NETWORK_ERROR -> KeyFieldStatus.NETWORK_ERROR
            }
            updateField(provider) { it.copy(status = status) }
        }
    }

    private fun fieldFor(provider: ApiProvider): KeyFieldState = when (provider) {
        ApiProvider.OPENROUTER -> _uiState.value.openRouter
        ApiProvider.DEEPGRAM -> _uiState.value.deepgram
        ApiProvider.ELEVENLABS -> _uiState.value.elevenLabs
    }

    private fun updateField(provider: ApiProvider, transform: (KeyFieldState) -> KeyFieldState) {
        _uiState.update { state ->
            when (provider) {
                ApiProvider.OPENROUTER -> state.copy(openRouter = transform(state.openRouter))
                ApiProvider.DEEPGRAM -> state.copy(deepgram = transform(state.deepgram))
                ApiProvider.ELEVENLABS -> state.copy(elevenLabs = transform(state.elevenLabs))
            }
        }
    }
}
