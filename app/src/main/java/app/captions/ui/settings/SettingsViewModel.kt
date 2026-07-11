package app.captions.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import app.captions.data.settings.ModelPreferencesRepository
import app.captions.providers.KeyValidationResult
import app.captions.providers.KeyValidator
import app.captions.providers.openrouter.ModelOption
import app.captions.providers.openrouter.OpenRouterModelCatalog
import app.captions.providers.openrouter.OpenRouterModels
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
    val translationModel: String = OpenRouterModels.DEFAULT_TRANSLATION,
    val openRouterSttModel: String = OpenRouterModels.DEFAULT_STT,
    val translationOptions: List<ModelOption> = OpenRouterModels.RECOMMENDED_TRANSLATION,
    val sttOptions: List<ModelOption> = OpenRouterModels.RECOMMENDED_STT,
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ApiKeyRepository,
    private val validator: KeyValidator,
    private val modelPreferences: ModelPreferencesRepository,
    private val modelCatalog: OpenRouterModelCatalog,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val saveJobs = mutableMapOf<ApiProvider, Job>()

    init {
        viewModelScope.launch {
            val openRouter = repository.key(ApiProvider.OPENROUTER).first().orEmpty()
            val deepgram = repository.key(ApiProvider.DEEPGRAM).first().orEmpty()
            val elevenLabs = repository.key(ApiProvider.ELEVENLABS).first().orEmpty()
            val translationModel = modelPreferences.translationModel.first()
            val sttModel = modelPreferences.openRouterSttModel.first()
            _uiState.update {
                it.copy(
                    loaded = true,
                    openRouter = KeyFieldState(openRouter),
                    deepgram = KeyFieldState(deepgram),
                    elevenLabs = KeyFieldState(elevenLabs),
                    translationModel = translationModel,
                    openRouterSttModel = sttModel,
                )
            }
            refreshModelCatalog()
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
            if (provider == ApiProvider.OPENROUTER) {
                refreshModelCatalog()
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
            if (provider == ApiProvider.OPENROUTER && status == KeyFieldStatus.VALID) {
                refreshModelCatalog()
            }
        }
    }

    fun onTranslationModelSelected(modelId: String) {
        _uiState.update { it.copy(translationModel = modelId) }
        viewModelScope.launch {
            modelPreferences.setTranslationModel(modelId)
        }
    }

    fun onOpenRouterSttModelSelected(modelId: String) {
        _uiState.update { it.copy(openRouterSttModel = modelId) }
        viewModelScope.launch {
            modelPreferences.setOpenRouterSttModel(modelId)
        }
    }

    fun refreshModelCatalog() {
        viewModelScope.launch {
            val openRouterKey = repository.key(ApiProvider.OPENROUTER).first()?.trim().orEmpty()
            if (openRouterKey.isEmpty()) {
                _uiState.update {
                    it.copy(
                        translationOptions = OpenRouterModels.RECOMMENDED_TRANSLATION,
                        sttOptions = OpenRouterModels.RECOMMENDED_STT,
                        modelsLoading = false,
                        modelsError = null,
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(modelsLoading = true, modelsError = null) }
            runCatching {
                val translationIds = modelCatalog.fetchTextModels(openRouterKey)
                val sttIds = modelCatalog.fetchAudioModels(openRouterKey)
                _uiState.update { state ->
                    state.copy(
                        translationOptions = OpenRouterModels.mergeOptions(
                            OpenRouterModels.RECOMMENDED_TRANSLATION,
                            translationIds,
                        ),
                        sttOptions = OpenRouterModels.mergeOptions(
                            OpenRouterModels.RECOMMENDED_STT,
                            sttIds,
                        ),
                        modelsLoading = false,
                        modelsError = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        translationOptions = OpenRouterModels.RECOMMENDED_TRANSLATION,
                        sttOptions = OpenRouterModels.RECOMMENDED_STT,
                        modelsLoading = false,
                        modelsError = error.message,
                    )
                }
            }
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
