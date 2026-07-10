package app.captions.data.settings

/**
 * Curated factory defaults from PLAN §4.2. Users can pick any option;
 * unknown / custom IDs are still accepted when typed in settings.
 */
data class ModelOption(
    val id: String,
    val label: String,
    val tier: String,
)

enum class TranscriptionProviderPreference {
    /** Deepgram → ElevenLabs → OpenRouter when keys are present. */
    AUTO,
    DEEPGRAM,
    ELEVENLABS,
    OPENROUTER,
}

object ModelCatalog {
    const val DEFAULT_TRANSLATION_MODEL = "google/gemini-3.1-pro-preview"
    const val DEFAULT_OPENROUTER_STT_MODEL = "google/gemini-3.1-pro-preview"

    val translationModels: List<ModelOption> = listOf(
        ModelOption(
            id = "google/gemini-3.1-pro-preview",
            label = "Gemini 3.1 Pro",
            tier = "Highest accuracy",
        ),
        ModelOption(
            id = "anthropic/claude-opus-4.7",
            label = "Claude Opus 4.7",
            tier = "Tone / literary",
        ),
        ModelOption(
            id = "anthropic/claude-sonnet-4.6",
            label = "Claude Sonnet 4.6",
            tier = "Balanced",
        ),
        ModelOption(
            id = "google/gemini-3-flash-preview",
            label = "Gemini 3 Flash",
            tier = "Low cost",
        ),
    )

    val openRouterTranscriptionModels: List<ModelOption> = listOf(
        ModelOption(
            id = "google/gemini-3.1-pro-preview",
            label = "Gemini 3.1 Pro",
            tier = "Highest accuracy",
        ),
        ModelOption(
            id = "google/gemini-3.5-flash",
            label = "Gemini 3.5 Flash",
            tier = "Balanced",
        ),
        ModelOption(
            id = "google/gemini-3-flash-preview",
            label = "Gemini 3 Flash",
            tier = "Low cost / low latency",
        ),
    )

    fun translationLabel(modelId: String): String =
        translationModels.firstOrNull { it.id == modelId }?.label ?: modelId

    fun openRouterSttLabel(modelId: String): String =
        openRouterTranscriptionModels.firstOrNull { it.id == modelId }?.label ?: modelId
}
