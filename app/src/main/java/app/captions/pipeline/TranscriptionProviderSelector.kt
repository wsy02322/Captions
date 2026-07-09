package app.captions.pipeline

import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import app.captions.transcription.SelectedProviderKind
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedTranscriptionProvider(
    val kind: SelectedProviderKind,
    val apiKey: String,
    val displayHint: String,
)

/**
 * Provider priority for this developer build:
 * Deepgram streaming (credits) → ElevenLabs Scribe batch → OpenRouter multimodal.
 */
@Singleton
class TranscriptionProviderSelector @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository,
) {
    suspend fun resolve(): ResolvedTranscriptionProvider? {
        val deepgram = apiKeyRepository.key(ApiProvider.DEEPGRAM).first()
        if (!deepgram.isNullOrBlank()) {
            return ResolvedTranscriptionProvider(
                kind = SelectedProviderKind.DEEPGRAM_STREAMING,
                apiKey = deepgram,
                displayHint = "Deepgram Nova-3",
            )
        }
        val eleven = apiKeyRepository.key(ApiProvider.ELEVENLABS).first()
        if (!eleven.isNullOrBlank()) {
            return ResolvedTranscriptionProvider(
                kind = SelectedProviderKind.ELEVENLABS_BATCH,
                apiKey = eleven,
                displayHint = "ElevenLabs Scribe v2",
            )
        }
        val openRouter = apiKeyRepository.key(ApiProvider.OPENROUTER).first()
        if (!openRouter.isNullOrBlank()) {
            return ResolvedTranscriptionProvider(
                kind = SelectedProviderKind.OPENROUTER_BATCH,
                apiKey = openRouter,
                displayHint = "OpenRouter multimodal",
            )
        }
        return null
    }

    companion object {
        fun canStart(
            hasDeepgram: Boolean,
            hasElevenLabs: Boolean,
            hasOpenRouter: Boolean,
        ): Boolean = hasDeepgram || hasElevenLabs || hasOpenRouter
    }
}
