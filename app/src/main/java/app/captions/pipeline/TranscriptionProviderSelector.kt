package app.captions.pipeline

import app.captions.data.keys.ApiKeyRepository
import app.captions.data.keys.ApiProvider
import app.captions.data.settings.TranscriptionProviderPreference
import app.captions.data.settings.UserPreferencesRepository
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
 * Resolves which STT provider to use.
 *
 * Default order when preference is [TranscriptionProviderPreference.AUTO]:
 * Deepgram streaming (credits) → ElevenLabs Scribe batch → OpenRouter multimodal.
 * A user preference pins a provider when its key is present; otherwise falls back to AUTO.
 */
@Singleton
class TranscriptionProviderSelector @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository,
    private val preferencesRepository: UserPreferencesRepository,
) {
    suspend fun resolve(
        preference: TranscriptionProviderPreference? = null,
    ): ResolvedTranscriptionProvider? {
        val pref = preference
            ?: preferencesRepository.preferences.first().transcriptionProvider
        val deepgram = apiKeyRepository.key(ApiProvider.DEEPGRAM).first()
        val eleven = apiKeyRepository.key(ApiProvider.ELEVENLABS).first()
        val openRouter = apiKeyRepository.key(ApiProvider.OPENROUTER).first()

        fun deepgramResolved() = deepgram?.takeIf { it.isNotBlank() }?.let {
            ResolvedTranscriptionProvider(
                kind = SelectedProviderKind.DEEPGRAM_STREAMING,
                apiKey = it,
                displayHint = "Deepgram Nova-3",
            )
        }

        fun elevenResolved() = eleven?.takeIf { it.isNotBlank() }?.let {
            ResolvedTranscriptionProvider(
                kind = SelectedProviderKind.ELEVENLABS_BATCH,
                apiKey = it,
                displayHint = "ElevenLabs Scribe v2",
            )
        }

        fun openRouterResolved() = openRouter?.takeIf { it.isNotBlank() }?.let {
            ResolvedTranscriptionProvider(
                kind = SelectedProviderKind.OPENROUTER_BATCH,
                apiKey = it,
                displayHint = "OpenRouter multimodal",
            )
        }

        val preferred = when (pref) {
            TranscriptionProviderPreference.AUTO -> null
            TranscriptionProviderPreference.DEEPGRAM -> deepgramResolved()
            TranscriptionProviderPreference.ELEVENLABS -> elevenResolved()
            TranscriptionProviderPreference.OPENROUTER -> openRouterResolved()
        }
        if (preferred != null) return preferred

        return deepgramResolved() ?: elevenResolved() ?: openRouterResolved()
    }

    companion object {
        fun canStart(
            hasDeepgram: Boolean,
            hasElevenLabs: Boolean,
            hasOpenRouter: Boolean,
        ): Boolean = hasDeepgram || hasElevenLabs || hasOpenRouter
    }
}
