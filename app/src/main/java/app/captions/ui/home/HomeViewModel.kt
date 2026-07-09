package app.captions.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val loaded: Boolean = false,
    val hasOpenRouterKey: Boolean = false,
    val hasDeepgramKey: Boolean = false,
    val hasElevenLabsKey: Boolean = false,
) {
    val canStartLive: Boolean get() = hasDeepgramKey
    val ready: Boolean get() = hasOpenRouterKey || hasDeepgramKey
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    repository: ApiKeyRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(
            repository.key(ApiProvider.OPENROUTER),
            repository.key(ApiProvider.DEEPGRAM),
            repository.key(ApiProvider.ELEVENLABS),
        ) { openRouter, deepgram, elevenLabs ->
            HomeUiState(
                loaded = true,
                hasOpenRouterKey = !openRouter.isNullOrBlank(),
                hasDeepgramKey = !deepgram.isNullOrBlank(),
                hasElevenLabsKey = !elevenLabs.isNullOrBlank(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
